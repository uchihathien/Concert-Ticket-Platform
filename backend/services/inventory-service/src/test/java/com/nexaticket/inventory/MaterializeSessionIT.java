// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.application.command.MaterializeSessionHandler;
import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.domain.port.SessionMaterializer;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dựng tồn kho từ danh sách chỗ mà Catalog gửi sang.
 *
 * <p>Đây là mắt xích nối Catalog với Inventory. Trước khi có nó, publish một suất diễn không tạo
 * ra tồn kho nào và không ai bán được vé — tồn kho chỉ tồn tại trong fixture của test.
 */
class MaterializeSessionIT extends InventoryTestBase {

    @Autowired
    MaterializeSessionHandler materialize;

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    SeatQueries queries;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Suất toàn ghế ngồi: mỗi chỗ thành một đơn vị tồn kho bán được")
    void suat_toan_ghe_ngoi() {
        var manifest = manifest(10, 0);

        assertThat(materialize.handle(manifest)).isEqualTo(10);

        var view = queries.seatMap(manifest.eventSessionId(), null);
        assertThat(view.seats()).hasSize(10);
        assertThat(view.standingZones()).isEmpty();
    }

    @Test
    @DisplayName("Khối vé đứng nở thành đơn vị ảo, KHÔNG hiện trên sơ đồ chỗ")
    void ve_dung_no_thanh_don_vi_ao() {
        // Message mang một khối có số lượng; 3.000 đơn vị ảo được sinh ở đây. Truyền từng đơn
        // vị qua message là 3.000 phần tử vô nghĩa (ADR-1012).
        var manifest = manifest(0, 3_000);

        assertThat(materialize.handle(manifest)).isEqualTo(3_000);

        var view = queries.seatMap(manifest.eventSessionId(), null);
        assertThat(view.seats()).isEmpty();
        assertThat(view.standingZones()).hasSize(1);
        assertThat(view.standingZones().get(0).available()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("Chỗ bị chặn vẫn có mặt trên sơ đồ nhưng không bán được")
    void cho_bi_chan_van_hien_nhung_khong_ban() {
        // Xoá hẳn khỏi tồn kho thì khách thấy một lỗ hổng khó hiểu giữa hàng ghế.
        var manifest = manifestWithBlocked(5, 2);

        materialize.handle(manifest);

        var view = queries.seatMap(manifest.eventSessionId(), null);
        assertThat(view.seats()).hasSize(5);
        assertThat(view.seats())
                .filteredOn(seat -> "BLOCKED".equals(seat.status()))
                .hasSize(2);
    }

    @Test
    @DisplayName("Materialize lại một suất đã có tồn kho: bỏ qua, không dựng đè")
    void materialize_lai_thi_bo_qua() {
        // Nếu suất đã bán vé, dựng lại sẽ xoá hoặc dịch chuyển những chỗ khách đang giữ và đã
        // trả tiền. Sửa một suất đang bán là nghiệp vụ riêng, không phải tác dụng phụ của một
        // message trùng.
        var manifest = manifest(10, 0);
        materialize.handle(manifest);

        assertThat(materialize.handle(manifest)).isZero();
        assertThat(seatCount(manifest.eventSessionId())).isEqualTo(10);
    }

    @Test
    @DisplayName("Tồn kho vừa dựng dùng được ngay cho đường giữ chỗ")
    void ton_kho_dung_duoc_ngay() {
        // Kiểm nối thật giữa hai mắt xích: nếu materialize ghi thiếu một cột mà đường giữ chỗ
        // cần, test này đỏ chứ không phải chờ tới lúc chạy production.
        var manifest = manifest(4, 0);
        materialize.handle(manifest);
        List<UUID> seatIds = seatIdsOf(manifest.eventSessionId(), 2);

        var hold = placeHold.handle(
                new PlaceHoldHandler.Command(manifest.eventSessionId(), UUID.randomUUID(), seatIds, List.of()));

        assertThat(hold.seatIds()).hasSize(2);
    }

    @Test
    @DisplayName("Trần mua vé từ message được áp cho đường giữ chỗ")
    void tran_mua_ve_tu_message_duoc_ap() {
        // Trần đã giải quyết kế thừa ở Catalog và sao sang đây (ADR-1014). Nếu materialize ghi
        // sai cột, khách sẽ mua được nhiều hơn hoặc ít hơn tổ chức cho phép.
        var manifest = manifestWithLimits(20, 2);
        materialize.handle(manifest);
        List<UUID> seatIds = seatIdsOf(manifest.eventSessionId(), 3);

        assertThat(codeOf(() -> placeHold.handle(new PlaceHoldHandler.Command(
                        manifest.eventSessionId(), UUID.randomUUID(), seatIds, List.of()))))
                .isEqualTo("HOLD_LIMIT_EXCEEDED");
    }

    // --- dựng dữ liệu ---

    private static SessionMaterializer.SessionManifest manifest(int seatedCount, int standingCapacity) {
        return build(seatedCount, standingCapacity, 0, 8);
    }

    private static SessionMaterializer.SessionManifest manifestWithBlocked(int seatedCount, int blockedCount) {
        return build(seatedCount, 0, blockedCount, 8);
    }

    private static SessionMaterializer.SessionManifest manifestWithLimits(int seatedCount, int maxSeatedPerHold) {
        return build(seatedCount, 0, 0, maxSeatedPerHold);
    }

    private static SessionMaterializer.SessionManifest build(
            int seatedCount, int standingCapacity, int blockedCount, int maxSeatedPerHold) {
        List<SessionMaterializer.SeatLine> seats = new ArrayList<>();
        for (int i = 1; i <= seatedCount; i++) {
            seats.add(new SessionMaterializer.SeatLine(
                    "A-" + i,
                    "A",
                    "Khan dai A",
                    "1",
                    String.valueOf(i),
                    BigDecimal.valueOf(i),
                    BigDecimal.ONE,
                    UUID.randomUUID(),
                    "Ve ngoi",
                    1_500_000L,
                    i <= blockedCount));
        }
        List<SessionMaterializer.StandingBlock> standing = standingCapacity == 0
                ? List.of()
                : List.of(new SessionMaterializer.StandingBlock(
                        "GA", standingCapacity, UUID.randomUUID(), "Ve dung", 800_000L));

        Instant now = Instant.now();
        return new SessionMaterializer.SessionManifest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                now.minus(1, ChronoUnit.HOURS),
                now.plus(30, ChronoUnit.DAYS),
                maxSeatedPerHold,
                10,
                10,
                10,
                seats,
                standing);
    }

    private int seatCount(UUID eventSessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_seats WHERE event_session_id = ?", Integer.class, eventSessionId);
        return count == null ? 0 : count;
    }

    private List<UUID> seatIdsOf(UUID eventSessionId, int limit) {
        return jdbc.query(
                """
                SELECT id FROM session_seats
                 WHERE event_session_id = ? AND status = 'AVAILABLE' AND admission_type = 'SEATED'
                 ORDER BY seat_code LIMIT ?
                """,
                (rs, i) -> rs.getObject("id", UUID.class),
                eventSessionId,
                limit);
    }

    private static String codeOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (com.nexaticket.platform.web.error.ApiException e) {
            return e.errorCode().code();
        }
    }
}
