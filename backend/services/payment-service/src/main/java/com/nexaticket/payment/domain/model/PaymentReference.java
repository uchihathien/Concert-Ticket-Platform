// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nội dung chuyển khoản khách phải ghi: {@code NT} + 8 ký tự, ví dụ {@code NTK7M2QP9X}.
 *
 * <p>Đây là <b>sợi dây duy nhất</b> nối một giao dịch ngân hàng với một đơn hàng. Ngân hàng không
 * gửi lại orderId, không gửi userId — chỉ có mấy chục ký tự trong ô "nội dung". Mọi ràng buộc dưới
 * đây đều xuất phát từ thực tế đó.
 *
 * <h2>Vì sao bảng chữ cái này</h2>
 *
 * <p>Crockford Base32 <b>bỏ I, L, O, U</b>: I/1, L/1, O/0 đọc nhầm lẫn nhau khi khách gõ tay từ
 * màn hình sang app ngân hàng, còn U bị loại để không vô tình ghép thành từ thô tục. Chỉ chữ hoa
 * và số — dấu gạch, khoảng trắng và chữ thường bị nhiều app ngân hàng Việt Nam lọc mất hoặc đổi
 * hoa, nên dùng chúng là tự chuốc lỗi đối soát.
 *
 * <p>40 bit ngẫu nhiên cho khoảng 10^12 khả năng; unique index trên
 * {@code payment_intents.payment_reference} mới là thứ bảo đảm không trùng.
 */
public record PaymentReference(String value) {

    private static final String PREFIX = "NT";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Tìm mã trong một chuỗi bất kỳ.
     *
     * <p>Ô nội dung về tới ta hiếm khi sạch: ngân hàng chèn thêm tên người chuyển, mã giao dịch
     * nội bộ, hoặc chữ "CK" ở đầu — {@code "CK NTK7M2QP9X GD 123456"} là dạng rất thường gặp. Vì
     * vậy phải TÌM mã trong chuỗi chứ không so khớp cả chuỗi.
     */
    private static final Pattern IN_TEXT = Pattern.compile(PREFIX + "[" + ALPHABET + "]{" + LENGTH + "}");

    public PaymentReference {
        if (value == null || !value.matches(PREFIX + "[" + ALPHABET + "]{" + LENGTH + "}")) {
            throw new IllegalArgumentException("Mã tham chiếu không hợp lệ: " + value);
        }
    }

    public static PaymentReference generate() {
        StringBuilder builder = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new PaymentReference(builder.toString());
    }

    /**
     * Rút mã ra khỏi nội dung chuyển khoản thô.
     *
     * @return rỗng nếu không tìm thấy — <b>không</b> ném, vì "không đọc được mã" là một nhánh
     *     nghiệp vụ hợp lệ của webhook (tiền đã về, phải đưa sang MANUAL_REVIEW) chứ không phải lỗi
     */
    public static Optional<PaymentReference> findIn(String rawContent) {
        if (rawContent == null) {
            return Optional.empty();
        }
        Matcher matcher = IN_TEXT.matcher(rawContent.toUpperCase());
        return matcher.find() ? Optional.of(new PaymentReference(matcher.group())) : Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
