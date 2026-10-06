// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Đọc mã lỗi từ body lỗi chuẩn của hệ thống ({@code ApiError} của starter-web) và đổi thành câu
 * cho khách.
 *
 * <p>Chỉ đọc trường {@code code}; {@code detail} của service kia là câu cho <i>client của họ</i>,
 * viết cho màn hình chứ không viết cho một mô hình đang đọc giữa chừng một lượt chat. Mỗi adapter
 * tự khai bảng dịch của mình, vì cùng một mã ({@code HOLD_EXPIRED}) ở inventory và ở ordering
 * nói về hai thời điểm khác nhau.
 */
final class ApiErrors {

    private ApiErrors() {}

    /** Hình dạng tối thiểu của body lỗi — các trường khác bỏ qua. */
    private record ErrorBody(String code, String detail) {}

    /**
     * @param translations mã lỗi → câu cho khách
     * @param fallback câu khi mã không có trong bảng, hoặc body không đọc được
     */
    static String reason(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Map<String, String> translations,
            String fallback) {
        try {
            ErrorBody body = response.bodyTo(ErrorBody.class);
            if (body != null && body.code() != null) {
                return translations.getOrDefault(body.code(), fallback);
            }
        } catch (RuntimeException ignored) {
            // Body không phải JSON chuẩn (proxy chen vào, HTML của gateway). Câu chung vẫn đúng.
        }
        return fallback;
    }
}
