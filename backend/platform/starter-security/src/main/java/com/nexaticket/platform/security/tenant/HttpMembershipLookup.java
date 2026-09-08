// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Tra membership qua Open Host Service của identity-service.
 *
 * <p>Đây là cài đặt mặc định cho <b>mọi service trừ identity</b> — identity tự cài trực tiếp trên
 * database của nó. Không service nào đọc bảng {@code organization_members} của identity (ADR-1002).
 *
 * <p>Có cache ngắn 60 giây: mỗi request đều cần tra membership, nếu gọi HTTP mỗi lần thì
 * identity-service thành nút thắt của toàn hệ thống. Đổi vai trò có hiệu lực chậm nhất 60 giây —
 * chấp nhận được, và ghi rõ ở đây để không ai ngạc nhiên khi test.
 */
public class HttpMembershipLookup implements MembershipLookup {

    private static final Logger log = LoggerFactory.getLogger(HttpMembershipLookup.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private record CacheEntry(Principal principal, long expiresAtMillis) {}

    private final RestClient client;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public HttpMembershipLookup(RestClient client) {
        this.client = client;
    }

    @Override
    public Principal resolve(Claims claims) {
        if (claims == null || claims.subject() == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(claims.subject());
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.principal();
        }

        Principal principal = fetch(claims);
        if (principal != null) {
            cache.put(claims.subject(), new CacheEntry(principal, now + TTL.toMillis()));
        }
        return principal;
    }

    /**
     * Gọi endpoint <b>tra-hoặc-tạo</b> của identity.
     *
     * <p>Truyền cả email và tên chứ không chỉ {@code sub}: người dùng vừa đăng nhập lần đầu chưa có
     * bản ghi nào ở identity, và nếu chỉ tra thì họ nhận 401 vĩnh viễn — không endpoint nào chạm
     * tới được, kể cả endpoint dùng để tạo bản ghi.
     */
    private Principal fetch(Claims claims) {
        try {
            PrincipalPayload payload = client.get()
                    .uri(uri -> uri.path("/internal/users/provision")
                            .queryParam("idpSubject", claims.subject())
                            .queryParam("email", claims.email())
                            .queryParam("fullName", claims.fullName())
                            .build())
                    .retrieve()
                    .body(PrincipalPayload.class);

            if (payload == null || payload.userId() == null) {
                return null;
            }
            Map<TenantId, Role> memberships = new HashMap<>();
            if (payload.memberships() != null) {
                payload.memberships()
                        .forEach((orgId, role) -> memberships.put(TenantId.parse(orgId), Role.valueOf(role)));
            }
            return new Principal(UserId.of(UUID.fromString(payload.userId())), memberships, payload.superAdmin());
        } catch (RuntimeException e) {
            // Identity không phản hồi thì coi như chưa xác thực được — request sẽ nhận 401/403.
            // Không cache lỗi: identity hồi phục thì request kế tiếp phải thử lại ngay.
            log.warn("Không tra được membership cho sub={}: {}", claims.subject(), e.toString());
            return null;
        }
    }

    /** Hình dạng response của {@code GET /internal/users/provision}. */
    public record PrincipalPayload(String userId, Map<String, String> memberships, boolean superAdmin) {}
}
