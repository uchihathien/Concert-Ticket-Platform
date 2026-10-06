// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Chính sách hoàn vé của một sự kiện, do ban tổ chức quyết và người soạn tri thức nhập vào
 * {@code event_rules}.
 *
 * <h2>Vì sao tính theo thời điểm thanh toán, không theo ngày diễn</h2>
 *
 * <p>Ở đây chỉ có dữ liệu của chính đơn hàng: {@code paidAt} đi kèm mọi đơn đã trả tiền, còn giờ
 * diễn thuộc về catalog và không tra được theo id sự kiện qua đường công khai. Một chính sách cần
 * dữ liệu không có sẵn là một chính sách luôn trả "không biết" — và lúc đó trợ lý lại chuyển hết
 * cho người, đúng thứ cả cơ chế này sinh ra để giảm.
 *
 * <p>Đây là <b>chốt từ chối</b>, không phải chốt chấp thuận: đủ điều kiện ở đây nghĩa là phiếu
 * được mở cho nhân viên xem xét, không nghĩa là tiền đã được hoàn. Hoàn tiền thật vẫn là việc của
 * người và của sổ cái.
 *
 * @param allowed sự kiện có nhận yêu cầu hoàn vé không
 * @param windowHours số giờ kể từ lúc thanh toán còn được xin hoàn; {@code 0} nghĩa là không giới
 *     hạn thời gian (chỉ có ý nghĩa khi {@code allowed})
 */
public record RefundPolicy(boolean allowed, int windowHours) {

    /** Mặc định khi sự kiện chưa khai gì: không nhận hoàn vé. Sai theo hướng an toàn. */
    public static final RefundPolicy NONE = new RefundPolicy(false, 0);

    public RefundPolicy {
        if (windowHours < 0) {
            throw new IllegalArgumentException("windowHours không được âm: " + windowHours);
        }
    }

    public enum Eligibility {
        /** Đủ điều kiện để mở phiếu cho nhân viên xem xét. */
        ELIGIBLE,
        /** Sự kiện không nhận hoàn vé. */
        NOT_ALLOWED,
        /** Đã quá thời hạn kể từ lúc thanh toán. */
        WINDOW_PASSED,
        /** Đơn chưa thanh toán — không có gì để hoàn. */
        NOT_PAID
    }

    /**
     * @param paidAt lúc đơn được thanh toán; {@code null} nghĩa là chưa trả tiền
     * @param now thời điểm xét
     */
    public Eligibility evaluate(Instant paidAt, Instant now) {
        if (paidAt == null) {
            return Eligibility.NOT_PAID;
        }
        if (!allowed) {
            return Eligibility.NOT_ALLOWED;
        }
        if (windowHours > 0 && now.isAfter(paidAt.plus(Duration.ofHours(windowHours)))) {
            return Eligibility.WINDOW_PASSED;
        }
        return Eligibility.ELIGIBLE;
    }
}
