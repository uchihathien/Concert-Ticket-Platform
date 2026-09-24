// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.domain.model.FloorPlan;
import java.util.List;
import java.util.UUID;

/**
 * DTO của mặt bằng: sân khấu, đường bao từng khu, và — chỉ ở đường quản trị — toạ độ từng ghế.
 *
 * <h3>Vì sao đường công khai không mang ghế</h3>
 *
 * <p>Khách vào trang chọn chỗ đã tải sơ đồ tồn kho từ inventory ({@code GET /v1/sessions/{id}/seats}),
 * và mỗi ghế ở đó <b>đã có</b> {@code posX}/{@code posY} cùng trạng thái còn/hết. Trả thêm toạ độ ở
 * đây là gửi 5.000 dòng lần thứ hai, qua một endpoint không có ETag, để vẽ đúng cái frontend vừa
 * nhận xong.
 *
 * <p>Đường quản trị thì ngược lại: ban tổ chức xem trước sơ đồ <b>trước khi publish</b>, lúc
 * inventory chưa có ghế nào. Không có toạ độ ở đây thì màn hình phải tự tính lại bằng một bản sao
 * của công thức trong {@code ZoneLayout} — viết bằng TypeScript, và lệch đi ở lần sửa thứ hai.
 *
 * <p>Cùng một phép tính, hai phép chiếu. Đó là lý do {@link FloorPlan.PlannedZone#withoutSeats()}
 * tồn tại thay vì hai đường dựng riêng.
 */
public final class FloorPlanViews {

    private FloorPlanViews() {}

    /**
     * @param bounds bao hình của cả mặt bằng — frontend đặt thẳng vào {@code viewBox} của SVG, nên
     *     không phải quét toàn bộ ghế chỉ để biết vẽ vào khung nào
     */
    public record FloorPlanView(UUID venueId, String venueName, Stage stage, List<Zone> zones, Bounds bounds) {

        static FloorPlanView of(UUID venueId, String venueName, FloorPlan plan) {
            return new FloorPlanView(
                    venueId,
                    venueName,
                    new Stage(
                            plan.stage().shape().name(),
                            plan.stage().x(),
                            plan.stage().y(),
                            plan.stage().width(),
                            plan.stage().effectiveHeight()),
                    plan.zones().stream().map(Zone::of).toList(),
                    new Bounds(
                            plan.bounds().minX(),
                            plan.bounds().minY(),
                            plan.bounds().maxX(),
                            plan.bounds().maxY()));
        }
    }

    /**
     * @param height với sân khấu tròn đây là đường kính, bằng {@code width} — đã giải sẵn ở backend
     *     để frontend không phải mang theo luật "CIRCLE thì bỏ qua height"
     */
    public record Stage(String shape, double x, double y, double width, double height) {}

    /**
     * @param outline đa giác bao khu; đóng vòng ở phần tử cuối là ngầm định, frontend nối điểm cuối
     *     về điểm đầu
     * @param seats rỗng ở đường công khai — xem ghi chú của lớp bao
     */
    public record Zone(
            String zoneCode,
            String name,
            String kind,
            int seatCount,
            String layoutShape,
            List<Point> outline,
            List<Seat> seats) {

        static Zone of(FloorPlan.PlannedZone zone) {
            return new Zone(
                    zone.zoneCode(),
                    zone.name(),
                    zone.kind().name(),
                    zone.seatCount(),
                    zone.layout().shape().name(),
                    zone.outline().stream()
                            .map(point -> new Point(point.x(), point.y()))
                            .toList(),
                    zone.seats().stream()
                            .map(seat -> new Seat(seat.seatCode(), seat.row(), seat.seat(), seat.x(), seat.y()))
                            .toList());
        }
    }

    public record Seat(String seatCode, int row, int seat, double x, double y) {}

    public record Point(double x, double y) {}

    public record Bounds(double minX, double minY, double maxX, double maxY) {}
}
