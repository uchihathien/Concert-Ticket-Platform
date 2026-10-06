// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.ledger", "com.nexaticket.platform"})
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
