// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.port.CheckinBundleReader;
import com.nexaticket.ticketing.domain.port.CheckinBundleReader.BundleTicket;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.IdentityPort;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Soát vé khi máy mất mạng, và tìm khách quên vé.
 *
 * <h2>Mô hình offline</h2>
 *
 * <p>Bắt đầu ca, máy tải về {@link #bundle gói dữ liệu} của suất: khoá công khai để tự kiểm chữ ký
 * QR, và danh sách vé kèm trạng thái trong/ngoài. Mất mạng, máy tự quyết định tại chỗ và xếp lượt
 * quét vào hàng đợi; có mạng lại thì {@link #sync gửi lên theo lô}. Server chạy lại ĐÚNG
 * {@link CheckInHandler} cho từng lượt theo thứ tự thời điểm quét — cùng các kiểm tra, cùng câu
 * UPDATE nguyên tử — nên dữ liệu không bao giờ lệch giữa đường online và offline.
 *
 * <p>Hai máy offline cùng cho một vé vào: lượt tới server trước thắng; lượt sau được ghi là
 * {@code ALREADY_CHECKED_IN} kèm cờ {@code conflict} — khách đã ở trong rồi, không sửa được, nhưng
 * nhân viên và ban tổ chức thấy được.
 *
 * <h2>Dữ liệu cá nhân</h2>
 *
 * <p>Email và SĐT chỉ ra khỏi service ở dạng ĐÃ CHE ({@code ng***@gmail.com}, {@code *******567}):
 * đủ để nhân viên hỏi khách "email của anh bắt đầu bằng ng phải không", không đủ để chép danh bạ.
 */
@Service
public class OfflineCheckin {

    private static final Logger log = LoggerFactory.getLogger(OfflineCheckin.class);

    /** Một lô đồng bộ tối đa — máy offline cả buổi ở một cổng đông vẫn dưới con số này. */
    public static final int MAX_BATCH = 500;

    private static final int SEARCH_LIMIT = 20;

    /** Liên lạc chụp cũ hơn thế này thì hỏi lại identity — đổi SĐT không phải chuyện hằng phút. */
    private static final Duration CONTACT_TTL = Duration.ofHours(1);

    private final CheckinAccess access;
    private final CheckInHandler checkIn;
    private final CheckinBundleReader reader;
    private final CheckinLogRepository checkinLog;
    private final IdentityPort identity;
    private final TicketSigner signer;
    private final Clock clock;

    public OfflineCheckin(
            CheckinAccess access,
            CheckInHandler checkIn,
            CheckinBundleReader reader,
            CheckinLogRepository checkinLog,
            IdentityPort identity,
            TicketSigner signer,
            Clock clock) {
        this.access = access;
        this.checkIn = checkIn;
        this.reader = reader;
        this.checkinLog = checkinLog;
        this.identity = identity;
        this.signer = signer;
        this.clock = clock;
    }

    /** Khoá công khai Ed25519 thô (32 byte, base64url) theo {@code kid}. */
    public record VerificationKey(String kid, String publicKey) {}

    /**
     * @param status VALID · CHECKED_IN · REVOKED (vé hoàn tiền cũng là REVOKED)
     * @param code 8 ký tự đầu mã vé, viết hoa — đúng thứ in trên vé và thứ nhân viên gõ tay
     */
    public record TicketRow(
            UUID ticketId,
            String code,
            String ticketTypeName,
            String zoneCode,
            String seatCode,
            String seatLabel,
            String holderName,
            String emailMasked,
            String phoneMasked,
            String status,
            boolean inside,
            int entryCount,
            Instant lastEntryAt,
            String lastEntryGate,
            Instant changedAt) {}

    /**
     * @param serverTime mốc để máy gửi lại làm {@code since} ở lần tải bù — tính trước khi đọc, nên
     *     thay đổi xảy ra trong lúc đọc sẽ có mặt ở lần sau thay vì bị bỏ sót
     * @param full {@code true} khi đây là toàn bộ vé của suất (máy thay hẳn dữ liệu cũ)
     */
    public record Bundle(
            UUID eventSessionId,
            Instant serverTime,
            boolean full,
            List<VerificationKey> keys,
            List<TicketRow> tickets) {}

    /**
     * Một lượt quét máy đã làm lúc mất mạng.
     *
     * @param deviceResult kết quả máy đã hiện cho khách (ACCEPTED, EXITED, ALREADY_CHECKED_IN…)
     */
    public record OfflineScan(
            UUID clientScanId,
            String qrToken,
            String ticketCode,
            UUID ticketId,
            String direction,
            Instant scannedAt,
            String deviceId,
            String deviceResult) {}

    /**
     * Kết quả server cho từng lượt.
     *
     * @param conflict máy đã cho vào nhưng server không đồng ý
     * @param duplicate lượt này đã được ghi ở lần gửi trước
     */
    public record SyncedScan(
            UUID clientScanId,
            String result,
            String deviceResult,
            boolean conflict,
            boolean duplicate,
            String ticketCode,
            String holderName,
            String seatLabel,
            String note) {}

    public record SyncResult(int accepted, int conflicts, List<SyncedScan> scans) {}

    // --- gói dữ liệu ---

    public Bundle bundle(UUID organizationId, UUID eventSessionId, Instant since) {
        access.requireScannerOnShift(TenantId.of(organizationId), eventSessionId, null);
        Instant serverTime = clock.instant();
        refreshContacts(organizationId, eventSessionId);
        List<TicketRow> tickets = reader.ticketsOf(organizationId, eventSessionId, since).stream()
                .map(OfflineCheckin::row)
                .toList();
        List<VerificationKey> verification = signer.verificationKeys().stream()
                .map(key -> new VerificationKey(key.kid(), key.publicKey()))
                .toList();
        return new Bundle(eventSessionId, serverTime, since == null, verification, tickets);
    }

    // --- đồng bộ lô ---

    /**
     * Chạy lại từng lượt quét offline qua {@link CheckInHandler}, theo thứ tự thời điểm quét.
     *
     * <p>Mỗi lượt là một transaction riêng (của chính {@code CheckInHandler}): một lượt hỏng không
     * kéo cả lô rollback, và lô gửi lại giữa chừng chỉ ghi phần chưa ghi ({@code clientScanId}).
     */
    public SyncResult sync(UUID organizationId, UUID eventSessionId, List<OfflineScan> scans) {
        if (scans.size() > MAX_BATCH) {
            throw new ApiException(
                    TicketingErrorCode.SYNC_BATCH_TOO_LARGE, "Tối đa " + MAX_BATCH + " lượt quét một lần gửi");
        }
        // Kiểm quyền chung một lần (đã đăng nhập, được soát suất này). Ràng buộc CA kiểm theo từng
        // lượt, tại thời điểm quét của lượt đó.
        access.requireScanner(TenantId.of(organizationId), eventSessionId);

        List<OfflineScan> ordered = new ArrayList<>(scans);
        Instant now = clock.instant();
        ordered.sort(Comparator.comparing(scan -> scan.scannedAt() == null ? now : scan.scannedAt()));

        List<SyncedScan> results = new ArrayList<>();
        int accepted = 0;
        int conflicts = 0;
        for (OfflineScan scan : ordered) {
            // Máy có đồng hồ sai thì đừng để nó ghi lượt quét vào tương lai.
            Instant at = scan.scannedAt() == null || scan.scannedAt().isAfter(now) ? now : scan.scannedAt();
            SyncedScan synced;
            try {
                var onShift = access.requireScannerOnShift(TenantId.of(organizationId), eventSessionId, at);
                var result = checkIn.handle(new CheckInHandler.Command(
                        scan.qrToken(),
                        scan.ticketCode(),
                        eventSessionId,
                        onShift.scope().userId().value(),
                        organizationId,
                        scan.deviceId(),
                        scan.direction(),
                        onShift.shiftGate(),
                        false,
                        null,
                        scan.ticketId(),
                        at,
                        true,
                        scan.clientScanId(),
                        scan.deviceResult()));
                synced = new SyncedScan(
                        scan.clientScanId(),
                        result.result(),
                        scan.deviceResult(),
                        result.conflict(),
                        result.duplicate(),
                        result.ticketId() == null ? null : codeOf(result.ticketId()),
                        result.holderName(),
                        result.seatLabel() != null ? result.seatLabel() : result.seatCode(),
                        result.note());
            } catch (ApiException e) {
                // Ngoài ca, hay không còn được gán: lượt này KHÔNG được ghi như một lượt soát vé hợp lệ.
                // Máy đã cho khách vào thì đó vẫn là xung đột cần người xem lại.
                log.warn("Lượt quét offline {} bị từ chối khi đồng bộ: {}", scan.clientScanId(), e.getMessage());
                boolean deviceLetIn = CheckinResult.ACCEPTED.name().equals(scan.deviceResult());
                synced = new SyncedScan(
                        scan.clientScanId(),
                        e.errorCode().code(),
                        scan.deviceResult(),
                        deviceLetIn,
                        false,
                        null,
                        null,
                        null,
                        e.getMessage());
            }
            if (CheckinResult.ACCEPTED.name().equals(synced.result()) && !synced.duplicate()) {
                accepted++;
            }
            if (synced.conflict()) {
                conflicts++;
            }
            results.add(synced);
        }
        if (conflicts > 0) {
            log.warn("Đồng bộ offline suất {}: {} lượt xung đột", eventSessionId, conflicts);
        }
        return new SyncResult(accepted, conflicts, results);
    }

    // --- tìm khách ---

    public List<TicketRow> search(UUID organizationId, UUID eventSessionId, String query) {
        access.requireScannerOnShift(TenantId.of(organizationId), eventSessionId, null);
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.length() < 2) {
            return List.of();
        }
        refreshContacts(organizationId, eventSessionId);
        String digits = trimmed.replaceAll("\\D", "");
        return reader.search(organizationId, eventSessionId, fold(trimmed), digits, SEARCH_LIMIT).stream()
                .map(OfflineCheckin::row)
                .toList();
    }

    /**
     * Cho vào bằng tra cứu: nhân viên đã tìm thấy khách và đối chiếu giấy tờ tuỳ thân.
     *
     * <p>Đi qua đủ các kiểm tra của lượt quét — đang ở trong, sai suất, thu hồi, ngoài ca. Khác "cho
     * vào thủ công" (override): không vượt được kết quả "đang ở trong", nên nhân viên thường cũng dùng
     * được, như Eventbrite.
     */
    public CheckInHandler.Result admitByLookup(
            UUID organizationId, UUID eventSessionId, UUID ticketId, boolean identityChecked, String deviceId) {
        if (!identityChecked) {
            throw new ApiException(
                    TicketingErrorCode.LOOKUP_IDENTITY_REQUIRED, "Cần đối chiếu giấy tờ tuỳ thân trước khi cho vào");
        }
        var onShift = access.requireScannerOnShift(TenantId.of(organizationId), eventSessionId, null);
        return checkIn.handle(new CheckInHandler.Command(
                null,
                null,
                eventSessionId,
                onShift.scope().userId().value(),
                organizationId,
                deviceId,
                "IN",
                onShift.shiftGate(),
                false,
                "Đã đối chiếu giấy tờ tuỳ thân",
                ticketId,
                null,
                false,
                null,
                null));
    }

    /** Một lượt quét offline bị đánh dấu xung đột — để nhân viên và ban tổ chức xem lại. */
    public record ConflictRow(
            Instant scannedAt,
            String result,
            String gate,
            String ticketCode,
            String holderName,
            String seatLabel,
            String ticketTypeName,
            String note) {}

    /** Xung đột offline của một suất, mới nhất trước. */
    public List<ConflictRow> conflicts(UUID organizationId, UUID eventSessionId) {
        access.requireScanner(TenantId.of(organizationId), eventSessionId);
        return checkinLog.conflicts(organizationId, eventSessionId, 100).stream()
                .map(entry -> new ConflictRow(
                        entry.scannedAt(),
                        entry.result(),
                        entry.gate(),
                        entry.ticketId() == null ? null : codeOf(entry.ticketId()),
                        entry.holderName(),
                        entry.seatLabel() != null ? entry.seatLabel() : entry.seatCode(),
                        entry.ticketTypeName(),
                        entry.note()))
                .toList();
    }

    /** Liên lạc chụp cũ hoặc chưa có thì hỏi identity; identity im lặng thì dùng bản cũ. */
    private void refreshContacts(UUID organizationId, UUID eventSessionId) {
        Instant now = clock.instant();
        List<UUID> stale = reader.holdersNeedingContacts(organizationId, eventSessionId, now.minus(CONTACT_TTL));
        if (stale.isEmpty()) {
            return;
        }
        identity.contactsOf(stale).ifPresent(contacts -> reader.saveContacts(contacts, now));
    }

    private static TicketRow row(BundleTicket ticket) {
        return new TicketRow(
                ticket.ticketId(),
                codeOf(ticket.ticketId()),
                ticket.ticketTypeName(),
                ticket.zoneCode(),
                ticket.seatCode(),
                ticket.seatLabel(),
                ticket.holderName(),
                maskEmail(ticket.email()),
                maskPhone(ticket.phone()),
                "REFUNDED".equals(ticket.paymentStatus()) ? "REVOKED" : ticket.status(),
                ticket.inside(),
                ticket.entryCount(),
                ticket.lastEntryAt(),
                ticket.lastEntryGate(),
                ticket.changedAt());
    }

    static String codeOf(UUID ticketId) {
        return ticketId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** {@code nguyenvana@gmail.com} → {@code ng***@gmail.com}. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "***" + email.substring(at);
    }

    /** {@code 0901234567} → {@code *******567}. */
    static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return digits.isEmpty() ? null : "***";
        }
        return "*".repeat(digits.length() - 3) + digits.substring(digits.length() - 3);
    }

    /** Cùng phép bỏ dấu với hàm SQL {@code vn_fold}: thường hoá, bỏ dấu, đ → d. */
    static String fold(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('đ', 'd');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
