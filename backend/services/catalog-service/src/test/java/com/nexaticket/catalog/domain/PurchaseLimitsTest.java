// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.PurchaseLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kế thừa ba tầng trần mua vé (ADR-1014 §1). */
class PurchaseLimitsTest {

    private static final PurchaseLimits PLATFORM = new PurchaseLimits(8, 10, 10, 10);

    @Test
    @DisplayName("Suất diễn NULL thì lấy của tổ chức; tổ chức NULL thì lấy của nền tảng")
    void ke_thua_ba_tang() {
        var organization = new PurchaseLimits(4, null, null, 6);
        var session = new PurchaseLimits(null, 2, null, null);

        var effective = PurchaseLimits.resolve(session, organization, PLATFORM);

        assertThat(effective.maxSeatedPerHold()).isEqualTo(4); // của tổ chức
        assertThat(effective.maxStandingPerHold()).isEqualTo(2); // của suất diễn
        assertThat(effective.maxUnitsPerHold()).isEqualTo(10); // của nền tảng
        assertThat(effective.maxTicketsPerCustomer()).isEqualTo(6); // của tổ chức
    }

    @Test
    @DisplayName("Tổ chức đặt vượt trần cứng: bị chặn ngay lúc LƯU")
    void vuot_tran_cung_bi_chan_luc_luu() {
        // Chặn ở màn hình cấu hình, nơi người dùng hiểu chuyện gì và sửa được — không phải ở
        // đường giữ chỗ, nơi người bị báo lỗi là khách hàng.
        assertThat(new PurchaseLimits(20, null, null, null).exceeds(PLATFORM)).isTrue();
        assertThat(new PurchaseLimits(8, null, null, null).exceeds(PLATFORM)).isFalse();
        assertThat(PurchaseLimits.INHERIT_ALL.exceeds(PLATFORM)).isFalse();
    }

    @Test
    @DisplayName("Hạ trần cứng SAU khi tổ chức đã lưu giá trị cao: kẹp lại lúc materialize")
    void ha_tran_cung_sau_thi_kep_lai() {
        // Nếu bắt mọi tổ chức phải sửa trước khi hạ được trần thì trên thực tế nền tảng sẽ
        // không bao giờ dám hạ trần.
        var organization = new PurchaseLimits(8, 10, 10, 10);
        var loweredCeiling = new PurchaseLimits(4, 4, 4, 4);

        var effective = PurchaseLimits.resolve(PurchaseLimits.INHERIT_ALL, organization, loweredCeiling);

        assertThat(effective.maxSeatedPerHold()).isEqualTo(4);
        assertThat(effective.maxTicketsPerCustomer()).isEqualTo(4);
    }
}
