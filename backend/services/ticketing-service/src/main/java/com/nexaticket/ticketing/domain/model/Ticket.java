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
    private final String holderName;

    private TicketStatus status;
    private Instant checkedInAt;
    private UUID checkedInBy;

    private boolean inside;
    private int entryCount;
    private Instant lastEntryAt;
    private String lastEntryGate;
    private Instant lastExitAt;

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
            String holderName,
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
        this.holderName = holderName;
        this.status = status;
        this.checkedInAt = checkedInAt;
        this.checkedInBy = checkedInBy;
        // Mặc định khớp với vé có từ trước khi có vào/ra: đã soát nghĩa là đang ở trong.
        this.inside = status == TicketStatus.CHECKED_IN;
        this.entryCount = this.inside ? 1 : 0;
        this.lastEntryAt = checkedInAt;
    }

    /** Nạp trạng thái trong/ngoài đọc từ database. */
    public Ticket withPresence(
            boolean inside, int entryCount, Instant lastEntryAt, String lastEntryGate, Instant lastExitAt) {
        this.inside = inside;
        this.entryCount = entryCount;
        this.lastEntryAt = lastEntryAt;
        this.lastEntryGate = lastEntryGate;
        this.lastExitAt = lastExitAt;
        return this;
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
            String ticketTypeName,
            String holderName) {
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
                holderName,
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
            String holderName,
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
                holderName,
                status,
                checkedInAt,
                checkedInBy);
    }

    /**
     * Quyết định kết quả một lần quét, <b>chưa</b> thay đổi gì.
     *
     * <p>Tách quyết định khỏi việc ghi để luật soát vé kiểm được bằng test thuần. Việc thực sự
     * đổi trạng thái phải là một {@code UPDATE ... WHERE inside = ...} nguyên tử ở tầng
     * persistence — hai máy quét cùng một vé trong cùng một giây là chuyện bình thường ở cửa vào.
     *
     * <p>Thứ tự là cố ý: sai suất trước, thu hồi sau, rồi mới tới trong/ngoài. Khách cầm vé đêm
     * qua tới đêm nay cần nghe "vé của suất khác", không phải "vé đang ở trong".
     *
     * @param scanningSession suất diễn mà máy soát vé đang phục vụ
     */
    public CheckinResult evaluate(UUID scanningSession, ScanDirection direction, boolean reentryAllowed) {
        if (!eventSessionId.equals(scanningSession)) {
            return CheckinResult.WRONG_SESSION;
        }
        if (status == TicketStatus.REVOKED) {
            return CheckinResult.REVOKED;
        }
        if (direction == ScanDirection.OUT) {
            return inside ? CheckinResult.EXITED : CheckinResult.NOT_INSIDE;
        }
        if (inside) {
            return CheckinResult.ALREADY_CHECKED_IN;
        }
        return entryCount > 0 && !reentryAllowed ? CheckinResult.REENTRY_NOT_ALLOWED : CheckinResult.ACCEPTED;
    }

    /** Quét Vào, cho phép vào lại. */
    public CheckinResult evaluate(UUID scanningSession) {
        return evaluate(scanningSession, ScanDirection.IN, true);
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

    /**
     * Tên người mua, chụp lại lúc phát vé.
     *
     * <p>{@code null} khi identity-service không trả lời được lúc đó. Phát vé không được hỏng vì
     * một cái tên — khách đã trả tiền rồi.
     */
    public String holderName() {
        return holderName;
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

    public boolean inside() {
        return inside;
    }

    /** Số lần đã vào cửa, kể cả lần đang ở trong. */
    public int entryCount() {
        return entryCount;
    }

    public Instant lastEntryAt() {
        return lastEntryAt;
    }

    public String lastEntryGate() {
        return lastEntryGate;
    }

    public Instant lastExitAt() {
        return lastExitAt;
    }
}
