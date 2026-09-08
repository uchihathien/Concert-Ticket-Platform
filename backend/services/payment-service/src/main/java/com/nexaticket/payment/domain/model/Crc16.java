// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.nio.charset.StandardCharsets;

/**
 * CRC-16/CCITT-FALSE — checksum bắt buộc ở cuối payload EMVCo.
 *
 * <p>Tham số: đa thức {@code 0x1021}, khởi tạo {@code 0xFFFF}, <b>không</b> đảo bit đầu vào,
 * <b>không</b> đảo bit đầu ra, XOR ra {@code 0x0000}.
 *
 * <p>Ghi rõ bộ tham số ở đây vì "CRC-16" là tên của cả một họ thuật toán khác nhau — CCITT-FALSE,
 * ARC, MODBUS, XMODEM đều là CRC-16 và cho kết quả khác nhau hoàn toàn. Chọn nhầm biến thể thì mã
 * QR vẫn dựng ra được, vẫn quét được, nhưng app ngân hàng từ chối với thông báo chung chung — một
 * lỗi rất tốn thời gian tìm.
 */
public final class Crc16 {

    private static final int POLYNOMIAL = 0x1021;
    private static final int INITIAL = 0xFFFF;

    private Crc16() {}

    /** @return 4 ký tự hex viết hoa */
    public static String ccittFalse(String input) {
        int crc = INITIAL;
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                boolean msbSet = (crc & 0x8000) != 0;
                crc <<= 1;
                if (msbSet) {
                    crc ^= POLYNOMIAL;
                }
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
