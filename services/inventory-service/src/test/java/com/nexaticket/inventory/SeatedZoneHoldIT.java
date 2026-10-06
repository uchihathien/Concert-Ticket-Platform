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
 * Mua vé ngồi theo KHU — khách nói "khu A, 2 vé", hệ thống chọn chỗ.
 *
 * <p>Đường này sinh ra vì đường cũ buộc khách bấm đúng ô ghế trên sơ đồ, mà sơ đồ chỉ vẽ đúng khi
 * toạ độ ghế khớp mặt bằng — một điều kiện 52/63 suất trong dữ liệu hiện có <b>không</b> thoả. Nên
 * nó phải đứng vững một mình: không đi qua cổng Redis (chưa có mã ghế nào để từ chối), và chốt
 * chặn duy nhất là {@code FOR UPDATE SKIP LOCKED} trong database.
 */
class SeatedZoneHoldIT extends InventoryTestBase {

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    InventoryFixture fixture;

    @Test
    @DisplayName("xin 2 vé khu A: nhận đúng 2 ghế, và là hai ghế gần sân khấu nhất")
    void chon_cho_gan_san_khau_truoc() {
        // 3 hàng × 4 ghế. Hàng 1 sát sân khấu.
        var session = fixture.materializeSeatedGrid(3, 4);

        var hold = placeHold.handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 2));

        assertThat(hold.seatIds()).hasSize(2);
        // Đây là lời hứa của cả tính năng: không chỉ "đủ số vé", mà "chỗ tốt nhất còn lại". Cấp
        // theo id như vé đứng sẽ qua được phép kiểm số lượng ở trên mà vẫn sai ở đây.
        assertThat(hold.seatIds()).containsExactlyElementsOf(session.seatIds().subList(0, 2));
    }

    @Test
    @DisplayName("hàng đầu đã có người: lượt sau nhận hàng kế tiếp, không nhận lại chỗ đã giữ")
    void khong_cap_lai_cho_da_giu() {
        var session = fixture.materializeSeatedGrid(2, 2);

        placeHold.handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 2));
        var second = placeHold.handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 2));

        assertThat(second.seatIds()).containsExactlyElementsOf(session.seatIds().subList(2, 4));
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(4);
    }

    @Test
    @DisplayName("khu không còn đủ chỗ: ZONE_SOLD_OUT, và những ghế vừa cấp được nhả")
    void khu_khong_du_cho() {
        var session = fixture.materializeSeatedGrid(1, 3);

        String code = catchCode(() -> placeHold.handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 5)));

        assertThat(code).isEqualTo("ZONE_SOLD_OUT");
        // Phần quan trọng hơn cả mã lỗi: cấp phát đã kịp chiếm 3 ghế trước khi biết thiếu, và nếu
        // giao dịch không rollback thì ba ghế ấy kẹt ở HELD cho tới khi hết hạn — của một lần giữ
        // chỗ chưa từng tồn tại.
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isEqualTo(3);
        assertThat(fixture.countHolders(session.id())).isZero();
    }

    @Test
    @DisplayName("trộn ghế đích danh với vé theo khu: cả hai vào cùng một lần giữ chỗ")
    void tron_ghe_dich_danh_va_ve_theo_khu() {
        var session = fixture.materializeSeatedGrid(2, 3);
        // Ghế cuối cùng — cố ý lấy chỗ mà cấp phát theo khu sẽ KHÔNG chọn, để hai đường không che
        // nhau: nếu chúng cùng chọn hàng đầu thì test này xanh cả khi một đường không chạy.
        UUID named = session.seatIds().get(5);

        var hold = placeHold.handle(new PlaceHoldHandler.Command(
                session.id(),
                UUID.randomUUID(),
                List.of(named),
                List.of(new PlaceHoldHandler.Command.ZoneLine("A", 2)),
                List.of()));

        assertThat(hold.seatIds()).hasSize(3).contains(named);
        assertThat(hold.seatIds()).containsAll(session.seatIds().subList(0, 2));
    }

    @Test
    @DisplayName("vé theo khu đếm vào trần vé ngồi mỗi lần giữ chỗ")
    void dem_vao_tran_ve_ngoi() {
        // maxSeatedPerHold của fixture là 8.
        var session = fixture.materializeSeatedGrid(4, 4);

        String code = catchCode(() -> placeHold.handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 9)));

        // Không có phép kiểm này thì trần chỉ còn áp cho đường bấm từng ghế, và đường mới trở thành
        // cửa sau để một người ôm cả khu.
        assertThat(code).isEqualTo("HOLD_LIMIT_EXCEEDED");
        assertThat(fixture.countByStatus(session.id(), "HELD")).isZero();
    }

    @Test
    @DisplayName("100 người cùng xin 2 vé ở khu 60 chỗ: phát đúng 30 lần, không ai trùng ghế")
    void mot_tram_nguoi_cung_xin_mot_khu() throws Exception {
        var session = fixture.materializeSeatedGrid(10, 6);

        List<Callable<UUID>> jobs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            jobs.add(() -> placeHold
                    .handle(zoneCommand(session.id(), UUID.randomUUID(), "A", 2))
                    .holdId());
        }

        var outcomes = Concurrently.run(jobs);

        // 60 ghế ÷ 2 = 30 lần thành công. Nhiều hơn là bán trùng; ít hơn là SKIP LOCKED đã bỏ qua
        // những ghế thật ra còn trống, và khán phòng bán không hết dù có người đang chờ mua.
        assertThat(Concurrently.successes(outcomes)).isEqualTo(30);
        assertThat(Concurrently.errorCodes(outcomes)).containsExactly("ZONE_SOLD_OUT");
        assertThat(fixture.countByStatus(session.id(), "HELD")).isEqualTo(60);
        assertThat(fixture.countActiveHoldItems(session.id())).isEqualTo(60);
    }

    private static PlaceHoldHandler.Command zoneCommand(UUID sessionId, UUID user, String zoneCode, int quantity) {
        return new PlaceHoldHandler.Command(
                sessionId,
                user,
                List.of(),
                List.of(new PlaceHoldHandler.Command.ZoneLine(zoneCode, quantity)),
                List.of());
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
