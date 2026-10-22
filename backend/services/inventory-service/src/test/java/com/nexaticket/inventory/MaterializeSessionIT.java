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
    @DisplayName("Publish lại sau khi sửa cửa bán: chỗ giữ nguyên, nhưng cấu hình được đồng bộ")
    void publish_lai_thi_dong_bo_cua_ban() {
        var first = manifest(10, 0);
        materialize.handle(first);

        // Ban tổ chức gỡ bán, dời giờ mở bán và siết trần, rồi publish lại.
        Instant moved = Instant.now().plus(3, ChronoUnit.DAYS);
        var edited = new SessionMaterializer.SessionManifest(
                first.eventSessionId(),
                first.eventId(),
                first.organizationId(),
                moved,
                moved.plus(10, ChronoUnit.DAYS),
                2,
                2,
                2,
                2,
                first.seats(),
                first.standingBlocks());

        // Vẫn trả 0: KHÔNG dựng lại chỗ. Ghế đã bán không được đụng tới, và người gọi dùng chính
        // con số này để biết có nên chạy bộ dữ liệu mẫu hay không.
        assertThat(materialize.handle(edited)).isZero();
        assertThat(seatCount(first.eventSessionId())).isEqualTo(10);

        // Nhưng cấu hình thì phải đổi. Trước bản sửa này, Inventory giữ cửa bán CŨ vĩnh viễn —
        // Catalog nói đã sửa, màn hình quản trị nói đã sửa, còn chỗ thật sự chặn khách thì không.
        assertThat(jdbc.queryForObject(
                        "SELECT max_tickets_per_customer FROM session_inventory WHERE event_session_id = ?",
                        Integer.class,
                        first.eventSessionId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT sales_open_at > now() FROM session_inventory WHERE event_session_id = ?",
                        Boolean.class,
                        first.eventSessionId()))
                .isTrue();
    }

    @Test
    @DisplayName("Publish lại sau khi sửa giá: chỗ CHƯA bán lấy giá mới, chỗ đã bán / đang giữ giữ giá khách đã thấy")
    void publish_lai_thi_dong_bo_gia_cho_chua_ban() {
        // Sửa giá hạng vé chỉ làm được khi sự kiện đã rút xuống, rồi publish lại. Trước đây Inventory giữ
        // giá cũ vĩnh viễn: Catalog niêm yết 25.000đ, đơn tính 2.500.000đ.
        var first = manifest(4, 0);
        materialize.handle(first);
        UUID sessionId = first.eventSessionId();
        UUID holder = UUID.randomUUID();
        jdbc.update(
                "UPDATE session_seats SET status = 'SOLD', holder_user_id = ? WHERE event_session_id = ? AND seat_code = 'A-1'",
                holder,
                sessionId);
        jdbc.update(
                "UPDATE session_seats SET status = 'HELD', holder_user_id = ? WHERE event_session_id = ? AND seat_code = 'A-2'",
                holder,
                sessionId);
        jdbc.update(
                "UPDATE session_seats SET status = 'BLOCKED' WHERE event_session_id = ? AND seat_code = 'A-4'",
                sessionId);

        materialize.handle(withPrice(first, 25_000L, "Ve ngoi (gia moi)", 40_000L));

        assertThat(priceOf(sessionId, "A-3")).as("chỗ còn trống").isEqualTo(25_000L);
        assertThat(priceOf(sessionId, "A-4"))
                .as("chỗ bị chặn, mở lại sau này sẽ bán đúng giá")
                .isEqualTo(25_000L);
        assertThat(priceOf(sessionId, "A-1"))
                .as("chỗ ĐÃ BÁN: hoá đơn phải khớp giá khách đã trả")
                .isEqualTo(1_500_000L);
        assertThat(priceOf(sessionId, "A-2"))
                .as("chỗ ĐANG GIỮ: khách đã thấy giá cũ")
                .isEqualTo(1_500_000L);
        assertThat(jdbc.queryForObject(
                        "SELECT ticket_type_name FROM session_seats WHERE event_session_id = ? AND seat_code = 'A-3'",
                        String.class,
                        sessionId))
                .isEqualTo("Ve ngoi (gia moi)");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM session_seats WHERE event_session_id = ? AND seat_code = 'A-2'",
                        String.class,
                        sessionId))
                .as("đồng bộ giá không được đụng tới trạng thái")
                .isEqualTo("HELD");

        // Vé đứng: các đơn vị ảo còn trống cũng lấy giá mới.
        var standing = manifest(0, 5);
        materialize.handle(standing);
        materialize.handle(withPrice(standing, 25_000L, "Ve ngoi", 50_000L));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM session_seats WHERE event_session_id = ? AND admission_type = 'STANDING' AND price_vnd = 50000",
                        Integer.class,
                        standing.eventSessionId()))
                .isEqualTo(5);
    }

    private long priceOf(UUID sessionId, String seatCode) {
        return jdbc.queryForObject(
                "SELECT price_vnd FROM session_seats WHERE event_session_id = ? AND seat_code = ?",
                Long.class,
                sessionId,
                seatCode);
    }

    /** Cùng suất, giá ghế ngồi và giá vé đứng mới. */
    private static SessionMaterializer.SessionManifest withPrice(
            SessionMaterializer.SessionManifest m, long seatPrice, String seatTypeName, long standingPrice) {
        return new SessionMaterializer.SessionManifest(
                m.eventSessionId(),
                m.eventId(),
                m.organizationId(),
                m.salesOpenAt(),
                m.salesCloseAt(),
                m.maxSeatedPerHold(),
                m.maxStandingPerHold(),
                m.maxUnitsPerHold(),
                m.maxTicketsPerCustomer(),
                m.seats().stream()
                        .map(s -> new SessionMaterializer.SeatLine(
                                s.seatCode(),
                                s.zoneCode(),
                                s.sectionLabel(),
                                s.rowLabel(),
                                s.seatLabel(),
                                s.posX(),
                                s.posY(),
                                s.ticketTypeId(),
                                seatTypeName,
                                seatPrice,
                                s.blocked()))
                        .toList(),
                m.standingBlocks().stream()
                        .map(b -> new SessionMaterializer.StandingBlock(
                                b.zoneCode(), b.quantity(), b.ticketTypeId(), b.ticketTypeName(), standingPrice))
                        .toList());
    }

    @Test
    @DisplayName("Publish lại với toạ độ mới: chỗ dịch đúng vị trí, trạng thái và người giữ không đổi")
    void publish_lai_thi_dong_bo_toa_do() {
        // Đây là đường chữa cho những suất đang giữ CHỈ SỐ hàng/cột thay vì toạ độ mặt bằng — 52
        // trên 63 suất trong dữ liệu thật. Không có nó thì sơ đồ của chúng sai vĩnh viễn, vì tồn
        // kho chỉ ghi toạ độ đúng một lần và không có đường nào sửa.
        var first = manifest(3, 0);
        materialize.handle(first);

        UUID sessionId = first.eventSessionId();
        UUID holder = UUID.randomUUID();
        jdbc.update(
                "UPDATE session_seats SET status = 'SOLD', holder_user_id = ? WHERE event_session_id = ? AND seat_code = 'A-1'",
                holder,
                sessionId);

        List<SessionMaterializer.SeatLine> moved = first.seats().stream()
                .map(seat -> new SessionMaterializer.SeatLine(
                        seat.seatCode(),
                        seat.zoneCode(),
                        seat.sectionLabel(),
                        seat.rowLabel(),
                        seat.seatLabel(),
                        seat.posX().subtract(BigDecimal.valueOf(100)),
                        seat.posY().subtract(BigDecimal.valueOf(100)),
                        seat.ticketTypeId(),
                        seat.ticketTypeName(),
                        seat.priceVnd(),
                        seat.blocked()))
                .toList();

        materialize.handle(new SessionMaterializer.SessionManifest(
                sessionId,
                first.eventId(),
                first.organizationId(),
                first.salesOpenAt(),
                first.salesCloseAt(),
                first.maxSeatedPerHold(),
                first.maxStandingPerHold(),
                first.maxUnitsPerHold(),
                first.maxTicketsPerCustomer(),
                moved,
                first.standingBlocks()));

        assertThat(jdbc.queryForObject(
                        "SELECT pos_y FROM session_seats WHERE event_session_id = ? AND seat_code = 'A-1'",
                        BigDecimal.class,
                        sessionId))
                .isEqualByComparingTo(BigDecimal.valueOf(-99));

        // Phần quan trọng hơn: một chỗ ĐÃ BÁN cũng dịch được vị trí mà không mất trạng thái hay
        // chủ của nó. Đồng bộ toạ độ phải là thao tác an toàn trên một suất đang bán, nếu không
        // thì không ai dám chạy nó.
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM session_seats WHERE event_session_id = ? AND seat_code = 'A-1'",
                        String.class,
                        sessionId))
                .isEqualTo("SOLD");
        assertThat(jdbc.queryForObject(
                        "SELECT holder_user_id FROM session_seats WHERE event_session_id = ? AND seat_code = 'A-1'",
                        UUID.class,
                        sessionId))
                .isEqualTo(holder);
    }

    @Test
    @DisplayName("Tồn kho vừa dựng dùng được ngay cho đường giữ chỗ")
    void ton_kho_dung_duoc_ngay() {
        // Kiểm nối thật giữa hai mắt xích: nếu materialize ghi thiếu một cột mà đường giữ chỗ
        // cần, test này đỏ chứ không phải chờ tới lúc chạy production.
        var manifest = manifest(4, 0);
        materialize.handle(manifest);
        List<UUID> seatIds = seatIdsOf(manifest.eventSessionId(), 2);

        var hold = placeHold.handle(new PlaceHoldHandler.Command(
                manifest.eventSessionId(), UUID.randomUUID(), seatIds, List.of(), List.of()));

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
                        manifest.eventSessionId(), UUID.randomUUID(), seatIds, List.of(), List.of()))))
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
