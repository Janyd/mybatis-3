package org.apache.ibatis.sample;

import org.apache.ibatis.BaseDataTest;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Test;

import java.io.IOException;

/**
 * 研究SqlSessionFactoryBuilder加载过程
 * @author janyd
 * @since 2020/4/24 19:21
 */
public class SqlSessionFactoryBuilderTest extends BaseDataTest {

    private static SqlSessionFactory sqlSessionFactory;

    @Test
    public void loadConfig() throws IOException {
        sqlSessionFactory  = new SqlSessionFactoryBuilder().build(Resources.getResourceAsReader("org/apache/ibatis/sample/mybatis-config.xml"));
    }

}
