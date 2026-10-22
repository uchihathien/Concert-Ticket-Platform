// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.payos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Các góc của chữ ký payOS mà {@code PayosSignatureTest} chưa phủ.
 *
 * <p>Kỳ vọng HMAC ở đây được tính bằng {@link Mac} trực tiếp trên chuỗi canonical <b>viết tay</b>, không
 * qua {@code canonicalize}: nếu cách dựng chuỗi đổi đi, test phải đỏ.
 */
class PayosSignatureEdgeCaseTest {

    private static final String KEY = "test-checksum-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("JSON của chính test sai", e);
        }
    }

    private static String hmac(String canonical, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    @Nested
    @DisplayName("Dựng chuỗi canonical")
    class Canonical {

        @Test
        @DisplayName("Giá trị chứa & và = vào NGUYÊN VĂN, không URL-encode")
        void khong_url_encode() {
            JsonNode data = json("{\"desc\":\"a=b&c\",\"amount\":1}");

            assertThat(PayosSignature.canonicalize(data)).isEqualTo("amount=1&desc=a=b&c");
            assertThat(PayosSignature.sign(data, KEY)).isEqualTo(hmac("amount=1&desc=a=b&c", KEY));
        }

        @Test
        @DisplayName("Tiếng Việt có dấu được ký theo byte UTF-8")
        void tieng_viet_utf8() {
            JsonNode data = json("{\"counterAccountName\":\"NGUYỄN VĂN A\",\"desc\":\"Thanh toán\"}");

            assertThat(PayosSignature.sign(data, KEY))
                    .isEqualTo(hmac("counterAccountName=NGUYỄN VĂN A&desc=Thanh toán", KEY));
        }

        @Test
        @DisplayName("Sắp key theo thứ tự byte: chữ hoa đứng trước chữ thường")
        void sap_key_phan_biet_hoa_thuong() {
            assertThat(PayosSignature.canonicalize(json("{\"b\":1,\"B\":2,\"a\":3}")))
                    .isEqualTo("B=2&a=3&b=1");
        }

        @Test
        @DisplayName("Boolean in ra true/false, số âm và số mũ in dạng thường")
        void boolean_va_so() {
            assertThat(PayosSignature.canonicalize(json("{\"ok\":true,\"neg\":-5,\"big\":1e3,\"dec\":12.50}")))
                    .isEqualTo("big=1000&dec=12.5&neg=-5&ok=true");
        }

        @Test
        @DisplayName("Chuỗi rỗng và khoảng trắng giữ nguyên, không bị cắt")
        void chuoi_rong_va_khoang_trang() {
            assertThat(PayosSignature.canonicalize(json("{\"a\":\"\",\"b\":\" x \"}")))
                    .isEqualTo("a=&b= x ");
        }

        @Test
        @DisplayName("Object lồng: JSON nén, key bên trong đã sắp, null bên trong giữ chữ null")
        void object_long() {
            assertThat(PayosSignature.canonicalize(json("{\"o\":{\"z\":null,\"a\":\"q\\\"t\"}}")))
                    .isEqualTo("o={\"a\":\"q\\\"t\",\"z\":null}");
        }

        @Test
        @DisplayName("Mảng rỗng và object rỗng")
        void mang_va_object_rong() {
            assertThat(PayosSignature.canonicalize(json("{\"items\":[],\"meta\":{}}")))
                    .isEqualTo("items=[]&meta={}");
        }

        @Test
        @DisplayName("Không phải object (null, mảng, chuỗi) hoặc object rỗng: chuỗi rỗng")
        void khong_phai_object() {
            assertThat(PayosSignature.canonicalize(null)).isEmpty();
            assertThat(PayosSignature.canonicalize(json("[1,2]"))).isEmpty();
            assertThat(PayosSignature.canonicalize(json("\"x\""))).isEmpty();
            assertThat(PayosSignature.canonicalize(json("{}"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Giả mạo")
    class GiaMao {

        private final JsonNode signed =
                json("{\"orderCode\":1000001,\"amount\":3000000,\"reference\":\"TF1\",\"code\":\"00\"}");

        @Test
        @DisplayName("Sửa số tiền sau khi ký: chữ ký không còn khớp")
        void sua_so_tien() {
            String signature = PayosSignature.sign(signed, KEY);
            JsonNode tampered =
                    json("{\"orderCode\":1000001,\"amount\":3000001,\"reference\":\"TF1\",\"code\":\"00\"}");

            assertThat(PayosSignature.matches(PayosSignature.sign(tampered, KEY), signature))
                    .isFalse();
        }

        @Test
        @DisplayName("Thêm một trường null: chữ ký ĐỔI — 'k=' vẫn là một phần của chuỗi ký")
        void them_truong_null() {
            JsonNode extended =
                    json("{\"orderCode\":1000001,\"amount\":3000000,\"reference\":\"TF1\",\"code\":\"00\",\"x\":null}");

            assertThat(PayosSignature.sign(extended, KEY)).isNotEqualTo(PayosSignature.sign(signed, KEY));
        }

        @Test
        @DisplayName("Đổi số thành chuỗi cùng chữ số: chữ ký KHÔNG đổi — đúng như bản tham chiếu payOS")
        void so_va_chuoi_cung_chu_so() {
            // Ghi lại một tính chất có thật của thuật toán payOS: kiểu dữ liệu không nằm trong chuỗi ký.
            // Vì vậy phía đọc phải dùng asLong(), không được tin rằng chữ ký đã chốt kiểu.
            JsonNode asString =
                    json("{\"orderCode\":\"1000001\",\"amount\":\"3000000\",\"reference\":\"TF1\",\"code\":\"00\"}");

            assertThat(PayosSignature.sign(asString, KEY)).isEqualTo(PayosSignature.sign(signed, KEY));
        }

        @Test
        @DisplayName("Khoá khác: chữ ký khác")
        void khoa_khac() {
            assertThat(PayosSignature.sign(signed, KEY)).isNotEqualTo(PayosSignature.sign(signed, KEY + "x"));
        }
    }

    @Nested
    @DisplayName("Thiếu khoá và so khớp")
    class ThieuKhoa {

        @Test
        @DisplayName("Checksum key null/trắng: ném, không bao giờ ký bằng khoá rỗng")
        void thieu_khoa_thi_nem() {
            assertThatThrownBy(() -> PayosSignature.sign(json("{\"a\":1}"), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("checksum-key");
            assertThatThrownBy(() -> PayosSignature.signPaymentRequest(1, "c", "d", 1, "r", "  "))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Chữ ký nhận về có khoảng trắng hai đầu vẫn khớp; expected null thì không bao giờ khớp")
        void so_khop_bien() {
            String expected = PayosSignature.sign(json("{\"a\":1}"), KEY);

            assertThat(PayosSignature.matches(expected, "  " + expected + "\n")).isTrue();
            assertThat(PayosSignature.matches(null, expected)).isFalse();
            assertThat(PayosSignature.matches(expected, expected + "00")).isFalse();
        }

        @Test
        @DisplayName("Ký request tạo link: chuỗi canonical đúng năm trường, URL không encode")
        void ky_request_tao_link_doc_lap() {
            String canonical = "amount=10000&cancelUrl=https://x/pay?a=1&b=2&description=NT1000001&"
                    + "orderCode=1000001&returnUrl=https://x/pay";

            assertThat(PayosSignature.signPaymentRequest(
                            10_000L, "https://x/pay?a=1&b=2", "NT1000001", 1_000_001L, "https://x/pay", KEY))
                    .isEqualTo(hmac(canonical, KEY));
        }
    }
}
