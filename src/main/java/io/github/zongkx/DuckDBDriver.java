package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBDatabase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class DuckDBDriver implements java.sql.Driver {

    static final String DUCKDB_URL_PREFIX = "jdbc:duckdb:";

    // 数据库实例缓存（包括内存库）
    private static final Map<String, DatabaseRef> pinnedDatabases = new HashMap<>();
    private static final ReentrantLock dbLock = new ReentrantLock();
    private static volatile boolean shutdownHookRegistered = false;

    static {
        try {
            DriverManager.registerDriver(new DuckDBDriver());
        } catch (SQLException e) {
            throw new RuntimeException("无法注册 DuckDB 驱动", e);
        }
    }

    static String dbNameFromUrl(String url) throws SQLException {
        if (null == url) {
            throw new SQLException("Invalid null URL specified");
        }
        if (!url.startsWith(DUCKDB_URL_PREFIX)) {
            throw new SQLException("DuckDB JDBC URL needs to start with 'jdbc:duckdb:'");
        }
        final String shortUrl;
        if (url.contains(";")) {
            String[] parts = url.split(";");
            shortUrl = parts[0].trim();
        } else {
            shortUrl = url;
        }
        String dbName = shortUrl.substring(DUCKDB_URL_PREFIX.length()).trim();
        if (dbName.length() == 0) {
            dbName = MEMORY_DB;
        }
        if (dbName.startsWith(MEMORY_DB.substring(1))) {
            dbName = ":" + dbName;
        }
        return dbName;
    }

    static final String MEMORY_DB = ":memory:";

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        Properties finalProps = info == null ? new Properties() : (Properties) info.clone();
        ParsedProps urlProps = parsePropsFromUrl(url);
        finalProps.putAll(urlProps.props);

        String dbPath = dbNameFromUrl(url);

        dbLock.lock();
        try {
            DatabaseRef ref = pinnedDatabases.get(dbPath);
            if (ref == null) {
                DuckDBDatabase db = new DuckDBDatabase(dbPath);
                ref = new DatabaseRef(db);
                pinnedDatabases.put(dbPath, ref);
            }
            ref.acquire();
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(DuckDBDriver::shutdownAllDatabases));
                shutdownHookRegistered = true;
            }
            DuckDBConnection nativeConn = new DuckDBConnection(ref.getDatabase());
            return new DuckDBJdbcConnection(nativeConn, dbPath, finalProps);

        } catch (Exception e) {
            throw new SQLException("建立 DuckDB 连接失败, URL: " + url, e);
        } finally {
            dbLock.unlock();
        }
    }

    // 由 DuckDBJdbcConnection.close() 调用，递减引用计数
    static void releaseDatabase(String dbPath) {
        dbLock.lock();
        try {
            DatabaseRef ref = pinnedDatabases.get(dbPath);
            if (ref != null) {
                if (ref.release() == 0) {
                    ref.getDatabase().close();
                    pinnedDatabases.remove(dbPath);
                }
            }
        } finally {
            dbLock.unlock();
        }
    }

    private static void shutdownAllDatabases() {
        dbLock.lock();
        try {
            for (DatabaseRef ref : pinnedDatabases.values()) {
                try {
                    ref.getDatabase().close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            pinnedDatabases.clear();
        } finally {
            dbLock.unlock();
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(DUCKDB_URL_PREFIX);
    }

    // 解析分号分隔的 URL 参数（保留原有风格）
    private static ParsedProps parsePropsFromUrl(String url) throws SQLException {
        if (!url.contains(";")) {
            return new ParsedProps(url, new LinkedHashMap<>());
        }
        String[] parts = url.split(";");
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String entry = parts[i].trim();
            if (entry.isEmpty()) continue;
            String[] kv = entry.split("=", 2);
            if (kv.length != 2) {
                throw new SQLException("无效的 URL 配置项: " + entry);
            }
            props.put(kv[0].trim(), kv[1].trim());
        }
        return new ParsedProps(parts[0].trim(), props);
    }

    // 内部类：包装数据库实例 + 引用计数
    private static class DatabaseRef {
        private final DuckDBDatabase database;
        private final AtomicInteger refCount = new AtomicInteger(0);

        DatabaseRef(DuckDBDatabase database) {
            this.database = database;
        }

        DuckDBDatabase getDatabase() {
            return database;
        }

        void acquire() {
            refCount.incrementAndGet();
        }

        int release() {
            return refCount.decrementAndGet();
        }
    }

    private static class ParsedProps {
        final String shortUrl;
        final LinkedHashMap<String, String> props;

        ParsedProps(String shortUrl, LinkedHashMap<String, String> props) {
            this.shortUrl = shortUrl;
            this.props = props;
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}