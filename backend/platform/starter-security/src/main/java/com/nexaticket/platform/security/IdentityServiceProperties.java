// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

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
 */
@ConfigurationProperties(prefix = "nexaticket.identity")
public record IdentityServiceProperties(String baseUrl) {

    private static final String DEV_DEFAULT = "http://localhost:8090";

    public IdentityServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEV_DEFAULT;
        }
    }
}
