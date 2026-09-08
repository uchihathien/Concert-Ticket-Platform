// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.support.Concurrently;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spike chống oversell (plan/backend.md §5, ưu tiên #2 ở §15).
 *
 * <p>Đây là bất biến quan trọng nhất hệ thống: bán trùng một ghế là một khách bị đuổi ở cửa vào
 * đêm diễn. Mọi test ở đây đều chạy <b>thật sự đồng thời</b> — nếu chúng xanh mà code có lỗi tranh
 * chấp thì bộ test này vô giá trị, nên xem {@link Concurrently} để biết cách đồng thời được ép.
 */
class SeatHoldConcurrencyIT extends InventoryTestBase {

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    InventoryFixture fixture;

    @Test
    @DisplayName("200 người giành cùng 1 ghế: đúng 1 người giữ được")
    void hai_tram_nguoi_gianh_mot_ghe() throws Exception {
        var session = fixture.materialize(1, 0);
        UUID theSeat = session.seatIds().get(0);

        List<Callable<UUID>> jobs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            jobs.add(() -> placeHold
                    .handle(new PlaceHoldHandler.Command(session.id(), UUID.randomUUID(), List.of(theSeat), List.of()))
                    .holdId());
        }

        var outcomes = Concurrently.run(jobs);

        assertThat(Concurrently.successes(outcomes)).isEqualTo(1);
        assertThat(Concurrently.errorCodes(outcomes)).containsExactly("SEAT_UNAVAILABLE");
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(1);
        // Số dòng chiếm chốt chặn phải khớp số chỗ đang giữ — lệch nghĩa là có dòng rác
        // đang khoá một ghế mà không ai giữ.
        assertThat(fixture.countActiveHoldItems(session.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("500 người mua vé đứng ở zone 200 chỗ: phát đúng 200, không hơn một vé")
    void nam_tram_nguoi_mua_ve_dung_zone_hai_tram() throws Exception {
        var session = fixture.materialize(0, 200);

        List<Callable<UUID>> jobs = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            jobs.add(() -> placeHold
                    .handle(new PlaceHoldHandler.Command(
                            session.id(),
                            UUID.randomUUID(),
                            List.of(),
                            List.of(new PlaceHoldHandler.Command.StandingLine("GA", 1))))
                    .holdId());
        }

        var outcomes = Concurrently.run(jobs);

        assertThat(Concurrently.successes(outcomes)).isEqualTo(200);
        assertThat(Concurrently.errorCodes(outcomes)).containsExactly("ZONE_SOLD_OUT");
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(200);
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isZero();
    }

    @Test
    @DisplayName("Giữ chỗ hỗn hợp 2 ngồi + 2 đứng: hỏng phần đứng thì rollback cả bốn")
    void giu_cho_hon_hop_hong_mot_phan_thi_rollback_ca_bon() {
        // Zone đứng chỉ có 1 chỗ, nhưng khách xin 2 ⇒ phần vé ngồi đã giữ được cũng phải nhả.
        var session = fixture.materialize(2, 1);

        UUID user = UUID.randomUUID();
        var command = new PlaceHoldHandler.Command(
                session.id(), user, session.seatIds(), List.of(new PlaceHoldHandler.Command.StandingLine("GA", 2)));

        assertThat(catchCode(() -> placeHold.handle(command))).isEqualTo("ZONE_SOLD_OUT");

        assertThat(fixture.countByStatus(session.id(), "HELD")).isZero();
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(3);
        // Quan trọng nhất: không còn dấu vết người giữ nào, nếu không khách bị trừ oan hạn mức
        // cho một lần giữ chỗ chưa từng thành công.
        assertThat(fixture.countHolders(session.id())).isZero();
    }

    @Test
    @DisplayName("Cùng một người mở 20 tab, trần 10 vé: giữ được đúng 10 chỗ")
    void mot_nguoi_hai_muoi_tab_khong_vuot_tran_cong_don() throws Exception {
        // 20 request song song, mỗi request 1 vé đứng, trần cộng dồn 10.
        var session = fixture.materialize(0, 100, 8, 10, 10, 10);
        UUID sameUser = UUID.randomUUID();

        List<Callable<UUID>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> placeHold
                    .handle(new PlaceHoldHandler.Command(
                            session.id(),
                            sameUser,
                            List.of(),
                            List.of(new PlaceHoldHandler.Command.StandingLine("GA", 1))))
                    .holdId());
        }

        var outcomes = Concurrently.run(jobs);

        assertThat(Concurrently.successes(outcomes)).isEqualTo(10);
        assertThat(Concurrently.errorCodes(outcomes)).containsExactly("CUSTOMER_LIMIT_EXCEEDED");
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(10);
    }

    @Test
    @DisplayName("Gửi trùng cùng một ghế hai lần trong một request: tính là một, không báo ghế bận")
    void ghe_trung_lap_trong_mot_request() {
        var session = fixture.materialize(1, 0);
        UUID seat = session.seatIds().get(0);

        var result = placeHold.handle(
                new PlaceHoldHandler.Command(session.id(), UUID.randomUUID(), List.of(seat, seat), List.of()));

        assertThat(result.seatIds()).containsExactly(seat);
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(1);
    }

    private static String catchCode(Runnable action) {
        try {
            action.run();
            return null;
        } catch (com.nexaticket.platform.web.error.ApiException e) {
            return e.errorCode().code();
        }
    }
}
