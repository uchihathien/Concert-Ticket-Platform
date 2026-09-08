// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.identity", "com.nexaticket.platform"})
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
