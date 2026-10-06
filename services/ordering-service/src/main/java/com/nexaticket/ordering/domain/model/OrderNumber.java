// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Mã đơn cho người đọc: {@code NT-260908-K7M2QP}.
 *
 * <p>Khách đọc mã này qua điện thoại cho tổng đài, nên bảng chữ cái là Crockford Base32 <b>bỏ
 * I, L, O, U</b>: I/1, L/1, O/0 đọc nhầm lẫn nhau, còn U bị loại để không vô tình ghép thành từ
 * thô tục. Chỉ chữ hoa và số, sống sót qua mọi kênh chữ.
 *
 * <p>Phần ngày tháng ở giữa không phải để chống trùng — nó để nhân viên hỗ trợ biết ngay đơn từ
 * bao giờ mà không phải tra. Chống trùng là việc của 6 ký tự ngẫu nhiên và unique index.
 */
public record OrderNumber(String value) {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SUFFIX_LENGTH = 6;

    public OrderNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mã đơn không được rỗng");
        }
    }

    public static OrderNumber generate(Instant now) {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        // Ngày theo giờ Việt Nam, không theo UTC: đơn đặt lúc 1h sáng ở Hà Nội mà in ra ngày
        // hôm trước sẽ làm cả khách lẫn nhân viên hỗ trợ bối rối.
        return new OrderNumber("NT-" + DAY.format(now.atZone(VIETNAM)) + "-" + suffix);
    }

    @Override
    public String toString() {
        return value;
    }
}
