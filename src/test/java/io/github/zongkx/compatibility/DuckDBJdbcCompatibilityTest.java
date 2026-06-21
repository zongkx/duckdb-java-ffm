package io.github.zongkx.compatibility;

import io.github.zongkx.DuckDBDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 规范一致性测试 (TCK - Technology Compatibility Kit)
 * <p>
 * 验证实现是否符合 JDBC 4.3 规范的核心契约要求：
 * - Driver 注册与 URL 协议校验
 * - Connection 生命周期与默认状态
 * - Statement / ResultSet 默认行为
 * - Wrapper 模式 (unwrap / isWrapperFor)
 * - 规范要求的异常与边界行为
 */
@Tag("tck")
@DisplayName("JDBC 规范一致性测试 (TCK)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class DuckDBJdbcCompatibilityTest {

    private static final String VALID_URL = "jdbc:duckdb:";
    private static final String MEMORY_URL = "jdbc:duckdb:memory";
    private static Connection conn;

    @BeforeAll
    static void globalSetup() throws SQLException {
        conn = DriverManager.getConnection(MEMORY_URL);
    }

    @AfterAll
    static void globalTeardown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ============================================================
    // §1. Driver 规范一致性
    // ============================================================

    @Test
    @Order(101)
    @DisplayName("[TCK-DRIVER-01] Driver 应通过 ServiceLoader SPI 自动注册到 DriverManager")
    void testDriverAutoRegistration() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);
        assertNotNull(driver, "DriverManager 应能通过 jdbc:duckdb: 协议找到注册的驱动");
        assertInstanceOf(DuckDBDriver.class, driver, "找到的驱动应为 DuckDBDriver 类型");
    }

    @Test
    @Order(102)
    @DisplayName("[TCK-DRIVER-02] acceptsURL 应正确识别有效/无效 URL")
    void testAcceptsUrl() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);

        // 合法 URL
        assertTrue(driver.acceptsURL("jdbc:duckdb:"), "应接受标准协议头");
        assertTrue(driver.acceptsURL("jdbc:duckdb:memory"), "应接受内存库 URL");
        assertTrue(driver.acceptsURL("jdbc:duckdb:/tmp/test.db"), "应接受文件库 URL");
        assertTrue(driver.acceptsURL("jdbc:duckdb:test.db;readonly=true"), "应接受携带参数的 URL");

        // 非法 URL
        assertTrue(driver.acceptsURL("jdbc:duckdb:"), "标准 jdbc:duckdb: 协议头应被接受");
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost"), "MySQL 协议应拒绝");
        assertFalse(driver.acceptsURL(""), "空字符串应拒绝");
        assertFalse(driver.acceptsURL(null), "null 应拒绝");
        assertFalse(driver.acceptsURL("duckdb:jmimic:memory"), "缺少 jdbc: 前缀应拒绝");
    }

    @Test
    @Order(103)
    @DisplayName("[TCK-DRIVER-03] getMajorVersion / getMinorVersion 应返回非负整数")
    void testDriverVersions() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);
        assertTrue(driver.getMajorVersion() >= 0, "Major version 应为非负整数");
        assertTrue(driver.getMinorVersion() >= 0, "Minor version 应为非负整数");
    }

    @Test
    @Order(104)
    @DisplayName("[TCK-DRIVER-04] jdbcCompliant() 可返回 false（非完全兼容）")
    void testJdbcCompliant() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);
        assertFalse(driver.jdbcCompliant(), "非完全 JDBC 兼容实现应返回 false");
    }

    @Test
    @Order(105)
    @DisplayName("[TCK-DRIVER-05] getPropertyInfo 应返回非 null 数组")
    void testPropertyInfo() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);
        DriverPropertyInfo[] info = driver.getPropertyInfo(VALID_URL, new Properties());
        assertNotNull(info, "getPropertyInfo 不应返回 null");
    }

    @Test
    @Order(106)
    @DisplayName("[TCK-DRIVER-06] getParentLogger 应抛出 SQLFeatureNotSupportedException")
    void testParentLogger() throws SQLException {
        Driver driver = DriverManager.getDriver(VALID_URL);
        assertThrows(SQLFeatureNotSupportedException.class, driver::getParentLogger,
                "getParentLogger 应抛出 SQLFeatureNotSupportedException");
    }

    // ============================================================
    // §2. Connection 规范一致性
    // ============================================================

    @Test
    @Order(201)
    @DisplayName("[TCK-CONN-01] 默认 autoCommit 应为 true")
    void testDefaultAutoCommit() throws SQLException {
        assertTrue(conn.getAutoCommit(), "JDBC 规范要求默认 autoCommit = true");
    }

    @Test
    @Order(202)
    @DisplayName("[TCK-CONN-02] 默认事务隔离级别应不为 -1")
    void testDefaultTransactionIsolation() throws SQLException {
        int level = conn.getTransactionIsolation();
        boolean valid = level == Connection.TRANSACTION_NONE
                || level == Connection.TRANSACTION_READ_UNCOMMITTED
                || level == Connection.TRANSACTION_READ_COMMITTED
                || level == Connection.TRANSACTION_REPEATABLE_READ
                || level == Connection.TRANSACTION_SERIALIZABLE;
        assertTrue(valid, "事务隔离级别应返回 JDBC 规范定义的常量之一，当前: " + level);
    }

    @Test
    @Order(203)
    @DisplayName("[TCK-CONN-03] 默认 catalog 应返回有效值")
    void testDefaultCatalog() throws SQLException {
        assertNotNull(conn.getCatalog(), "getCatalog() 不应返回 null");
    }

    @Test
    @Order(204)
    @DisplayName("[TCK-CONN-04] 默认 schema 应返回有效值")
    void testDefaultSchema() throws SQLException {
        String schema = conn.getSchema();
        assertNotNull(schema, "getSchema() 不应返回 null");
    }

    @Test
    @Order(205)
    @DisplayName("[TCK-CONN-05] createStatement 应返回非 null 的 Statement")
    void testCreateStatement() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertNotNull(stmt, "createStatement() 不应返回 null");
        }
    }

    @Test
    @Order(206)
    @DisplayName("[TCK-CONN-06] isClosed() 在 close() 前后行为正确")
    void testIsClosedAfterClose() throws SQLException {
        Connection c = DriverManager.getConnection(MEMORY_URL);
        assertFalse(c.isClosed(), "close() 前应返回 false");
        c.close();
        assertTrue(c.isClosed(), "close() 后应返回 true");
    }

    @Test
    @Order(207)
    @DisplayName("[TCK-CONN-07] close() 后调用方法应抛出 SQLException")
    void testOperationsAfterClose() throws SQLException {
        Connection c = DriverManager.getConnection(MEMORY_URL);
        c.close();
        assertThrows(SQLException.class, c::createStatement, "close() 后 createStatement() 应抛异常");
    }

    @Test
    @Order(208)
    @DisplayName("[TCK-CONN-08] isValid(负数超时) 应抛出 SQLException")
    void testIsValidWithNegativeTimeout() {
        assertThrows(SQLException.class, () -> conn.isValid(-1));
    }

    @Test
    @Order(209)
    @DisplayName("[TCK-CONN-09] isValid(5) 应返回 true")
    void testIsValid() throws SQLException {
        assertTrue(conn.isValid(5), "有效连接 isValid(5) 应返回 true");
    }

    @Test
    @Order(210)
    @DisplayName("[TCK-CONN-10] close() 后 isValid 应返回 false")
    void testIsValidAfterClose() throws SQLException {
        Connection c = DriverManager.getConnection(MEMORY_URL);
        c.close();
        assertFalse(c.isValid(5), "close() 后 isValid() 应返回 false");
    }

    @Test
    @Order(211)
    @DisplayName("[TCK-CONN-11] setReadOnly(true) 应抛出 SQLFeatureNotSupportedException")
    void testReadOnly() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.setReadOnly(true));
    }

    @Test
    @Order(212)
    @DisplayName("[TCK-CONN-12] getWarnings / clearWarnings 不应抛异常")
    void testWarnings() throws SQLException {
        assertNull(conn.getWarnings(), "初始 getWarnings 应为 null");
        conn.clearWarnings();
    }

    @Test
    @Order(213)
    @DisplayName("[TCK-CONN-13] getTypeMap 应返回非 null Map")
    void testTypeMap() throws SQLException {
        assertNotNull(conn.getTypeMap(), "getTypeMap() 不应返回 null");
    }

    @Test
    @Order(214)
    @DisplayName("[TCK-CONN-14] setHoldability / getHoldability 不应抛异常")
    void testHoldability() throws SQLException {
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, conn.getHoldability(),
                "默认 holdability 应为 CLOSE_CURSORS_AT_COMMIT");
    }

    @Test
    @Order(215)
    @DisplayName("[TCK-CONN-15] 未实现的方法应抛出 SQLFeatureNotSupportedException")
    void testUnsupportedMethodsThrow() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.prepareCall("SELECT 1"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.setSavepoint());
    }

    // ============================================================
    // §3. Statement 规范一致性
    // ============================================================

    @Test
    @Order(301)
    @DisplayName("[TCK-STMT-01] 默认 ResultSet 类型应为 TYPE_FORWARD_ONLY")
    void testDefaultResultSetType() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, stmt.getResultSetType());
        }
    }

    @Test
    @Order(302)
    @DisplayName("[TCK-STMT-02] 默认并发模式应为 CONCUR_READ_ONLY")
    void testDefaultConcurrency() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());
        }
    }

    @Test
    @Order(303)
    @DisplayName("[TCK-STMT-03] 默认 FetchDirection 应为 FETCH_FORWARD")
    void testDefaultFetchDirection() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertEquals(ResultSet.FETCH_FORWARD, stmt.getFetchDirection());
        }
    }

    @Test
    @Order(304)
    @DisplayName("[TCK-STMT-04] isClosed() 在 close() 前后正确")
    void testStatementIsClosed() throws SQLException {
        Statement stmt = conn.createStatement();
        assertFalse(stmt.isClosed());
        stmt.close();
        assertTrue(stmt.isClosed());
    }

    @Test
    @Order(305)
    @DisplayName("[TCK-STMT-05] close() 后 executeQuery 应抛出 SQLException")
    void testStatementExecuteAfterClose() throws SQLException {
        Statement stmt = conn.createStatement();
        stmt.close();
        assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT 1"));
    }

    @Test
    @Order(306)
    @DisplayName("[TCK-STMT-06] getConnection() 应返回创建者 Connection")
    void testStatementGetConnection() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertSame(conn, stmt.getConnection());
        }
    }

    @Test
    @Order(307)
    @DisplayName("[TCK-STMT-07] 查询后 getUpdateCount 应返回 -1")
    void testGetUpdateCountForQuery() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertEquals(-1, stmt.getUpdateCount());
        }
    }

    @Test
    @Order(308)
    @DisplayName("[TCK-STMT-08] getMoreResults() 应返回 false")
    void testGetMoreResults() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertFalse(stmt.getMoreResults());
        }
    }

    @Test
    @Order(309)
    @DisplayName("[TCK-STMT-09] cancel() 应抛出 SQLFeatureNotSupportedException")
    void testCancel() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class, stmt::cancel);
        }
    }

    @Test
    @Order(310)
    @DisplayName("[TCK-STMT-10] setCursorName 应抛出 SQLFeatureNotSupportedException")
    void testSetCursorName() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> stmt.setCursorName("c1"));
        }
    }

    // ============================================================
    // §4. PreparedStatement 规范一致性
    // ============================================================

    @Test
    @Order(401)
    @DisplayName("[TCK-PSTMT-01] prepareStatement 应返回有效的 PreparedStatement")
    void testPrepareStatement() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT 1")) {
            assertNotNull(pstmt);
            assertFalse(pstmt.isClosed());
        }
    }

    @Test
    @Order(402)
    @DisplayName("[TCK-PSTMT-02] clearParameters() 不应抛异常")
    void testClearParameters() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT ?")) {
            pstmt.setInt(1, 42);
            pstmt.clearParameters();
        }
    }

    @Test
    @Order(403)
    @DisplayName("[TCK-PSTMT-03] 不支持的 set 方法应抛 SQLFeatureNotSupportedException")
    void testUnsupportedSetMethods() throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT 1")) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> pstmt.setDate(1, java.sql.Date.valueOf("2024-01-01")));
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> pstmt.setTime(1, java.sql.Time.valueOf("12:00:00")));
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> pstmt.setBytes(1, new byte[]{1, 2, 3}));
        }
    }

    // ============================================================
    // §5. ResultSet 规范一致性
    // ============================================================

    @Test
    @Order(501)
    @DisplayName("[TCK-RS-01] 默认 ResultSet 类型应为 TYPE_FORWARD_ONLY")
    void testResultSetType() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS val")) {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
        }
    }

    @Test
    @Order(502)
    @DisplayName("[TCK-RS-02] 默认并发模式应为 CONCUR_READ_ONLY")
    void testResultSetConcurrency() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS val")) {
            assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
        }
    }

    @Test
    @Order(503)
    @DisplayName("[TCK-RS-03] close() 前后 isClosed 正确")
    void testResultSetIsClosed() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1");
        assertFalse(rs.isClosed());
        rs.close();
        assertTrue(rs.isClosed());
        stmt.close();
    }

    @Test
    @Order(504)
    @DisplayName("[TCK-RS-04] findColumn 不区分大小写")
    void testFindColumnCaseInsensitive() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS \"MyColumn\"")) {
            rs.next();
            assertEquals(42, rs.getInt("mycolumn"));
            assertEquals(42, rs.getInt("MYCOLUMN"));
            assertEquals(42, rs.getInt("MyColumn"));
        }
    }

    @Test
    @Order(505)
    @DisplayName("[TCK-RS-05] findColumn 对不存在列应抛出 SQLException")
    void testFindColumnNotFound() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS val")) {
            assertThrows(SQLException.class, () -> rs.findColumn("nonexistent"));
        }
    }

    @Test
    @Order(506)
    @DisplayName("[TCK-RS-06] wasNull() 语义正确")
    void testWasNull() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT NULL AS val")) {
            rs.next();
            assertNull(rs.getString("val"));
            assertTrue(rs.wasNull());
            // 读取非 null 列后会重置
            try (Statement stmt2 = conn.createStatement();
                 ResultSet rs2 = stmt2.executeQuery("SELECT 1 AS val")) {
                rs2.next();
                rs2.getInt("val");
                assertFalse(rs2.wasNull());
            }
        }
    }

    @Test
    @Order(507)
    @DisplayName("[TCK-RS-07] getFetchDirection / getType 不应抛异常")
    void testResultSetMetaDefaults() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS val")) {
            assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        }
    }
}
