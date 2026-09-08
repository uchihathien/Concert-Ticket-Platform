// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.application.command.ExpireHoldsJob;
import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.command.ReleaseHoldHandler;
import com.nexaticket.inventory.application.command.ReserveSeatsHandler;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Các đường nhả chỗ phải trả lại hạn mức của khách (ADR-1014, mục "Tiêu cực phải chấp nhận").
 *
 * <p>ADR đòi <b>bốn</b> đường. Ba đường đầu — khách tự bỏ, giữ chỗ hết hạn, huỷ đơn — đã có và
 * được kiểm ở đây. Đường thứ tư, hoàn tiền, <b>chưa làm</b>; test cuối lớp này khoá lại khoảng
 * trống đó cho khỏi quên.
 *
 * <p>Đây là chỗ dễ hỏng âm thầm nhất của thiết kế trần mua vé: {@code holder_user_id} phải được xoá
 * ở <b>mọi</b> đường quay về {@code AVAILABLE}. Quên một đường thì khách bị khoá hạn mức mà không
 * có lỗi nào được ghi ra — họ chỉ đơn giản là không mua được nữa.
 */
class SeatReleaseIT extends InventoryTestBase {

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
    InventoryFixture fixture;

    @Test
    @DisplayName("Đường 1 — khách tự bỏ giữ chỗ: chỗ về AVAILABLE, hạn mức trả lại")
    void duong_1_khach_tu_bo_giu_cho() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));

        releaseHold.handle(hold.holdId(), user);

        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(fixture.countHolders(session.id())).isZero();
        assertThat(fixture.countActiveHoldItems(session.id())).isZero();
    }

    @Test
    @DisplayName("Đường 2 — giữ chỗ hết hạn: worker nhả chỗ")
    void duong_2_giu_cho_het_han() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));

        fixture.expireHold(hold.holdId());

        // KHÔNG khẳng định runOnce() trả đúng 1: job này quét toàn database, và container
        // PostgreSQL được dùng lại giữa các lần build (withReuse), nên nó sẽ nhặt cả những
        // lần giữ chỗ hết hạn do lần chạy trước để lại. Khẳng định đúng là "có dọn" cộng với
        // các khẳng định trên chính suất diễn của test này.
        assertThat(expireHolds.runOnce()).isPositive();

        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(fixture.countHolders(session.id())).isZero();
    }

    @Test
    @DisplayName("Đường 3 — huỷ đơn (saga bù trừ): chỗ đã RESERVED quay về AVAILABLE")
    void duong_3_huy_don() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        UUID orderId = UUID.randomUUID();
        reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));
        assertThat(fixture.countByStatus(session.id(), "RESERVED")).isEqualTo(2);

        settleReservation.cancel(orderId);

        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(2);
        assertThat(fixture.countHolders(session.id())).isZero();
    }

    @Test
    @DisplayName("Đường 4 — hoàn tiền CHƯA CÓ: cancel() không đụng được chỗ đã SOLD")
    void duong_4_hoan_tien_chua_duoc_ho_tro() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        UUID orderId = UUID.randomUUID();
        reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));
        settleReservation.settle(orderId);
        assertThat(fixture.countByStatus(session.id(), "SOLD")).isEqualTo(2);

        // ADR-1014 đòi bốn đường nhả chỗ. Ba đường đầu đã có; đường thứ tư — hoàn tiền — thì
        // CHƯA. cancel() lọc WHERE status = 'RESERVED', nên với đơn đã SETTLED nó trả 0 hàng và
        // không làm gì. Test này khoá lại hành vi hiện tại để nó không âm thầm đổi, và để lộ rõ
        // khoảng trống: chừng nào chưa có lệnh hoàn tiền riêng, khách được hoàn tiền vẫn bị giữ
        // hạn mức cho suất đó.
        settleReservation.cancel(orderId);
        assertThat(fixture.countByStatus(session.id(), "SOLD")).isEqualTo(2);
        assertThat(fixture.countHolders(session.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("Đặt chỗ hai lần cùng orderId: idempotent, không nổ và không đổi trạng thái")
    void dat_cho_idempotent_theo_order() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        UUID orderId = UUID.randomUUID();

        var first = reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));
        var second = reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));

        assertThat(second.seatIds()).containsExactlyInAnyOrderElementsOf(first.seatIds());
        assertThat(fixture.countByStatus(session.id(), "RESERVED")).isEqualTo(2);
    }

    @Test
    @DisplayName("Xác nhận thanh toán hai lần: chỉ bán một lần")
    void settle_idempotent() {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(command(session.id(), user, session.seatIds()));
        UUID orderId = UUID.randomUUID();
        reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));

        settleReservation.settle(orderId);
        settleReservation.settle(orderId);

        assertThat(fixture.countByStatus(session.id(), "SOLD")).isEqualTo(2);
    }

    private static PlaceHoldHandler.Command command(UUID sessionId, UUID user, List<UUID> seatIds) {
        return new PlaceHoldHandler.Command(sessionId, user, seatIds, List.of());
    }
}
