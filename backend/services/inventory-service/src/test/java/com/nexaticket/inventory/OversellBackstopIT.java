// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.domain.port.AvailabilityGate;
import com.nexaticket.inventory.support.Concurrently;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chứng minh <b>database</b> mới là chốt chặn oversell, không phải Redis.
 *
 * <p>Bộ test ở {@link SeatHoldConcurrencyIT} chạy với cổng Redis bật, nên nếu chỉ có nó thì ta
 * không biết được ai đang chặn: có thể Redis đang gánh toàn bộ, và ngày Redis failover thì hệ
 * thống bán trùng ghế mà mọi test vẫn xanh.
 *
 * <p>Ở đây cổng được thay bằng bản <b>luôn cho qua</b> — mô phỏng đúng tình huống Redis mất key
 * hoặc vừa failover xong. Nếu database làm đúng việc của nó, kết quả không đổi.
 */
@Import(OversellBackstopIT.AlwaysOpenGateConfig.class)
class OversellBackstopIT extends InventoryTestBase {

    @TestConfiguration
    static class AlwaysOpenGateConfig {

        /** Cổng mù: luôn báo "chỗ nào cũng trống". Đúng như Redis vừa mất sạch key. */
        @Bean
        @Primary
        AvailabilityGate alwaysOpenGate() {
            return new AvailabilityGate() {
                @Override
                public List<UUID> tryAcquire(
                        UUID eventSessionId, List<UUID> seatIds, UUID holdId, UUID userId, int ttlSeconds) {
                    return List.of();
                }

                @Override
                public void release(UUID eventSessionId, List<UUID> seatIds) {
                    // không làm gì
                }
            };
        }
    }

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    InventoryFixture fixture;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Cổng Redis mù, 200 người giành 1 ghế: database vẫn chỉ cho đúng 1 người")
    void database_van_chan_khi_cong_redis_mu() throws Exception {
        var session = fixture.materialize(1, 0);
        UUID theSeat = session.seatIds().get(0);

        List<Callable<UUID>> jobs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            jobs.add(() -> placeHold
                    .handle(new PlaceHoldHandler.Command(session.id(), UUID.randomUUID(), List.of(theSeat), List.of()))
                    .holdId());
        }

        var outcomes = Concurrently.run(jobs);

        assertThat(Concurrently.successes(outcomes)).isEqualTo(1);
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(1);
    }

    @Test
    @DisplayName("Ghi thẳng SQL hai dòng ACTIVE cho cùng một chỗ: unique index từ chối")
    void unique_index_tu_choi_hai_dong_active_cung_mot_cho() {
        var session = fixture.materialize(1, 0);
        UUID seat = session.seatIds().get(0);
        UUID holdA = insertHold(session.id());
        UUID holdB = insertHold(session.id());

        insertItem(holdA, seat, "ACTIVE");

        // Cố tình đi vòng qua domain và ghi thẳng SQL — đúng như một con bug sẽ làm.
        assertThatThrownBy(() -> insertItem(holdB, seat, "ACTIVE")).isInstanceOf(DuplicateKeyException.class);

        // Nhưng dòng đã nhả thì không chiếm index nữa: chỗ dùng lại được sau khi giữ chỗ hết hạn.
        jdbc.update("UPDATE seat_hold_items SET status = 'RELEASED' WHERE hold_id = ?", holdA);
        insertItem(holdB, seat, "ACTIVE");

        assertThat(fixture.countActiveHoldItems(session.id())).isEqualTo(1);
    }

    private UUID insertHold(UUID sessionId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO seat_holds (id, event_session_id, user_id, status, expires_at)
                VALUES (?, ?, ?, 'ACTIVE', now() + interval '10 minutes')
                """,
                id,
                sessionId,
                UUID.randomUUID());
        return id;
    }

    private void insertItem(UUID holdId, UUID seatId, String status) {
        jdbc.update(
                "INSERT INTO seat_hold_items (id, hold_id, session_seat_id, status) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                holdId,
                seatId,
                status);
    }
}
