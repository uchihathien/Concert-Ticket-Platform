// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.model;

/** Vòng đời một thư. */
public enum NotificationStatus {
    PENDING,
    SENT,

    /** Lần gửi vừa rồi hỏng, còn lượt thử. */
    FAILED,

    /**
     * Hết lượt thử.
     *
     * <p>Phải có người nhìn, không được im lặng bỏ qua: một email vé không tới nghĩa là khách
     * đứng ở cửa mà không có mã QR.
     */
    DEAD
}
