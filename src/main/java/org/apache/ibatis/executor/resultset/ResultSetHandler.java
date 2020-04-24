/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.cursor.Cursor;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 结果集处理器
 *
 * @author Clinton Begin
 */
public interface ResultSetHandler {

    /**
     * 处理结果集
     *
     * @param stmt statement
     * @param <E>  类型
     * @return 数据
     * @throws SQLException 异常
     */
    <E> List<E> handleResultSets(Statement stmt) throws SQLException;

    /**
     * 处理游标结果集
     *
     * @param stmt statement
     * @param <E>  类型
     * @return 游标
     * @throws SQLException 异常
     */
    <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException;

    /**
     * 处理存储过程
     *
     * @param cs 存储过程statement
     * @throws SQLException 异常
     */
    void handleOutputParameters(CallableStatement cs) throws SQLException;

}
