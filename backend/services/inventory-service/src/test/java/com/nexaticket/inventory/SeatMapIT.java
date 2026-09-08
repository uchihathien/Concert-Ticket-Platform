// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Đường đọc sơ đồ chỗ. */
class SeatMapIT extends InventoryTestBase {

    @Autowired
    SeatQueries queries;

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    InventoryFixture fixture;

    @Test
    @DisplayName("Sự kiện 3.000 vé đứng KHÔNG trả 3.000 phần tử, chỉ một dòng tóm tắt mỗi zone")
    void ve_dung_khong_tra_tung_don_vi() {
        var session = fixture.materialize(10, 3_000);

        var view = queries.seatMap(session.id(), null);

        assertThat(view.seats()).hasSize(10);
        assertThat(view.standingZones()).hasSize(1);
        assertThat(view.standingZones().get(0).available()).isEqualTo(3_000);
        assertThat(view.standingZones().get(0).zoneCode()).isEqualTo("GA");
    }

    @Test
    @DisplayName("Khách chưa đăng nhập vẫn xem được sơ đồ, nhưng không có phần hạn mức")
    void khach_an_danh_khong_co_han_muc() {
        var session = fixture.materialize(5, 0);

        assertThat(queries.seatMap(session.id(), null).purchaseAllowance()).isNull();
    }

    @Test
    @DisplayName("purchaseAllowance khớp số đếm thật sau mỗi thao tác")
    void han_muc_khop_so_dem_that() {
        var session = fixture.materialize(0, 50, 8, 10, 10, 10);
        UUID user = UUID.randomUUID();

        var before = queries.seatMap(session.id(), user).purchaseAllowance();
        assertThat(before.limit()).isEqualTo(10);
        assertThat(before.used()).isZero();
        assertThat(before.remaining()).isEqualTo(10);

        placeHold.handle(new PlaceHoldHandler.Command(
                session.id(), user, List.of(), List.of(new PlaceHoldHandler.Command.StandingLine("GA", 4))));

        var after = queries.seatMap(session.id(), user).purchaseAllowance();
        assertThat(after.used()).isEqualTo(4);
        assertThat(after.remaining()).isEqualTo(6);

        // Hạn mức là của riêng từng người: khách khác không bị ảnh hưởng.
        assertThat(queries.seatMap(session.id(), UUID.randomUUID())
                        .purchaseAllowance()
                        .used())
                .isZero();
    }

    @Test
    @DisplayName("Đường đọc không làm nhảy version — nếu không, ETag đổi mỗi lần gọi và mất sạch 304")
    void duong_doc_khong_bump_version() {
        var session = fixture.materialize(3, 0);

        long first = queries.seatMap(session.id(), null).availabilityVersion();
        long second = queries.seatMap(session.id(), null).availabilityVersion();
        assertThat(second).isEqualTo(first);

        placeHold.handle(new PlaceHoldHandler.Command(
                session.id(), UUID.randomUUID(), List.of(session.seatIds().get(0)), List.of()));

        assertThat(queries.seatMap(session.id(), null).availabilityVersion()).isGreaterThan(first);
    }
}
