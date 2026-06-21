package io.github.zongkx.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.sql.SQLException;

public class DuckDBDatabase implements AutoCloseable {

    private final MemorySegment dbPtr;
    private final MemorySegment dbHandle;
    private final Arena arena = Arena.ofShared();
    private volatile boolean closed = false;

    public DuckDBDatabase() {
        this(null);
    }

    public DuckDBDatabase(String path) {
        try {
            MemorySegment cPath = (path != null) ? arena.allocateFrom(path) : MemorySegment.NULL;
            dbPtr = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) DuckDBNative.duckdb_open.HANDLE.invokeExact(cPath, dbPtr);
            if (rc != 0) {
                throw new RuntimeException("duckdb_open failed, rc=" + rc);
            }
            dbHandle = dbPtr.get(ValueLayout.ADDRESS, 0);
            if (dbHandle == null || dbHandle.equals(MemorySegment.NULL)) {
                throw new RuntimeException("duckdb_open success but dbHandle is NULL");
            }
        } catch (Throwable t) {
            try {
                arena.close();
            } catch (Exception ignore) {
            }
            throw new RuntimeException(t);
        }

    }

    public MemorySegment getHandle() {
        if (closed) {
            throw new IllegalStateException("Database already closed");
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
            DuckDBNative.duckdb_close.HANDLE.invokeExact(dbPtr);
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