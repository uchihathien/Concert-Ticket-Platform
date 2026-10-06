// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình thu tiền.
 *
 * <p>Từ ADR-0016, nền tảng không tự sinh mã VietQR nữa mà đi qua <b>payOS</b>. Hệ quả trực tiếp lên
 * cấu hình: <b>không còn</b> {@code bank-bin} và {@code bank-account-number} ở đây. Tài khoản nhận
 * tiền là tài khoản ảo payOS cấp cho từng link thanh toán, nó về trong response lúc tạo link, và
 * chính vì vậy nó được <i>lưu lại</i> theo từng intent chứ không đọc từ cấu hình. Một số tài khoản
 * trong file cấu hình sẽ là một lời hứa mà hệ thống không giữ được: link tạo hôm nay vẫn trỏ về
 * tài khoản ảo cũ.
 *
 * @param sandbox bật đường giả lập chuyển khoản. <b>Phải false ở production</b> — bật nghĩa là ai
 *     gọi được service này đều đánh dấu đơn đã trả tiền, tức là phát vé miễn phí.
 * @param payos thông tin kênh thanh toán payOS
 */
@ConfigurationProperties(prefix = "nexaticket.payment")
public record PaymentProperties(boolean sandbox, Payos payos) {

    public PaymentProperties {
        if (payos == null) {
            throw new IllegalArgumentException("Thiếu khối cấu hình nexaticket.payment.payos");
        }
    }

    /**
     * Thông tin kênh thanh toán payOS.
     *
     * <p>Ba khoá dưới đây là ba thứ khác nhau và <b>không thay được cho nhau</b>, nên đáng nói rõ
     * một lần:
     *
     * <ul>
     *   <li>{@code clientId} + {@code apiKey}: đi ở header mỗi request <i>ta gọi sang</i> payOS. Nó
     *       chứng minh ta là ai.
     *   <li>{@code checksumKey}: <b>không bao giờ</b> gửi đi đâu. Nó dùng để ký request tạo link và
     *       để kiểm chữ ký webhook payOS gửi về. Lộ khoá này nghĩa là bất kỳ ai cũng giả được một
     *       webhook "đã trả tiền" — tức là phát vé miễn phí. Nó là secret nặng nhất của service.
     * </ul>
     *
     * @param baseUrl gốc API payOS. payOS <b>không có môi trường sandbox riêng</b>; thử nghiệm diễn
     *     ra trên chính production với số tiền nhỏ, nên mọi lần chạy thử đều là tiền thật.
     * @param checksumKey khoá HMAC-SHA256. Rỗng nghĩa là service từ chối khởi động — xem bên dưới.
     * @param webBaseUrl gốc URL của web khách hàng, để dựng {@code returnUrl}/{@code cancelUrl}
     * @param webhookUrl URL công khai payOS sẽ gọi khi tiền vào. Chỉ dùng cho lệnh đăng ký webhook;
     *     payOS yêu cầu HTTPS và phải gọi được từ internet, nên ở máy phát triển cần một tunnel.
     */
    public record Payos(
            String baseUrl,
            String clientId,
            String apiKey,
            String checksumKey,
            String webBaseUrl,
            String webhookUrl,
            Duration connectTimeout,
            Duration readTimeout) {

        public Payos {
            baseUrl = blankToNull(baseUrl) == null ? "https://api-merchant.payos.vn" : stripTrailingSlash(baseUrl);
            webBaseUrl = stripTrailingSlash(blankToNull(webBaseUrl) == null ? "http://localhost:3000" : webBaseUrl);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        }

        /**
         * Đã khai đủ thông tin để gọi payOS chưa.
         *
         * <p>Cố ý <b>không</b> ném lỗi ở constructor khi thiếu khoá: service vẫn phải khởi động được
         * ở máy phát triển và trong test mà không cần credential thật — chỉ luồng mở link mới cần
         * đến payOS. {@link com.nexaticket.payment.infrastructure.payos.PayosHttpGateway} kêu to ở
         * lúc khởi động và trả lỗi 503 rõ ràng nếu có ai gọi thật.
         */
        public boolean configured() {
            return blankToNull(clientId) != null && blankToNull(apiKey) != null && blankToNull(checksumKey) != null;
        }

        /**
         * Trang khách quay về sau khi thanh toán trên payOS.
         *
         * <p>Trỏ thẳng về trang thanh toán của đơn, và đó là chủ đích: trang đó <b>hỏi lại trạng
         * thái đơn</b> chứ không tin các tham số payOS gắn vào URL. Tham số trên URL do trình duyệt
         * mang về nên người dùng sửa được; thứ duy nhất đáng tin là webhook đã ký.
         */
        public String returnUrl(java.util.UUID orderId) {
            return webBaseUrl + "/orders/" + orderId + "/pay";
        }

        /** Giống {@link #returnUrl}: khách bấm huỷ trên payOS thì về đúng trang đang chờ tiền. */
        public String cancelUrl(java.util.UUID orderId) {
            return returnUrl(orderId);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static String stripTrailingSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
