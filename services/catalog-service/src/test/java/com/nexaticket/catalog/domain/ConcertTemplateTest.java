// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.model.VenueZone;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Luật của khung concert, kiểm ở mức domain.
 *
 * <p>Không dựng Spring: những luật này không cần database để đúng, và một test chạy trong mili giây
 * là một test người ta chạy lại sau mỗi lần sửa.
 */
class ConcertTemplateTest {

    @Test
    @DisplayName("khung rỗng không mở cho tổ chức dùng được")
    void khung_rong_khong_activate_duoc() {
        // Không phải thủ tục: khung rỗng sinh ra sự kiện không bán được gì, và ban tổ chức chỉ phát
        // hiện ở bước publish — sau khi đã điền xong mọi thứ khác.
        ConcertTemplate template = ConcertTemplate.draft("ARENA_5K", "Nhà thi đấu 5.000 chỗ", "concert", null);

        assertThatThrownBy(template::activate).isInstanceOf(IllegalStateException.class);
        assertThat(template.status()).isEqualTo(TemplateStatus.DRAFT);
    }

    @Test
    @DisplayName("sức chứa cộng cả khu ngồi lẫn khu đứng")
    void suc_chua_cong_ca_hai_loai_khu() {
        ConcertTemplate template = withZones(seated("VIP", 10, 10), standing("SAN", 2_000));

        // 10×10 ghế ngồi + 2.000 chỗ đứng. Con số này là thứ ban tổ chức nhìn để chọn khung, nên
        // nó phải tính đúng cả hai loại khu chứ không chỉ loại có ghế.
        assertThat(template.capacity()).isEqualTo(2_100);
    }

    @Test
    @DisplayName("mã khu trùng nhau trong một khung bị chặn ngay ở aggregate")
    void ma_khu_trung_bi_chan() {
        ConcertTemplate template = ConcertTemplate.draft("ARENA", "Nhà thi đấu", "concert", null);
        List<TemplateZone> trung = List.of(seated("VIP", 5, 5), seated("VIP", 6, 6));

        // Ràng buộc UNIQUE của database cũng bắt được, nhưng nó nói bằng tiếng của Postgres. Bắt ở
        // đây thì thông báo lỗi nói đúng mã khu nào bị trùng.
        assertThatThrownBy(() -> template.replaceZones(trung))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIP");
    }

    @Test
    @DisplayName("thay tập khu là thay cả tập, không phải thêm vào")
    void thay_ca_tap() {
        ConcertTemplate template = withZones(seated("VIP", 5, 5), seated("THUONG", 10, 10));

        template.replaceZones(List.of(seated("VIP", 5, 5)));

        assertThat(template.zones()).hasSize(1);
        assertThat(template.capacity()).isEqualTo(25);
    }

    @Test
    @DisplayName("lưu trữ khung không đụng tới khu đã chép sang địa điểm")
    void luu_tru_khong_dung_toi_ban_da_chep() {
        // Đây là lý do áp khung được thiết kế thành phép CHÉP: sau khi chép, sự kiện của tổ chức
        // không còn phụ thuộc vào khung, nên nền tảng lưu trữ khung lúc nào cũng an toàn.
        ConcertTemplate template = withZones(seated("VIP", 10, 10));
        UUID venueId = UUID.randomUUID();
        VenueZone daChep = template.zones().get(0).materialize(venueId);

        template.archive();

        assertThat(template.status()).isEqualTo(TemplateStatus.ARCHIVED);
        assertThat(daChep.seatCount()).isEqualTo(100);
        assertThat(daChep.venueId()).isEqualTo(venueId);
    }

    @Test
    @DisplayName("khu chép từ khung là khu cố định; khu tự dựng thì không")
    void khu_chep_tu_khung_la_co_dinh() {
        // isFixed() là chỗ câu "khu vực cố định, không thay đổi được" thành một thứ chạy được thay
        // vì một dòng trong tài liệu.
        VenueZone chep = seated("VIP", 10, 10).materialize(UUID.randomUUID());
        VenueZone tuDung = new VenueZone(
                UUID.randomUUID(), UUID.randomUUID(), "VIP", "Khu VIP", AdmissionKind.SEATED, 10, 10, null, 0);

        assertThat(chep.isFixed()).isTrue();
        assertThat(chep.sourceTemplateZoneId()).isNotNull();
        assertThat(tuDung.isFixed()).isFalse();
    }

    @Test
    @DisplayName("khu ngồi thiếu số hàng thì không dựng được khung")
    void khu_ngoi_phai_co_so_hang() {
        // Luật này trùng với VenueZone và với ck_template_zone_shape, và đó là chủ đích: khung nào
        // lưu được thứ mà venue_zones từ chối thì lỗi sẽ nổ ở màn hình của ban tổ chức.
        assertThatThrownBy(() -> TemplateZone.create(
                        UUID.randomUUID(), "VIP", "Khu VIP", AdmissionKind.SEATED, null, null, null, 0, 500_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("giá gợi ý 0 là hợp lệ — đó là vé mời, không phải giá bị bỏ trống")
    void gia_goi_y_bang_khong_la_hop_le() {
        TemplateZone veMoi =
                TemplateZone.create(UUID.randomUUID(), "VIP", "Khu mời", AdmissionKind.SEATED, 2, 2, null, 0, 0L);

        assertThat(veMoi.suggestedPriceVnd()).isZero();
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static ConcertTemplate withZones(TemplateZone... zones) {
        ConcertTemplate template = ConcertTemplate.draft("ARENA", "Nhà thi đấu", "concert", null);
        template.replaceZones(List.of(zones));
        return template;
    }

    private static TemplateZone seated(String code, int rows, int seatsPerRow) {
        return TemplateZone.create(
                UUID.randomUUID(), code, "Khu " + code, AdmissionKind.SEATED, rows, seatsPerRow, null, 0, 500_000L);
    }

    private static TemplateZone standing(String code, int capacity) {
        return TemplateZone.create(
                UUID.randomUUID(), code, "Khu " + code, AdmissionKind.STANDING, null, null, capacity, 1, 300_000L);
    }
}
