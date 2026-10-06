// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Realtime gateway — service duy nhat khong co database.
 *
 * <p>Tach khoi inventory-service vi ho so tai nguyen khac han: 10.000 ket noi WebSocket ton RAM va
 * file descriptor nhung gan nhu khong ton CPU, con inventory-service ton CPU va ket noi DB. Gop
 * chung thi phai scale ca hai theo chieu xau nhat cua ca hai (services.md §4).
 */
@SpringBootApplication(
        scanBasePackages = {"com.nexaticket.realtime", "com.nexaticket.platform"},
        exclude = DataSourceAutoConfiguration.class)
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
