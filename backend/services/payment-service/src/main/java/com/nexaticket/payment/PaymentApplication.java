// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.payment", "com.nexaticket.platform"})
@ConfigurationPropertiesScan
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
