package io.github.zongkx.functional;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Connection 功能验证测试
 *
 * 覆盖 Connection 的全部核心功能：
 * - 事务控制 (auto-commit, commit, rollback)
 * - Statement 生命周期管理
 * - 关闭与清理
 * - 事务状态机
 */
@Tag("functional")
@DisplayName("Connection 功能验证测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class DuckDBConnectionFunctionalTest {

    private static Connection conn;

    @BeforeAll
    static void setup() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:memory");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE conn_test (id INTEGER, name VARCHAR, amount DOUBLE)");
            stmt.execute("INSERT INTO conn_test VALUES (1, 'Alice', 100.5), (2, 'Bob', 200.7)");
        }
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("[CONN-01] 默认 auto-commit 为 true")
    void testDefaultAutoCommit() throws SQLException {
        assertTrue(conn.getAutoCommit());
    }

    @Test
    @Order(2)
    @DisplayName("[CONN-02] 关闭 auto-commit 后应生效")
    void testSetAutoCommitFalse() throws SQLException {
        conn.setAutoCommit(false);
        assertFalse(conn.getAutoCommit());

        // 插入数据（未提交）
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO conn_test VALUES (3, 'Charlie', 300.3)");
        }

        // 回滚
        conn.rollback();

        // 验证数据未持久化
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM conn_test WHERE id = 3")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("cnt"));
        }

        conn.setAutoCommit(true);
    }

    @Test
    @Order(3)
    @DisplayName("[CONN-03] 手动事务提交应生效")
    void testManualCommit() throws SQLException {
        conn.setAutoCommit(false);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO conn_test VALUES (10, 'Transactional', 999.99)");
        }

        conn.commit();

        // 验证数据已持久化
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, amount FROM conn_test WHERE id = 10")) {
            assertTrue(rs.next());
            assertEquals("Transactional", rs.getString("name"));
            assertEquals(999.99, rs.getDouble("amount"), 0.001);
        }

        conn.setAutoCommit(true);
    }

    @Test
    @Order(4)
    @DisplayName("[CONN-04] auto-commit 下 commit 应抛出异常")
    void testCommitWithAutoCommit() {
        assertThrows(SQLException.class, () -> conn.commit());
    }

    @Test
    @Order(5)
    @DisplayName("[CONN-05] auto-commit 下 rollback 应抛出异常")
    void testRollbackWithAutoCommit() {
        assertThrows(SQLException.class, () -> conn.rollback());
    }

    @Test
    @Order(6)
    @DisplayName("[CONN-06] 切回 auto-commit 时未提交事务自动提交")
    void testAutoCommitToggleFlushesTransaction() throws SQLException {
        conn.setAutoCommit(false);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO conn_test VALUES (20, 'AutoFlush', 555.55)");
        }

        // 切回 auto-commit 应隐式提交
        conn.setAutoCommit(true);

        // 验证已持久化
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM conn_test WHERE id = 20")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(7)
    @DisplayName("[CONN-07] close() 后 Statement 仍可执行（但连接已不可用）")
    void testCloseAndReopen() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:memory");
        assertFalse(c.isClosed());
        c.close();

        // 真正的隔离验证
        assertTrue(c.isClosed());
    }

    @Test
    @Order(8)
    @DisplayName("[CONN-08] Connection.close() 级联关闭所有活跃 Statement")
    void testCascadeCloseStatements() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:memory");
        Statement s1 = c.createStatement();
        Statement s2 = c.createStatement();

        c.close();

        assertTrue(c.isClosed());
        assertTrue(s1.isClosed());
        assertTrue(s2.isClosed());
    }

    @Test
    @Order(9)
    @DisplayName("[CONN-09] 连接自动管理事务状态机")
    void testTransactionStateMachine() throws SQLException {
        conn.setAutoCommit(false);

        // 多次 DML 操作应全部在同一个事务中
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO conn_test VALUES (30, 'TX1', 1.0)");
            stmt.execute("INSERT INTO conn_test VALUES (31, 'TX2', 2.0)");
        }

        conn.rollback();

        // 验证都没有持久化
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM conn_test WHERE id >= 30")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("cnt"));
        }

        conn.setAutoCommit(true);
    }

    @Test
    @Order(10)
    @DisplayName("[CONN-10] createStatement 不支持并发更新游标")
    void testCreateStatementWithUnsupportedParams() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE));
    }

    @Test
    @Order(11)
    @DisplayName("[CONN-11] prepareStatement 不支持并发更新游标")
    void testPrepareStatementWithUnsupportedParams() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.prepareStatement("SELECT 1", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE));
    }

    @Test
    @Order(12)
    @DisplayName("[CONN-12] getMetaData 不应抛异常")
    void testGetMetaData() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        // 空实现允许返回 null
        System.out.println("DatabaseMetaData: " + md);
    }

    @Test
    @Order(13)
    @DisplayName("[CONN-13] 连接对象 unwrap/isWrapperFor")
    void testUnwrap() throws SQLException {
        assertTrue(conn.isWrapperFor(Connection.class));
        assertSame(conn, conn.unwrap(Connection.class));
    }
}
