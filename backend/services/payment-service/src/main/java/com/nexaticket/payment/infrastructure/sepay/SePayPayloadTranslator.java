// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.sepay;

import com.nexaticket.payment.domain.model.BankTransfer;
import com.nexaticket.payment.domain.port.BankTransferTranslator;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Anti-Corruption Layer cho SePay.
 *
 * <p><b>Không trường nào mang tên theo SePay được đi quá lớp này.</b> Đó là toàn bộ lý do nó tồn
 * tại: đổi nhà cung cấp webhook chỉ phải viết một translator mới, còn ma trận phân loại — nơi
 * chứa mọi luật nghiệp vụ về tiền — không đổi một dòng.
 *
 * <p>Nhận {@code Map} thay vì một DTO có annotation: payload của nhà cung cấp có thể thêm trường
 * bất cứ lúc nào mà không báo, và một DTO chặt sẽ nổ thay vì bỏ qua trường lạ. Ở đây "bỏ qua
 * trường lạ" là hành vi đúng.
 */
@Component
public class SePayPayloadTranslator implements BankTransferTranslator {

    /**
     * @throws MalformedPayloadException khi thiếu trường bắt buộc — payload rác thì trả 4xx để
     *     nhà cung cấp không retry một thứ vĩnh viễn không xử lý được
     */
    @Override
    public BankTransfer translate(Map<String, Object> payload) {
        String id = text(payload, "id");
        if (id == null) {
            throw new BankTransferTranslator.MalformedPayloadException("Thiếu trường id");
        }

        // SePay tách tiền vào và tiền ra thành hai trường. Gộp lại thành một số có dấu để
        // domain chỉ phải hiểu MỘT khái niệm "số tiền", không phải hai.
        long in = number(payload, "transferAmount");
        String type = text(payload, "transferType");
        long signed = "out".equalsIgnoreCase(type) ? -Math.abs(in) : Math.abs(in);

        return new BankTransfer(
                id,
                // "content" là ô nội dung; "description" là bản đầy đủ hơn ở một số ngân hàng.
                // Ghép cả hai rồi mới dò mã tham chiếu, vì tuỳ ngân hàng mà mã rơi vào ô nào.
                (text(payload, "content") + " " + text(payload, "description")).trim(),
                signed,
                text(payload, "bankBin"),
                text(payload, "accountNumber"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private static long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new BankTransferTranslator.MalformedPayloadException("Trường " + key + " không phải số: " + value);
        }
    }
}
