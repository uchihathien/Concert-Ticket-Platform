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

    /** Vé hợp lệ nhưng đã được quét trước đó. Nhân viên cần biết ai quét và lúc nào. */
    ALREADY_CHECKED_IN,

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
}
