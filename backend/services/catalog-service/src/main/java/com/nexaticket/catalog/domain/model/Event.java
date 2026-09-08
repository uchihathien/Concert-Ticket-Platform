// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root của Catalog.
 *
 * <p>Suất diễn và hạng vé là entity con, không phải aggregate riêng: kiểm tra trước khi publish
 * ("mọi suất đều bán được thứ gì đó") chỉ trả lời được khi nhìn cả cụm, và không ai sửa một hạng
 * vé mà không nghĩ tới sự kiện chứa nó.
 *
 * <p>Địa điểm thì <b>ở ngoài</b> aggregate: nó sống lâu hơn sự kiện và được nhiều sự kiện dùng
 * chung. Nên chỗ nào cần khu của địa điểm — publish — thì nhận {@link Venue} như tham số.
 */
public final class Event {

    private final UUID id;
    private final UUID organizationId;
    private final UUID venueId;
    private final Slug slug;
    private String title;
    private String summary;
    private String description;
    private String category;
    private String posterUrl;
    private EventStatus status;
    private Instant publishedAt;
    private final List<EventSession> sessions;

    public Event(
            UUID id,
            UUID organizationId,
            UUID venueId,
            Slug slug,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl,
            EventStatus status,
            Instant publishedAt,
            List<EventSession> sessions) {
        this.id = id;
        this.organizationId = organizationId;
        this.venueId = venueId;
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.description = description;
        this.category = category;
        this.posterUrl = posterUrl;
        this.status = status;
        this.publishedAt = publishedAt;
        this.sessions = new ArrayList<>(sessions);
    }

    public static Event draft(
            UUID organizationId,
            UUID venueId,
            Slug slug,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Sự kiện phải có tiêu đề");
        }
        return new Event(
                UUID.randomUUID(),
                organizationId,
                venueId,
                slug,
                title,
                summary,
                description,
                category,
                posterUrl,
                EventStatus.DRAFT,
                null,
                List.of());
    }

    /**
     * Những gì còn thiếu để publish được.
     *
     * <p>Danh sách rỗng nghĩa là publish được. Không ném lỗi ở đây — quyết định biến danh sách này
     * thành phản hồi HTTP nào là việc của tầng application.
     *
     * <p><b>Chưa kiểm tài khoản ngân hàng</b>, dù docs/02-catalog-admin/api.md có liệt kê
     * {@code NO_ACTIVE_BANK_ACCOUNT}: tài khoản ngân hàng thuộc payment-service, và gọi đồng bộ
     * sang đó lúc publish sẽ khiến Catalog không publish nổi mỗi khi Payments trục trặc. Chỗ đúng
     * cho ràng buộc đó là lúc tạo đơn hàng, nơi tiền thật sự chảy.
     */
    public List<PublishBlocker> preflight(Venue venue) {
        List<PublishBlocker> blockers = new ArrayList<>();
        if (!venue.hasZones()) {
            blockers.add(PublishBlocker.VENUE_WITHOUT_ZONE);
        }
        if (sessions.isEmpty()) {
            blockers.add(PublishBlocker.NO_SESSION);
        }
        if (sessions.stream().anyMatch(s -> !s.sellsSomething())) {
            blockers.add(PublishBlocker.SESSION_WITHOUT_TICKET_TYPE);
        }
        if (sessions.stream().anyMatch(s -> !s.hasValidSalesWindow())) {
            blockers.add(PublishBlocker.INVALID_SALES_WINDOW);
        }
        return List.copyOf(blockers);
    }

    /** Gọi sau khi {@link #preflight} trả về danh sách rỗng. */
    public void publish(Instant now) {
        if (!status.canPublish()) {
            throw new IllegalStateException("Không publish được sự kiện đang ở trạng thái " + status);
        }
        status = EventStatus.PUBLISHED;
        publishedAt = now;
    }

    /**
     * Rút khỏi trang công khai.
     *
     * <p>Không xoá tồn kho bên Inventory và không đụng tới vé đã bán: khách đã trả tiền vẫn phải
     * vào được. Rút xuống chỉ có nghĩa "không bán thêm nữa".
     */
    public void unpublish() {
        if (status != EventStatus.PUBLISHED) {
            throw new IllegalStateException("Chỉ rút xuống được sự kiện đang bán");
        }
        status = EventStatus.UNPUBLISHED;
        publishedAt = null;
    }

    public void rename(
            String newTitle, String newSummary, String newDescription, String newCategory, String newPoster) {
        if (newTitle != null && !newTitle.isBlank()) {
            title = newTitle;
        }
        if (newSummary != null) {
            summary = newSummary;
        }
        if (newDescription != null) {
            description = newDescription;
        }
        if (newCategory != null && !newCategory.isBlank()) {
            category = newCategory;
        }
        if (newPoster != null) {
            posterUrl = newPoster;
        }
    }

    public void addSession(EventSession session) {
        sessions.add(session);
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID venueId() {
        return venueId;
    }

    public Slug slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public String summary() {
        return summary;
    }

    public String description() {
        return description;
    }

    public String category() {
        return category;
    }

    public String posterUrl() {
        return posterUrl;
    }

    public EventStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public List<EventSession> sessions() {
        return List.copyOf(sessions);
    }
}
