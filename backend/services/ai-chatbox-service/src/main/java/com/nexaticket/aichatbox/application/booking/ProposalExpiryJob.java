// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.domain.port.BookingProposalRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đề xuất quá 10 phút không ai bấm → EXPIRED, theo lịch.
 *
 * <h2>Vì sao cần</h2>
 *
 * <p>Thẻ phía khách tự khoá khi hết giờ và endpoint xác nhận tự kiểm {@code expires_at}, nên job
 * này KHÔNG phải chốt chặn. Nó tồn tại vì ba thứ khác: bảng phải nói thật (không tích đề xuất "mở"
 * vô nghĩa); index bộ phận "một đề xuất mở cho một khu" phải mở lại sau 10 phút mà không cần đường
 * ghi nào tự dọn; và metric {@code aichatbox_proposals_total} cần một ảnh chụp theo trạng thái.
 *
 * <h2>Chỉ đụng PROPOSED, và nhiều instance chạy cùng lúc không sao</h2>
 *
 * <p>Phép chuyển là một {@code UPDATE … WHERE status = 'PROPOSED' AND expires_at <= now}: dòng đã
 * CONFIRMED/CANCELLED không bao giờ bị chạm, và instance thứ hai chạy cùng lúc chỉ thấy 0 dòng. Không
 * cần khoá phân tán — cùng mẫu với {@code AbandonedHandoffReaper}.
 */
@Component
@ConditionalOnProperty(name = "nexaticket.aichatbox.proposal.expiry.enabled", matchIfMissing = true)
public class ProposalExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ProposalExpiryJob.class);

    private final BookingProposalRepository proposals;
    private final AgentMetrics metrics;
    private final Clock clock;

    public ProposalExpiryJob(BookingProposalRepository proposals, AgentMetrics metrics, Clock clock) {
        this.proposals = proposals;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Mỗi phút một lần. {@code fixedDelay} chứ không {@code fixedRate}: rate xếp hàng các lần chạy
     * bị chậm rồi bắn liên tiếp để "đuổi kịp" — không ai muốn thế ở một job dọn dẹp.
     *
     * @return số đề xuất vừa chuyển sang EXPIRED
     */
    @Scheduled(fixedDelayString = "${nexaticket.aichatbox.proposal.expiry.interval:60s}", initialDelay = 60_000)
    public int expireOverdue() {
        Instant now = clock.instant();
        int expired = 0;
        try {
            expired = proposals.expireOverdue(now);
            if (expired > 0) {
                // INFO: con số này tăng đều nghĩa là khách bỏ đề xuất nhiều hơn bấm — tin về sản phẩm.
                log.info("Đã đánh dấu {} đề xuất hết hạn", expired);
            }
        } finally {
            // Metric là ảnh chụp theo bảng, cập nhật cả khi không có gì hết hạn: đề xuất vừa được
            // xác nhận hay huỷ ở đường khác cũng phải lên số.
            metrics.updateProposalTotals(proposals.countByStatus());
        }
        return expired;
    }
}
