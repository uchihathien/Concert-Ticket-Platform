// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Gập chữ tiếng Việt để so khớp cụm từ: chữ thường, bỏ dấu, {@code đ → d}.
 *
 * <p>Một chỗ cho cả {@link HandoffIntent} và {@link BookingClaimGuard}: "đã đặt", "Đã Đặt", "da dat"
 * đều thành {@code da dat}. {@link Normalizer} không tách được {@code đ} nên thay tay trước.
 */
final class TextFolding {

    private TextFolding() {}

    static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
