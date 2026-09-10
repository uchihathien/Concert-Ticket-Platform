// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketSession;

/**
 * Trạng thái chia sẻ của realtime gateway: sổ kết nối và bộ gộp thay đổi.
 *
 * <p><b>Cố ý nằm riêng, KHÔNG gộp vào {@code WebSocketConfig}.</b> Trước đây hai {@code @Bean} này
 * nằm trong {@code WebSocketConfig}, và hệ quả là service <b>không khởi động được</b>:
 *
 * <pre>
 *   WebSocketConfig ──cần──▶ SeatMapWebSocketHandler ──cần──▶ SessionRegistry, UpdateCoalescer
 *          ▲                                                          │
 *          └──────────────── do WebSocketConfig cung cấp ◀─────────────┘
 * </pre>
 *
 * <p>Một {@code @Configuration} vừa <i>cung cấp</i> bean, vừa <i>nhận</i> một bean cần chính những
 * bean đó, là một vòng tròn phụ thuộc — và Spring Boot cấm vòng tròn theo mặc định từ 2.6. Lỗi này
 * biên dịch được, test được (FanoutTest dựng thủ công, không qua Spring), và chỉ lộ ra khi thật sự
 * chạy app: {@code APPLICATION FAILED TO START ... form a cycle}.
 *
 * <p>Nên đừng dọn hai bean này về lại {@code WebSocketConfig} cho "gọn": đó chính là cách lỗi quay
 * lại. Cách chữa đúng không phải {@code spring.main.allow-circular-references=true} — cờ đó chỉ ẩn
 * vòng tròn đi, và thứ tự khởi tạo sẽ phụ thuộc vào may mắn.
 */
@Configuration
public class RealtimeBeans {

    /** Xem {@link UpdateCoalescer} để biết vì sao không đẩy thẳng từng thay đổi xuống client. */
    @Bean
    public UpdateCoalescer updateCoalescer() {
        return new UpdateCoalescer();
    }

    /**
     * @param maxSessions trần số suất diễn một kết nối được theo dõi — chặn một client mở vô hạn
     *     subscription trên cùng một socket
     */
    @Bean
    public SessionRegistry<WebSocketSession> sessionRegistry(
            @Value("${nexaticket.realtime.max-sessions-per-connection:5}") int maxSessions) {
        return new SessionRegistry<>(maxSessions);
    }
}
