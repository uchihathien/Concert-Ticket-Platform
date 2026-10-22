// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.port.IdentityPort;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang identity-service. Không kiểu nào của Identity đi quá lớp này. */
@Component
public class IdentityHttpAdapter implements IdentityPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityHttpAdapter.class);

    private final RestClient client;

    public IdentityHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.ticketing.identity-url:http://localhost:8090}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Ngắn hơn hẳn hạn 5 giây sang Ordering, và đó là chủ đích: Ordering giữ dữ liệu mà không
        // có thì KHÔNG phát vé được, còn ở đây chỉ là một cái tên. Chờ lâu cho một trường tô điểm
        // là kéo dài thời gian giữ transaction phát vé mà không đổi được kết quả.
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Nuốt mọi lỗi và trả rỗng — xem {@link IdentityPort#displayNameOf} để biết vì sao.
     *
     * <p>Vẫn ghi log ở mức cảnh báo: im lặng hoàn toàn thì một identity sập cả ngày sẽ để lại một
     * ngày vé không có tên, và không ai biết cho tới khi ban tổ chức thử tra cứu.
     */
    @Override
    public Optional<String> displayNameOf(UUID userId) {
        try {
            UserContactResponse response =
                    client.get().uri("/internal/users/{id}", userId).retrieve().body(UserContactResponse.class);

            return Optional.ofNullable(response)
                    .map(UserContactResponse::fullName)
                    .filter(name -> !name.isBlank());
        } catch (RuntimeException e) {
            log.warn("Không đọc được tên người dùng {}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<java.util.List<Contact>> contactsOf(java.util.Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Optional.of(java.util.List.of());
        }
        try {
            java.util.List<Contact> all = new java.util.ArrayList<>();
            java.util.List<UUID> ids = java.util.List.copyOf(userIds);
            // Identity nhận tối đa 500 người một lần.
            for (int from = 0; from < ids.size(); from += 500) {
                ContactResponse[] body = client.post()
                        .uri("/internal/users/contacts")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(java.util.Map.of("userIds", ids.subList(from, Math.min(ids.size(), from + 500))))
                        .retrieve()
                        .body(ContactResponse[].class);
                for (ContactResponse contact : body == null ? new ContactResponse[0] : body) {
                    all.add(new Contact(contact.userId(), contact.email(), contact.fullName(), contact.phone()));
                }
            }
            return Optional.of(all);
        } catch (RuntimeException e) {
            log.warn("Không đọc được liên lạc người mua: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Hợp đồng với {@code OrganizationQueries.BuyerContactView} của identity-service. */
    private record ContactResponse(UUID userId, String email, String fullName, String phone) {}

    /** Tên field là hợp đồng với {@code UserContactView} của identity-service. */
    private record UserContactResponse(String userId, String email, String fullName) {}
}
