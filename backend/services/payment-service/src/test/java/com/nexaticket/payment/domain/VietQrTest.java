// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.payment.domain.model.Crc16;
import com.nexaticket.payment.domain.model.PaymentReference;
import com.nexaticket.payment.domain.model.VietQr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payload VietQR theo chuẩn EMVCo.
 *
 * <p>Đây là thứ khách quét bằng app ngân hàng thật. Sai một byte thì mã vẫn dựng ra được, vẫn
 * quét ra chuỗi, nhưng app từ chối với thông báo chung chung — nên bộ test này kiểm từng trường
 * chứ không chỉ kiểm "có sinh ra chuỗi".
 */
class VietQrTest {

    private static final PaymentReference REFERENCE = new PaymentReference("NTK7M2QP9X");

    @Test
    @DisplayName("CRC-16/CCITT-FALSE khớp vector kiểm chuẩn của thuật toán")
    void crc_khop_vector_chuan() {
        // Vector chính thức của CRC-16/CCITT-FALSE: "123456789" -> 0x29B1.
        // Kiểm bằng vector chuẩn chứ không bằng giá trị tự sinh, vì "CRC-16" là tên của cả
        // một họ thuật toán (ARC, MODBUS, XMODEM...) cho kết quả khác nhau hoàn toàn.
        assertThat(Crc16.ccittFalse("123456789")).isEqualTo("29B1");
    }

    @Test
    @DisplayName("Payload có đủ các trường EMVCo bắt buộc, đúng thứ tự")
    void payload_du_truong_bat_buoc() {
        String payload = VietQr.build("970422", "0123456789", 3_000_000L, REFERENCE);

        assertThat(payload).startsWith("000201"); // format indicator + point of initiation
        assertThat(payload).contains("0102 12".replace(" ", ""));
        assertThat(payload).contains("A000000727"); // GUID NAPAS
        assertThat(payload).contains("QRIBFTTA"); // chuyển tới tài khoản
        assertThat(payload).contains("5303704"); // tiền tệ VND
        assertThat(payload).contains("5802VN"); // quốc gia
        assertThat(payload).contains("0123456789"); // số tài khoản
        assertThat(payload).contains(REFERENCE.value()); // nội dung chuyển khoản
    }

    @Test
    @DisplayName("CRC được tính TRÊN CẢ tag 6304, không phải phần trước nó")
    void crc_tinh_ca_tag_6304() {
        // Đây là lỗi phổ biến nhất khi tự dựng VietQR, và nó cho ra một mã trông hợp lệ
        // hoàn toàn — chỉ app ngân hàng mới từ chối.
        String payload = VietQr.build("970422", "0123456789", 3_000_000L, REFERENCE);

        String withoutCrcValue = payload.substring(0, payload.length() - 4);
        assertThat(withoutCrcValue).endsWith("6304");
        assertThat(payload).endsWith(Crc16.ccittFalse(withoutCrcValue));
    }

    @Test
    @DisplayName("Số tiền là số nguyên VND, không có phần thập phân")
    void so_tien_khong_co_thap_phan() {
        // VND không có đơn vị nhỏ hơn đồng; ghi "3000000.00" khiến một số app đọc sai số tiền.
        assertThat(VietQr.build("970422", "0123456789", 3_000_000L, REFERENCE))
                .contains("54073000000")
                .doesNotContain(".");
    }

    @Test
    @DisplayName("Độ dài TLV luôn hai chữ số có số 0 ở đầu")
    void do_dai_tlv_hai_chu_so() {
        // Số tiền 5 chữ số ⇒ "5405" + "50000". Thiếu số 0 ở đầu sẽ làm lệch toàn bộ phần sau.
        assertThat(VietQr.build("970422", "0123456789", 50_000L, REFERENCE)).contains("540550000");
    }

    @Test
    @DisplayName("Số tiền không dương bị từ chối, không sinh ra mã QR vô nghĩa")
    void so_tien_khong_duong_bi_tu_choi() {
        assertThatThrownBy(() -> VietQr.build("970422", "0123456789", 0L, REFERENCE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
