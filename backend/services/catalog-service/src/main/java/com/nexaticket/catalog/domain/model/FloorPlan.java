// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mặt bằng đã giải xong của một địa điểm: sân khấu ở đâu, mỗi khu nằm chỗ nào, ghế nào ở toạ độ nào.
 *
 * <h3>Đây là nơi duy nhất "khu chưa đặt vị trí" được giải</h3>
 *
 * <p>{@link VenueZone#layout()} cho phép {@code null} — tổ chức khai "VIP 10×20, Thường 20×40" rồi
 * bấm lưu, không đụng tới toạ độ nào. Bố cục tự động xếp các khu ấy xuống dưới sân khấu theo
 * {@code sortOrder}. Nếu chỗ nào trong hệ thống cũng tự xếp lấy thì màn hình quản trị, ảnh poster
 * và sơ đồ của khách sẽ xếp ra ba kiểu khác nhau; nên mọi đường đọc đều đi qua đây.
 *
 * <h3>Ghế được sinh ở đây, không lưu</h3>
 *
 * <p>Cùng lý do với {@code SessionPublishedPayload}: Catalog không cần biết từng ghế, nó chỉ cần
 * biết hình dạng khu. 5.000 dòng ghế trong {@code catalog_db} là bản sao thứ hai của một thứ tính
 * lại hết vài trăm micro giây.
 */
public record FloorPlan(StageArea stage, List<PlannedZone> zones, Bounds bounds) {

    /** Khoảng trống giữa hai khu xếp tự động, tính theo đơn vị hàng. */
    private static final double AUTO_ZONE_GAP = 2.5;

    /** Khu đứng không có ghế, nhưng vẫn phải chiếm một mảng trên sơ đồ. Bề ngang tối đa của mảng đó. */
    private static final int STANDING_MAX_WIDTH = 60;

    public FloorPlan {
        zones = List.copyOf(zones);
    }

    /**
     * Giải mặt bằng của một địa điểm.
     *
     * <p>Khu đã khai {@code layout} giữ nguyên chỗ của nó; khu chưa khai được xếp nối tiếp bên dưới
     * <b>phần đã chiếm</b> — kể cả phần do khu đã khai chiếm. Trộn hai kiểu vì thực tế là vậy: tổ
     * chức kéo hai khu cánh vào đúng chỗ rồi để yên khu khán đài chính cho hệ thống xếp.
     */
    public static FloorPlan of(Venue venue) {
        StageArea stage = venue.stageOrDefault();

        List<VenueZone> ordered = venue.zones().stream()
                .sorted(java.util.Comparator.comparingInt(VenueZone::sortOrder).thenComparing(VenueZone::zoneCode))
                .toList();

        double cursorY = stage.maxY() + AUTO_ZONE_GAP;
        for (VenueZone zone : ordered) {
            if (zone.layout() != null) {
                cursorY = Math.max(cursorY, bottomOf(zone) + AUTO_ZONE_GAP);
            }
        }

        List<PlannedZone> planned = new ArrayList<>(ordered.size());
        for (VenueZone zone : ordered) {
            Shape shape = shapeOf(zone);
            ZoneLayout layout = zone.layout();
            if (layout == null) {
                layout = ZoneLayout.grid(stage.x(), cursorY, 0);
                cursorY += (shape.rows() - 1) * ZoneLayout.ROW_PITCH + AUTO_ZONE_GAP;
            }
            planned.add(plan(zone, layout, shape));
        }

        return new FloorPlan(stage, planned, Bounds.around(stage, planned));
    }

    private static PlannedZone plan(VenueZone zone, ZoneLayout layout, Shape shape) {
        List<PlannedSeat> seats = List.of();
        if (zone.kind() == AdmissionKind.SEATED) {
            seats = new ArrayList<>(zone.seatCount());
            for (int row = 1; row <= shape.rows(); row++) {
                for (int seat = 1; seat <= shape.seatsPerRow(); seat++) {
                    ZoneLayout.Point at = layout.seatPosition(row, seat, shape.rows(), shape.seatsPerRow());
                    seats.add(new PlannedSeat(zone.seatCode(row, seat), row, seat, at.x(), at.y()));
                }
            }
        }
        return new PlannedZone(
                zone.zoneCode(),
                zone.name(),
                zone.kind(),
                zone.seatCount(),
                layout,
                layout.outline(shape.rows(), shape.seatsPerRow()),
                List.copyOf(seats));
    }

    /**
     * Hình chữ nhật danh nghĩa của một khu.
     *
     * <p>Khu ngồi dùng thẳng {@code rowCount × seatsPerRow}. Khu đứng không có hàng nào, nhưng vẫn
     * phải chiếm một mảng có bề ngang và chiều sâu — nếu không thì một khu đứng 3.000 vé sẽ là một
     * đường kẻ trên sơ đồ. Mảng đó được suy từ sức chứa với bề ngang chặn trên, để khu đứng 300 vé
     * và khu đứng 3.000 vé trông khác nhau đúng theo tỷ lệ.
     */
    private static Shape shapeOf(VenueZone zone) {
        if (zone.kind() == AdmissionKind.SEATED) {
            return new Shape(zone.rowCount(), zone.seatsPerRow());
        }
        int width = Math.min(STANDING_MAX_WIDTH, Math.max(8, (int) Math.ceil(Math.sqrt(zone.capacity() * 2.0))));
        int depth = Math.max(2, (int) Math.ceil(zone.capacity() / (double) width / ZoneLayout.ROW_PITCH));
        return new Shape(depth, width);
    }

    private static double bottomOf(VenueZone zone) {
        Shape shape = shapeOf(zone);
        return zone.layout().outline(shape.rows(), shape.seatsPerRow()).stream()
                .mapToDouble(ZoneLayout.Point::y)
                .max()
                .orElse(0);
    }

    private record Shape(int rows, int seatsPerRow) {}

    public record PlannedZone(
            String zoneCode,
            String name,
            AdmissionKind kind,
            int seatCount,
            ZoneLayout layout,
            List<ZoneLayout.Point> outline,
            List<PlannedSeat> seats) {

        public PlannedZone {
            outline = List.copyOf(outline);
            seats = List.copyOf(seats);
        }

        /** Bỏ phần ghế đi — dùng cho đường đọc công khai, nơi toạ độ ghế đã đi kèm sơ đồ tồn kho. */
        public PlannedZone withoutSeats() {
            return new PlannedZone(zoneCode, name, kind, seatCount, layout, outline, List.of());
        }
    }

    public record PlannedSeat(String seatCode, int row, int seat, double x, double y) {}

    /**
     * Bao hình của cả mặt bằng, để frontend đặt {@code viewBox} mà không phải quét lại toàn bộ ghế.
     *
     * <p>Tính từ <b>đường bao khu</b> chứ không từ từng ghế: đường bao đã nới nửa ô ra ngoài ghế
     * mép, nên nó luôn rộng hơn — quét thêm 5.000 điểm ghế cho ra đúng cùng một khung.
     */
    public record Bounds(double minX, double minY, double maxX, double maxY) {

        public static Bounds around(StageArea stage, List<PlannedZone> zones) {
            double minX = stage.minX();
            double minY = stage.minY();
            double maxX = stage.maxX();
            double maxY = stage.maxY();

            for (PlannedZone zone : zones) {
                for (ZoneLayout.Point point : zone.outline()) {
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
            return new Bounds(minX, minY, maxX, maxY);
        }

        public double width() {
            return maxX - minX;
        }

        public double height() {
            return maxY - minY;
        }
    }
}
