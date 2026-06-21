package io.github.zongkx;

import org.junit.jupiter.api.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class DuckDBJdbcIntegrationTest {

    private static Connection conn;

    @BeforeAll
    static void initDb() throws SQLException {// 强制加载驱动类，触发其 static 块中的 DriverManager.registerDriver()
        try {
            Class.forName("io.github.zongkx.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到驱动类，请检查类路径", e);
        }
        // 假设你有一个 Driver 实现了 java.sql.Driver 接口
        // 或者通过手动实例化你的 Connection
        conn = DriverManager.getConnection("jdbc:duckdb:"); 
        
        // 执行初始化，写入测试数据
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_table (id INTEGER, name VARCHAR)");
            stmt.execute("INSERT INTO test_table VALUES (1, 'Alice'), (2, 'Bob')");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (conn != null) conn.close();
    }

    @Test
    void testQueryData() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM test_table WHERE id = 1")) {
            
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("id"));
            assertEquals("Alice", rs.getString("name"));
        }
    }
}