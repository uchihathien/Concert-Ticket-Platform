// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Nền cho integration test: PostgreSQL thật qua Testcontainers, Flyway chạy đủ migration của
 * platform và identity.
 *
 * <p>Container khai bằng {@link Container} để Testcontainers quản lý vòng đời. Không start trong
 * static block: làm vậy thì khi Docker không chạy, JUnit hỏng ngay ở bước <i>discovery</i> với
 * thông báo khó hiểu thay vì báo lỗi test sạch sẽ.
 *
 * <p>{@code withReuse(true)} giữ container sống giữa các lớp test và giữa các lần chạy — bật bằng
 * {@code testcontainers.reuse.enable=true} trong {@code ~/.testcontainers.properties}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class PostgresTestBase {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("identity_db")
            .withUsername("identity")
            .withPassword("identity")
            .withReuse(true);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
