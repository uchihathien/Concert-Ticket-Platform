// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Vé — một chỗ đã bán, đã trả tiền.
 *
 * <p>Toàn bộ nhãn hiển thị là snapshot từ đơn hàng: vé phải in ra đúng thứ khách đã mua, kể cả khi
 * tổ chức đã đổi tên hạng vé hoặc vẽ lại sơ đồ chỗ sau đó.
 */
public final class Ticket {

    private final UUID id;
    private final UUID orderId;
    private final UUID orderItemId;
    private final UUID eventSessionId;
    private final UUID organizationId;
    private final UUID userId;
    private final UUID sessionSeatId;
    private final String seatCode;
    private final String zoneCode;
    private final String admissionType;
    private final String seatLabel;
    private final String ticketTypeName;

    private TicketStatus status;
    private Instant checkedInAt;
    private UUID checkedInBy;

    @SuppressWarnings("java:S107") // Vé có nhiều nhãn snapshot; gom thành DTO chỉ đổi chỗ đặt tham số.
    private Ticket(
            UUID id,
            UUID orderId,
            UUID orderItemId,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName,
            TicketStatus status,
            Instant checkedInAt,
            UUID checkedInBy) {
        this.id = id;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.eventSessionId = eventSessionId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.sessionSeatId = sessionSeatId;
        this.seatCode = seatCode;
        this.zoneCode = zoneCode;
        this.admissionType = admissionType;
        this.seatLabel = seatLabel;
        this.ticketTypeName = ticketTypeName;
        this.status = status;
        this.checkedInAt = checkedInAt;
        this.checkedInBy = checkedInBy;
    }

    @SuppressWarnings("java:S107")
    public static Ticket issue(
            UUID orderId,
            UUID orderItemId,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName) {
        return new Ticket(
                UUID.randomUUID(),
                orderId,
                orderItemId,
                eventSessionId,
                organizationId,
                userId,
                sessionSeatId,
                seatCode,
                zoneCode,
                admissionType,
                seatLabel,
                ticketTypeName,
                TicketStatus.VALID,
                null,
                null);
    }

    @SuppressWarnings("java:S107")
    public static Ticket rehydrate(
            UUID id,
            UUID orderId,
            UUID orderItemId,
            UUID eventSessionId,
            UUID organizationId,
            UUID userId,
            UUID sessionSeatId,
            String seatCode,
            String zoneCode,
            String admissionType,
            String seatLabel,
            String ticketTypeName,
            TicketStatus status,
            Instant checkedInAt,
            UUID checkedInBy) {
        return new Ticket(
                id,
                orderId,
                orderItemId,
                eventSessionId,
                organizationId,
                userId,
                sessionSeatId,
                seatCode,
                zoneCode,
                admissionType,
                seatLabel,
                ticketTypeName,
                status,
                checkedInAt,
                checkedInBy);
    }

    /**
     * Quyết định kết quả một lần quét, <b>chưa</b> thay đổi gì.
     *
     * <p>Tách quyết định khỏi việc ghi để luật soát vé kiểm được bằng test thuần. Việc thực sự
     * chuyển sang {@code CHECKED_IN} phải là một {@code UPDATE ... WHERE status = 'VALID'} nguyên
     * tử ở tầng persistence — hai máy quét cùng một vé trong cùng một giây là chuyện bình thường ở
     * cửa vào.
     *
     * @param scanningSession suất diễn mà máy soát vé đang phục vụ
     */
    public CheckinResult evaluate(UUID scanningSession) {
        if (!eventSessionId.equals(scanningSession)) {
            return CheckinResult.WRONG_SESSION;
        }
        return switch (status) {
            case REVOKED -> CheckinResult.REVOKED;
            case CHECKED_IN -> CheckinResult.ALREADY_CHECKED_IN;
            case VALID -> CheckinResult.ACCEPTED;
        };
    }

    public void markCheckedIn(Instant now, UUID staffId) {
        this.status = TicketStatus.CHECKED_IN;
        this.checkedInAt = now;
        this.checkedInBy = staffId;
    }

    public boolean belongsToOrganization(UUID candidate) {
        return organizationId.equals(candidate);
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID orderItemId() {
        return orderItemId;
    }

    public UUID eventSessionId() {
        return eventSessionId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID sessionSeatId() {
        return sessionSeatId;
    }

    public String seatCode() {
        return seatCode;
    }

    public String zoneCode() {
        return zoneCode;
    }

    public String admissionType() {
        return admissionType;
    }

    public String seatLabel() {
        return seatLabel;
    }

    public String ticketTypeName() {
        return ticketTypeName;
    }

    public TicketStatus status() {
        return status;
    }

    public Instant checkedInAt() {
        return checkedInAt;
    }

    public UUID checkedInBy() {
        return checkedInBy;
    }
}
