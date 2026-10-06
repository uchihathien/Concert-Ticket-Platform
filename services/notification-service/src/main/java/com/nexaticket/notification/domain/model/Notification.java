// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Một thư cần gửi.
 *
 * <p>Được lưu vào database <b>trước</b> khi gửi, không phải gửi rồi mới lưu. Nhờ vậy consumer ack
 * message ngay và việc gửi trở thành một hàng đợi riêng có retry — máy chủ SMTP chậm không làm
 * nghẽn cả consumer, và process chết giữa chừng không làm mất thư.
 */
public final class Notification {

    /** Sau ngần này lần thất bại thì bỏ cuộc và để người xử lý. */
    private static final int MAX_ATTEMPTS = 5;

    private final UUID id;
    private final UUID eventId;
    private final String eventType;
    private final String recipient;
    private final EmailTemplate template;
    private final Map<String, Object> payload;

    private NotificationStatus status;
    private int attempts;
    private String lastError;
    private Instant nextRetryAt;
    private Instant sentAt;

    private Notification(
            UUID id,
            UUID eventId,
            String eventType,
            String recipient,
            EmailTemplate template,
            Map<String, Object> payload,
            NotificationStatus status,
            int attempts) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.recipient = recipient;
        this.template = template;
        this.payload = Map.copyOf(payload);
        this.status = status;
        this.attempts = attempts;
    }

    public static Notification queue(
            UUID eventId, String eventType, String recipient, EmailTemplate template, Map<String, Object> payload) {
        return new Notification(
                UUID.randomUUID(), eventId, eventType, recipient, template, payload, NotificationStatus.PENDING, 0);
    }

    public static Notification rehydrate(
            UUID id,
            UUID eventId,
            String eventType,
            String recipient,
            EmailTemplate template,
            Map<String, Object> payload,
            NotificationStatus status,
            int attempts) {
        return new Notification(id, eventId, eventType, recipient, template, payload, status, attempts);
    }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.sentAt = now;
    }

    /**
     * Ghi nhận một lần gửi hỏng và tính thời điểm thử lại.
     *
     * <p>Backoff mũ: 1, 2, 4, 8, 16 phút. Thử lại ngay lập tức khi máy chủ SMTP đang quá tải chỉ
     * làm nó quá tải thêm, và một hàng đợi retry dày đặc là cách nhanh nhất để bị nhà cung cấp
     * email chặn.
     */
    public void markFailed(String error, Instant now) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= MAX_ATTEMPTS) {
            this.status = NotificationStatus.DEAD;
            this.nextRetryAt = null;
        } else {
            this.status = NotificationStatus.PENDING;
            this.nextRetryAt = now.plus(Duration.ofMinutes(1L << (attempts - 1)));
        }
    }

    public String subject() {
        return template.subject(payload);
    }

    public String body() {
        return template.body(payload);
    }

    public UUID id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String recipient() {
        return recipient;
    }

    public EmailTemplate template() {
        return template;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public NotificationStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public Instant nextRetryAt() {
        return nextRetryAt;
    }

    public Instant sentAt() {
        return sentAt;
    }
}
