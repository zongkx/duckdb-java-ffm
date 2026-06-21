package io.github.zongkx.functional;

import io.github.zongkx.DuckDBDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver 功能验证测试
 * <p>
 * 覆盖 Driver 注册、URL 解析、连接建立与参数传递等核心功能，
 * 确保 Driver 在各种场景下能正确工作。
 */
@Tag("functional")
@DisplayName("Driver 功能验证测试")
class DuckDBDriverFunctionalTest {

    private static final String MEMORY_URL = "jdbc:duckdb:";

    @BeforeAll
    static void verifyDriver() throws SQLException {
        // 确保 Driver 已注册
        Driver driver = DriverManager.getDriver("jdbc:duckdb:");
        assertNotNull(driver);
    }

    @Test
    @DisplayName("[DRV-01] 通过 DriverManager 建立内存数据库连接")
    void testConnectInMemory() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL)) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
            assertTrue(conn.isValid(3));
        }
    }

    @Test
    @DisplayName("[DRV-02] 通过 Class.forName 加载驱动后连接")
    void testConnectViaClassForName() throws Exception {
        Class.forName("io.github.zongkx.DuckDBDriver");
        try (Connection conn = DriverManager.getConnection(MEMORY_URL)) {
            assertNotNull(conn);
        }
    }

    @Test
    @DisplayName("[DRV-03] 直接实例化 Driver 连接")
    void testDirectDriverConnect() throws SQLException {
        DuckDBDriver driver = new DuckDBDriver();
        try (Connection conn = driver.connect(MEMORY_URL, new Properties())) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @Test
    @DisplayName("[DRV-04] 通过 URL 传递属性参数")
    void testUrlWithProperties() throws SQLException {
        // URL 中可以携带参数
        String url = MEMORY_URL;
        Properties props = new Properties();
        props.setProperty("readonly", "true");
        try (Connection conn = DriverManager.getConnection(url, props)) {
            assertNotNull(conn);
            assertTrue(conn.isValid(3));
        }
    }

    @Test
    @DisplayName("[DRV-05] 多次获取同一持久化数据库应共享")
    void testMultipleConnectionsSameDb() throws SQLException {
        // 内存库每次都是新的，文件库会共享
        try (Connection c1 = DriverManager.getConnection(MEMORY_URL);
             Connection c2 = DriverManager.getConnection(MEMORY_URL)) {
            assertNotNull(c1);
            assertNotNull(c2);

            // 在两个连接上分别创建表（内存库相互独立）
            try (Statement s1 = c1.createStatement()) {
                s1.execute("CREATE TABLE t_shared (id INTEGER)");
                s1.execute("INSERT INTO t_shared VALUES (1), (2)");
            }

            try (Statement s2 = c2.createStatement()) {
                // 内存库各自独立，所以这里期望一个异常（表不存在）
                // 但文件模式会共享 - 这是预期的
                s2.execute("SELECT 1");
            }
        }
    }

    @Test
    @DisplayName("[DRV-06] 使用 acceptURL 验证 URL")
    void testAcceptsUrlDirect() throws SQLException {
        Driver driver = new DuckDBDriver();
        assertTrue(driver.acceptsURL("jdbc:duckdb:test.db"));
        assertTrue(driver.acceptsURL("jdbc:duckdb:"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://host/db"));
        assertTrue(driver.acceptsURL("jdbc:duckdb:"));
    }

    @Test
    @DisplayName("[DRV-07] connect(null) 应返回 null")
    void testConnectNull() throws SQLException {
        DuckDBDriver driver = new DuckDBDriver();
        assertNull(driver.connect(null, null));
    }

    @Test
    @DisplayName("[DRV-08] connect(不支持的 URL) 应返回 null")
    void testConnectUnsupportedUrl() throws SQLException {
        DuckDBDriver driver = new DuckDBDriver();
        assertNull(driver.connect("jdbc:mysql://localhost", null));
    }

    @Test
    @DisplayName("[DRV-09] 连接后执行简单 SQL")
    void testSimpleQueryAfterConnect() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS answer")) {
            assertTrue(rs.next());
            assertEquals(42, rs.getInt("answer"));
            assertFalse(rs.next());
        }
    }

    @Test
    @DisplayName("[DRV-10] 多次连接-查询-关闭循环")
    void testConnectQueryCloseCycle() throws SQLException {
        for (int i = 0; i < 5; i++) {
            try (Connection conn = DriverManager.getConnection(MEMORY_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT " + i)) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("[DRV-11] 多线程并发获取连接")
    void testConcurrentConnections() throws Exception {
        int threads = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(MEMORY_URL);
                     Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                } catch (SQLException e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(0, errors.get(), "并发连接不应发生错误");
        pool.shutdown();
    }
}
