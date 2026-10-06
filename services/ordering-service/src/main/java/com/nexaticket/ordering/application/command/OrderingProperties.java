// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.command;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tham số vận hành của checkout.
 *
 * @param paymentWindow khách có bao lâu để chuyển khoản. Cũng là lưới an toàn cuối cùng của saga:
 *     hết hạn này thì ghế được nhả dù mọi cơ chế bù trừ khác đã hỏng.
 * @param remoteTimeout hạn cho mỗi lời gọi nội bộ. Ngắn là cố ý — khách đang chờ màn hình, và
 *     retry trong luồng đồng bộ sẽ vượt ngân sách thời gian của họ (sagas.md §2).
 */
@ConfigurationProperties(prefix = "nexaticket.ordering")
public record OrderingProperties(Duration paymentWindow, Duration remoteTimeout) {

    public OrderingProperties {
        paymentWindow = paymentWindow == null ? Duration.ofMinutes(15) : paymentWindow;
        remoteTimeout = remoteTimeout == null ? Duration.ofSeconds(2) : remoteTimeout;
    }
}
