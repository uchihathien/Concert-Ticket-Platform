// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.util.Map;
import java.util.Optional;
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
        return translate(code(response), translations, fallback);
    }

    /**
     * Trường {@code code} của body lỗi. Body chỉ đọc được MỘT lần — bên cần vừa rẽ nhánh theo mã
     * vừa dịch thì gọi hàm này một lần rồi dùng {@link #translate}.
     */
    static Optional<String> code(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            ErrorBody body = response.bodyTo(ErrorBody.class);
            return Optional.ofNullable(body).map(ErrorBody::code);
        } catch (RuntimeException ignored) {
            // Body không phải JSON chuẩn (proxy chen vào, HTML của gateway). Câu chung vẫn đúng.
            return Optional.empty();
        }
    }

    static String translate(Optional<String> code, Map<String, String> translations, String fallback) {
        return code.map(c -> translations.getOrDefault(c, fallback)).orElse(fallback);
    }
}
