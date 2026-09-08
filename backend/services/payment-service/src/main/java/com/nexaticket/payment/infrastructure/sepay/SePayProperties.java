// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.sepay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey khoá SePay gửi kèm header {@code Authorization: Apikey <key>}
 * @param orderingUrl địa chỉ ordering-service để báo tiền đã về
 */
@ConfigurationProperties(prefix = "nexaticket.payment.sepay")
public record SePayProperties(String apiKey, String orderingUrl) {

    public SePayProperties {
        orderingUrl = orderingUrl == null ? "http://localhost:8093" : orderingUrl;
    }
}
