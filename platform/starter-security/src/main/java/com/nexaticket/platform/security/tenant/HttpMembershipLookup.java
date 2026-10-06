// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Tra membership qua Open Host Service của identity-service.
 *
 * <p>Đây là cài đặt mặc định cho <b>mọi service trừ identity</b> — identity tự cài trực tiếp trên
 * database của nó. Không service nào đọc bảng {@code organization_members} của identity (ADR-1002).
 *
 * <p>Có cache ngắn (mặc định 60 giây, xem {@code nexaticket.identity.membership-cache-ttl}): mỗi
 * request đều cần tra membership, nếu gọi HTTP mỗi lần thì identity-service thành nút thắt của
 * toàn hệ thống. Đổi vai trò có hiệu lực chậm nhất bằng đúng TTL đó — chấp nhận được, và ghi rõ ở
 * đây để không ai ngạc nhiên khi test.
 *
 * <p>Việc <b>thu hồi phiên</b> và <b>vô hiệu hoá tài khoản</b> chịu cùng độ trễ đó, vì cả hai đều
 * được identity trả lời qua chính lời gọi này. Có hiệu lực ngay ở identity-service; ở service khác
 * thì chậm nhất một TTL. Muốn nhanh hơn thì hạ TTL, và cái giá là identity nhận nhiều lưu lượng
 * hơn theo đúng tỷ lệ.
 */
public class HttpMembershipLookup implements MembershipLookup {

    private static final Logger log = LoggerFactory.getLogger(HttpMembershipLookup.class);

    /**
     * Trần số bản ghi trong cache.
     *
     * <p>Bản trước không có trần và không bao giờ dọn: mỗi người từng đăng nhập để lại một bản ghi
     * sống mãi trong bộ nhớ tiến trình. Với một sàn vé thì "số người từng đăng nhập" là con số chỉ
     * tăng, nên đó là rò rỉ bộ nhớ có thật, chỉ chậm.
     */
    private static final int MAX_ENTRIES = 10_000;

    private record CacheEntry(Principal principal, long expiresAtMillis) {}

    private final RestClient client;
    private final String internalToken;
    private final long ttlMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * @param ttl độ trễ tối đa của việc đổi vai trò, thu hồi phiên và khoá tài khoản ở service này.
     *     Cấu hình được qua {@code nexaticket.identity.membership-cache-ttl} — trước đây nó là hằng
     *     số 60 giây nằm trong code, tức là một quyết định bảo mật mà người vận hành không chỉnh
     *     được khi cần siết lại.
     */
    public HttpMembershipLookup(RestClient client, String internalToken, Duration ttl) {
        this.client = client;
        this.internalToken = internalToken;
        this.ttlMillis = ttl.toMillis();
        log.info("Cache membership {} giây", ttl.toSeconds());
    }

