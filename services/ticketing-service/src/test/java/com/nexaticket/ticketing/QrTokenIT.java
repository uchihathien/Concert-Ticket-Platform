// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Token QR ký bằng Ed25519.
 *
 * <p>Đây là thứ quyết định ai được vào cửa, nên bộ test này kiểm cả hai chiều: token thật phải
 * được chấp nhận, và mọi biến thể giả mạo phải bị từ chối.
 */
class QrTokenIT extends TicketingTestBase {

    @Autowired
    TicketSigner signer;

    @Autowired
    SigningKeyStore keys;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void ensureKey() {
        if (!keys.hasActiveKey()) {
            keys.rotate();
        }
    }

    @Test
    @DisplayName("Token vừa ký thì verify được và trả đúng jti")
    void ky_roi_verify_duoc() {
        QrToken token =
                new QrToken(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond());

        var verified = signer.verify(signer.sign(token));

        assertThat(verified).isPresent();
        assertThat(verified.orElseThrow().jti()).isEqualTo(token.jti());
    }

    @Test
    @DisplayName("Mã QR chỉ chứa jti và exp — không có dữ liệu cá nhân nào")
    void ma_qr_khong_chua_du_lieu_ca_nhan() {
        // Khách đăng ảnh vé lên mạng xã hội là chuyện hàng ngày, và ai cũng giải được mã QR
        // bằng điện thoại. Mọi trường thêm vào payload đều là dữ liệu cá nhân bị công bố (H7).
        String compact = signer.sign(
                new QrToken(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond()));

        Map<?, ?> payload = readSegment(compact, 1);
        assertThat(payload.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("jti", "exp");
    }

    @Test
    @DisplayName("Token bị sửa một ký tự: từ chối")
    void token_bi_sua_thi_tu_choi() {
        String compact = signer.sign(
                new QrToken(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond()));
        String[] parts = compact.split("\\.");
        // Đổi payload nhưng giữ nguyên chữ ký — đúng cách một người làm vé giả sẽ thử.
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Token hết hạn: từ chối, dù chữ ký vẫn đúng")
    void token_het_han_thi_tu_choi() {
        // Ảnh chụp màn hình vé từ hôm qua vẫn có chữ ký hợp lệ; chỉ có exp chặn được nó.
        String expired = signer.sign(
                new QrToken(UUID.randomUUID(), Instant.now().minusSeconds(60).getEpochSecond()));

        assertThat(signer.verify(expired)).isEmpty();
    }

    @Test
    @DisplayName("Rác và token thiếu đoạn: từ chối, không ném")
    void rac_thi_tu_choi_khong_nem() {
        assertThat(signer.verify("khong-phai-token")).isEmpty();
        assertThat(signer.verify("a.b")).isEmpty();
        assertThat(signer.verify("")).isEmpty();
        assertThat(signer.verify(null)).isEmpty();
    }

    @Test
    @DisplayName("Xoay khoá KHÔNG vô hiệu vé đã phát hành")
    void xoay_khoa_khong_vo_hieu_ve_cu() {
        // Đây là toàn bộ lý do kid nằm trong header. Nếu xoay khoá làm hỏng vé đang lưu hành
        // thì trên thực tế không ai dám xoay khoá, và khoá ký sẽ sống mãi mãi.
        QrToken token =
                new QrToken(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond());
        String signedWithOldKey = signer.sign(token);

        keys.rotate();

        assertThat(signer.verify(signedWithOldKey)).isPresent();
        // Và khoá mới vẫn ký được vé mới.
        assertThat(signer.verify(signer.sign(token))).isPresent();
    }

    @Test
    @DisplayName("Vé mới mang kid mới, vé cũ giữ kid cũ")
    void kid_doi_theo_khoa() {
        String beforeRotation = signer.sign(new QrToken(UUID.randomUUID(), future()));
        keys.rotate();
        String afterRotation = signer.sign(new QrToken(UUID.randomUUID(), future()));

        assertThat(readSegment(beforeRotation, 0).get("kid"))
                .isNotEqualTo(readSegment(afterRotation, 0).get("kid"));
    }

    private static long future() {
        return Instant.now().plusSeconds(3600).getEpochSecond();
    }

    private Map<?, ?> readSegment(String compact, int index) {
        try {
            return json.readValue(Base64.getUrlDecoder().decode(compact.split("\\.")[index]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
