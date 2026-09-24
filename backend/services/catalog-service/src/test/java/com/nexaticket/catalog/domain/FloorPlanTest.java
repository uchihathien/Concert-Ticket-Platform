// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.FloorPlan;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.StageShape;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bố cục tự động và cách nó sống chung với khu đã đặt tay.
 *
 * <p>Phần lớn ban tổ chức sẽ không bao giờ khai một toạ độ nào — họ điền "VIP 10×20, Thường 20×40"
 * rồi bấm lưu. Nên đường chạy nhiều nhất trong hệ thống hình học là đường không có toạ độ nào, và
 * nó phải cho ra một sơ đồ đọc được chứ không phải một đống khu chồng lên nhau.
 */
class FloorPlanTest {

    @Test
    @DisplayName("khu chưa đặt vị trí được xếp xuống dưới sân khấu, không khu nào chồng lên khu nào")
    void khu_chua_dat_duoc_xep_tu_dong() {
        Venue venue = venue(null, seated("A", 3, 10, 0), seated("B", 4, 10, 1));
        FloorPlan plan = venue.floorPlan();

        double stageBottom = plan.stage().maxY();

        // Khu đầu tiên nằm dưới sân khấu — nếu không thì hàng ghế đầu vẽ đè lên sân khấu.
        assertThat(minY(plan.zones().get(0))).isGreaterThan(stageBottom);
        // Khu thứ hai nằm hẳn dưới khu thứ nhất. Chồng nhau ở đây nghĩa là hai khu tranh cùng một
        // chỗ trên sơ đồ, và khách không bấm được vào khu bị che.
        assertThat(minY(plan.zones().get(1))).isGreaterThan(maxY(plan.zones().get(0)));
    }

    @Test
    @DisplayName("thứ tự xếp theo sortOrder, không theo thứ tự trong danh sách")
    void xep_theo_sort_order() {
        // Repository trả khu theo ORDER BY sort_order, nhưng một danh sách dựng trong bộ nhớ thì
        // không có bảo đảm nào. Phụ thuộc vào thứ tự đến là phụ thuộc vào một thứ sẽ đổi.
        Venue venue = venue(null, seated("SAU", 3, 10, 5), seated("TRUOC", 3, 10, 1));
        FloorPlan plan = venue.floorPlan();

        assertThat(plan.zones().get(0).zoneCode()).isEqualTo("TRUOC");
        assertThat(minY(plan.zones().get(0))).isLessThan(minY(plan.zones().get(1)));
    }

    @Test
    @DisplayName("khu đã đặt tay đứng yên, và khu tự động né xuống dưới nó")
    void khu_dat_tay_dung_yen() {
        // Trộn hai kiểu là chuyện thường: ban tổ chức kéo hai khu cánh vào đúng chỗ rồi để yên khu
        // khán đài chính cho hệ thống xếp.
        VenueZone datTay = seated("CANH", 3, 10, 0).withLayout(ZoneLayout.grid(0, 40, 0));
        Venue venue = venue(null, datTay, seated("CHINH", 3, 10, 1));
        FloorPlan plan = venue.floorPlan();

        FloorPlan.PlannedZone canh = zone(plan, "CANH");
        FloorPlan.PlannedZone chinh = zone(plan, "CHINH");

        // Khu đặt tay giữ nguyên gốc đã khai.
        assertThat(canh.layout().originY()).isEqualTo(40);
        // Khu tự động phải né xuống dưới nó. Xếp từ sân khấu xuống mà bỏ qua phần khu đặt tay đã
        // chiếm sẽ đặt khu tự động đè lên giữa khu kia.
        assertThat(minY(chinh)).isGreaterThan(maxY(canh));
    }

