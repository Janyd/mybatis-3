/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * 执行器的抽象类，主要封装一些公用方法
 *
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    /**
     * 事务
     */
    protected Transaction transaction;

    /**
     * 可内置另外执行器
     */
    protected Executor wrapper;

    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    protected PerpetualCache localCache;
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    protected int queryStack;

    /**
     * 是否已关闭
     */
    private boolean closed;

    /**
     * 构造函数
     *
     * @param configuration 配置
     * @param transaction   事务
     */
    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        //默认是本身
        this.wrapper = this;
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    /**
     * 关闭
     *
     * @param forceRollback 是否强制回滚
     */
    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore. There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    /**
     * 是否已关闭
     *
     * @return 是否已关闭
     */
    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        //执行写入操作，需要清除本地缓存
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return doFlushStatements(isRollBack);
    }

    /**
     * 查询从ms提取sql再去执行query
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    /**
     * 查询数据
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param key           一级缓存key
     * @param boundSql      BoundSql内含相关sql、参数映射
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        //根据需要清除本地缓存
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        List<E> list;
        try {
            queryStack++;
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            if (list != null) {
                //处理存储过程
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            queryStack--;
        }
        if (queryStack == 0) {
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            deferredLoads.clear();
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                clearLocalCache();
            }
        }
        return list;
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        if (deferredLoad.canLoad()) {
            deferredLoad.load();
        } else {
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    /**
     * 创建缓存key
     *
     * @param ms              MappedStatement对象
     * @param parameterObject 入参
     * @param rowBounds       分页信息
     * @param boundSql        BoundSql对象内含相关sql、参数映射
     * @return 缓存key
     */
    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(ms.getId());
        cacheKey.update(rowBounds.getOffset());
        cacheKey.update(rowBounds.getLimit());
        cacheKey.update(boundSql.getSql());
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic
        for (ParameterMapping parameterMapping : parameterMappings) {
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                cacheKey.update(value);
            }
        }
        if (configuration.getEnvironment() != null) {
            // issue #176
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        clearLocalCache();
        flushStatements();
        if (required) {
            transaction.commit();
        }
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache();
                flushStatements(true);
            } finally {
                if (required) {
                    transaction.rollback();
                }
            }
        }
    }

    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    /**
     * 更新、删除、插入操作
     *
     * @param ms        MappedStatement对象
     * @param parameter 参数
     * @return 影响条数
     * @throws SQLException SQL异常
     */
    protected abstract int doUpdate(MappedStatement ms, Object parameter) throws SQLException;

    /**
     * @param isRollback
     * @return
     * @throws SQLException
     */
    protected abstract List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException;

    /**
     * 执行查询抽象方法
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param boundSql      BoundSql内含相关sql、参数映射
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
        throws SQLException;

    /**
     * 基于游标查询
     *
     * @param ms        MappedStatement对象
     * @param parameter 入参
     * @param rowBounds 分页信息
     * @param <E>       返回结果类型
     * @return 游标
     * @throws SQLException 异常
     */
    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
        throws SQLException;

    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Apply a transaction timeout.
     *
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     * @since 3.4.0
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    /**
     * @param ms        MappedStatement对象
     * @param key       缓存key
     * @param parameter 入参
     * @param boundSql  sql、入参类型映射
     */
    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        //处理存储过程
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    /**
     * 从数据库查询
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param boundSql      BoundSql内含相关sql、参数映射
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        //存入一个占位符
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            localCache.removeObject(key);
        }
        localCache.putObject(key, list);
        if (ms.getStatementType() == StatementType.CALLABLE) {
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }

    protected Connection getConnection(Log statementLog) throws SQLException {
        Connection connection = transaction.getConnection();
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    private static class DeferredLoad {

        private final MetaObject resultObject;
        private final String property;
        private final Class<?> targetType;
        private final CacheKey key;
        private final PerpetualCache localCache;
        private final ObjectFactory objectFactory;
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        public boolean canLoad() {
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        public void load() {
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
                List<Object> list = (List<Object>) localCache.getObject(key);
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            resultObject.setValue(property, value);
        }

    }

}
