// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.util.regex.Pattern;

/**
 * Nội dung chuyển khoản — và từ ADR-0016, cũng là hai cách viết của <b>cùng một con số</b>.
 *
 * <p>Thời SePay, reference là 10 ký tự Base32 ngẫu nhiên và là sợi dây <i>duy nhất</i> nối một giao
 * dịch ngân hàng với một đơn hàng: webhook gửi về nguyên văn nội dung chuyển khoản, ta phải rút mã
 * ra khỏi đó. Với payOS thì khác hẳn — webhook trả về {@code orderCode}, một số nguyên do ta cấp khi
 * tạo link, <b>đã ký</b>. Việc đối soát không còn phụ thuộc vào việc khách gõ đúng chữ.
 *
 * <p>Nên reference đổi vai: nó không còn là khoá đối soát mà là <b>mô tả cho người đọc</b>, và nó
 * được <i>suy ra</i> từ orderCode để một con người nhìn màn hình CSKH, nội dung sao kê ngân hàng và
 * log của payOS đều thấy cùng một con số.
 *
 * <p>Hai ràng buộc còn lại đến từ đời thực:
 *
 * <ul>
 *   <li><b>Tối đa 9 ký tự.</b> payOS giới hạn trường {@code description} ở 9 ký tự với tài khoản
 *       ngân hàng chưa liên kết payOS, và chuỗi này chính là cái hiện ở ô nội dung chuyển khoản.
 *       Dài hơn thì payOS từ chối tạo link — tức là checkout chết, không phải chỉ xấu màn hình.
 *   <li><b>Chỉ chữ hoa và chữ số.</b> Dấu gạch, dấu cách hay ký tự tiếng Việt bị nhiều app ngân
 *       hàng bỏ hoặc đổi trong im lặng.
 * </ul>
 *
 * <p>Dạng {@code NT} + 7 chữ số, đúng 9 ký tự. Bảy chữ số cho khoảng {@link #MIN_ORDER_CODE}..{@link
 * #MAX_ORDER_CODE} — chín triệu link thanh toán. Vượt trần thì {@link #forOrderCode} <b>ném lỗi</b>
 * chứ không lặng lẽ sinh chuỗi 10 ký tự: một reference dài 10 ký tự sẽ làm payOS từ chối mọi lần tạo
 * link sau đó, và đó là thứ phải nổ ở lần đầu chứ không phải ở lần thứ một triệu.
 */
public record PaymentReference(String value) {

    private static final String PREFIX = "NT";
    private static final Pattern PATTERN = Pattern.compile("^" + PREFIX + "\\d{7}$");

    /** Trần cứng của payOS cho trường {@code description} với tài khoản chưa liên kết. */
    public static final int MAX_DESCRIPTION_LENGTH = 9;

    public static final long MIN_ORDER_CODE = 1_000_000L;
    public static final long MAX_ORDER_CODE = 9_999_999L;

    public PaymentReference {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Nội dung chuyển khoản không hợp lệ: " + value);
        }
    }

    /** Reference của một orderCode payOS. */
    public static PaymentReference forOrderCode(long orderCode) {
        if (orderCode < MIN_ORDER_CODE || orderCode > MAX_ORDER_CODE) {
            throw new IllegalArgumentException("orderCode %d nằm ngoài khoảng %d..%d: reference sẽ vượt %d ký tự mà "
                            .formatted(orderCode, MIN_ORDER_CODE, MAX_ORDER_CODE, MAX_DESCRIPTION_LENGTH)
                    + "payOS cho phép. Cần mở rộng dải trước khi sinh tiếp.");
        }
        return new PaymentReference(PREFIX + orderCode);
    }

    /** Số payOS biết, rút lại từ chuỗi người đọc. Nghịch đảo của {@link #forOrderCode}. */
    public long orderCode() {
        return Long.parseLong(value.substring(PREFIX.length()));
    }

    @Override
    public String toString() {
        return value;
    }
}
