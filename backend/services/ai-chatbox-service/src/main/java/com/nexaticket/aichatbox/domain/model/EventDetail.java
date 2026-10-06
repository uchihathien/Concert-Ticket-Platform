// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một sự kiện với đủ thứ khách hay hỏi: diễn ở đâu, khi nào, vé bao nhiêu.
 *
 * <p><b>Vì sao agent tra catalog thay vì đọc kho tri thức.</b> Giá vé, suất diễn và hạn bán vé là
 * dữ liệu ban tổ chức sửa bất cứ lúc nào. Chép chúng thành đoạn tri thức có nhúng vector nghĩa là
 * mỗi lần ban tổ chức đổi giá, trợ lý vẫn đọc số cũ cho tới khi có người nhớ ra phải nhúng lại —
 * và không có gì báo rằng nó đang nói sai. Tra trực tiếp thì không có bản sao nào để lỗi thời.
 *
 * <p>Kho tri thức giữ đúng phần nó giỏi: chính sách và quy định, thứ đổi theo tháng chứ không theo
 * giờ, và cần tìm theo ý nghĩa chứ không theo tên.
 */
public record EventDetail(
        String slug,
        String title,
        String summary,
        String description,
        String category,
        String city,
        String venueName,
        String venueAddress,
        List<Session> sessions) {

    public EventDetail {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    /**
     * @param id mã suất diễn — thứ tool giữ chỗ cần. Là mã duy nhất của catalog được phép đi vào
     *     prompt, vì không có nó thì mô hình không có cách nào nói với inventory "suất nào"; và nó
     *     chỉ đi ra từ kết quả tool, nên mô hình không tự dựng được.
     */
    public record Session(UUID id, Instant startsAt, Instant endsAt, Instant salesCloseAt, List<Tier> tiers) {
        public Session {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }

    /**
     * Một hạng vé: tên khách đọc được, giá, và khu vực tương ứng trên sơ đồ.
     *
     * @param zoneCode mã khu để giữ chỗ — cùng lý do với {@code Session.id}
     */
    public record Tier(String name, long priceVnd, String zoneCode, String zoneName) {}
}
