// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.http;

import com.nexaticket.notification.domain.port.RecipientDirectory;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang identity-service.
 *
 * <p>Không tra được thì trả rỗng chứ không ném: người gọi là một consumer, và một exception ở đây
 * sẽ khiến message bị giao lại mãi vì một người dùng có thể đã bị xoá. Consumer bỏ qua thư đó và
 * ghi log; thư giao dịch không phải là thứ đáng chặn cả hàng đợi.
 */
@Component
public class IdentityHttpAdapter implements RecipientDirectory {

    private static final Logger log = LoggerFactory.getLogger(IdentityHttpAdapter.class);

    private final RestClient client;

    public IdentityHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.notification.identity-url:http://localhost:8090}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        // Builder ĐƯỢC TIÊM, không phải RestClient.builder() tĩnh: chỉ bản này mang theo
        // correlation id và trace span sang service được gọi. Xem CorrelationPropagation.
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<Recipient> lookup(UUID userId) {
        try {
            UserContactResponse response = client.get()
                    .uri("/internal/users/{userId}", userId)
                    .retrieve()
                    .body(UserContactResponse.class);
            if (response == null || response.email() == null || response.email().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Recipient(response.email(), response.fullName()));
        } catch (RuntimeException e) {
            // Không log userId kèm lỗi ở mức ERROR: đây là đường phụ, và một identity-service chậm
            // không được biến thành một trang log đỏ. Thư sẽ không gửi, và dòng WARN này là dấu vết.
            log.warn("Không tra được người nhận {}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    /** Phải khớp {@code InternalMembershipController.UserContactView} của identity-service. */
    private record UserContactResponse(String userId, String email, String fullName) {}
}
