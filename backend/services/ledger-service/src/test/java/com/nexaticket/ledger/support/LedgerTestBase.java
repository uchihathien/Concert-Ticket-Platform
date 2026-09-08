// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL thật cho test sổ cái.
 *
 * <p>Bắt buộc phải là database thật, không thể thay bằng H2: cả ba bất biến của sổ cái đều do
 * PostgreSQL ép (constraint trigger DEFERRABLE, partial unique index, CHECK). Test trên database
 * giả sẽ chứng minh một thứ khác với thứ chạy ở production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class LedgerTestBase {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger")
            .withReuse(true);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
