// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.handoff.IncidentTemplates;
import com.nexaticket.aichatbox.domain.model.IncidentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Khung mẫu phiếu sự cố. */
class IncidentTemplatesTest {

    @ParameterizedTest
    @EnumSource(IncidentKind.class)
    void moi_loai_su_co_deu_co_khung_mau_va_checklist(IncidentKind kind) {
        IncidentTemplates.Template template = IncidentTemplates.forKind(kind);

        assertThat(template).isNotNull();
        assertThat(template.headline()).isNotBlank();
        assertThat(template.checklist()).isNotEmpty();
    }

    @Test
    void ly_do_theo_khung_loai_truoc_don_sau_roi_moi_toi_mo_ta() {
        String reason = IncidentTemplates.composeReason(
                IncidentKind.TICKET_NOT_RECEIVED, "NT-240001", "Thanh toán lúc 9h sáng mà ví vé vẫn trống");

        assertThat(reason)
                .isEqualTo("[Sự cố] Chưa nhận được vé · đơn NT-240001: Thanh toán lúc 9h sáng mà ví vé vẫn trống");
    }

    @Test
    void khong_co_don_thi_bo_phan_don() {
        String reason = IncidentTemplates.composeReason(IncidentKind.EVENT_CHANGED, null, "Nghe nói huỷ rồi");

        assertThat(reason).isEqualTo("[Sự cố] Sự kiện thay đổi hoặc huỷ: Nghe nói huỷ rồi");
    }

    @Test
    void mo_ta_dai_bi_cat_vi_day_la_mot_dong() {
        String reason = IncidentTemplates.composeReason(IncidentKind.OTHER, null, "x".repeat(1000));

        assertThat(reason).hasSizeLessThan(400).endsWith("…");
    }

    @Test
    void doc_ten_loai_tu_chuoi_mo_hinh_sinh() {
        assertThat(IncidentKind.parse("ticket_not_received")).contains(IncidentKind.TICKET_NOT_RECEIVED);
        assertThat(IncidentKind.parse("QR-NOT-SCANNABLE")).contains(IncidentKind.QR_NOT_SCANNABLE);
        assertThat(IncidentKind.parse("không rõ")).isEmpty();
        assertThat(IncidentKind.parse(null)).isEmpty();
    }

    @Test
    void bon_loai_dau_can_ma_don() {
        assertThat(IncidentKind.TICKET_NOT_RECEIVED.needsOrder()).isTrue();
        assertThat(IncidentKind.PAYMENT_NOT_CONFIRMED.needsOrder()).isTrue();
        assertThat(IncidentKind.EVENT_CHANGED.needsOrder()).isFalse();
        assertThat(IncidentKind.OTHER.needsOrder()).isFalse();
    }
}
