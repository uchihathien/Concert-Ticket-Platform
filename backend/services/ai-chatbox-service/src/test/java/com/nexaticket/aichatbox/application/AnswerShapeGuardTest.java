// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AnswerShapeGuard;
import com.nexaticket.aichatbox.application.agent.AnswerShapeGuard.Pass;
import com.nexaticket.aichatbox.application.agent.AnswerShapeGuard.Rejected;
import com.nexaticket.aichatbox.application.agent.AnswerShapeGuard.Verdict;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Chốt chặn hình dạng: ô trống, chữ Hán, sự kiện chưa tra. */
class AnswerShapeGuardTest {

    private static final String FOUND_TRINH =
            "{\"found\":true,\"events\":[{\"slug\":\"dem-nhac-trinh\",\"title\":\"Đêm nhạc Trịnh · Ru Đời Đi Nhé\"}]}";

    private static final String MISS = "{\"found\":false,\"reason\":\"KHÔNG CÓ SỰ KIỆN NÀO KHỚP\"}";

    /** Một lượt có đúng một lời gọi tool tên {@code tool}, trả về {@code payload}. */
    private record Turn(List<ToolOutcome> outcomes, List<Exchange> exchanges) {}

    private static Turn called(String tool, String payload) {
        ToolInvocation call = new ToolInvocation("c1", tool, Map.of("query", "x"));
        ToolOutcome outcome = ToolOutcome.ok("c1", payload);
        return new Turn(
                List.of(outcome),
                List.of(
                        new Exchange.UserSaid("q"),
                        new Exchange.AssistantRequestedTools(List.of(call), null),
                        new Exchange.ToolsReturned(List.of(outcome))));
    }

    private static final Turn NO_TOOLS = new Turn(List.of(), List.of(new Exchange.UserSaid("q")));

    private static Verdict check(String answer, String query, Turn turn, List<Exchange> prior) {
        return AnswerShapeGuard.check(answer, query, turn.outcomes(), prior, turn.exchanges());
    }

    private static Verdict check(String answer, Turn turn) {
        return check(answer, "có sự kiện nhạc Trịnh không", turn, List.of());
    }

    private static String reason(Verdict v) {
        assertThat(v).isInstanceOf(Rejected.class);
        return ((Rejected) v).reason();
    }

    // --- Ô trống ----------------------------------------------------------------

    @Test
    void mau_chua_dien_bi_chan_va_loi_nhac_bao_goi_findEvents() {
        Verdict v = check(
                """
                Mình đã tìm thấy sự kiện. Đây là thông tin chi tiết:
                - Diễn ra vào: [Ngày tháng năm]
                - Địa điểm: [Tên địa điểm]
                Bạn muốn mua vé hạng nào?""",
                called(SupportAgentTools.FIND_EVENTS, FOUND_TRINH));

        assertThat(reason(v)).isEqualTo(AnswerShapeGuard.TEMPLATE_PLACEHOLDER);
        assertThat(((Rejected) v).correction()).contains("[Ngày tháng năm]").contains("findEvents");
    }

    @Test
    void ngoac_nhon_kep_cung_la_o_trong() {
        assertThat(reason(check("Giá vé là {{gia_ve}} nhé.", NO_TOOLS)))
                .isEqualTo(AnswerShapeGuard.TEMPLATE_PLACEHOLDER);
    }

    @Test
    void lien_ket_markdown_va_so_trong_ngoac_khong_phai_o_trong() {
        assertThat(check("Bạn xem [trang sự kiện](/events/dem-nhac-trinh) nhé. Ghi chú [1].", NO_TOOLS))
                .isInstanceOf(Pass.class);
    }

    @Test
    void chu_trong_ngoac_khach_tu_go_thi_duoc_nhac_lai() {
        Verdict v =
                check("Mã [VIP-A] bạn gửi là mã khu ghế, không phải mã đơn.", "mã [VIP-A] là gì", NO_TOOLS, List.of());
        assertThat(v).isInstanceOf(Pass.class);
    }

    // --- Chữ Hán ----------------------------------------------------------------

