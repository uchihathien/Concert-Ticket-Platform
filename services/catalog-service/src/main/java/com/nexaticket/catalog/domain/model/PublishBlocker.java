// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Lý do một sự kiện chưa publish được.
 *
 * <p>Trả về cả danh sách chứ không ném lỗi ở vướng mắc đầu tiên: màn hình publish là một checklist
 * (docs/ui/flows/organizer-publish.md), nên ban tổ chức cần thấy hết những gì còn thiếu trong một
 * lần, không phải sửa một chỗ rồi bấm lại để lộ ra chỗ tiếp theo.
 */
public enum PublishBlocker {
    /** Chưa có suất diễn nào. */
    NO_SESSION,
    /** Có suất diễn chưa khai hạng vé nào — tức là có suất không bán được gì. */
    SESSION_WITHOUT_TICKET_TYPE,
    /** Cửa bán đóng trước khi mở, hoặc mở sau khi suất diễn đã diễn ra. */
    INVALID_SALES_WINDOW,
    /** Địa điểm chưa khai khu nào, nên không có chỗ để bán. */
    VENUE_WITHOUT_ZONE
}
