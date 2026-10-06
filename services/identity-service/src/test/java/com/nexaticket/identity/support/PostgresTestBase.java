// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test: PostgreSQL thật, Flyway chạy đủ migration của platform và identity.
 *
 * <p>Dùng PostgreSQL thật để test đúng thứ chạy ở production: partial unique index trên slug và ràng buộc membership đều là ràng buộc của database.
 *
 * <p>Vòng đời container do {@link PostgresSingleton} giữ, <b>không</b> do JUnit — xem javadoc ở đó
 * để biết vì sao {@code @Testcontainers} + {@code @Container} làm hỏng lớp test thứ hai.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("identity_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
