package io.github.zongkx.functional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement 全功能验证测试
 * <p>
 * 覆盖 Statement 的所有核心操作：
 * - executeQuery / executeUpdate / execute 路由
 * - CRUD 全流程
 * - 结果集生命周期
 * - 关闭与资源释放
 */
@Tag("functional")
@DisplayName("Statement 功能验证测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBStatementFunctionalTest {

    private static Connection conn;

    @BeforeAll
    static void setup() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stmt_test ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name VARCHAR(100), "
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
            stmt.execute("DELETE FROM stmt_test");
        }
    }

    @Test
    @DisplayName("[STMT-01] executeQuery 查询并读取结果")
    void testExecuteQuery() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stmt_test VALUES (1, 'Alice', 50000, true)");

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM stmt_test WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("id"));
                assertEquals("Alice", rs.getString("name"));
                assertEquals(50000.0, rs.getDouble("salary"), 0.001);
                assertTrue(rs.getBoolean("active"));
            }
        }
    }

    @Test
    @DisplayName("[STMT-02] executeUpdate 返回影响行数")
    void testExecuteUpdate() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stmt_test VALUES (2, 'Bob', 60000, true)");

            int rows = stmt.executeUpdate("UPDATE stmt_test SET salary = 65000 WHERE id = 2");
            assertEquals(1, rows);

            rows = stmt.executeUpdate("UPDATE stmt_test SET salary = 99999 WHERE id = 999");
            assertEquals(0, rows);
        }
    }

    @Test
    @DisplayName("[STMT-03] execute 正确路由 SELECT/DML")
    void testExecuteRouting() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stmt_test VALUES (3, 'Carol', 70000, false)");

            // SELECT -> true
            boolean isResultSet = stmt.execute("SELECT * FROM stmt_test WHERE id = 3");
            assertTrue(isResultSet);
            ResultSet rs = stmt.getResultSet();
            assertNotNull(rs);
            assertTrue(rs.next());
            assertEquals("Carol", rs.getString("name"));
            rs.close();

            // INSERT -> false
            boolean isUpdate = stmt.execute("INSERT INTO stmt_test VALUES (4, 'Dave', 80000, true)");
            assertFalse(isUpdate);
            assertEquals(-1, stmt.getUpdateCount()); // 或返回影响行数
        }
    }

    @Test
    @DisplayName("[STMT-04] 批量插入并查询")
    void testBulkInsertAndQuery() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (int i = 10; i < 110; i++) {
                stmt.execute("INSERT INTO stmt_test VALUES (" + i + ", 'User" + i + "', " + (i * 1000.0) + ", true)");
            }

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stmt_test")) {
                assertTrue(rs.next());
                assertEquals(100, rs.getInt("cnt"));
            }
        }
    }

    @Test
    @DisplayName("[STMT-05] 查询空结果集")
    void testEmptyResultSet() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM stmt_test WHERE id = -1")) {
            assertFalse(rs.next());
        }
    }

    @Test
    @DisplayName("[STMT-06] DELETE 操作")
    void testDelete() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO stmt_test VALUES (1001, 'Temp', 1.0, true)");
            stmt.execute("INSERT INTO stmt_test VALUES (1002, 'Temp2', 2.0, false)");

            int deleted = stmt.executeUpdate("DELETE FROM stmt_test WHERE id = 1001");
            assertEquals(1, deleted);

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stmt_test WHERE id >= 1000")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"));
            }
        }
    }

    @Test
    @DisplayName("[STMT-07] executeUpdate DDL 返回 0")
    void testDdlReturnsZero() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            int rows = stmt.executeUpdate("CREATE TABLE ddl_test (x INTEGER)");
            assertEquals(0, rows);
        }
    }

    @Test
    @DisplayName("[STMT-08] WITH 语句通过 execute 路由到查询")
    void testWithStatement() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("WITH t AS (SELECT 1 AS v) SELECT * FROM t");
            assertTrue(hasResultSet);
        }
    }

    @Test
    @DisplayName("[STMT-09] SHOW 语句通过 execute 路由到查询")
    void testShowStatement() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            boolean hasResultSet = stmt.execute("SHOW TABLES");
            assertTrue(hasResultSet);
        }
    }

    @Test
    @DisplayName("[STMT-10] Statement 重用自动关闭前一个 ResultSet")
    void testStatementReuseClosePreviousResultSet() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery("SELECT 1 AS a");
            ResultSet rs2 = stmt.executeQuery("SELECT 2 AS b");
            assertTrue(rs1.isClosed(), "重用 Statement 应关闭前一个 ResultSet");
            assertFalse(rs2.isClosed());
            rs2.close();
        }
    }

    @Test
    @DisplayName("[STMT-11] 同时多个 Statement 并行工作")
    void testMultipleStatements() throws SQLException {
        try (Statement s1 = conn.createStatement();
             Statement s2 = conn.createStatement()) {

            s1.execute("INSERT INTO stmt_test VALUES (200, 'S1', 1000, true)");
            s2.execute("INSERT INTO stmt_test VALUES (201, 'S2', 2000, false)");

            try (ResultSet rs = s1.executeQuery("SELECT COUNT(*) AS cnt FROM stmt_test WHERE id >= 200")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("cnt"));
            }
        }
    }

    @Test
    @DisplayName("[STMT-12] close 后所有操作应抛异常")
    void testOperationsAfterStatementClose() throws SQLException {
        Statement stmt = conn.createStatement();
        stmt.execute("INSERT INTO stmt_test VALUES (999, 'CloseTest', 0, true)");
        stmt.close();

        assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT 1"));
        assertThrows(SQLException.class, () -> stmt.executeUpdate("UPDATE stmt_test SET name = 'x' WHERE id = 999"));
        assertThrows(SQLException.class, () -> stmt.execute("SELECT 1"));
    }
}
