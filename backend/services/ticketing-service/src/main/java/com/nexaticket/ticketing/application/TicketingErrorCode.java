// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của ticketing. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum TicketingErrorCode implements ErrorCode {
    TICKET_NOT_FOUND(404),

    /** Nhân viên soát vé không thuộc tổ chức sở hữu vé. */
    NOT_YOUR_EVENT(403),

    /**
     * Có phân công cho sự kiện này nhưng đã hết hạn: suất cuối đã kết thúc quá 6 giờ. Tách khỏi
     * {@link #NOT_YOUR_EVENT} để app nói đúng chuyện — "phân công đã hết hạn, liên hệ ban tổ chức nếu
     * cần gán lại" thay vì "bạn chưa được gán".
     */
    CHECKIN_ASSIGNMENT_EXPIRED(403),

    /** Lệnh gán chứa sự kiện không thuộc tổ chức, hoặc suất không thuộc sự kiện đó. */
    EVENT_NOT_IN_ORGANIZATION(400),

    /** catalog-service không trả lời nên không kiểm được sự kiện. Người gọi thử lại được. */
    CATALOG_UNAVAILABLE(503),

    /** Cho vào thủ công mà không khai lý do. Lý do là thứ duy nhất giải thích được lần ghi đè đó về sau. */
    OVERRIDE_REASON_REQUIRED(400),

    /** Chưa cấu hình khoá ký nào — không phát hành được vé. */
    /** Lượt xuất vượt trần số dòng; người dùng phải thu hẹp theo sự kiện hoặc suất. */
    EXPORT_TOO_LARGE(400),

    /** Không ghi được nhật ký nên không cho xuất dữ liệu cá nhân. */
    AUDIT_UNAVAILABLE(503),

    NO_SIGNING_KEY(500);

    private final int status;

    TicketingErrorCode(int status) {
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
