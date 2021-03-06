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
package org.apache.ibatis.session;

/**
 * specifies if and how mybatis should automatically map columns to fields/properties.
 * <p>
 * 自动映射字段
 *
 * @author Eduardo Macarron
 */
public enum AutoMappingBehavior {

    /**
     * Disables auto-mapping. 禁用自动映射
     */
    NONE,

    /**
     * 只映射那些没有定义映射的
     * Will only auto-map results with no nested result mappings defined inside.
     */
    PARTIAL,

    /**
     * 将完全自动映射并且包含复杂的映射
     * Will auto-map result mappings of any complexity (containing nested or otherwise).
     */
    FULL
}
