// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import com.nexaticket.catalog.application.CatalogProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.catalog", "com.nexaticket.platform"})
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
