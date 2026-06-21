package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBDatabase;
import io.github.zongkx.ffm.DuckDBResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DuckDBTest {

    @Test
    @DisplayName("测试：成功在内存中初始化 DuckDB")
    void testInMemoryDuckDBInitialization() {
        // 1. 初始化数据库
        try (DuckDBDatabase db = new DuckDBDatabase(":memory:");
             DuckDBConnection conn = new DuckDBConnection(db)) {

            System.out.println("成功初始化并建立连接！");

            // 2. 建表与插数据
            conn.query("CREATE TABLE users (id INT, name VARCHAR);");
            conn.query("INSERT INTO users VALUES (1, 'Alice'), (2, 'Bob');");

            // 3. 查询并解析
            try (DuckDBResultSet rs = conn.query("SELECT id, name FROM users;")) {
                while (rs.next()) {
                    String id = rs.getString(0);
                    String name = rs.getString(1);
                    System.out.println("读取到数据 -> ID: " + id + ", Name: " + name);
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("资源释放完毕，安全退出。");
    }


}