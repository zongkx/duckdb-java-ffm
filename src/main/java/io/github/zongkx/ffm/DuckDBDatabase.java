package io.github.zongkx.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.sql.SQLException;

public class DuckDBDatabase implements AutoCloseable {

    /**
     * duckdb_database*
     * <p>
     * 对应：
     * <p>
     * duckdb_database db;
     * duckdb_open(path, &db);
     * <p>
     * 保存的是 &db
     */
    private final MemorySegment dbPtr;

    /**
     * duckdb_database
     * <p>
     * 真正数据库对象
     */
    private final MemorySegment dbHandle;

    private final Arena arena = Arena.ofShared();

    private volatile boolean closed = false;


    public DuckDBDatabase() {
        this(null);   // 或者调用另一个接收 String 的构造器，传入 null
    }

    public DuckDBDatabase(String path) {
        try {
            MemorySegment cPath = (path != null) ? arena.allocateFrom(path) : MemorySegment.NULL;
            dbPtr = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) DuckDBNative.duckdb_open.HANDLE.invokeExact(cPath, dbPtr);
            if (rc != 0) {
                throw new RuntimeException(
                        "duckdb_open failed, rc=" + rc
                );
            }
            dbHandle = dbPtr.get(
                    ValueLayout.ADDRESS,
                    0
            );
            if (dbHandle == null
                    || dbHandle.equals(MemorySegment.NULL)) {

                throw new RuntimeException(
                        "duckdb_open success but dbHandle is NULL"
                );

            }

        } catch (Throwable t) {

            try {

                arena.close();

            } catch (Exception ignore) {

            }

            throw new RuntimeException(t);

        }

    }

    /**
     * 返回真正数据库句柄
     * <p>
     * 用于：
     * <p>
     * duckdb_connect
     */
    public MemorySegment getHandle() {

        if (closed) {

            throw new IllegalStateException(
                    "Database already closed"
            );

        }

        return dbHandle;

    }

    @Override
    public void close() {

        if (closed) {

            return;

        }

        closed = true;

        try {

            System.out.println(
                    "duckdb_close"
            );

            System.out.println(
                    "dbPtr    = " + dbPtr
            );

            System.out.println(
                    "dbHandle = " + dbHandle
            );

            // 注意这里必须传 dbPtr
            // duckdb_close(
            //      duckdb_database*
            // )
            DuckDBNative.duckdb_close.HANDLE
                    .invokeExact(
                            dbPtr
                    );

        } catch (Throwable t) {

            t.printStackTrace();

        } finally {

            if (arena.scope().isAlive()) {

                arena.close();

            }

        }

    }

    public DuckDBConnection connect() throws SQLException {
        return new DuckDBConnection(this);
    }
}