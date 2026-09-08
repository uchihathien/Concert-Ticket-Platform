// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test: PostgreSQL thật, Flyway chạy đủ migration của platform và ledger.
 *
 * <p>Bắt buộc phải là database thật, không thể thay bằng H2: cả ba bất biến của sổ cái đều do PostgreSQL ép (constraint trigger DEFERRABLE, partial unique index, CHECK). Test trên database giả sẽ chứng minh một thứ khác với thứ chạy ở production.
 *
 * <p>Vòng đời container do {@link PostgresSingleton} giữ, <b>không</b> do JUnit — xem javadoc ở đó
 * để biết vì sao {@code @Testcontainers} + {@code @Container} làm hỏng lớp test thứ hai.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class LedgerTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("ledger_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
