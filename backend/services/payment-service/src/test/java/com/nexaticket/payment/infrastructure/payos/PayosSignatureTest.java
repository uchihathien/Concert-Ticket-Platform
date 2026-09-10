// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.payos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chữ ký payOS — lớp bảo vệ duy nhất giữa internet và việc phát vé thật.
 *
 * <p>Các giá trị kỳ vọng dưới đây là <b>vector vàng</b>, tính độc lập bằng
 * {@code hmac.new(key, canonical, sha256).hexdigest()} theo đúng bản tham chiếu của payOS, chứ không
 * bằng chính lớp đang được kiểm. Một test tự tính kỳ vọng bằng code đang kiểm sẽ xanh với mọi cách
 * canonicalize, kể cả cách sai.
 *
 * <p>Khoá ở đây là khoá giả. Checksum key thật không bao giờ nằm trong repository.
 */
class PayosSignatureTest {

    private static final String KEY = "test-checksum-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("JSON của chính test sai", e);
        }
    }

    @Test
    @DisplayName("Ký webhook: sắp key theo alphabet, null thành chuỗi rỗng")
    void ky_webhook_theo_dung_bản_tham_chieu() {
        // Hình dạng payload thật của payOS, cố ý để các trường counterAccount*/virtualAccount* là null:
        // đó là trường hợp thường gặp nhất với chuyển khoản trong nước, và là chỗ một cách xử lý null
        // khác đi sẽ làm lệch chữ ký của MỌI webhook.
        JsonNode data = json(
                """
                {
                  "orderCode": 1000001,
                  "amount": 3000,
                  "desc": "Thanh cong",
                  "accountNumber": "12345678",
                  "reference": "TF230204212323",
                  "transactionDateTime": "2023-02-04 18:25:00",
                  "currency": "VND",
                  "paymentLinkId": "124c33293c43417ab7879e14c8d9eb18",
                  "code": "00",
                  "counterAccountBankId": null,
                  "counterAccountBankName": null,
                  "counterAccountName": null,
                  "counterAccountNumber": null,
                  "virtualAccountName": null,
                  "virtualAccountNumber": null
                }
                """);

        assertThat(PayosSignature.canonicalize(data))
                .isEqualTo("accountNumber=12345678&amount=3000&code=00&counterAccountBankId=&"
                        + "counterAccountBankName=&counterAccountName=&counterAccountNumber=&currency=VND&"
                        + "desc=Thanh cong&orderCode=1000001&"
                        + "paymentLinkId=124c33293c43417ab7879e14c8d9eb18&reference=TF230204212323&"
                        + "transactionDateTime=2023-02-04 18:25:00&virtualAccountName=&virtualAccountNumber=");

        assertThat(PayosSignature.sign(data, KEY))
                .isEqualTo("a249d3f22764df29c2c7f820a43aeeac6c434d78525b9cce8beb0e37e53a141d");
    }

    @Test
    @DisplayName("Ký request tạo link: đúng năm trường payOS chốt cứng")
    void ky_request_tao_link() {
        // Danh sách trường là của payOS, không suy ra từ body. Thêm expiredAt hay buyerEmail vào request
        // KHÔNG được làm đổi chữ ký — nếu có, payOS trả "signature không hợp lệ" mà không nói thiếu gì.
        String signature = PayosSignature.signPaymentRequest(
                3_000_000L, "https://nexaticket.vn/pay", "NT1000001", 1_000_001L, "https://nexaticket.vn/pay", KEY);

        assertThat(signature).isEqualTo("e9481f70e7d0e5218477a822a58873878d7f9be492f3c0017ddaff648ba3ce23");
    }

    @Test
    @DisplayName("Thứ tự field trong JSON KHÔNG ảnh hưởng chữ ký")
    void thu_tu_field_khong_doi_chu_ky() {
        // payOS không đảm bảo thứ tự field, nên một cách canonicalize phụ thuộc thứ tự sẽ hỏng ngẫu nhiên
        // — loại lỗi tệ nhất vì nó chỉ xảy ra với một phần webhook và trông như mạng chập chờn.
        String a = PayosSignature.sign(json("{\"amount\":3000,\"code\":\"00\",\"orderCode\":7}"), KEY);
        String b = PayosSignature.sign(json("{\"orderCode\":7,\"amount\":3000,\"code\":\"00\"}"), KEY);

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Số in ra dạng thường, không phần thập phân thừa")
    void so_in_ra_giong_javascript() {
        // Bản tham chiếu của payOS viết bằng JavaScript: String(3000.00) cho "3000". Dùng
        // Double.toString ở Java sẽ cho "3000.0" và lệch chữ ký với đúng những webhook có số thập phân.
        assertThat(PayosSignature.canonicalize(json("{\"amount\":3000.00}"))).isEqualTo("amount=3000");
        assertThat(PayosSignature.canonicalize(json("{\"amount\":3000}"))).isEqualTo("amount=3000");
        assertThat(PayosSignature.canonicalize(json("{\"amount\":0}"))).isEqualTo("amount=0");
    }

    @Test
    @DisplayName("Chuỗi \"null\"/\"undefined\" cũng quy về rỗng, đúng như payOS làm")
    void chuoi_null_cung_thanh_rong() {
        // Nghe vô lý, nhưng phải khớp: bản tham chiếu của họ gộp cả hai chuỗi này với giá trị null thật.
        assertThat(PayosSignature.canonicalize(json("{\"a\":\"null\",\"b\":\"undefined\",\"c\":\"x\"}")))
                .isEqualTo("a=&b=&c=x");
    }

    @Test
    @DisplayName("Mảng được JSON hoá với key của từng phần tử đã sắp")
    void mang_duoc_json_hoa_voi_key_da_sap() {
        // Dùng ở trường `items` của request tạo link. Giữ NGUYÊN thứ tự phần tử, chỉ sắp key bên trong.
        assertThat(PayosSignature.canonicalize(json("{\"items\":[{\"price\":10,\"name\":\"B\"},{\"name\":\"A\"}]}")))
                .isEqualTo("items=[{\"name\":\"B\",\"price\":10},{\"name\":\"A\"}]");
    }

    @Test
    @DisplayName("So chữ ký: không phân biệt chữ hoa/thường, từ chối rỗng")
    void so_chu_ky() {
        String expected = "a249d3f22764df29c2c7f820a43aeeac6c434d78525b9cce8beb0e37e53a141d";

        assertThat(PayosSignature.matches(expected, expected)).isTrue();
        // payOS in hex thường, nhưng một lần đổi thư viện phía họ không nên làm cả hệ thống từ chối tiền.
        assertThat(PayosSignature.matches(expected, expected.toUpperCase(java.util.Locale.ROOT)))
                .isTrue();

        // Quan trọng hơn cả: chữ ký thiếu KHÔNG được coi là khớp. Một payload không có trường signature
        // là payload chưa được xác thực, không phải payload "không cần xác thực".
        assertThat(PayosSignature.matches(expected, null)).isFalse();
        assertThat(PayosSignature.matches(expected, "")).isFalse();
        assertThat(PayosSignature.matches(expected, "   ")).isFalse();
        assertThat(PayosSignature.matches(expected, expected.substring(0, 63) + "e"))
                .isFalse();
    }
}
