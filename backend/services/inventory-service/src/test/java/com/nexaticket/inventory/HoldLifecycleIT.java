// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.application.command.ExpireHoldsJob;
import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.command.ReleaseHoldHandler;
import com.nexaticket.inventory.application.command.ReserveSeatsHandler;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import com.nexaticket.inventory.domain.port.SessionInventoryRepository;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Các chuyển trạng thái <b>nối tiếp</b> mà unit test với mock không chứng minh được: chúng phụ
 * thuộc vào điều kiện {@code WHERE status = ...} trong chính câu SQL.
 *
 * <p>Không có đồng thời ở đây — xem {@link ReleaseReserveRaceIT} cho phần tranh chấp. Mỗi test đi
 * hết một vòng đời rồi khẳng định trạng thái cuối cùng của ghế, của giữ chỗ và của đặt chỗ.
 */
class HoldLifecycleIT extends InventoryTestBase {

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    ReleaseHoldHandler releaseHold;

    @Autowired
    ReserveSeatsHandler reserveSeats;

    @Autowired
    SettleReservationHandler settleReservation;

    @Autowired
    ExpireHoldsJob expireHolds;

    @Autowired
    SessionInventoryRepository sessions;

    @Autowired
    InventoryFixture fixture;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Hết hạn rồi khách mới bấm bỏ: không nhả ghế mà người SAU đã giữ được")
    void bo_giu_cho_sau_khi_het_han_khong_dung_ghe_nguoi_sau() {
        var session = fixture.materialize(2, 0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var stale = placeHold.handle(command(session.id(), first, session.seatIds()));
        fixture.expireHold(stale.holdId());
        assertThat(expireHolds.runOnce()).isEqualTo(1);
        placeHold.handle(command(session.id(), second, session.seatIds()));

        releaseHold.handle(stale.holdId(), first);

        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(2);
        assertThat(fixture.countActiveHoldItems(session.id())).isEqualTo(2);
        assertThat(holderCount(session.id(), second)).isEqualTo(2);
        assertThat(holdStatus(stale.holdId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Bỏ giữ chỗ hai lần: ghế về AVAILABLE đúng một lần, version chỉ nhảy một lần")
    void bo_hai_lan_chi_nha_mot_lan() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));

        releaseHold.handle(hold.holdId(), user);
        long afterFirst = sessions.currentAvailabilityVersion(session.id());
        releaseHold.handle(hold.holdId(), user);

        assertThat(sessions.currentAvailabilityVersion(session.id())).isEqualTo(afterFirst);
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(holdStatus(hold.holdId())).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Worker chạy lại sau khi đã nhả: không còn gì để nhả, ghế không bị đụng lần hai")
    void worker_chay_lai_khong_nha_lan_hai() {
        var session = fixture.materialize(2, 0);
        var hold = placeHold.handle(command(session.id(), UUID.randomUUID(), session.seatIds()));
        fixture.expireHold(hold.holdId());

        assertThat(expireHolds.runOnce()).isEqualTo(1);
        long afterFirst = sessions.currentAvailabilityVersion(session.id());
        assertThat(expireHolds.runOnce()).isZero();

        assertThat(sessions.currentAvailabilityVersion(session.id())).isEqualTo(afterFirst);
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(fixture.countHolders(session.id())).isZero();
    }

    @Test
    @DisplayName("Giữ chỗ đã thành đơn rồi quá hạn: worker KHÔNG nhả ghế đang RESERVED")
    void worker_khong_nha_giu_cho_da_thanh_don() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        reserveSeats.handle(new ReserveSeatsHandler.Command(UUID.randomUUID(), hold.holdId(), user));
        fixture.expireHold(hold.holdId());

        assertThat(expireHolds.runOnce()).isZero();

        assertThat(fixture.countByStatus(session.id(), "RESERVED")).isEqualTo(2);
        assertThat(holdStatus(hold.holdId())).isEqualTo("CONVERTED");
    }

    @Test
    @DisplayName("Đặt chỗ từ giữ chỗ quá hạn mà worker chưa quét: HOLD_EXPIRED, ghế vẫn HELD cho worker dọn")
    void dat_cho_tu_giu_cho_qua_han() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        fixture.expireHold(hold.holdId());

        assertThatThrownBy(() ->
                        reserveSeats.handle(new ReserveSeatsHandler.Command(UUID.randomUUID(), hold.holdId(), user)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("HOLD_EXPIRED"));
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(2);

        assertThat(expireHolds.runOnce()).isEqualTo(1);
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
    }

    @Test
    @DisplayName("Xác nhận thanh toán đến SAU khi đơn đã huỷ: không bán ghế đã trả về kho")
    void chot_ban_sau_khi_huy() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        UUID orderId = UUID.randomUUID();
        reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));
        assertThat(settleReservation.cancel(orderId)).isTrue();

        assertThat(settleReservation.settle(orderId)).isFalse();

        assertThat(fixture.countByStatus(session.id(), "SOLD")).isZero();
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(reservationStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Đặt chỗ lại bằng giữ chỗ đã thành đơn cho một orderId KHÁC: từ chối, không có hai đơn trên cùng ghế")
    void hai_don_mot_giu_cho() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        reserveSeats.handle(new ReserveSeatsHandler.Command(UUID.randomUUID(), hold.holdId(), user));
        UUID secondOrder = UUID.randomUUID();

        assertThatThrownBy(() -> reserveSeats.handle(new ReserveSeatsHandler.Command(secondOrder, hold.holdId(), user)))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM seat_reservations WHERE order_id = ?", Integer.class, secondOrder))
                .isZero();
        assertThat(fixture.countByStatus(session.id(), "RESERVED")).isEqualTo(2);
    }

    private int holderCount(UUID sessionId, UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_seats WHERE event_session_id = ? AND holder_user_id = ?",
                Integer.class,
                sessionId,
                userId);
        return n == null ? 0 : n;
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, holdId);
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM seat_reservations WHERE order_id = ?", String.class, orderId);
    }

    private static PlaceHoldHandler.Command command(UUID sessionId, UUID user, List<UUID> seatIds) {
        return new PlaceHoldHandler.Command(sessionId, user, seatIds, List.of(), List.of());
    }
}
