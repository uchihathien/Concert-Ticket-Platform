// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Lời gọi liên service hỏng vì <b>hạ tầng</b>: quá hạn, connection refused, 5xx.
 *
 * <p>Tách khỏi lỗi nghiệp vụ ({@link OrderNotFoundException}) vì agent nói với khách hai câu khác
 * nhau: "không tìm thấy đơn này" là câu trả lời dứt khoát, còn "hệ thống tra cứu đang bận, bạn thử
 * lại sau ít phút" là lời mời quay lại. Nói nhầm câu đầu khi thật ra là câu sau sẽ khiến khách tin
 * rằng đơn của họ đã biến mất.
 */
public class RemoteCallException extends RuntimeException {

    private final String service;

    public RemoteCallException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    public String service() {
        return service;
    }
}
