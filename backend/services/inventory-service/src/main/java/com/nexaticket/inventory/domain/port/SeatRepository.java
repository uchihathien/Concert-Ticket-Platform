// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Đơn vị tồn kho. Mọi phương thức đổi trạng thái đều là <b>UPDATE có điều kiện</b> và trả về số
 * hàng bị ảnh hưởng — không đọc-rồi-ghi.
 *
 * <p>Đọc rồi ghi sẽ mở một cửa sổ giữa hai lệnh cho transaction khác chen vào. UPDATE có điều kiện
 * đẩy việc kiểm tra vào chính lệnh ghi, nên khoảng trống đó không tồn tại.
 */
public interface SeatRepository {

    /**
     * Tuần tự hoá các request của <b>cùng một người trên cùng một suất</b>.
     *
     * <p>Chống người dùng mở hai tab bấm cùng lúc: cả hai đếm ra 4 và cả hai đều nghĩ còn chỗ trong
     * trần. Khoá này <b>không</b> tạo điểm nghẽn — hai người khác nhau không bao giờ đụng nhau, nên
     * thông lượng ở 10k đồng thời không đổi (ADR-1014 §3).
     */
    void lockCustomerSession(UUID userId, UUID eventSessionId);

    /**
     * Đếm chỗ người này đang giữ / đã đặt / đã mua ở suất diễn này.
     *
     * <p>Chỗ đã nhả, đơn hết hạn và vé đã hoàn tiền tự động không tính — ngữ nghĩa rơi ra đúng như
     * mong muốn mà không cần hỏi Ordering hay Ticketing.
     */
    int countUnitsHeldBy(UUID eventSessionId, UUID userId);

    /**
     * Chuyển các đơn vị vé ngồi sang HELD, chỉ khi đang AVAILABLE.
     *
     * @return số hàng đổi được; nhỏ hơn {@code seatIds.size()} nghĩa là có chỗ đã bị chiếm
     */
    int holdSeated(UUID eventSessionId, List<UUID> seatIds, UUID userId);

    /**
     * Cấp phát vé đứng bằng {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>Trả về ít hơn số lượng yêu cầu ⇒ zone hết chỗ, caller rollback (ADR-1012).
     *
     * @return id các đơn vị đã cấp, tối đa {@code quantity} phần tử
     */
    List<UUID> allocateStanding(UUID eventSessionId, String zoneCode, int quantity, UUID userId);

    /** HELD → RESERVED khi giữ chỗ thành đơn hàng. */
    int reserve(List<UUID> seatIds);

    /** RESERVED → SOLD sau khi thanh toán xác nhận. */
    int markSold(List<UUID> seatIds);

    /**
     * Về AVAILABLE và <b>xoá holder_user_id</b>.
     *
     * <p>Dùng cho cả bốn đường nhả: hết hạn giữ chỗ, đơn hết hạn, huỷ đơn, hoàn tiền. Quên xoá
     * holder là khách bị khoá oan hạn mức — database có CHECK chặn, nhưng đúng vẫn hơn là bị chặn.
     */
    int release(List<UUID> seatIds);

    /** Sơ đồ chỗ ngồi — chỉ vé ngồi, không kèm đơn vị ảo của vé đứng. */
    List<SeatRow> seatedRows(UUID eventSessionId);

    /** Tóm tắt tồn kho vé đứng theo zone — thay cho việc trả 3.000 đơn vị ảo xuống client. */
    List<StandingZoneRow> standingZones(UUID eventSessionId);

    /** Một đơn vị vé ngồi như client nhìn thấy. */
    record SeatRow(
            UUID id,
            String seatCode,
            String zoneCode,
            String sectionLabel,
            String rowLabel,
            String seatLabel,
            java.math.BigDecimal posX,
            java.math.BigDecimal posY,
            UUID ticketTypeId,
            String ticketTypeName,
            long priceVnd,
            String status) {}

    /** Tồn kho vé đứng của một zone. */
    record StandingZoneRow(
            String zoneCode, UUID ticketTypeId, String ticketTypeName, long priceVnd, int available, int capacity) {}
}
