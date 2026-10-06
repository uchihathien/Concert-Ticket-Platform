// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.nexaticket.catalog.domain.model.LayoutShape;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Khu bàn tròn.
 *
 * <p>Hình này khác hai hình kia ở chỗ "hàng" không còn là một dãy ghế mà là một cái bàn. Mã chỗ
 * {@code A-3-5} vì thế đọc thành "khu A, bàn 3, ghế 5" — và giữ được cách đọc ấy chính là điều
 * kiện để hình này được nhận vào {@link LayoutShape}.
 */
class TableZoneLayoutTest {

    @Test
    @DisplayName("ghế quây đều quanh bàn, đúng bán kính")
    void ghe_quay_deu_quanh_ban() {
        ZoneLayout layout = ZoneLayout.tables(0, 0, 2, 0);

        // Một bàn 8 ghế: mọi ghế phải cách tâm bàn đúng 2 đơn vị.
        for (int seat = 1; seat <= 8; seat++) {
            ZoneLayout.Point point = layout.seatPosition(1, seat, 1, 8);
            double distance = Math.hypot(point.x(), point.y());
            assertThat(distance).as("ghế %d", seat).isCloseTo(2, within(0.01));
        }
    }

    @Test
    @DisplayName("ghế số 1 quay lưng về sân khấu")
    void ghe_so_mot_quay_lung_ve_san_khau() {
        // Quy ước này tồn tại để nhân viên đọc "bàn 3 ghế 1" thì người ngồi đó tìm được mình mà
        // không phải đoán bàn đang xoay kiểu gì. Sân khấu ở phía trên (y âm), nên ghế 1 ở y âm.
        ZoneLayout.Point first = ZoneLayout.tables(0, 0, 2, 0).seatPosition(1, 1, 1, 8);

        assertThat(first.x()).isCloseTo(0, within(0.01));
        assertThat(first.y()).isCloseTo(-2, within(0.01));
    }

    @Test
    @DisplayName("không hai ghế nào trùng chỗ trong cả khu")
    void khong_ghe_nao_trung_cho() {
        // Đây là bài kiểm thật sự quan trọng: hai ghế cùng toạ độ nghĩa là trên sơ đồ có một chỗ
        // bán được hai lần mà mắt thường không thấy.
        ZoneLayout layout = ZoneLayout.tables(0, 0, 1.5, 0);
        int tables = 12;
        int seatsPerTable = 10;

        List<ZoneLayout.Point> points = new java.util.ArrayList<>();
        for (int table = 1; table <= tables; table++) {
            for (int seat = 1; seat <= seatsPerTable; seat++) {
                points.add(layout.seatPosition(table, seat, tables, seatsPerTable));
            }
        }

        assertThat(points).hasSize(tables * seatsPerTable);
        assertThat(java.util.Set.copyOf(points)).hasSize(tables * seatsPerTable);
    }

    @Test
    @DisplayName("các bàn không chồng lên nhau")
    void cac_ban_khong_chong_nhau() {
        double radius = 2;
        ZoneLayout layout = ZoneLayout.tables(0, 0, radius, 0);

        // Tâm hai bàn cạnh nhau phải cách nhau hơn hai lần bán kính, nếu không ghế của bàn này
        // ngồi lên lòng bàn kia.
        ZoneLayout.Point first = tableCentre(layout, 1, 4, 8);
        ZoneLayout.Point second = tableCentre(layout, 2, 4, 8);

        assertThat(Math.hypot(second.x() - first.x(), second.y() - first.y())).isGreaterThan(2 * radius);
    }

    @Test
    @DisplayName("đường bao chứa hết mọi ghế")
    void duong_bao_chua_het_ghe() {
        // Đường bao là vùng bắt sự kiện chuột của cả khu. Ghế nằm ngoài nó là ghế không bấm được.
        ZoneLayout layout = ZoneLayout.tables(5, 3, 1.5, 0);
        int tables = 9;
        int seats = 8;

        List<ZoneLayout.Point> outline = layout.outline(tables, seats);
        double minX = outline.stream().mapToDouble(ZoneLayout.Point::x).min().orElseThrow();
        double maxX = outline.stream().mapToDouble(ZoneLayout.Point::x).max().orElseThrow();
        double minY = outline.stream().mapToDouble(ZoneLayout.Point::y).min().orElseThrow();
        double maxY = outline.stream().mapToDouble(ZoneLayout.Point::y).max().orElseThrow();

        for (int table = 1; table <= tables; table++) {
            for (int seat = 1; seat <= seats; seat++) {
                ZoneLayout.Point point = layout.seatPosition(table, seat, tables, seats);
                assertThat(point.x()).isBetween(minX, maxX);
                assertThat(point.y()).isBetween(minY, maxY);
            }
        }
    }

    @Test
    @DisplayName("xoay khu thì mọi ghế xoay theo, khoảng cách giữ nguyên")
    void xoay_khu_giu_nguyen_hinh_dang() {
        ZoneLayout straight = ZoneLayout.tables(0, 0, 2, 0);
        ZoneLayout turned = ZoneLayout.tables(0, 0, 2, 90);

        double before = distance(straight.seatPosition(1, 1, 4, 8), straight.seatPosition(3, 5, 4, 8));
        double after = distance(turned.seatPosition(1, 1, 4, 8), turned.seatPosition(3, 5, 4, 8));

        assertThat(after).isCloseTo(before, within(0.01));
    }

    @Test
    @DisplayName("bán kính bàn phải dương — 0 làm mọi ghế chồng lên tâm bàn")
    void ban_kinh_phai_duong() {
        assertThatThrownBy(() -> ZoneLayout.tables(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bán kính bàn");
    }

    @Test
    @DisplayName("một bàn duy nhất vẫn ra đúng hình, không chia cho 0")
    void mot_ban_duy_nhat() {
        // `ceil(sqrt(1)) = 1`, và phép chia trong công thức xếp lưới phải chịu được con số ấy.
        ZoneLayout layout = ZoneLayout.tables(0, 0, 2, 0);

        assertThat(layout.outline(1, 6)).hasSize(4);
        assertThat(layout.seatPosition(1, 1, 1, 6)).isNotNull();
    }

    /** Tâm bàn suy ra từ hai ghế đối diện nhau — trung điểm của chúng chính là tâm. */
    private static ZoneLayout.Point tableCentre(ZoneLayout layout, int table, int tables, int seats) {
        ZoneLayout.Point a = layout.seatPosition(table, 1, tables, seats);
        ZoneLayout.Point b = layout.seatPosition(table, 1 + seats / 2, tables, seats);
        return new ZoneLayout.Point((a.x() + b.x()) / 2, (a.y() + b.y()) / 2);
    }

    private static double distance(ZoneLayout.Point a, ZoneLayout.Point b) {
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }
}
