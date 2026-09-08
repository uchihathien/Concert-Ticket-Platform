// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbcForReset;

    /**
     * Mọi test bắt đầu từ database rỗng.
     *
     * <p>Container PostgreSQL được dùng lại giữa các lần build ({@code withReuse}), nên không dọn
     * thì mỗi lần chạy kế thừa dữ liệu của lần trước. Điều đó phá mọi khẳng định đếm: một test
     * đếm "có đúng một giao dịch cần đối soát" sẽ xanh lần đầu và đỏ từ lần thứ hai trở đi — kiểu
     * flaky tệ nhất vì nó không lộ ra ở lần chạy đầu tiên.
     */
    @BeforeEach
    void resetPaymentTables() {
        jdbcForReset.update(
                """
                TRUNCATE payment_attempts, webhook_events, payment_intents, escrow_bank_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
