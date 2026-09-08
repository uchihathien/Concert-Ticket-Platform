// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

/**
 * Lời gọi nội bộ hỏng vì <b>hạ tầng</b>: timeout, connection refused, 5xx.
 *
 * <p>Tách khỏi lỗi nghiệp vụ vì hai loại này xử lý khác hẳn nhau. Lỗi nghiệp vụ ("chỗ đã có người
 * giữ") là câu trả lời dứt khoát — thử lại cũng vậy. Lỗi hạ tầng thì <b>không biết</b> bên kia đã
 * làm hay chưa, nên phải giả định là đã làm và bù trừ.
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
