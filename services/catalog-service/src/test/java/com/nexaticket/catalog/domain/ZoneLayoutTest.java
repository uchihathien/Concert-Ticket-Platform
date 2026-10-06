// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.domain.model.LayoutShape;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Công thức sinh toạ độ ghế.
 *
 * <p>Đây là phép tính mà cả ba nơi cùng dùng — bản xem trước của ban tổ chức, sơ đồ của khách, và
 * ảnh poster — nên sai ở đây là sai ở cả ba, theo đúng cùng một kiểu và vì vậy không ai nghi ngờ
 * gì. Kiểm ở mức domain, không Spring, không database.
 */
class ZoneLayoutTest {

    @Test
    @DisplayName("khu chữ nhật: ghế căn giữa quanh gốc, hàng chạy xuống dưới")
    void grid_can_giua_quanh_goc() {
        ZoneLayout layout = ZoneLayout.grid(0, 0, 0);

        // 5 ghế mỗi hàng ⇒ ghế 3 là ghế giữa và phải nằm đúng trên trục gốc. Lệch nửa ô ở đây
        // nghĩa là mọi khu trên sơ đồ đều lệch nửa ô so với sân khấu.
        assertThat(layout.seatPosition(1, 3, 2, 5).x()).isZero();
        assertThat(layout.seatPosition(1, 1, 2, 5).x()).isEqualTo(-2);
        assertThat(layout.seatPosition(1, 5, 2, 5).x()).isEqualTo(2);

        // Hàng 1 nằm đúng ở gốc, hàng 2 cách một ROW_PITCH — hàng 1 là hàng gần sân khấu nhất.
        assertThat(layout.seatPosition(1, 3, 2, 5).y()).isZero();
        assertThat(layout.seatPosition(2, 3, 2, 5).y()).isEqualTo(ZoneLayout.ROW_PITCH);
    }