    @Test
    void chu_han_chen_giua_cau_bi_chan() {
        Verdict v = check(
                "Xin lỗi, tôi đã gặp một小小提示：保持在120字内，并且不要重复客户的问题。",
                called(SupportAgentTools.FIND_EVENTS, FOUND_TRINH));
        assertThat(reason(v)).isEqualTo(AnswerShapeGuard.FOREIGN_SCRIPT);
    }

    @Test
    void khach_nhan_bang_chu_han_thi_tra_loi_chu_han_duoc() {
        Verdict v = check("你好，请问您需要什么帮助？", "你好", NO_TOOLS, List.of());
        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void chu_han_nam_nguyen_van_trong_ket_qua_tool_thi_duoc() {
        Verdict v = check(
                "Sự kiện \"東京 Night\" diễn ra ở Hà Nội.",
                called(SupportAgentTools.FIND_EVENTS, "{\"found\":true,\"title\":\"東京 Night\"}"));
        assertThat(v).isInstanceOf(Pass.class);
    }

    // --- Sự kiện chưa tra -------------------------------------------------------

    @Test
    void ten_su_kien_tu_dat_khi_khong_goi_tool_bi_chan() {
        Verdict v =
                check("Mình đã tìm thấy sự kiện \"Sự kiện âm nhạc Trịnh Công Sơn\". Bạn muốn mua hạng nào?", NO_TOOLS);

        assertThat(reason(v)).isEqualTo(AnswerShapeGuard.UNLOOKED_EVENT);
        assertThat(((Rejected) v).correction())
                .contains("Sự kiện âm nhạc Trịnh Công Sơn")
                .contains("findEvents");
    }

    @Test
    void ten_su_kien_co_trong_ket_qua_findEvents_thi_duoc_ke_ca_khac_dau_cach() {
        Verdict v = check(
                "Sự kiện “Đêm nhạc Trịnh – Ru Đời Đi Nhé” còn vé hạng Phổ thông.",
                called(SupportAgentTools.FIND_EVENTS, FOUND_TRINH));
        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void ten_su_kien_da_co_o_luot_tro_ly_truoc_thi_duoc() {
        Verdict v = check(
                "Sự kiện \"Đêm nhạc Trịnh\" mở cửa lúc 19h.",
                "mấy giờ mở cửa",
                NO_TOOLS,
                List.of(new Exchange.AssistantSaid("Sự kiện \"Đêm nhạc Trịnh\" diễn ra ở Nhà hát Lớn.")));
        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void tra_khong_ra_thi_duoc_nhac_lai_ten_khach_go() {
        Verdict v = check(
                "Mình chưa thấy sự kiện \"Trịnh Công Sơn tri ân\" trong danh mục của NexaTicket.",
                "có sự kiện Trịnh Công Sơn tri ân không",
                called(SupportAgentTools.FIND_EVENTS, MISS),
                List.of());
        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void tra_ra_su_kien_khac_ma_van_ke_ten_tu_nghi_thi_bi_chan() {
        Verdict v = check(
                "Mình tìm thấy sự kiện \"Trịnh Công Sơn tri ân\" ở Huế.",
                "có sự kiện Trịnh Công Sơn tri ân không",
                called(SupportAgentTools.FIND_EVENTS, FOUND_TRINH),
                List.of());
        assertThat(reason(v)).isEqualTo(AnswerShapeGuard.UNLOOKED_EVENT);
    }

    @Test
    void dong_liet_ke_su_kien_khong_co_nguon_bi_chan() {
        Verdict v = check(
                """
                Đây là đề xuất đặt vé:
                · Sự kiện: Lễ hội Ánh sáng Sông Hàn
                · Số vé: 2""",
                NO_TOOLS);
        assertThat(reason(v)).isEqualTo(AnswerShapeGuard.UNLOOKED_EVENT);
    }

    @Test
    void cau_chinh_sach_noi_chung_ve_su_kien_khong_bi_chan() {
        assertThat(check(
                        "Mỗi sự kiện có quy định hoàn vé riêng. Bạn cho mình biết sự kiện nào để mình tra nhé.",
                        NO_TOOLS))
                .isInstanceOf(Pass.class);
    }
}
