// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Địa chỉ Open Host Service của identity-service.
 *
 * <p><b>Có giá trị mặc định, và điều đó là cố ý.</b> Trước đây khoá này không có mặc định và bean
 * {@code MembershipLookup} chỉ được tạo khi service tự khai nó. Hậu quả: service nào quên khai thì
 * {@code TenantFilter} rơi về bản tra cứu luôn trả null, {@code TenantScope} luôn rỗng, và
 * <b>mọi</b> endpoint cần đăng nhập trả 401 — kể cả với token hoàn toàn hợp lệ.
 *
 * <p>Kiểu hỏng đó rất tốn thời gian để lần ra vì mọi thứ khác đều bình thường: service khởi động
 * sạch, health xanh, JWT được xác thực thành công, không log lỗi nào. Triệu chứng trông hệt như
 * cấu hình OIDC sai, nên người ta đi soi Keycloak thay vì soi một dòng cấu hình bị thiếu.
 *
 * <p>Thực tế đã xảy ra: mười trên mười một service quên khai. Chỉ catalog-service có, vì nó được
 * viết sau khi lỗi này bị phát hiện một lần.
 *
 * <p>Mặc định trỏ vào cổng dev; môi trường thật ghi đè bằng biến {@code NEXATICKET_IDENTITY_BASEURL}
 * hoặc khai lại trong {@code application.yml}. Mặc định sai địa chỉ thì lỗi là "không gọi được
 * identity" — ồn ào và dễ lần ra, khác hẳn với 401 câm.
 *
 * @param baseUrl ví dụ {@code http://identity-service:8090}
 * @param membershipCacheTtl bao lâu thì hỏi lại identity-service về một phiên. Đây là <b>độ trễ
 *     tối đa</b> của bốn thao tác quản trị ở mọi service KHÁC identity: đổi vai trò, gỡ thành viên,
 *     thu hồi phiên, khoá tài khoản. Ở chính identity-service thì cả bốn có hiệu lực ngay vì nó đọc
 *     thẳng database.
 *     <p>Hạ xuống thì lưu lượng tới identity tăng theo đúng tỷ lệ nghịch — 10 giây là gấp sáu lần
 *     60 giây. Với môi trường cần thu hồi gần như tức thì, cách đúng không phải hạ về 1 giây mà là
 *     phát sự kiện huỷ cache: identity đã có outbox và RabbitMQ, chỉ thiếu một consumer ở
 *     starter-security. Đó là thay đổi đụng tới cả mười một service nên phải là một quyết định
 *     riêng, không phải hệ quả của việc chỉnh một con số.
 */
@ConfigurationProperties(prefix = "nexaticket.identity")
public record IdentityServiceProperties(String baseUrl, Duration membershipCacheTtl) {

    private static final String DEV_DEFAULT = "http://localhost:8090";

    /** Đủ để identity không thành nút thắt, đủ ngắn để một lần đổi vai trò không kéo dài cả buổi. */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /**
     * Trần cứng.
     *
     * <p>Cache này giữ cả <b>quyền</b> lẫn kết quả kiểm <b>thu hồi phiên</b> và <b>khoá tài
     * khoản</b>. Đặt nó dài nghĩa là một nhân viên vừa bị cho nghỉ vẫn thao tác được đúng ngần ấy
     * thời gian — nên đây là một con số bảo mật, không phải một núm vặn hiệu năng, và nó không được
     * phép nhận giá trị tuỳ ý.
     */
    private static final Duration MAX_TTL = Duration.ofMinutes(5);

    public IdentityServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEV_DEFAULT;
        }
        if (membershipCacheTtl == null || membershipCacheTtl.isNegative() || membershipCacheTtl.isZero()) {
            membershipCacheTtl = DEFAULT_TTL;
        }
        if (membershipCacheTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("nexaticket.identity.membership-cache-ttl không được quá " + MAX_TTL
                    + "; nhận được " + membershipCacheTtl);
        }
    }
}
