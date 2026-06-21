package io.github.zongkx.ffm;

import java.lang.foreign.*;

public class DuckDBDatabase implements AutoCloseable {
    private final MemorySegment dbHandle;
    private final Arena arena = Arena.ofShared(); // 允许跨线程共享，供多个 Connection 引用

    public DuckDBDatabase(String path) {
        try {
            MemorySegment cPath = arena.allocateFrom(path);
            MemorySegment outDb = arena.allocate(DuckDBNative.C_POINTER);

            int rc = (int) DuckDBNative.duckdb_open.HANDLE.invokeExact(cPath, outDb);
            if (rc != 0) {
                throw new RuntimeException("无法打开 DuckDB 数据库: " + path);
            }

            this.dbHandle = outDb.get(DuckDBNative.C_POINTER, 0);
        } catch (Throwable t) {
            arena.close();
            throw new RuntimeException(t);
        }
    }

    public MemorySegment getHandle() {
        return dbHandle;
    }

    @Override
    public void close() {
        try {
            if (dbHandle != null && !dbHandle.equals(MemorySegment.NULL)) {
                DuckDBNative.duckdb_close.HANDLE.invokeExact(dbHandle);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            arena.close();
        }
    }
}