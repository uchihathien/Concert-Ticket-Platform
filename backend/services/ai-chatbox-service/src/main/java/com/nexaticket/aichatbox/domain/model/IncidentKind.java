// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Loại sự cố khách báo — mỗi loại là một khung mẫu mà người trực đã quen đọc.
 *
 * <p>Cố định thành một danh sách ngắn thay vì để mô hình tự đặt tên: người trực xử lý "chưa nhận
 * được vé" bằng một quy trình, "QR không quét được" bằng một quy trình khác, và một phiếu ghi
 * "sự cố vé" thì không nói được cần quy trình nào. Loại nào không khớp thì vào {@link #OTHER} —
 * vẫn là phiếu, chỉ không có khung mẫu sẵn.
 *
 * @see com.nexaticket.aichatbox.application.handoff.IncidentTemplates
 */
public enum IncidentKind {

    /** Đã thanh toán nhưng ví vé trống. */
    TICKET_NOT_RECEIVED("Chưa nhận được vé", true),

    /** Đã chuyển khoản mà đơn vẫn báo chờ thanh toán. */
    PAYMENT_NOT_CONFIRMED("Thanh toán chưa được ghi nhận", true),

    /** Mã QR không quét được ở cổng soát vé. */
    QR_NOT_SCANNABLE("Mã QR không quét được", true),

    /** Vé ghi sai chỗ, sai hạng, sai suất so với lúc đặt. */
    WRONG_TICKET_DETAILS("Vé sai thông tin", true),

    /** Sự kiện đổi lịch, đổi địa điểm hoặc bị huỷ. */
    EVENT_CHANGED("Sự kiện thay đổi hoặc huỷ", false),

    /** Không xếp được vào loại nào ở trên. */
    OTHER("Sự cố khác", false);

    private final String label;
    private final boolean needsOrder;

    IncidentKind(String label, boolean needsOrder) {
        this.label = label;
        this.needsOrder = needsOrder;
    }

    /** Tên tiếng Việt để hiện cho người trực và cho khách. */
    public String label() {
        return label;
    }

    /**
     * Sự cố này có cần mã đơn hàng để xử lý không.
     *
     * <p>"Chưa nhận được vé" mà không có mã đơn thì người trực không có gì để tra; "sự kiện bị huỷ"
     * thì có thể hỏi chung cho mọi đơn. Khung mẫu dùng cờ này để đòi mô hình hỏi xin mã trước.
     */
    public boolean needsOrder() {
        return needsOrder;
    }

    /**
     * Đọc tên loại từ chuỗi mô hình sinh ra — không phân biệt hoa thường, nhận cả dấu gạch nối.
     *
     * @return rỗng khi không khớp loại nào; bên gọi quyết định dùng {@link #OTHER} hay hỏi lại
     */
    public static Optional<IncidentKind> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (IncidentKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
