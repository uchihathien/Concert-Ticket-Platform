// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.platform.test.PostgresSingleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Ngữ nghĩa thật của {@link IdempotencyStore} và {@link ProcessedEvents} trên PostgreSQL.
 *
 * <p>Không dựng Spring: chạy thẳng script migration của module rồi dùng JdbcTemplate. Mọi khoá
 * sinh theo UUID ngẫu nhiên vì container được dùng lại giữa các lần chạy build.
 */
class IdempotencyStoreIT {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void dung_schema() throws Exception {
        PostgreSQLContainer<?> pg = PostgresSingleton.forDatabase("idempotency_it_db");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = 'idempotency_records'", Integer.class);
        if (exists == null || exists == 0) {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/platform/V0002__idempotency.sql")));
        }
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS processed_events (consumer_queue TEXT NOT NULL, event_id TEXT NOT NULL, "
                        + "PRIMARY KEY (consumer_queue, event_id))");
    }

    @Test
    @DisplayName("tryBegin: chỉ lần đầu giành được; lần sau cùng (user, key) trả false")
    void try_begin_chi_lan_dau() {
        IdempotencyStore store = new IdempotencyStore(jdbc);
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();

        assertThat(store.tryBegin(user, key, "h1")).isTrue();
        assertThat(store.tryBegin(user, key, "h1")).isFalse();
        assertThat(store.tryBegin(UUID.randomUUID(), key, "h1"))
                .as("người dùng khác, cùng key")
                .isTrue();
    }

    @Test
    @DisplayName("vòng đời: đang chạy -> hoàn tất -> release không xoá câu trả lời đã lưu")
    void vong_doi() {
        IdempotencyStore store = new IdempotencyStore(jdbc);
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        store.tryBegin(user, key, "hash");

        assertThat(store.find(user, key)).get().satisfies(e -> {
            assertThat(e.isComplete()).isFalse();
            assertThat(e.requestHash()).isEqualTo("hash");
        });

        store.complete(user, key, 201, "{\"id\":7}");
        store.release(user, key);

        assertThat(store.find(user, key)).get().satisfies(e -> {
            assertThat(e.isComplete()).isTrue();
            assertThat(e.responseStatus()).isEqualTo(201);
            assertThat(e.responseBody()).contains("\"id\"");
        });
    }

    @Test
    @DisplayName("release xoá bản ghi chưa xong để client retry được ngay")
    void release_ban_chua_xong() {
        IdempotencyStore store = new IdempotencyStore(jdbc);
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        store.tryBegin(user, key, "h");

        store.release(user, key);

        assertThat(store.find(user, key)).isEmpty();
        assertThat(store.tryBegin(user, key, "h")).isTrue();
    }

    @Test
    @DisplayName("user null (chưa đăng nhập): find và complete vẫn khớp nhờ IS NOT DISTINCT FROM")
    void user_null() {
        IdempotencyStore store = new IdempotencyStore(jdbc);
        String key = "anon-" + UUID.randomUUID();
        store.tryBegin(null, key, "h");

        store.complete(null, key, 200, "{}");

        assertThat(store.find(null, key))
                .get()
                .extracting(IdempotencyStore.Existing::isComplete)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("ProcessedEvents.markIfNew: lần đầu true, lần hai false, queue khác thì độc lập")
    void processed_events() {
        ProcessedEvents processed = new ProcessedEvents(jdbc);
        String event = UUID.randomUUID().toString();

        assertThat(processed.markIfNew("q.a", event)).isTrue();
        assertThat(processed.markIfNew("q.a", event)).isFalse();
        assertThat(processed.markIfNew("q.b", event)).isTrue();
    }
}
