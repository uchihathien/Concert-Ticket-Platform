// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của payment. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_INTENT_NOT_FOUND(404),

    /** Không huỷ được yêu cầu thanh toán đã nhận tiền. */
    PAYMENT_ALREADY_CONFIRMED(409),

    /**
     * Chỉ có ở chế độ sandbox: cố giả lập chuyển khoản khi service đang chạy với cổng thật.
     *
     * <p>403 chứ không phải 404: đường dẫn tồn tại, chỉ là môi trường này không cho phép — và một
     * cái 404 sẽ khiến người ta tưởng mình gõ sai URL rồi đi tìm nhầm chỗ.
     */
    SANDBOX_DISABLED(403),

    /** Không gọi được ordering-service; payOS sẽ giao lại webhook. */
    ORDERING_UNAVAILABLE(503),

    /**
     * Không nói chuyện được với payOS — mạng, timeout, 5xx, hoặc chưa cấu hình credential.
     *
     * <p>503 vì đây là lỗi TẠM THỜI: saga checkout bù trừ rồi mời khách thử lại, và lần sau có thể
     * thành công. Phân biệt với {@link #PAYOS_REJECTED} là quan trọng — gộp hai cái lại thì không ai
     * biết nên thử lại hay nên đi đọc tài liệu payOS.
     */
    PAYOS_UNAVAILABLE(503),

    /**
     * payOS trả lời, và câu trả lời là "không" — từ chối tạo link, hoặc chữ ký response không khớp.
     *
     * <p>502 chứ không 503: thử lại sẽ nhận đúng câu trả lời đó. Cần một con người đọc log.
     */
    PAYOS_REJECTED(502);

    private final int status;

    PaymentErrorCode(int status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return status;
    }
}
