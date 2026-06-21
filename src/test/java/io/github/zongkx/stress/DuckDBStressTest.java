package io.github.zongkx.stress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能与稳定性测试 (Stress Testing)
 * <p>
 * 模拟工业级场景下的多线程并发、高频连接、大数据量和资源泄漏检测。
 */
@Tag("stress")
@DisplayName("性能与稳定性测试")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBStressTest {

    private static final String MEMORY_URL = "jdbc:duckdb:stress_test.db";
    // ============================================================
    // §1. 并发压力测试
    // ============================================================

    @Test
    @Order(101)
    @DisplayName("[STRESS-01] 16 线程并发查询同一个表")
    void testConcurrentQueries() throws Exception {
        // 准备数据
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_concurrent (id INTEGER, val VARCHAR)");
            for (int i = 0; i < 1000; i++) {
                stmt.execute("INSERT INTO stress_concurrent VALUES (" + i + ", 'data" + i + "')");
            }
        }

        int threadCount = 16;
        int queryPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(MEMORY_URL);
                     Statement stmt = c.createStatement()) {
                    for (int q = 0; q < queryPerThread; q++) {
                        int targetId = (threadId * queryPerThread + q) % 1000;
                        try (ResultSet rs = stmt.executeQuery(
                                "SELECT val FROM stress_concurrent WHERE id = " + targetId)) {
                            if (rs.next()) {
                                String val = rs.getString("val");
                                assertNotNull(val);
                                assertTrue(val.startsWith("data"));
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        assertEquals(0, errors.get(), "并发查询不应产生错误，错误数: " + errors.get());
        pool.shutdown();
    }

    @Test
    @Order(102)
    @DisplayName("[STRESS-02] 多线程并发写入并验证")
    void testConcurrentWrites() throws Exception {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_write (id INTEGER PRIMARY KEY, thread_id INTEGER, val VARCHAR)");
        }

        int threadCount = 8;
        int rowsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 1; t <= threadCount; t++) {
            final int tid = t;
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(MEMORY_URL);
                     PreparedStatement pstmt = c.prepareStatement(
                             "INSERT INTO stress_write VALUES (?, ?, ?)")) {
                    for (int i = 0; i < rowsPerThread; i++) {
                        int id = tid * 10000 + i;
                        pstmt.setInt(1, id);
                        pstmt.setInt(2, tid);
                        pstmt.setString(3, "thread-" + tid + "-row-" + i);
                        pstmt.executeUpdate();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        assertEquals(0, errors.get(), "并发写入不应产生错误");

        // 验证总行数
        try (Connection c = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stress_write")) {
            assertTrue(rs.next());
            assertEquals(threadCount * rowsPerThread, rs.getInt("cnt"));
        }

        pool.shutdown();
    }

    @Test
    @Order(103)
    @DisplayName("[STRESS-03] 连接池模拟: 高频获取/释放连接")
    void testConnectionPoolSimulation() throws Exception {
        int iterations = 50;
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            try (Connection conn = DriverManager.getConnection(MEMORY_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT " + i)) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
            } catch (SQLException e) {
                errors.incrementAndGet();
            }
        }

        assertEquals(0, errors.get(), "高频连接释放不应产生错误");
    }

    // ============================================================
    // §2. 大数据量测试
    // ============================================================

    @Test
    @Order(201)
    @DisplayName("[STRESS-04] 写入并读取 10000 行数据")
    void testLargeDataset() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_large (id INTEGER, name VARCHAR, score DOUBLE)");

            // 批量插入
            conn.setAutoCommit(false);
            for (int i = 0; i < 10000; i++) {
                stmt.execute("INSERT INTO stress_large VALUES (" + i + ", 'name-" + i + "', " + (i * 1.5) + ")");
            }
            conn.commit();
            conn.setAutoCommit(true);

            // 验证总行数
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stress_large")) {
                assertTrue(rs.next());
                assertEquals(10000, rs.getInt("cnt"));
            }

            // 验证精确数据
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM stress_large WHERE id = 5000")) {
                assertTrue(rs.next());
                assertEquals("name-5000", rs.getString("name"));
                assertEquals(7500.0, rs.getDouble("score"), 0.001);
            }

            // 聚合查询
            try (ResultSet rs = stmt.executeQuery("SELECT AVG(score) AS avg_score FROM stress_large")) {
                assertTrue(rs.next());
                double avg = rs.getDouble("avg_score");
                assertTrue(avg > 0);
            }
        }
    }

    @Test
    @Order(202)
    @DisplayName("[STRESS-05] 大数据量下流式逐行读取")
    void testLargeResultSetIteration() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_iter (id INTEGER, label VARCHAR)");
            for (int i = 0; i < 5000; i++) {
                stmt.execute("INSERT INTO stress_iter VALUES (" + i + ", 'label-" + i + "')");
            }

            int count = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM stress_iter ORDER BY id")) {
                while (rs.next()) {
                    assertEquals(count, rs.getInt("id"));
                    assertEquals("label-" + count, rs.getString("label"));
                    count++;
                }
            }
            assertEquals(5000, count);
        }
    }

    // ============================================================
    // §3. 资源泄漏检测
    // ============================================================

    @Test
    @Order(301)
    @DisplayName("[STRESS-06] 重复打开关闭数据库不泄漏")
    void testRepeatedOpenClose() throws SQLException {
        for (int i = 0; i < 30; i++) {
            try (Connection conn = DriverManager.getConnection(MEMORY_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT " + i)) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
            }
        }
    }

    @Test
    @Order(302)
    @DisplayName("[STRESS-07] Statement 未手动关闭不泄漏（依赖 Connection close 级联）")
    void testUngracefulStatementCleanup() throws SQLException {
        Connection conn = DriverManager.getConnection(MEMORY_URL);

        // 创建多个 Statement 但不关闭
        for (int i = 0; i < 20; i++) {
            Statement stmt = conn.createStatement();
            stmt.executeQuery("SELECT " + i);
            // 故意不关闭
        }

        // 关闭 Connection 应级联清理所有 Statement
        conn.close();
        assertTrue(conn.isClosed());
    }

    @Test
    @Order(303)
    @DisplayName("[STRESS-08] 混合读写交替操作")
    void testMixedReadWrite() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_mix (k INTEGER PRIMARY KEY, v VARCHAR)");

            for (int round = 0; round < 20; round++) {
                // 写入
                stmt.execute("INSERT OR REPLACE INTO stress_mix VALUES (" + round + ", 'round-" + round + "')");

                // 查询验证
                try (ResultSet rs = stmt.executeQuery("SELECT v FROM stress_mix WHERE k = " + round)) {
                    assertTrue(rs.next());
                    assertEquals("round-" + round, rs.getString("v"));
                }

                // 统计
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stress_mix")) {
                    assertTrue(rs.next());
                    assertEquals(round + 1, rs.getInt("cnt"));
                }
            }
        }
    }

    @Test
    @Order(304)
    @DisplayName("[STRESS-09] 事务密集提交回滚")
    void testTransactionStress() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_tx (id INTEGER)");

            for (int i = 0; i < 50; i++) {
                conn.setAutoCommit(false);
                stmt.execute("INSERT INTO stress_tx VALUES (" + i + ")");

                if (i % 2 == 0) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                conn.setAutoCommit(true);
            }

            // 只有偶数 id 应该存在
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS cnt FROM stress_tx")) {
                assertTrue(rs.next());
                assertEquals(25, rs.getInt("cnt"));
            }
        }
    }

    @Test
    @Order(305)
    @DisplayName("[STRESS-10] 大量 PreparedStatement 复用")
    void testPreparedStatementReuseStress() throws SQLException {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement init = conn.createStatement()) {
            init.execute("CREATE TABLE stress_pstmt (id INTEGER, name VARCHAR)");

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO stress_pstmt VALUES (?, ?)")) {
                for (int i = 0; i < 2000; i++) {
                    pstmt.setInt(1, i);
                    pstmt.setString(2, "user-" + i);
                    pstmt.executeUpdate();
                }
            }

            // 验证少量样本
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT name FROM stress_pstmt WHERE id = ?")) {
                for (int i = 0; i < 100; i++) {
                    pstmt.setInt(1, i * 20);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("user-" + (i * 20), rs.getString("name"));
                    }
                }
            }
        }
    }

    @Test
    @Order(306)
    @DisplayName("[STRESS-11] 长文本读写")
    void testLongString() throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("Hello World ");
        }
        String longStr = sb.toString();

        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_long (id INTEGER, content VARCHAR)");
            stmt.execute("INSERT INTO stress_long VALUES (1, '" + longStr.replace("'", "''") + "')");

            try (ResultSet rs = stmt.executeQuery("SELECT content FROM stress_long WHERE id = 1")) {
                assertTrue(rs.next());
                String result = rs.getString("content");
                assertEquals(longStr, result);
                assertEquals(10000 * 12, result.length()); // "Hello World " = 12 chars
            }
        }
    }

    @Test
    @Order(307)
    @DisplayName("[STRESS-12] 并发环境下 PreparedStatement 复用")
    void testConcurrentPreparedStatements() throws Exception {
        try (Connection conn = DriverManager.getConnection(MEMORY_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE stress_cp (id INTEGER PRIMARY KEY, val VARCHAR)");
            for (int i = 0; i < 500; i++) {
                stmt.execute("INSERT INTO stress_cp VALUES (" + i + ", 'init-" + i + "')");
            }
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int offset = t * 50;
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(MEMORY_URL);
                     PreparedStatement pstmt = c.prepareStatement(
                             "SELECT val FROM stress_cp WHERE id = ?")) {
                    for (int i = 0; i < 50; i++) {
                        pstmt.setInt(1, offset + i);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                assertNotNull(rs.getString("val"));
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        assertEquals(0, errors.get());
        pool.shutdown();
    }
}
