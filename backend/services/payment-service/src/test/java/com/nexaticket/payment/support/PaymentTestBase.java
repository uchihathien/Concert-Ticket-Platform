// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test của payment.
 *
 * <p>Worker hết hạn bị tắt để test tự điều khiển thời điểm — riêng ở đây điều đó còn quan trọng
 * hơn chỗ khác: một trong các test dựng đúng tình huống webhook đến cùng lúc worker chạy, và
 * worker chạy ngầm sẽ làm kết quả không lặp lại được.
 */
@SpringBootTest(properties = {"nexaticket.payment.workers.enabled=false", "nexaticket.payment.sepay.api-key=test-key"})
@ActiveProfiles("test")
@Import(FakeOrdering.class)
public abstract class PaymentTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("payment_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
