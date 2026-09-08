// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

import java.util.UUID;

/**
 * Nội dung mã QR trên vé — <b>chỉ có hai trường</b>.
 *
 * <pre>{@code { "jti": "550e8400-...", "exp": 1793000000 }}</pre>
 *
 * <p>Không userId, không email, không nhãn ghế, không tên sự kiện (H7). Lý do rất cụ thể: khách
 * đăng ảnh vé lên mạng xã hội là chuyện xảy ra hàng ngày, và mã QR thì ai cũng giải ra được bằng
 * điện thoại. Mọi trường thêm vào đây đều là dữ liệu cá nhân bị công bố.
 *
 * <p>{@code jti} là id ngẫu nhiên của vé, không phải số thứ tự — đoán được id vé nghĩa là dò được
 * vé của người khác.
 *
 * <p>Máy soát vé gửi token lên server; server verify chữ ký rồi tra {@code tickets} theo
 * {@code jti}. Nhãn ghế hiện lên màn hình máy soát là do <b>server trả về</b> sau khi đã kiểm
 * quyền của nhân viên, không phải đọc từ mã QR.
 */
public record QrToken(UUID jti, long expiresAtEpochSecond) {

    public boolean isExpiredAt(long nowEpochSecond) {
        return nowEpochSecond >= expiresAtEpochSecond;
    }
}
