// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.platform.test.PostgresSingleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Câu {@code DELETE ... LIMIT} của {@link IdempotencyStore#purgeOlderThan} trên PostgreSQL thật.
 *
 * <p>Mọi dòng thuộc một người dùng sinh ngẫu nhiên mỗi lần chạy, và mọi đếm chỉ tính dòng của người dùng đó:
 * container được dùng lại giữa các lần chạy và giữa các lớp IT, nên đếm cả bảng sẽ lẫn dữ liệu của test khác.
 */
class IdempotencyCleanupJobIT {

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
    }

    private void insert(UUID user, String key, String age, boolean complete) {
        jdbc.update(
                "INSERT INTO idempotency_records (id, user_id, idem_key, request_hash, response_status, created_at) "
                        + "VALUES (?, ?, ?, 'h', ?, now() - (?::text)::interval)",
                UUID.randomUUID(),
                user,
                key,
                complete ? 201 : null,
                age);
    }

    private int count(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id = ?", Integer.class, user);
    }

    @Test
    @DisplayName("Xoá bản ghi cũ hơn thời hạn giữ, GIỮ bản ghi còn trong hạn — cả đã hoàn tất lẫn đang dở")
    void xoa_cu_giu_moi() {
        UUID user = UUID.randomUUID();
        insert(user, "cu-xong", "72 hours", true);
        insert(user, "cu-do", "72 hours", false);
        insert(user, "moi-xong", "1 hour", true);
        insert(user, "moi-do", "1 minute", false);
        IdempotencyStore store = new IdempotencyStore(jdbc);

        // Toàn bảng có thể còn dòng cũ của lớp IT khác; ta chỉ khẳng định về dòng của người dùng này.
        store.purgeOlderThan(Duration.ofHours(48), 10_000);

        assertThat(count(user)).isEqualTo(2);
        assertThat(store.find(user, "moi-xong")).isPresent();
        assertThat(store.find(user, "moi-do")).isPresent();
        assertThat(store.find(user, "cu-xong")).isEmpty();
        assertThat(store.find(user, "cu-do")).isEmpty();
    }

    @Test
    @DisplayName("Chia lô: mỗi câu lệnh xoá tối đa `batch` dòng, job lặp tới khi sạch")
    void chia_lo() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 25; i++) {
            insert(user, "cu-" + i, "100 hours", true);
        }
        insert(user, "moi", "5 minutes", true);
        IdempotencyStore store = new IdempotencyStore(jdbc);
        IdempotencyProperties props = new IdempotencyProperties(null, null, null, Duration.ofHours(48), null, 10);

        int total = new IdempotencyCleanupJob(store, props).runOnce();

        assertThat(total).as("có thể kèm dòng cũ của lớp IT khác").isGreaterThanOrEqualTo(25);
        assertThat(count(user)).as("chỉ còn dòng mới").isEqualTo(1);
    }

    @Test
    @DisplayName("Một lệnh xoá không vượt `batch`")
    void khong_vuot_lo() {
        UUID user = UUID.randomUUID();
        // Đẩy tất cả dòng cũ của người dùng này lên đầu hàng đợi xoá (cũ nhất trước) để chắc chắn bị chọn.
        for (int i = 0; i < 5; i++) {
            insert(user, "rat-cu-" + i, "9000 hours", true);
        }
        IdempotencyStore store = new IdempotencyStore(jdbc);

        int deleted = store.purgeOlderThan(Duration.ofHours(48), 3);

        assertThat(deleted).isEqualTo(3);
    }
}
