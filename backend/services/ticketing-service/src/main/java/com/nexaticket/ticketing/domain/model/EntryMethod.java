// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

/** Vé được nhận diện bằng cách nào trong một lần quét. Ghi vào {@code checkin_log.entry_method}. */
public enum EntryMethod {
    QR,

    /** Nhân viên gõ mã vé vì QR hỏng. Không có chữ ký để kiểm, nên phải tra lại được. */
    CODE,

    /** Cho vào thủ công dù vé đang được ghi nhận là ở trong. Luôn kèm lý do. */
    OVERRIDE
}