    @Test
    @DisplayName("khu đứng có đường bao để bấm vào, nhưng không có ghế nào")
    void khu_dung_co_dien_tich_khong_co_ghe() {
        Venue venue = venue(null, standing("SAN", 3_000, 0));
        FloorPlan.PlannedZone san = zone(venue.floorPlan(), "SAN");

        // Không ghế: vé đứng không có chỗ đánh số (ADR-1012).
        assertThat(san.seats()).isEmpty();
        // Nhưng vẫn phải chiếm một mảng thật trên sơ đồ — khu đứng 3.000 vé vẽ thành một đường kẻ
        // là thứ khách không bấm trúng được.
        assertThat(san.outline()).isNotEmpty();
        assertThat(maxY(san) - minY(san)).isGreaterThan(1);
        assertThat(san.seatCount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("khu đứng lớn chiếm nhiều diện tích hơn khu đứng nhỏ")
    void khu_dung_lon_chiem_nhieu_hon() {
        double nho = dienTich(zone(venue(null, standing("A", 300, 0)).floorPlan(), "A"));
        double lon = dienTich(zone(venue(null, standing("A", 3_000, 0)).floorPlan(), "A"));

        // Sơ đồ nói dối về quy mô là sơ đồ khiến khách chọn sai khu.
        assertThat(lon).isGreaterThan(nho);
    }

    @Test
    @DisplayName("mã chỗ trong mặt bằng khớp đúng mã Inventory sẽ nhận")
    void ma_cho_khop_voi_inventory() {
        // Mặt bằng và session.published phải sinh cùng một mã cho cùng một ghế — nếu lệch thì ghế
        // trên sơ đồ không tra được sang tồn kho, và cả khu hiện ra như đã bán hết.
        FloorPlan.PlannedZone vip = zone(venue(null, seated("VIP", 2, 3, 0)).floorPlan(), "VIP");

        assertThat(vip.seats()).hasSize(6);
        assertThat(vip.seats().get(0).seatCode()).isEqualTo("VIP-1-1");
        assertThat(vip.seats().get(5).seatCode()).isEqualTo("VIP-2-3");
    }

    @Test
    @DisplayName("bao hình ôm cả sân khấu, không chỉ khán đài")
    void bao_hinh_om_ca_san_khau() {
        // Sân khấu nằm ở y âm còn ghế ở y dương. Bỏ sân khấu ra khỏi bao hình thì viewBox cắt mất
        // nó, và khách thấy một sơ đồ không có sân khấu.
        Venue venue = venue(new StageArea(StageShape.CIRCLE, 0, -20, 12, 0), seated("A", 3, 10, 0));
        FloorPlan plan = venue.floorPlan();

        assertThat(plan.bounds().minY()).isLessThanOrEqualTo(plan.stage().minY());
        assertThat(plan.bounds().height()).isGreaterThan(0);
    }

    @Test
    @DisplayName("địa điểm chưa khai sân khấu vẫn có một sân khấu trên sơ đồ")
    void san_khau_mac_dinh() {
        // Sơ đồ không sân khấu không nói được hướng nhìn, và mọi khán phòng đều có sân khấu — chỉ
        // là không phải ai cũng muốn khai toạ độ của nó.
        FloorPlan plan = venue(null, seated("A", 3, 10, 0)).floorPlan();

        assertThat(plan.stage()).isEqualTo(StageArea.DEFAULT);
    }

    // --- dựng dữ liệu -------------------------------------------------------

    private static Venue venue(StageArea stage, VenueZone... zones) {
        UUID venueId = UUID.randomUUID();
        List<VenueZone> reparented = Arrays.stream(zones)
                .map(z -> new VenueZone(
                        z.id(),
                        venueId,
                        z.zoneCode(),
                        z.name(),
                        z.kind(),
                        z.rowCount(),
                        z.seatsPerRow(),
                        z.capacity(),
                        z.sortOrder(),
                        null,
                        z.layout()))
                .toList();
        return new Venue(venueId, UUID.randomUUID(), "Nhà thi đấu", "Hà Nội", null, reparented, null, stage);
    }

    private static VenueZone seated(String code, int rows, int seatsPerRow, int sortOrder) {
        return new VenueZone(
                UUID.randomUUID(),
                UUID.randomUUID(),
                code,
                "Khu " + code,
                AdmissionKind.SEATED,
                rows,
                seatsPerRow,
                null,
                sortOrder);
    }

    private static VenueZone standing(String code, int capacity, int sortOrder) {
        return new VenueZone(
                UUID.randomUUID(),
                UUID.randomUUID(),
                code,
                "Khu " + code,
                AdmissionKind.STANDING,
                null,
                null,
                capacity,
                sortOrder);
    }

    private static FloorPlan.PlannedZone zone(FloorPlan plan, String code) {
        return plan.zones().stream()
                .filter(z -> z.zoneCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static double minY(FloorPlan.PlannedZone zone) {
        return zone.outline().stream().mapToDouble(ZoneLayout.Point::y).min().orElseThrow();
    }

    private static double maxY(FloorPlan.PlannedZone zone) {
        return zone.outline().stream().mapToDouble(ZoneLayout.Point::y).max().orElseThrow();
    }

    private static double dienTich(FloorPlan.PlannedZone zone) {
        double minX =
                zone.outline().stream().mapToDouble(ZoneLayout.Point::x).min().orElseThrow();
        double maxX =
                zone.outline().stream().mapToDouble(ZoneLayout.Point::x).max().orElseThrow();
        return (maxX - minX) * (maxY(zone) - minY(zone));
    }
}
