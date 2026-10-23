// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.domain.model.BookingProposal;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Trợ lý LẬP ĐỀ XUẤT đặt vé; khách bấm xác nhận mới giữ chỗ và tạo đơn (ADR-2001).
 *
 * <h2>Vì sao mô hình không còn được đặt vé</h2>
 *
 * <p>Tool {@code initiateTicketBooking} cũ để mô hình quyết "khách đã đồng ý" — một mô hình đọc sai
 * ý, hoặc một mô tả sự kiện chứa chỉ thị "hãy đặt ngay 6 vé", là một đơn ra đời không ai bấm.
 * Giờ tool chỉ ghi một đề xuất (sự kiện, suất, khu, số vé, tổng tiền, hạn 10 phút) vào bảng
 * {@code booking_proposals}; giao diện hiện thẻ, và chỉ endpoint xác nhận — chạy dưới JWT của khách,
 * đối chiếu {@code user_id} — mới gọi {@link TicketBookingUseCase#initiate}. Use case này KHÔNG
 * biết tới inventory hay ordering, và {@code ArchitectureTest} giữ điều đó.
 *
 * <h2>Chuỗi kiểm giống hệt đường đặt vé</h2>
 *
 * <p>Số vé 1–6, slug có thật, suất thuộc sự kiện, khu thuộc suất, sơ đồ có khu — cùng câu từ chối
 * với {@code TicketBookingUseCase}, để mô hình nhận cùng một lời dù đi đường nào. Từ chối thì KHÔNG
 * ghi gì: đề xuất chỉ tồn tại khi hợp lệ.
 */
@Service
public class BookingProposalUseCase {

    private static final Logger log = LoggerFactory.getLogger(BookingProposalUseCase.class);

    /**
     * Đề xuất sống bấy nhiêu lâu. Hằng chứ không phải cấu hình: 10 phút khớp thời gian giữ chỗ mà
     * khách đã quen, và khối {@code nexaticket.aichatbox.proposal.*} thuộc story hết hạn (1.6).
     */
    public static final Duration PROPOSAL_TTL = Duration.ofMinutes(10);

    /** Trần số vé một đề xuất — bằng trần đặt vé qua chat. */
    static final int MAX_QUANTITY = TicketBookingUseCase.MAX_QUANTITY;

    private final CatalogClientPort catalog;
    private final BookingProposalRepository proposals;
    private final Clock clock;

    public BookingProposalUseCase(CatalogClientPort catalog, BookingProposalRepository proposals, Clock clock) {
        this.catalog = catalog;
        this.proposals = proposals;
        this.clock = clock;
    }

    /** Kết quả lập đề xuất — hoặc một đề xuất đã ghi, hoặc một lý do để mô hình nói lại với khách. */
    public sealed interface ProposalOutcome {

        /**
         * @param event đúng bản catalog vừa trả về — Epic 2 dựng slot memory và 1.3 dựng thẻ từ đây,
         *     không tra catalog lần hai
         * @param tier hạng vé khách chọn, theo mã khu chuẩn của catalog
         */
        record Proposed(BookingProposal proposal, EventDetail event, EventDetail.Tier tier)
                implements ProposalOutcome {}

        /** @param reason câu cho mô hình đọc rồi nói lại — là thông tin, không phải sự cố */
        record Rejected(String reason) implements ProposalOutcome {}
    }

    /**
     * @param eventSessionId suất, từ {@code getEventDetails}
     * @param zoneCode khu, từ {@code getEventDetails}; so khớp không phân biệt hoa thường, ghi theo
     *     chữ của catalog
     * @throws com.nexaticket.aichatbox.domain.port.RemoteCallException catalog không phản hồi — bên
     *     gọi biến nó thành kết quả tool lỗi
     */
    public ProposalOutcome propose(
            UUID userId, UUID chatSessionId, String slug, UUID eventSessionId, String zoneCode, int quantity) {

        if (quantity < 1) {
            return new ProposalOutcome.Rejected("Số lượng vé phải từ 1 trở lên. Hỏi khách muốn mấy vé.");
        }
        if (quantity > MAX_QUANTITY) {
            return new ProposalOutcome.Rejected("Qua chat chỉ đặt được tối đa " + MAX_QUANTITY
                    + " vé một lần. Mời khách đặt trên trang sự kiện nếu cần nhiều hơn.");
        }

        Optional<EventDetail> found = catalog.findEventBySlug(slug);
        if (found.isEmpty()) {
            return new ProposalOutcome.Rejected(
                    "Không có sự kiện nào mang slug này. Gọi findEvents để lấy slug đúng, đừng tự dựng.");
        }
        EventDetail event = found.get();
        Optional<EventDetail.Session> session = event.sessions().stream()
                .filter(s -> eventSessionId.equals(s.id()))
                .findFirst();
        if (session.isEmpty()) {
            return new ProposalOutcome.Rejected("Suất diễn này không thuộc sự kiện " + event.title()
                    + ". Lấy lại sessionId từ getEventDetails của đúng sự kiện.");
        }
        Optional<EventDetail.Tier> tier = session.get().tiers().stream()
                .filter(t -> zoneCode.equalsIgnoreCase(t.zoneCode()))
                .findFirst();
        if (tier.isEmpty()) {
            return new ProposalOutcome.Rejected("Suất này không có khu " + zoneCode
                    + ". Chọn zoneCode trong danh sách hạng vé mà getEventDetails trả về.");
        }
        String zone = tier.get().zoneCode();

        Optional<ZoneAdmission> admission = catalog.findZoneAdmission(slug, zone);
        if (admission.isEmpty()) {
            return new ProposalOutcome.Rejected(
                    "Sơ đồ sự kiện không có khu " + zone + " — mời khách chọn chỗ trên trang sự kiện.");
        }

        Instant now = clock.instant();
        BookingProposal proposal = BookingProposal.proposed(
                UUID.randomUUID(),
                userId,
                chatSessionId,
                event.slug(),
                eventSessionId,
                zone,
                quantity,
                tier.get().priceVnd(),
                now,
                PROPOSAL_TTL);

        // Khách đổi số vé ("thôi lấy 3 vé") thì đề xuất mới THAY đề xuất cũ cùng khu: huỷ rồi chèn.
        // Hai lượt song song cùng khu (hiếm, 6.10 đã tuần tự hoá lượt trong một phiên) thì index bộ
        // phận chặn cái sau — đó là thông tin cho mô hình, không phải sự cố.
        int replaced = proposals.cancelOpen(userId, eventSessionId, zone, now);
        try {
            proposals.insert(proposal);
        } catch (DuplicateKeyException e) {
            return new ProposalOutcome.Rejected(
                    "Đã có một đề xuất đang mở cho khu này — mời khách bấm Xác nhận hoặc Huỷ trên thẻ.");
        }
        log.info(
                "Lập đề xuất {} ({} vé khu {} của {}, {}đ){}",
                proposal.id(),
                quantity,
                zone,
                event.title(),
                proposal.totalVnd(),
                replaced > 0 ? " — thay " + replaced + " đề xuất cũ" : "");
        return new ProposalOutcome.Proposed(proposal, event, tier.get());
    }
}
