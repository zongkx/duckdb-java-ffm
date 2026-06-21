package io.github.zongkx.functional;

import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PreparedStatement 全功能验证测试
 *
 * 覆盖参数绑定、多类型、参数复用、清理等关键场景。
 */
@Tag("functional")
@DisplayName("PreparedStatement 功能验证测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBPreparedStatementFunctionalTest {

    private static Connection conn;

    @BeforeAll
    static void setup() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:memory");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE pstmt_test ("
                    + "id INTEGER, "
                    + "name VARCHAR, "
                    + "salary DOUBLE, "
                    + "active BOOLEAN"
                    + ")");
        }
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @BeforeEach
    void cleanData() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM pstmt_test");
        }
    }

    @Test
    @DisplayName("[PSTMT-01] 预编译并执行参数化查询")
    void testParameterizedQuery() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {
            pstmt.setInt(1, 1);
            pstmt.setString(2, "Alice");
            pstmt.setDouble(3, 50000.0);
            pstmt.setBoolean(4, true);
            int rows = pstmt.executeUpdate();
            assertEquals(1, rows);
        }

        // 验证数据
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 1);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString("name"));
                assertEquals(50000.0, rs.getDouble("salary"), 0.001);
                assertTrue(rs.getBoolean("active"));
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-02] 传入 null 参数")
    void testNullParameter() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test (id, name) VALUES (?, ?)")) {
            pstmt.setInt(1, 2);
            pstmt.setNull(2, Types.VARCHAR);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 2);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("name"));
                assertTrue(rs.wasNull());
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-03] 复用 PreparedStatement 多次执行")
    void testReusePreparedStatement() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {

            for (int i = 1; i <= 5; i++) {
                pstmt.setInt(1, i);
                pstmt.setString(2, "User" + i);
                pstmt.setDouble(3, i * 1000.0);
                pstmt.setBoolean(4, i % 2 == 0);
                pstmt.executeUpdate();
            }
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM pstmt_test")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("cnt"));
        }
    }

    @Test
    @DisplayName("[PSTMT-04] clearParameters 后重新绑定")
    void testClearParameters() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {

            pstmt.setInt(1, 10);
            pstmt.setString(2, "First");
            pstmt.setDouble(3, 100.0);
            pstmt.setBoolean(4, true);
            pstmt.executeUpdate();

            // 清除参数
            pstmt.clearParameters();

            // 重新绑定
            pstmt.setInt(1, 11);
            pstmt.setString(2, "Second");
            pstmt.setDouble(3, 200.0);
            pstmt.setBoolean(4, false);
            pstmt.executeUpdate();
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM pstmt_test WHERE id IN (10, 11)")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("cnt"));
        }
    }

    @Test
    @DisplayName("[PSTMT-05] setObject 自动类型映射")
    void testSetObjectAutoMapping() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {

            // 通过 setObject 传入不同类型
            pstmt.setObject(1, 100);
            pstmt.setObject(2, "ObjectTest");
            pstmt.setObject(3, 12345.67);
            pstmt.setObject(4, true);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 100);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("ObjectTest", rs.getString("name"));
                assertEquals(12345.67, rs.getDouble("salary"), 0.001);
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-06] setObject(null) 应产生 NULL")
    void testSetObjectNull() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test (id, name) VALUES (?, ?)")) {
            pstmt.setObject(1, 200);
            pstmt.setObject(2, null);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 200);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("name"));
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-07] executeQuery 参数化查询返回数据集")
    void testExecuteQueryWithParams() throws SQLException {
        // 插入测试数据
        try (Statement stmt = conn.createStatement()) {
            for (int i = 1; i <= 3; i++) {
                stmt.execute("INSERT INTO pstmt_test VALUES (" + i + ", 'Name" + i + "', " + (i * 1000.0) + ", true)");
            }
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id >= ? AND id <= ?")) {
            pstmt.setInt(1, 2);
            pstmt.setInt(2, 3);

            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("id"));
                assertTrue(rs.next());
                assertEquals(3, rs.getInt("id"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-08] setBoolean 参数绑定")
    void testSetBoolean() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {
            pstmt.setInt(1, 300);
            pstmt.setString(2, "BoolTest");
            pstmt.setDouble(3, 0.0);
            pstmt.setBoolean(4, false);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 300);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("active"));
            }
        }
    }

    @Test
    @DisplayName("[PSTMT-09] close 后调用应抛异常")
    void testOperationsAfterClose() throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("SELECT ?");
        pstmt.setInt(1, 1);
        pstmt.close();

        assertThrows(SQLException.class, pstmt::executeQuery);
        assertThrows(SQLException.class, pstmt::executeUpdate);
    }

    @Test
    @DisplayName("[PSTMT-10] setDouble 参数绑定")
    void testSetDouble() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO pstmt_test VALUES (?, ?, ?, ?)")) {
            pstmt.setInt(1, 400);
            pstmt.setString(2, "DoubleTest");
            pstmt.setDouble(3, 3.14159);
            pstmt.setBoolean(4, true);
            pstmt.executeUpdate();
        }

        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT * FROM pstmt_test WHERE id = ?")) {
            pstmt.setInt(1, 400);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(3.14159, rs.getDouble("salary"), 0.00001);
            }
        }
    }
}
