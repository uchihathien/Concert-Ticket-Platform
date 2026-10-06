// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.id;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Mã tương quan đi xuyên toàn bộ saga: HTTP header, AMQP header, log, audit.
 *
 * <p>26 ký tự Crockford Base32, sắp xếp được theo thời gian (48 bit timestamp + 80 bit ngẫu nhiên),
 * giống ULID. Dùng chữ hoa và số để dán vào ticket hỗ trợ không bị nhầm.
 */
public record CorrelationId(String value) {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public CorrelationId {
        Objects.requireNonNull(value, "CorrelationId không được null");
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("CorrelationId không hợp lệ: " + value);
        }
    }

    public static CorrelationId generate() {
        long timestamp = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(26);
        for (int i = 9; i >= 0; i--) {
            sb.append(ALPHABET.charAt((int) ((timestamp >>> (i * 5)) & 0x1F)));
        }
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(32)));
        }
        return new CorrelationId(sb.toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
