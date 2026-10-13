// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.http;

import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nhờ identity-service ghi nhật ký qua Open Host Service {@code POST /internal/audit-logs}.
 *
 * <p>Khác các adapter đọc ở cùng thư mục: lỗi ở đây KHÔNG bị nuốt thành giá trị mặc định. Không ghi
 * được nhật ký thì phải nổ, để lượt xuất dừng lại.
 */
@Component
public class IdentityAuditHttpAdapter implements ExportAuditPort {

    private final RestClient client;

    public IdentityAuditHttpAdapter(
            RestClient.Builder builder,
            @Value("${nexaticket.ticketing.identity-url:http://localhost:8090}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public void buyerListExported(UUID organizationId, UUID actorUserId, Map<String, Object> details) {
        Map<String, Object> body = new HashMap<>();
        body.put("actorUserId", actorUserId);
        body.put("organizationId", organizationId);
        body.put("action", "BUYER_LIST_EXPORTED");
        body.put("entityType", "organization");
        body.put("entityId", organizationId);
        body.put("details", details);
        try {
            client.post().uri("/internal/audit-logs").body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            throw new AuditUnavailableException("Không ghi được nhật ký xuất danh sách người mua", e);
        }
    }
}
