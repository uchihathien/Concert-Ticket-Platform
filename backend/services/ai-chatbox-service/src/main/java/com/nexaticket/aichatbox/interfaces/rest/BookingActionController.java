// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.agent.ActionCardView;
import com.nexaticket.aichatbox.application.booking.BookingProposalUseCase;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nút Xác nhận / Huỷ trên thẻ đề xuất — đường TẤT ĐỊNH, không qua mô hình (ADR-2001).
 *
 * <p>Đơn chỉ ra đời từ hành động của khách: endpoint này chạy dưới JWT của khách, đối chiếu
 * {@code user_id} của đề xuất, rồi mới giữ chỗ và tạo đơn với khoá chống trùng suy từ
 * {@code proposalId} — bấm hai lần vẫn một đơn. Không có {@code @RequestBody}: mọi dữ liệu đã nằm
 * trong đề xuất, và một body là một chỗ để điền số vé khác với thứ khách đã thấy trên thẻ.
 *
 * <p>Chạy trên luồng request thường (không phải pool của trợ lý): không gọi mô hình, p95 ≤ 2 giây.
 */
@RestController
@RequestMapping("/v1/chat/agent/proposals")
public class BookingActionController {

    private final BookingProposalUseCase proposals;

    public BookingActionController(BookingProposalUseCase proposals) {
        this.proposals = proposals;
    }

    public record ConfirmResponse(
            UUID proposalId, String status, BookingProposalUseCase.OrderView order, ActionCardView action) {}

    /**
     * 201 kèm thẻ PAYMENT. Lỗi: 404 không có/khác chủ, 410 hết hạn hoặc đã huỷ, 409 đã xác nhận (kèm
     * đơn cũ trong {@code meta}), 422 inventory/ordering từ chối, 503 service phía sau không phản hồi.
     */
    @PostMapping("/{proposalId}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfirmResponse confirm(@PathVariable UUID proposalId) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        BookingProposalUseCase.Confirmed confirmed = proposals.confirm(userId, proposalId);
        return new ConfirmResponse(proposalId, "CONFIRMED", confirmed.orderView(), ActionCardView.of(confirmed.card()));
    }

    /** 204. Idempotent: đề xuất đã hết hạn hay đã huỷ thì không làm gì; đã xác nhận thì 409. */
    @PostMapping("/{proposalId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID proposalId) {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        proposals.cancel(userId, proposalId);
    }
}
