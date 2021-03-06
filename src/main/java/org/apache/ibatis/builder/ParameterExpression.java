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
package org.apache.ibatis.builder;

import java.util.HashMap;

/**
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

    private static final long serialVersionUID = -2417552199605158680L;

    public ParameterExpression(String expression) {
        parse(expression);
    }

    private void parse(String expression) {
        //找到不是无效字符的下标
        int p = skipWS(expression, 0);
        if (expression.charAt(p) == '(') {
            expression(expression, p + 1);
        } else {
            property(expression, p);
        }
    }

    /**
     * 处理()内表达式
     *
     * @param expression 表达式
     * @param left       左括号下一个下标位置
     */
    private void expression(String expression, int left) {
        int match = 1;
        int right = left + 1;
        //一直到找到对标的右括号 即match == 0 ，在此之前已经记录一个左括号，所以match=1，再次寻找下一个字符遇到左括号时，match++，如果遇到右括号则match--
        while (match > 0) {
            if (expression.charAt(right) == ')') {
                match--;
            } else if (expression.charAt(right) == '(') {
                match++;
            }
            right++;
        }
        //寻找到最外层括号内的表达式
        put("expression", expression.substring(left, right - 1));
        jdbcTypeOpt(expression, right);
    }

    /**
     * #{}的名称
     *
     * @param expression 表达式
     * @param left       开始位置
     */
    private void property(String expression, int left) {
        if (left < expression.length()) {
            int right = skipUntil(expression, left, ",:");
            put("property", trimmedStr(expression, left, right));
            jdbcTypeOpt(expression, right);
        }
    }

    /**
     * 跳过签名空格
     *
     * @param expression 表达式
     * @param p          当前指针位置
     * @return 返回不是空格下标
     */
    private int skipWS(String expression, int p) {
        for (int i = p; i < expression.length(); i++) {
            if (expression.charAt(i) > 0x20) {
                return i;
            }
        }
        return expression.length();
    }

    /**
     * 返回p下标位置之后的含有endChars字符的下标位置
     * 例如：expression="jdbcType=VARCHAR", p=0, endChars="="
     * 返回8,(因为=的下表是8)
     *
     * @param expression 表达式
     * @param p          开始位置
     * @param endChars   是否包含的内容
     * @return 返回p之后第一个含有endChars
     */
    private int skipUntil(String expression, int p, final String endChars) {
        for (int i = p; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (endChars.indexOf(c) > -1) {
                return i;
            }
        }
        return expression.length();
    }

    /**
     * 解析表达式p下标位置之后是否含有":,"字符，如有则进入相应的逻辑
     *
     * @param expression 表达式
     * @param p          开始位置
     */
    private void jdbcTypeOpt(String expression, int p) {
        p = skipWS(expression, p);
        if (p < expression.length()) {
            if (expression.charAt(p) == ':') {
                jdbcType(expression, p + 1);
            } else if (expression.charAt(p) == ',') {
                option(expression, p + 1);
            } else {
                throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
            }
        }
    }

    /**
     * 直接将字符 ':' 之后的jdbcType等式加入到当前map
     *
     * @param expression 表达式
     * @param p          开始位置
     */
    private void jdbcType(String expression, int p) {
        int left = skipWS(expression, p);
        int right = skipUntil(expression, left, ",");
        if (right > left) {
            put("jdbcType", trimmedStr(expression, left, right));
        } else {
            throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
        }
        option(expression, right + 1);
    }

    /**
     * 解析表达式,之后的内容, 表达式中的等式加入到当前map
     *
     * @param expression 表达式
     * @param p          开始下标位置
     */
    private void option(String expression, int p) {
        int left = skipWS(expression, p);
        if (left < expression.length()) {
            int right = skipUntil(expression, left, "=");
            String name = trimmedStr(expression, left, right);
            left = right + 1;
            right = skipUntil(expression, left, ",");
            String value = trimmedStr(expression, left, right);
            put(name, value);
            option(expression, right + 1);
        }
    }

    /**
     * 过滤空格等等无效字符
     *
     * @param str   源字符串
     * @param start 开始位置
     * @param end   结束位置
     * @return 过滤后的字符串
     */
    private String trimmedStr(String str, int start, int end) {
        while (str.charAt(start) <= 0x20) {
            start++;
        }
        while (str.charAt(end - 1) <= 0x20) {
            end--;
        }
        return start >= end ? "" : str.substring(start, end);
    }

}
