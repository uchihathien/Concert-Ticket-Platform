// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test của ordering: PostgreSQL thật, ba service ngoài là hàng giả điều
 * khiển được.
 *
 * <p>Ba port kia được thay bằng {@link FakeRemoteServices} chứ không dựng thật, vì thứ cần kiểm ở
 * đây là <b>hành vi khi chúng hỏng</b>: timeout, 5xx, và cả trường hợp bù trừ cũng hỏng. Dựng
 * service thật thì không ép được các tình huống đó một cách xác định.
 *
 * <p>Worker bị tắt: test tự gọi {@code runOnce()} ở đúng thời điểm muốn, thay vì chờ đồng hồ và
 * nhận kết quả bập bênh.
 */
@SpringBootTest(properties = "nexaticket.ordering.workers.enabled=false")
@ActiveProfiles("test")
@Import(FakeRemoteServices.class)
public abstract class OrderingTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("ordering_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