    @Override
    public Principal resolve(Claims claims) {
        if (claims == null || claims.subject() == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String key = cacheKey(claims);
        CacheEntry cached = cache.get(key);
        if (cached != null) {
            if (cached.expiresAtMillis() > now) {
                return cached.principal();
            }
            cache.remove(key, cached);
        }

        Principal principal = fetch(claims);
        if (principal != null) {
            if (cache.size() >= MAX_ENTRIES) {
                evictExpired(now);
            }
            cache.put(key, new CacheEntry(principal, now + ttlMillis));
        }
        return principal;
    }

    /**
     * Khoá cache gồm cả phiên, không chỉ người dùng.
     *
     * <p>Thu hồi được làm theo từng phiên: khoá chỉ theo {@code sub} thì một phiên vừa bị thu hồi
     * vẫn đọc được bản cache mà phiên khác của cùng người dùng đã nạp — tức là thao tác thu hồi
     * không có tác dụng cho tới khi bản cache đó hết hạn, và nó được làm mới liên tục bởi chính
     * phiên hợp lệ kia.
     *
     * <p>Không đưa {@code iat} vào khoá: nó đổi sau mỗi lần làm mới token, nên cache sẽ trượt gần
     * như mọi lần. Mốc thu hồi toàn bộ phiên vì thế có hiệu lực chậm nhất một TTL — chấp nhận
     * được, và là cùng độ trễ mà việc đổi vai trò đã chịu từ trước.
     */
    private static String cacheKey(Claims claims) {
        return claims.sessionId() == null ? claims.subject() : claims.subject() + "|" + claims.sessionId();
    }

    /**
     * Gọi endpoint <b>tra-hoặc-tạo</b> của identity.
     *
     * <p>Truyền cả email và tên chứ không chỉ {@code sub}: người dùng vừa đăng nhập lần đầu chưa có
     * bản ghi nào ở identity, và nếu chỉ tra thì họ nhận 401 vĩnh viễn — không endpoint nào chạm
     * tới được, kể cả endpoint dùng để tạo bản ghi.
     *
     * <p><b>Không</b> gọi một endpoint chuyên ghi. Bản trước gọi {@code /internal/users/provision},
     * vốn UPSERT vô điều kiện trước khi tra, và điều đó sai ở hai mặt:
     *
     * <ul>
     *   <li>Đường nóng của mười service biến thành một lệnh ghi mỗi 60 giây mỗi người dùng mỗi
     *       instance — đúng thứ mà {@code LocalMembershipLookup} ghi rõ là phải tránh.
     *   <li>UPSERT đó ghi đè {@code full_name} bằng claim của Keycloak, nên tên người dùng vừa tự
     *       sửa ở {@code PATCH /v1/me} bị trả về bản cũ chậm nhất một phút sau. Sửa hồ sơ xong,
     *       tải lại trang, thấy tên cũ — và không có lỗi nào để lần theo.
     * </ul>
     *
     * <p>{@code /internal/memberships} tự tạo bản ghi ở lần chạm đầu tiên và không đụng vào bản ghi
     * đã có, nên nó làm đúng cả hai việc.
     */
    private Principal fetch(Claims claims) {
        try {
            PrincipalPayload payload = client.get()
                    .uri(uri -> membershipUri(uri, claims))
                    .headers(this::authenticate)
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

    /**
     * Tham số nào null thì <b>bỏ hẳn</b>, không gửi đi rỗng.
     *
     * <p>{@code queryParam("email", null)} sinh ra {@code ?email} không có dấu bằng, và phía nhận
     * đọc nó thành chuỗi rỗng chứ không phải null. Chuỗi rỗng thì lọt qua mọi phép kiểm "có null
     * không": identity sẽ tạo một người dùng với email rỗng — không liên lạc được, và người thứ
     * hai như vậy đụng ràng buộc UNIQUE rồi hỏng bằng một lỗi 500 chẳng liên quan gì tới nguyên
     * nhân thật.
     */
    private static java.net.URI membershipUri(UriBuilder uri, Claims claims) {
        uri.path("/internal/memberships").queryParam("idpSubject", claims.subject());
        if (hasText(claims.email())) {
            uri.queryParam("email", claims.email());
        }
        if (hasText(claims.fullName())) {
            uri.queryParam("fullName", claims.fullName());
        }
        // Thông tin phiên: identity cần chúng để biết token này đã bị thu hồi chưa. Thiếu thì
        // service này vẫn chạy, chỉ là lệnh thu hồi phiên không với tới được nó.
        if (hasText(claims.sessionId())) {
            uri.queryParam("sid", claims.sessionId());
        }
        if (claims.issuedAt() != null) {
            uri.queryParam("issuedAt", claims.issuedAt().toString());
        }
        return uri.build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Open Host Service không lộ ra internet, nhưng "không route ra ngoài" là một tính chất của
     * cấu hình gateway — nó không tự bảo vệ service khỏi bất kỳ ai đã đứng trong mạng nội bộ. Bí
     * mật dùng chung là hàng rào thứ hai; không khai thì không gửi, và phía nhận cũng không đòi.
     */
    private void authenticate(HttpHeaders headers) {
        if (hasText(internalToken)) {
            headers.set(InternalApiFilter.HEADER, internalToken);
        }
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMillis() <= now) {
                it.remove();
            }
        }
        if (cache.size() >= MAX_ENTRIES) {
            // Toàn bộ đang còn hạn: thà mất cache còn hơn phình bộ nhớ không giới hạn.
            cache.clear();
        }
    }

    /** Hình dạng response của {@code GET /internal/memberships}. */
    public record PrincipalPayload(String userId, Map<String, String> memberships, boolean superAdmin) {}
}
