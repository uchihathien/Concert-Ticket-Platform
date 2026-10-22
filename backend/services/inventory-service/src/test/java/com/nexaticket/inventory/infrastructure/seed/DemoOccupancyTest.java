// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.seed;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hai chốt chặn của dữ liệu mẫu: cờ bật/tắt và đúng tổ chức mẫu.
 *
 * <p>Chốt này hỏng thì suất diễn THẬT bị bán sẵn chỗ cho khách ảo — nên kiểm rằng nó không đụng
 * vào database chút nào khi không được phép.
 */
class DemoOccupancyTest {

    private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-4000-a000-000000000001");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    @DisplayName("Cờ tắt: không đụng database, kể cả suất của tổ chức mẫu")
    void co_tat() {
        new DemoOccupancy(jdbc, false, DEMO_ORG).applyTo(UUID.randomUUID(), DEMO_ORG);

        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("Cờ bật nhưng suất của tổ chức khác: không đụng database")
    void to_chuc_khac() {
        new DemoOccupancy(jdbc, true, DEMO_ORG).applyTo(UUID.randomUUID(), UUID.randomUUID());

        verifyNoInteractions(jdbc);
    }
}