    @Test
    @DisplayName("số ghế chẵn: hai ghế giữa nằm hai bên trục, không ghế nào đè lên trục")
    void grid_so_ghe_chan_khong_co_ghe_giua() {
        ZoneLayout layout = ZoneLayout.grid(0, 0, 0);

        // 4 ghế ⇒ tâm nằm giữa ghế 2 và 3. Công thức làm tròn về phía một bên sẽ dồn cả khu lệch
        // nửa ô, và nó chỉ lộ ra ở những khu có số ghế chẵn.
        assertThat(layout.seatPosition(1, 2, 1, 4).x()).isEqualTo(-0.5);
        assertThat(layout.seatPosition(1, 3, 1, 4).x()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("khu chữ nhật xoay 90°: hàng chạy sang ngang, ghế chạy xuống dưới")
    void grid_xoay_90_do() {
        // Đây là khu cánh của khán phòng chữ U: cùng một khối 3×5, dựng đứng lên.
        ZoneLayout layout = ZoneLayout.grid(10, 0, 90);

        // Ghế 1 của hàng 1 lệch -2 theo trục ghế; xoay 90° biến lệch ngang thành lệch dọc.
        assertThat(layout.seatPosition(1, 1, 3, 5).x()).isEqualTo(10);
        assertThat(layout.seatPosition(1, 1, 3, 5).y()).isEqualTo(-2);

        // Hàng 2 lùi ra xa theo trục hàng, và sau khi xoay thì "ra xa" là hướng -x.
        assertThat(layout.seatPosition(2, 1, 3, 5).x()).isEqualTo(10 - ZoneLayout.ROW_PITCH);
    }

    @Test
    @DisplayName("khu cung: ghế nằm đúng trên bán kính của hàng mình")
    void arc_ghe_nam_tren_ban_kinh_cua_hang() {
        ZoneLayout layout = ZoneLayout.arc(0, 0, 10, 0, 180);

        // Khoảng cách từ tâm tới ghế phải đúng bằng bán kính hàng — đó là toàn bộ nghĩa của "hàng
        // là một cung tròn". Sai ở đây thì hàng ghế thành hình xoắn ốc.
        for (int row = 1; row <= 3; row++) {
            ZoneLayout.Point at = layout.seatPosition(row, 7, 3, 20);
            double radius = Math.hypot(at.x(), at.y());
            assertThat(radius).isCloseTo(10 + (row - 1) * ZoneLayout.ROW_PITCH, within());
        }
    }

    @Test
    @DisplayName("khu cung: hàng ghế căn giữa trong cung, không dính mép")
    void arc_can_giua_trong_cung() {
        // Cung 0°–180°, 2 ghế ⇒ hai ghế phải ở 45° và 135°, không phải ở 0° và 90°. Dùng
        // (seat - 1) thay vì (seat - 0.5) sẽ dồn cả hàng sang mép trái và bỏ trống một ô bên phải.
        ZoneLayout layout = ZoneLayout.arc(0, 0, 10, 0, 180);

        ZoneLayout.Point first = layout.seatPosition(1, 1, 1, 2);
        ZoneLayout.Point last = layout.seatPosition(1, 2, 1, 2);

        assertThat(Math.toDegrees(Math.atan2(first.y(), first.x()))).isCloseTo(45, within());
        assertThat(Math.toDegrees(Math.atan2(last.y(), last.x()))).isCloseTo(135, within());
    }

    @Test
    @DisplayName("đường bao chữ nhật ôm ngoài ghế mép, không cắt qua chúng")
    void outline_om_ngoai_ghe_mep() {
        ZoneLayout layout = ZoneLayout.grid(0, 0, 0);
        List<ZoneLayout.Point> outline = layout.outline(3, 5);

        double minX = outline.stream().mapToDouble(ZoneLayout.Point::x).min().orElseThrow();
        double maxY = outline.stream().mapToDouble(ZoneLayout.Point::y).max().orElseThrow();

        // Ghế 1 ở x = -2 và hàng cuối ở y = 2×ROW_PITCH. Đường bao phải rộng hơn cả hai, nếu không
        // thì viền khu vẽ đè lên ghế ngoài cùng.
        assertThat(minX).isLessThan(-2);
        assertThat(maxY).isGreaterThan(2 * ZoneLayout.ROW_PITCH);
    }

    @Test
    @DisplayName("đường bao cung lấy nhiều điểm hơn khi cung mở rộng hơn")
    void outline_cung_rong_lay_nhieu_diem_hon() {
        // Một cung 300° lấy 4 điểm sẽ thành hình thoi. Số điểm phải đi theo độ mở.
        int hep = ZoneLayout.arc(0, 0, 10, 0, 20).outline(2, 10).size();
        int rong = ZoneLayout.arc(0, 0, 10, 0, 300).outline(2, 10).size();

        assertThat(rong).isGreaterThan(hep);
    }

    @Test
    @DisplayName("cung quét ngược hoặc quá một vòng bị từ chối ngay khi dựng")
    void cung_khong_hop_le_bi_tu_choi() {
        // Góc kết thúc ≤ góc bắt đầu cho ra một cung rộng 0 hoặc âm: mọi ghế chồng lên một điểm.
        assertThatThrownBy(() -> ZoneLayout.arc(0, 0, 10, 180, 180)).isInstanceOf(IllegalArgumentException.class);
        // Quét quá 360° thì cung chồng lên chính nó và hai vé khác nhau chỉ vào cùng một chỗ.
        assertThatThrownBy(() -> ZoneLayout.arc(0, 0, 10, 0, 400)).isInstanceOf(IllegalArgumentException.class);
        // Bán kính 0 đặt cả khu vào đúng tâm sân khấu.
        assertThatThrownBy(() -> ZoneLayout.arc(0, 0, 0, 0, 90)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("khu chữ nhật không đòi tham số của cung")
    void grid_khong_doi_tham_so_cung() {
        // Ràng buộc cung chỉ áp cho cung. Áp nhầm cho cả GRID thì không khu chữ nhật nào dựng được
        // nếu không khai một bán kính vô nghĩa.
        ZoneLayout layout = ZoneLayout.grid(0, 0, 0);
        assertThat(layout.shape()).isEqualTo(LayoutShape.GRID);
    }

    /** Toạ độ làm tròn 2 chữ số (khớp {@code NUMERIC(8,2)}), nên so sánh phải có dung sai. */
    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.02);
    }
}
