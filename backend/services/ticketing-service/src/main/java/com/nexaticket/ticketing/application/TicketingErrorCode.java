// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của ticketing. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum TicketingErrorCode implements ErrorCode {
    TICKET_NOT_FOUND(404),

    /** Nhân viên soát vé không thuộc tổ chức sở hữu vé. */
    NOT_YOUR_EVENT(403),

    /** Cho vào thủ công mà không khai lý do. Lý do là thứ duy nhất giải thích được lần ghi đè đó về sau. */
    OVERRIDE_REASON_REQUIRED(400),

    /** Chưa cấu hình khoá ký nào — không phát hành được vé. */
    /** Lượt xuất vượt trần số dòng; người dùng phải thu hẹp theo sự kiện hoặc suất. */
    EXPORT_TOO_LARGE(400),

    /** Không ghi được nhật ký nên không cho xuất dữ liệu cá nhân. */
    AUDIT_UNAVAILABLE(503),

    NO_SIGNING_KEY(500),

    /** catalog-service không trả lời ở một đường cần lịch suất để quyết định (ca trực, hạn tài khoản). */
    CATALOG_UNAVAILABLE(503),

    /**
     * Nhân viên soát vé quét khi không có ca đang mở của đúng suất này (chưa bấm "Bắt đầu ca", ca
     * chưa tới giờ hay đã hết giờ). Người quản lý sự kiện không bao giờ nhận mã này.
     */
    SHIFT_NOT_ACTIVE(403),

    SHIFT_NOT_FOUND(404),

    /** Ca không hợp lệ: giờ ngược, suất không thuộc sự kiện của cổng, nhân viên chưa được gán. */
    SHIFT_INVALID(400),

    GATE_NOT_FOUND(404),

    GATE_NAME_TAKEN(409),

    /** Cổng còn ca trực — không xoá. */
    GATE_IN_USE(409),

    /** Lô đồng bộ offline quá lớn. */
    SYNC_BATCH_TOO_LARGE(400),

    /** Cho vào bằng tra cứu mà chưa đánh dấu "đã đối chiếu giấy tờ tuỳ thân". */
    LOOKUP_IDENTITY_REQUIRED(400);

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
