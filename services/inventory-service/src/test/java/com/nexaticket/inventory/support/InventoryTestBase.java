// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.support;

import com.nexaticket.platform.test.PostgresSingleton;
import com.nexaticket.platform.test.RedisSingleton;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test của inventory: PostgreSQL thật + Redis thật.
 *
 * <p>Không thay được bằng hàng giả. Toàn bộ giá trị của bộ test này nằm ở chỗ nó chạy đúng các cơ
 * chế của PostgreSQL — partial unique index, {@code FOR UPDATE SKIP LOCKED}, advisory lock — và
 * đúng tính nguyên tử của script Lua trong Redis. Test trên bản giả sẽ chứng minh một thứ khác với
 * thứ chạy ở production.
 *
 * <p>Worker dọn giữ chỗ hết hạn bị tắt: test tự gọi {@code runOnce()} ở đúng thời điểm muốn, thay
 * vì chờ đồng hồ và nhận kết quả bập bênh.
 */
@SpringBootTest(properties = "nexaticket.inventory.hold-expiry.enabled=false")
@ActiveProfiles("test")
public abstract class InventoryTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("inventory_db");

    @Autowired
    private InventoryFixture fixtureForReset;

    /** Mọi test bắt đầu từ tồn kho rỗng — xem {@link InventoryFixture#reset()} để biết vì sao. */
    @BeforeEach
    void resetInventory() {
        fixtureForReset.reset();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
        RedisSingleton.bind(registry);
    }
}
