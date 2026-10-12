// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

/**
 * Kết quả một lần quét ở cửa.
 *
 * <p>Mọi giá trị ở đây đều được ghi vào {@code checkin_log}, kể cả các kết quả từ chối. Đó là bằng
 * chứng khi có tranh cãi tại cửa — "tôi đã quét rồi mà máy báo lỗi" phải tra được.
 */
public enum CheckinResult {
    ACCEPTED,

    /**
     * Quét Vào một vé đang được ghi nhận là ở trong khu vực — dấu hiệu dùng chung mã QR. Nhân viên
     * cần biết lần vào trước là lúc nào, ở cổng nào.
     *
     * <p>Tên giữ nguyên từ thời chưa có vào/ra: web-scanner rẽ nhánh giao diện theo chuỗi này.
     */
    ALREADY_CHECKED_IN,

    /** Quét Ra thành công. Không phải "cho vào", nhưng cũng không phải từ chối. */
    EXITED,

    /** Quét Ra một vé chưa vào hoặc đã ra rồi. */
    NOT_INSIDE,

    /** Chưa tới giờ mở cổng của suất diễn. */
    NOT_OPEN_YET,

    /** Suất diễn đã kết thúc. */
    SESSION_ENDED,

    /** Vé đã vào rồi ra, và hệ thống đang cấu hình không cho vào lại. */
    REENTRY_NOT_ALLOWED,

    REVOKED,

    /** Vé thật nhưng của suất diễn khác — rất thường gặp với sự kiện nhiều đêm. */
    WRONG_SESSION,

    /** Chữ ký sai hoặc token hết hạn. Nhiều khả năng là vé giả hoặc ảnh chụp màn hình cũ. */
    INVALID_TOKEN,

    /** Chữ ký hợp lệ nhưng không có vé nào mang mã này — vé đã bị xoá hoặc khoá bị lộ. */
    NOT_FOUND;

    public boolean accepted() {
        return this == ACCEPTED;
    }

    /** Lần quét đã đổi trạng thái vé: cho vào, hoặc ghi nhận ra. */
    public boolean succeeded() {
        return this == ACCEPTED || this == EXITED;
    }
}
