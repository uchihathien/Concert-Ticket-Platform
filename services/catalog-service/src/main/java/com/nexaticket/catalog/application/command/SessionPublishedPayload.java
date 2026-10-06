// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.FloorPlan;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hợp đồng của sự kiện {@code session.published}.
 *
 * <p>Đây là <b>ranh giới giữa hai service</b>, nên hình dạng của nó được viết ra tường minh thay vì
 * để Jackson tự suy từ model của domain. Đổi một tên field trong domain mà payload đi theo là làm
 * hỏng inventory-service ở runtime, và không có gì trong lúc biên dịch báo cho biết.
 *
 * <p>Mọi UUID và mốc thời gian đều là {@link String}, có chủ đích: bên nhận gọi
 * {@code UUID.fromString} và {@code Instant.parse}. Để kiểu {@code Instant} ở đây thì cách tuần tự
 * hoá phụ thuộc cấu hình {@code ObjectMapper} — một service bật {@code WRITE_DATES_AS_TIMESTAMPS}
 * là payload thành số epoch và bên nhận vỡ, ở runtime, chỉ với những message thật.
 */
public record SessionPublishedPayload(
        String eventSessionId,
        String eventId,
        String organizationId,
        String salesOpenAt,
        String salesCloseAt,
        Limits limits,
        List<Seat> seats,
        List<StandingBlock> standingBlocks) {

    public record Limits(
            int maxSeatedPerHold, int maxStandingPerHold, int maxUnitsPerHold, int maxTicketsPerCustomer) {}

    /**
     * Một chỗ ngồi cụ thể.
     *
     * @param posX vị trí trên mặt bằng, đơn vị là "ghế" — một đơn vị là khoảng cách giữa hai ghế
     *     cạnh nhau. Không phải pixel: cùng sơ đồ này được vẽ trên điện thoại 360px, màn hình quản
     *     trị 1600px và ảnh poster 2480px, nên toạ độ mang đơn vị hiển thị sẽ sai ở hai trong ba
     *     chỗ. Số thực chứ không phải số nguyên vì khu hình cung đặt ghế ở giữa cung góc của nó.
     */
    public record Seat(
            String seatCode,
            String zoneCode,
            String sectionLabel,
            String rowLabel,
            String seatLabel,
            double posX,
            double posY,
            String ticketTypeId,
            String ticketTypeName,
            long priceVnd,
            boolean blocked) {}

    /**
     * Một khối vé đứng.
     *
     * <p>Chỉ gửi số lượng, không gửi từng đơn vị: Inventory tự sinh {@code quantity} đơn vị ảo
     * (ADR-1012). Gửi sẵn 5.000 dòng cho một khu đứng là phình message mà không thêm thông tin gì.
     */
    public record StandingBlock(
            String zoneCode, int quantity, String ticketTypeId, String ticketTypeName, long priceVnd) {}

    /**
     * Dựng payload từ một suất diễn đã sẵn sàng bán.
     *
     * <p>Đây là chỗ khu vực của địa điểm được "trải phẳng" thành từng chỗ. Ghế được sinh ở đây chứ
     * không lưu sẵn trong database của Catalog vì Catalog không cần biết từng ghế — nó chỉ cần biết
     * hình dạng khu. Chỗ duy nhất cần từng ghế là Inventory, và nó nhận qua message này.
     *
     * <p>Toạ độ lấy từ {@link FloorPlan} — cùng phép tính mà màn hình quản trị và ảnh poster dùng.
     * Tính lại ở đây bằng một công thức riêng sẽ cho ra một sơ đồ thứ hai, và nó sẽ lệch đúng vào
     * ngày ban tổ chức đổi hình dạng một khu.
     *
     * @throws IllegalStateException nếu một hạng vé trỏ vào khu không thuộc địa điểm của sự kiện —
     *     dữ liệu đã hỏng từ trước, và publish tiếp sẽ dựng tồn kho sai
     */
    public static SessionPublishedPayload of(Event event, EventSession session, Venue venue, PurchaseLimits limits) {

        Map<UUID, VenueZone> zonesById =
                venue.zones().stream().collect(Collectors.toMap(VenueZone::id, Function.identity()));

        Map<String, FloorPlan.PlannedSeat> positions = new HashMap<>();
        for (FloorPlan.PlannedZone zone : venue.floorPlan().zones()) {
            for (FloorPlan.PlannedSeat seat : zone.seats()) {
                positions.put(seat.seatCode(), seat);
            }
        }

        List<Seat> seats = new ArrayList<>();
        List<StandingBlock> standing = new ArrayList<>();

        for (TicketType type : session.ticketTypes()) {
            VenueZone zone = zonesById.get(type.venueZoneId());
            if (zone == null) {
                throw new IllegalStateException(
                        "Hạng vé " + type.id() + " trỏ vào khu không thuộc địa điểm " + venue.id());
            }
            if (zone.kind() == AdmissionKind.STANDING) {
                standing.add(new StandingBlock(
                        zone.zoneCode(), zone.capacity(), type.id().toString(), type.name(), type.priceVnd()));
            } else {
                for (int row = 1; row <= zone.rowCount(); row++) {
                    for (int seat = 1; seat <= zone.seatsPerRow(); seat++) {
                        String seatCode = zone.seatCode(row, seat);
                        FloorPlan.PlannedSeat at = positions.get(seatCode);
                        if (at == null) {
                            // FloorPlan dựng từ đúng cùng danh sách khu, nên khuyết một mã chỗ ở đây
                            // nghĩa là hai bên đã lệch nhau — và tồn kho dựng tiếp sẽ có ghế không
                            // nằm ở đâu trên sơ đồ của khách.
                            throw new IllegalStateException("Mặt bằng thiếu chỗ " + seatCode);
                        }
                        seats.add(new Seat(
                                seatCode,
                                zone.zoneCode(),
                                zone.name(),
                                String.valueOf(row),
                                String.valueOf(seat),
                                at.x(),
                                at.y(),
                                type.id().toString(),
                                type.name(),
                                type.priceVnd(),
                                false));
                    }
                }
            }
        }

        return new SessionPublishedPayload(
                session.id().toString(),
                event.id().toString(),
                event.organizationId().toString(),
                session.salesOpenAt().toString(),
                session.salesCloseAt().toString(),
                new Limits(
                        limits.maxSeatedPerHold(),
                        limits.maxStandingPerHold(),
                        limits.maxUnitsPerHold(),
                        limits.maxTicketsPerCustomer()),
                List.copyOf(seats),
                List.copyOf(standing));
    }
}
