// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.application.query.SeatStatusView;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Đếm tồn kho theo khu và theo trạng thái — đường đọc mà bảng điều khiển của ban tổ chức dùng.
 *
 * <p>Điều đáng kiểm không phải phép đếm mà là <b>nó đếm những gì</b>: vé đứng phải nằm trong tổng
 * (một vé đứng cũng là một chỗ đã bán), và chỗ BLOCKED phải tách riêng chứ không lẫn vào phần bán
 * được — lẫn vào là ban tổ chức thấy còn hàng ở những chỗ không bao giờ bán.
 */
class SeatStatusIT extends InventoryTestBase {

    @Autowired
    InventoryFixture fixture;

    @Autowired
    SeatQueries queries;

    @Autowired
    PlaceHoldHandler placeHold;

    @Test
    @DisplayName("gộp theo khu, và vé đứng nằm trong tổng")
    void gop_theo_khu_va_gom_ca_ve_dung() {
        InventoryFixture.Session session = fixture.materialize(20, 50);

        SeatStatusView view = queries.seatStatus(session.id());

        assertThat(view.zones()).hasSize(2);
        assertThat(zone(view, "A").available()).isEqualTo(20);
        assertThat(zone(view, "A").admissionType()).isEqualTo("SEATED");
        // 50 đơn vị ảo của vé đứng: chúng không hiện trên sơ đồ, nhưng chúng là chỗ bán được nên
        // phải có mặt ở đây (ADR-1012).
        assertThat(zone(view, "GA").available()).isEqualTo(50);
        assertThat(zone(view, "GA").admissionType()).isEqualTo("STANDING");
    }

    @Test
    @DisplayName("giữ chỗ chuyển AVAILABLE sang HELD trong đúng khu đó")
    void giu_cho_doi_trang_thai() {
        InventoryFixture.Session session = fixture.materialize(20, 0);
        placeHold.handle(new PlaceHoldHandler.Command(
                session.id(), UUID.randomUUID(), session.seatIds().subList(0, 3), List.of()));

        SeatStatusView view = queries.seatStatus(session.id());

        assertThat(zone(view, "A").available()).isEqualTo(17);
        assertThat(zone(view, "A").held()).isEqualTo(3);
        assertThat(zone(view, "A").sold()).isZero();
    }

    @Test
    @DisplayName("chỗ bị chặn đếm riêng, không lẫn vào phần bán được")
    void cho_bi_chan_dem_rieng() {
        // BLOCKED có mặt trong tồn kho để sơ đồ hiện đúng hình khán phòng, nhưng không bán được.
        // Cộng nó vào available là báo cho ban tổ chức số hàng họ không có.
        InventoryFixture.Session session = fixture.materialize(20, 0);
        blockSeats(session, 4);

        SeatStatusView view = queries.seatStatus(session.id());

        assertThat(zone(view, "A").available()).isEqualTo(16);
        assertThat(zone(view, "A").blocked()).isEqualTo(4);
    }

    @Test
    @DisplayName("suất chưa được dựng tồn kho: SESSION_NOT_FOUND, không phải một bảng số 0")
    void suat_chua_materialize() {
        // Bên gọi dịch 404 thành "chưa lên bán". Trả về số 0 sẽ khiến "chưa publish" và "publish
        // rồi nhưng bán sạch" trông giống hệt nhau.
        assertThatThrownBy(() -> queries.seatStatus(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    private void blockSeats(InventoryFixture.Session session, int count) {
        for (UUID seatId : session.seatIds().subList(0, count)) {
            jdbcUpdateStatus(seatId);
        }
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void jdbcUpdateStatus(UUID seatId) {
        jdbc.update("UPDATE session_seats SET status = 'BLOCKED' WHERE id = ?", seatId);
    }

    private static SeatStatusView.ZoneStatus zone(SeatStatusView view, String zoneCode) {
        return view.zones().stream()
                .filter(z -> z.zoneCode().equals(zoneCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Không thấy khu " + zoneCode));
    }
}
