// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.ordering", "com.nexaticket.platform"})
@ConfigurationPropertiesScan
public class OrderingApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderingApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
