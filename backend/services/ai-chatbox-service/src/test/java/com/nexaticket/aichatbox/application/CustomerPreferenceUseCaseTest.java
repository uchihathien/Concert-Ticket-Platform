// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase.Remembered;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase.Update;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase.View;
import com.nexaticket.aichatbox.support.InMemoryCustomerPreferences;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sở thích khách: gộp, lọc giá trị lạ, không lưu thông tin liên lạc, xoá hẳn. */
class CustomerPreferenceUseCaseTest {

    private final InMemoryCustomerPreferences rows = new InMemoryCustomerPreferences();
    private final CustomerPreferenceUseCase prefs =
            new CustomerPreferenceUseCase(rows, Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    private static View saved(Remembered r) {
        assertThat(r.saved()).isPresent();
        return r.saved().get();
    }

    @Test
    void lan_dau_luu_dung_dieu_khach_noi() {
        View v = saved(prefs.remember(
                userId, new Update(List.of("nhac-song"), "Đà Nẵng", 500_000L, "thích ngồi gần sân khấu")));

        assertThat(v.categories()).containsExactly("nhac-song");
        assertThat(v.cities()).containsExactly("Đà Nẵng");
        assertThat(v.maxBudgetVnd()).isEqualTo(500_000L);
        assertThat(v.note()).isEqualTo("thích ngồi gần sân khấu");
    }

    @Test
    void lan_sau_gop_the_loai_thanh_pho_moi_dung_dau_ngan_sach_thay_the() {
        prefs.remember(userId, new Update(List.of("nhac-song"), "Hà Nội", 300_000L, null));
        prefs.remember(userId, new Update(List.of("the-thao", "NHAC-SONG"), "Huế", null, null));
        View v = saved(prefs.remember(userId, new Update(List.of(), "hà nội", 800_000L, null)));

        assertThat(v.categories()).containsExactly("nhac-song", "the-thao");
        assertThat(v.cities()).containsExactly("hà nội", "Huế");
        assertThat(v.maxBudgetVnd()).isEqualTo(800_000L);
    }

    @Test
    void toi_da_ba_thanh_pho_moi_nhat_dung_dau() {
        for (String city : List.of("A", "B", "C", "D")) {
            prefs.remember(userId, new Update(null, city, null, null));
        }
        assertThat(prefs.view(userId).orElseThrow().cities()).containsExactly("D", "C", "B");
    }

    @Test
    void the_loai_la_bi_bo_va_bao_lai_phan_hop_le_van_luu() {
        Remembered r = prefs.remember(userId, new Update(List.of("nhạc sống", "san-khau"), null, null, null));

        assertThat(saved(r).categories()).containsExactly("san-khau");
        assertThat(r.ignored()).singleElement().asString().contains("nhạc sống");
    }

    @Test
    void ngan_sach_vo_ly_bi_bo_va_khong_ghi_dong_rong() {
        Remembered r = prefs.remember(userId, new Update(null, null, 500L, null));

        assertThat(r.saved()).isEmpty();
        assertThat(r.ignored()).singleElement().asString().contains("maxBudgetVnd");
        assertThat(rows.rows).isEmpty();
    }

    @Test
    void ghi_chu_co_so_dien_thoai_hoac_email_khong_duoc_luu() {
        Remembered phone = prefs.remember(userId, new Update(null, null, null, "gọi mình 0905 123 456 nhé"));
        Remembered email = prefs.remember(userId, new Update(null, null, null, "gửi về an.nguyen@gmail.com"));

        assertThat(phone.saved()).isEmpty();
        assertThat(email.saved()).isEmpty();
        assertThat(rows.rows).isEmpty();
    }

    @Test
    void thanh_pho_bo_phan_sau_dau_phay() {
        assertThat(saved(prefs.remember(userId, new Update(null, "Đà Nẵng, Việt Nam", null, null)))
                        .cities())
                .containsExactly("Đà Nẵng");
    }

    @Test
    void ghi_chu_dai_bi_cat_con_200_ky_tu() {
        View v = saved(prefs.remember(userId, new Update(null, null, null, "x".repeat(300))));
        assertThat(v.note()).hasSize(200);
    }

    @Test
    void xoa_han_va_xoa_lan_hai_khong_loi() {
        prefs.remember(userId, new Update(List.of("hoi-thao"), null, null, null));

        assertThat(prefs.forget(userId)).isTrue();
        assertThat(prefs.view(userId)).isEmpty();
        assertThat(prefs.forget(userId)).isFalse();
    }

    @Test
    void moi_khach_mot_dong_rieng() {
        UUID other = UUID.randomUUID();
        prefs.remember(userId, new Update(List.of("nhac-song"), null, null, null));
        prefs.remember(other, new Update(List.of("the-thao"), null, null, null));
        prefs.forget(other);

        assertThat(prefs.view(userId).orElseThrow().categories()).containsExactly("nhac-song");
    }
}
