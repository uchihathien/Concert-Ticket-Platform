// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Đường đọc cho máy soát vé: vé của một suất (để soát offline) và tìm khách quên vé.
 *
 * <p>Cùng ranh giới CQRS với {@link TicketSearchPort}: hàng phẳng, không dựng aggregate. Email và SĐT
 * ở đây là bản ĐẦY ĐỦ đọc từ bảng chụp — tầng application che chúng trước khi trả ra ngoài.
 */
public interface CheckinBundleReader {

    /**
     * Vé của một suất. {@code since} khác {@code null} thì chỉ những vé đổi trạng thái (phát hành,
     * vào, ra, thu hồi) sau mốc đó — phần tải bù định kỳ của máy soát vé.
     */
    List<BundleTicket> ticketsOf(UUID organizationId, UUID eventSessionId, Instant since);

    /**
     * Tìm vé trong một suất theo tên (không dấu), email, SĐT hoặc phần đầu mã vé.
     *
     * @param folded chuỗi đã bỏ dấu, chữ thường
     * @param digits các chữ số trong chuỗi tìm (cho SĐT); rỗng thì không tìm theo SĐT
     */
    List<BundleTicket> search(UUID organizationId, UUID eventSessionId, String folded, String digits, int limit);

    /** Người mua của suất chưa có liên lạc chụp, hoặc bản chụp cũ hơn {@code olderThan}. */
    List<UUID> holdersNeedingContacts(UUID organizationId, UUID eventSessionId, Instant olderThan);

    void saveContacts(Collection<IdentityPort.Contact> contacts, Instant at);

    /**
     * @param status VALID · CHECKED_IN · REVOKED
     * @param changedAt lần đổi trạng thái gần nhất — mốc {@code since} của lần tải bù kế tiếp
     */
    record BundleTicket(
            UUID ticketId,
            String ticketTypeName,
            String zoneCode,
            String seatCode,
            String seatLabel,
            String holderName,
            String email,
            String phone,
            String status,
            String paymentStatus,
            boolean inside,
            int entryCount,
            Instant lastEntryAt,
            String lastEntryGate,
            Instant changedAt) {}
}
