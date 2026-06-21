package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBDatabase;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class DuckDBDriver implements java.sql.Driver {

    static final String DUCKDB_URL_PREFIX = "jdbc:duckdb:jmimic:";

    private static final Map<String, DuckDBDatabase> pinnedDatabases = new HashMap<>();
    private static final ReentrantLock dbLock = new ReentrantLock();
    private static boolean shutdownHookRegistered = false;

    static {
        try {
            DriverManager.registerDriver(new DuckDBDriver());
        } catch (SQLException e) {
            throw new RuntimeException("无法注册 DuckDB 驱动", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        Properties finalProps = info == null ? new Properties() : (Properties) info.clone();
        ParsedProps urlProps = parsePropsFromUrl(url);
        for (Map.Entry<String, String> entry : urlProps.props.entrySet()) {
            finalProps.put(entry.getKey(), entry.getValue());
        }

        String dbPath = urlProps.shortUrl.substring(DUCKDB_URL_PREFIX.length()).trim();
        if (dbPath.isEmpty()) {
            dbPath = ":memory:";
        }

        dbLock.lock();
        try {
            DuckDBDatabase dbInstance;
            if (":memory:".equals(dbPath)) {
                dbInstance = new DuckDBDatabase(dbPath);
            } else {
                dbInstance = pinnedDatabases.get(dbPath);
                if (dbInstance == null) {
                    dbInstance = new DuckDBDatabase(dbPath);
                    pinnedDatabases.put(dbPath, dbInstance);
                }
            }

            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(DuckDBDriver::shutdownAllDatabases));
                shutdownHookRegistered = true;
            }

            DuckDBConnection nativeConn = new DuckDBConnection(dbInstance);
            return new DuckDBJdbcConnection(nativeConn, dbInstance);

        } catch (Exception e) {
            throw new SQLException("建立 DuckDB 连接失败, URL: " + url, e);
        } finally {
            dbLock.unlock();
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(DUCKDB_URL_PREFIX);
    }

    private static ParsedProps parsePropsFromUrl(String url) throws SQLException {
        if (!url.contains(";")) {
            return new ParsedProps(url, new LinkedHashMap<>());
        }
        String[] parts = url.split(";");
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String entry = parts[i].trim();
            if (entry.isEmpty()) continue;
            String[] kv = entry.split("=");
            if (kv.length != 2) {
                throw new SQLException("无效的 URL 配置项: " + entry);
            }
            props.put(kv[0].trim(), kv[1].trim());
        }
        return new ParsedProps(parts[0].trim(), props);
    }

    private static void shutdownAllDatabases() {
        dbLock.lock();
        try {
            for (DuckDBDatabase db : pinnedDatabases.values()) {
                try { db.close(); } catch (Exception e) { e.printStackTrace(); }
            }
            pinnedDatabases.clear();
        } finally {
            dbLock.unlock();
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

    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}