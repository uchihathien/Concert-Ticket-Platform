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
 * Nền cho integration test của payment: PostgreSQL thật, payOS và Ordering là hàng giả điều khiển được.
 *
 * <p><b>Database thật, không H2.</b> Phần lớn giá trị của những test này nằm ở chỗ schema và câu SQL gặp
 * nhau: tên cột trong {@code SELECT}, sequence {@code payment_order_code_seq}, partial unique index
 * {@code uq_payos_order_code}, và câu {@code UPDATE … WHERE status = 'PENDING'}. Không cái nào trong số
 * đó sai ở lúc biên dịch — chúng sai ở lần chạy đầu tiên trên production.
 *
 * <p>payOS được thay bằng {@link FakePayos} chứ không gọi thật, và lý do mạnh hơn "cho nhanh": payOS
 * <b>không có môi trường sandbox</b>, nên một test gọi thật sẽ tạo link thanh toán thật và tiêu tiền
 * thật mỗi lần CI chạy.
 *
 * <p>Checksum key ở đây là khoá giả, đặt tường minh: nhiều nhánh của webhook chỉ đi tới được khi service
 * <i>có</i> khoá, và mặc định trong {@code application.yml} là rỗng.
 */
@SpringBootTest(
        properties = {
            "nexaticket.payment.payos.checksum-key=" + PaymentTestBase.CHECKSUM_KEY,
            "nexaticket.payment.payos.client-id=test-client",
            "nexaticket.payment.payos.api-key=test-api-key",
            "nexaticket.payment.payos.web-base-url=https://nexaticket.test",
            "nexaticket.payment.sandbox=true",
            // Job đóng intent quá hạn chạy ngầm sẽ làm test webhook đỏ ngẫu nhiên: nó có thể đóng
            // đúng cái intent mà test vừa mở. Tắt ở đây, và test nào cần thì gọi thẳng runOnce().
            "nexaticket.payment.workers.enabled=false"
        })
@ActiveProfiles("test")
@Import(FakePayos.class)
public abstract class PaymentTestBase {

    public static final String CHECKSUM_KEY = "test-checksum-key";

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("payment_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
