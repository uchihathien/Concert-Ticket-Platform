// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.BankTransfer;
import java.util.Map;

/**
 * Dịch payload thô của nhà cung cấp webhook sang ngôn ngữ của ta.
 *
 * <p>Là port chứ không phải lớp cụ thể để đổi nhà cung cấp chỉ phải viết một adapter mới. Nhận
 * {@code Map} thay vì DTO có annotation: payload của nhà cung cấp có thể thêm trường bất cứ lúc
 * nào mà không báo, và ở đây "bỏ qua trường lạ" là hành vi đúng.
 */
public interface BankTransferTranslator {

    BankTransfer translate(Map<String, Object> payload);

    /** Payload không đọc được — trả 4xx để nhà cung cấp không retry thứ vĩnh viễn hỏng. */
    class MalformedPayloadException extends RuntimeException {
        public MalformedPayloadException(String message) {
            super(message);
        }
    }
}
