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
package org.apache.ibatis.executor.keygen;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

import java.sql.Statement;

/**
 * @author Clinton Begin
 */
public interface KeyGenerator {

    /**
     * 执行SQL前置操作
     *
     * @param executor  执行器
     * @param ms        MappedStatement对象
     * @param stmt      statement对象
     * @param parameter 入参
     */
    void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

    /**
     * 执行SQL后置操作
     *
     * @param executor  执行器
     * @param ms        MappedStatement对象
     * @param stmt      statement对象
     * @param parameter 入参
     */
    void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

}
