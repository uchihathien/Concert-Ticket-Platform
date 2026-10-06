// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bí mật dùng chung giữa các service khi gọi Open Host Service ({@code /internal/**}).
 *
 * <p>Cùng một giá trị cho mọi service — đây là hàng rào "người gọi có nằm trong hệ thống không",
 * không phải danh tính của từng service. Ở production đặt bằng biến {@code NEXATICKET_INTERNAL_SHAREDSECRET}
 * và xoay vòng như một mật khẩu database.
 *
 * <p><b>Không có giá trị mặc định.</b> Một bí mật mặc định nằm trong repo thì ai cũng đọc được, và
 * tệ hơn không có gì: nó tạo cảm giác đã bảo vệ. Trống thì filter không được cắm vào và mọi thứ
 * chạy như cũ, kèm một cảnh báo lúc khởi động.
 *
 * @param sharedSecret giá trị của header {@code X-Internal-Token}
 */
@ConfigurationProperties(prefix = "nexaticket.internal")
public record InternalApiProperties(String sharedSecret) {

    public boolean enforced() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
