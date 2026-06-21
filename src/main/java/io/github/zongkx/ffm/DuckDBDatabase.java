package io.github.zongkx.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class DuckDBDatabase implements AutoCloseable {

    /**
     * duckdb_database*
     *
     * 对应：
     *
     * duckdb_database db;
     * duckdb_open(path, &db);
     *
     * 保存的是 &db
     */
    private final MemorySegment dbPtr;

    /**
     * duckdb_database
     *
     * 真正数据库对象
     */
    private final MemorySegment dbHandle;

    private final Arena arena = Arena.ofShared();

    private volatile boolean closed = false;

    public DuckDBDatabase(String path) {

        try {

            MemorySegment cPath =
                    arena.allocateFrom(path);

            // duckdb_database*
            dbPtr =
                    arena.allocate(
                            ValueLayout.ADDRESS
                    );

            int rc =
                    (int) DuckDBNative.duckdb_open.HANDLE.invokeExact(
                            cPath,
                            dbPtr
                    );

            if (rc != 0) {

                throw new RuntimeException(
                        "duckdb_open failed, rc=" + rc
                );

            }

            // duckdb_database
            dbHandle =
                    dbPtr.get(
                            ValueLayout.ADDRESS,
                            0
                    );

            System.out.println(
                    "dbPtr    = " + dbPtr
            );

            System.out.println(
                    "dbHandle = " + dbHandle
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
     *
     * 用于：
     *
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

}