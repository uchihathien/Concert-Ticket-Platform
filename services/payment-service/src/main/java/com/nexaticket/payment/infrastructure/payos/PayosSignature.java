// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.payos;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chữ ký HMAC-SHA256 của payOS.
 *
 * <p>Đây là <b>toàn bộ</b> lớp xác thực giữa internet và một endpoint đánh dấu đơn hàng đã trả tiền.
 * Không có khoá API, không có IP allowlist, không có mTLS — chỉ có chữ ký này. Nên mọi chi tiết dưới
 * đây là một phần của hợp đồng bảo mật, không phải chuyện định dạng.
 *
 * <p><b>Cách payOS tính chữ ký.</b> Lấy object cần ký, sắp các key theo thứ tự alphabet, nối thành
 * {@code key1=value1&key2=value2...}, rồi HMAC-SHA256 bằng <i>checksum key</i> và in ra hex thường.
 * Bốn chỗ dễ sai, và cả bốn đều cho ra một chữ ký trông hợp lệ mà không khớp:
 *
 * <ul>
 *   <li><b>Sắp theo key, không theo thứ tự JSON gửi về.</b> Thứ tự field trong JSON không ổn định và
 *       không phải thứ tự ký.
 *   <li><b>{@code null} thành chuỗi rỗng</b>, không phải chữ {@code "null"}. Và đúng như bản tham
 *       chiếu của payOS, cả chuỗi {@code "null"}/{@code "undefined"} gửi về cũng bị quy về rỗng —
 *       nghe vô lý nhưng phải khớp, vì bên kia làm vậy.
 *   <li><b>Số in ra dạng thường, không khoa học và không số 0 thừa.</b> Bản tham chiếu viết bằng
 *       JavaScript nên {@code 3000.00} ra {@code "3000"}. Dùng {@code Double.toString} ở Java sẽ ra
 *       {@code "3000.0"} và lệch chữ ký.
 *   <li><b>Không URL-encode.</b> Khác với luồng chi hộ (payout) của payOS; ở đây giá trị vào nguyên
 *       văn, kể cả khi chứa {@code &} hay {@code =}.
 * </ul>
 *
 * <p>Ở {@code infrastructure} chứ không ở {@code domain}: nó đọc {@link JsonNode}, tức là biết về
 * định dạng của một nhà cung cấp cụ thể. Domain không được biết payOS tồn tại.
 */
public final class PayosSignature {

    private PayosSignature() {}

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * Ký một object JSON — dùng cho webhook và để kiểm chữ ký response của payOS.
     *
     * @param data object cần ký; với webhook là đúng trường {@code data}, <b>không</b> phải cả body
     */
    public static String sign(JsonNode data, String checksumKey) {
        return hmacHex(canonicalize(data), checksumKey);
    }

    /**
     * Ký request tạo link thanh toán.
     *
     * <p>payOS chốt cứng <b>đúng năm trường này</b> cho chữ ký tạo link, bất kể body còn gửi thêm gì
     * (buyer, items, expiredAt…). Nên danh sách dưới đây được viết tường minh chứ không suy ra từ
     * body: thêm một trường vào request rồi vô tình kéo nó vào chữ ký là cách chắc chắn nhất để
     * payOS trả về "signature không hợp lệ" mà không nói thiếu hay thừa gì.
     */
    public static String signPaymentRequest(
            long amountVnd,
            String cancelUrl,
            String description,
            long orderCode,
            String returnUrl,
            String checksumKey) {

        String canonical = "amount=" + amountVnd
                + "&cancelUrl=" + cancelUrl
                + "&description=" + description
                + "&orderCode=" + orderCode
                + "&returnUrl=" + returnUrl;
        return hmacHex(canonical, checksumKey);
    }

    /**
     * So chữ ký bằng thời gian hằng số.
     *
     * <p>{@code equals} của String thoát ra ở ký tự đầu tiên khác nhau. Với một endpoint công khai
     * mà kẻ gọi được thử lại tuỳ ý, khoảng thời gian đó đủ để dò chữ ký đúng từng ký tự một.
     *
     * @return false nếu chữ ký nhận được rỗng hoặc không khớp
     */
    public static boolean matches(String expected, String received) {
        if (expected == null || received == null || received.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** Dựng chuỗi {@code key=value&...} đã sắp theo key. Visible-for-testing. */
    static String canonicalize(JsonNode data) {
        if (data == null || !data.isObject()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>();
        Iterator<String> names = data.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            sorted.put(name, render(data.get(name)));
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static String render(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isNumber()) {
            // stripTrailingZeros + toPlainString để khớp cách JavaScript in số: 3000.00 -> "3000",
            // 1e3 -> "1000". Không dùng asText() vì DoubleNode trả "3000.0".
            return new BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        }
        if (node.isArray() || node.isObject()) {
            return jsonWithSortedKeys(node);
        }
        String text = node.asText();
        // Bản tham chiếu của payOS quy cả chuỗi "null"/"undefined" về rỗng. Không bắt chước thì một
        // webhook có counterAccountName rỗng kiểu đó sẽ lệch chữ ký.
        return "null".equals(text) || "undefined".equals(text) ? "" : text;
    }

    /**
     * JSON nén, key của mọi object đã sắp theo alphabet.
     *
     * <p>payOS chỉ làm điều này cho <b>mảng</b> ({@code items} của request tạo link). Webhook hiện
     * tại không có trường lồng nào, nhưng xử lý luôn cả object lồng thì rẻ và không có lý do để một
     * trường mới của họ làm chữ ký sai im lặng.
     */
    private static String jsonWithSortedKeys(JsonNode node) {
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(element -> parts.add(jsonWithSortedKeys(element)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fieldNames().forEachRemaining(name -> sorted.put(name, node.get(name)));
            List<String> parts = new ArrayList<>();
            sorted.forEach((name, value) -> parts.add(quote(name) + ":" + jsonWithSortedKeys(value)));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return quote(node.asText());
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String hmacHex(String canonical, String checksumKey) {
        if (checksumKey == null || checksumKey.isBlank()) {
            throw new IllegalStateException("Thiếu nexaticket.payment.payos.checksum-key: không ký được request "
                    + "và không kiểm được webhook");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(checksumKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 có trong mọi JRE; tới được đây là JRE hỏng, không phải lỗi cấu hình.
            throw new IllegalStateException("JRE không hỗ trợ " + HMAC_SHA256, e);
        }
    }
}
