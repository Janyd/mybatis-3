/**
 * Copyright 2009-2015 the original author or authors.
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
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * 执行器接口，定义了各种操作
 *
 * @author Clinton Begin
 */
public interface Executor {

    /**
     * 无结果的结果处理器
     */
    ResultHandler NO_RESULT_HANDLER = null;

    /**
     * 更新、删除、插入操作
     *
     * @param ms        MappedStatement对象
     * @param parameter 参数
     * @return 影响条数
     * @throws SQLException SQL异常
     */
    int update(MappedStatement ms, Object parameter) throws SQLException;

    /**
     * 查询数据
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param cacheKey      一级缓存key
     * @param boundSql      BoundSql内含相关sql、参数映射
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;

    /**
     * 执行sql可从ms提取
     *
     * @param ms            MappedStatement对象
     * @param parameter     入参
     * @param rowBounds     分页信息
     * @param resultHandler 结果处理器
     * @param <E>           返回结果类型
     * @return 数据
     * @throws SQLException 异常
     */
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;

    /**
     * 查询数据，返回游标位置
     *
     * @param ms        MappedStatement对象
     * @param parameter 入参
     * @param rowBounds 分页信息
     * @param <E>       返回结果类型
     * @return 游标
     * @throws SQLException 异常
     */
    <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;

    /**
     * @return
     * @throws SQLException 异常
     */
    List<BatchResult> flushStatements() throws SQLException;

    /**
     * 事务提交
     *
     * @param required 是否必要
     * @throws SQLException 异常
     */
    void commit(boolean required) throws SQLException;

    /**
     * 回滚
     *
     * @param required 是否必要
     * @throws SQLException 异常
     */
    void rollback(boolean required) throws SQLException;

    /**
     * 根据当前SQL参数等等创建一个CacheKey
     *
     * @param ms              MappedStatement对象
     * @param parameterObject 入参
     * @param rowBounds       分页信息
     * @param boundSql        BoundSql对象内含相关sql、参数映射
     * @return CacheKey
     */
    CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);

    /**
     * 查询一级缓存是否缓存了
     *
     * @param ms  MappedStatement对象
     * @param key CacheKey
     * @return 是否已缓存
     */
    boolean isCached(MappedStatement ms, CacheKey key);

    /**
     * 清除一级缓存
     */
    void clearLocalCache();

    /**
     * 未知
     *
     * @param ms           MappedStatement对象
     * @param resultObject 结果
     * @param property     属性名称
     * @param key          缓存key
     * @param targetType   目标类型
     */
    void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);

    /**
     * 获取事务
     *
     * @return 事务
     */
    Transaction getTransaction();

    /**
     * 关闭
     *
     * @param forceRollback 是否强制回滚
     */
    void close(boolean forceRollback);

    /**
     * 连接是否已关闭
     *
     * @return 是否已关闭
     */
    boolean isClosed();

    /**
     * 设置执行器包装
     *
     * @param executor 执行器
     */
    void setExecutorWrapper(Executor executor);

}
