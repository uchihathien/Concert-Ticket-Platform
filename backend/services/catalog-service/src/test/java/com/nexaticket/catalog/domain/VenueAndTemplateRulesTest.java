// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.StageShape;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Luật hình dạng của địa điểm, khu, sân khấu và vòng đời khung concert.
 *
 * <p>Bổ sung cho {@code ConcertTemplateTest} và {@code ZoneLayoutTest}: ở đây là những bất biến
 * mà tầng application dựa vào để không phải tự kiểm lại — và một chỗ tràn số mà chưa lớp nào chặn.
 */
class VenueAndTemplateRulesTest {

    @Nested
    @DisplayName("Khu của địa điểm")
    class KhuDiaDiem {

        @Test
        @DisplayName("khu ngồi bỏ sức chứa khai kèm; khu đứng bỏ số hàng/ghế khai kèm")
        void chuan_hoa_theo_loai() {
            VenueZone seated = zone(AdmissionKind.SEATED, 10, 20, 999);
            VenueZone standing = zone(AdmissionKind.STANDING, 5, 5, 300);

            assertThat(seated.capacity()).isNull();
            assertThat(seated.seatCount()).isEqualTo(200);
            assertThat(standing.rowCount()).isNull();
            assertThat(standing.seatsPerRow()).isNull();
            assertThat(standing.seatCount()).isEqualTo(300);
        }

        @Test
        @DisplayName("khu ngồi thiếu hoặc có số hàng ≤ 0 bị từ chối")
        void khu_ngoi_sai_hinh() {
            assertThatThrownBy(() -> zone(AdmissionKind.SEATED, null, 10, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> zone(AdmissionKind.SEATED, 0, 10, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> zone(AdmissionKind.SEATED, 10, -1, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("khu đứng thiếu sức chứa hoặc sức chứa ≤ 0 bị từ chối")
        void khu_dung_sai_hinh() {
            assertThatThrownBy(() -> zone(AdmissionKind.STANDING, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> zone(AdmissionKind.STANDING, null, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("khu không mã bị từ chối")
        void khu_khong_ma() {
            assertThatThrownBy(() -> new VenueZone(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            " ",
                            "Khu",
                            AdmissionKind.STANDING,
                            null,
                            null,
                            10,
                            0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("mã chỗ ghép từ mã khu, hàng, ghế — đúng định dạng Inventory nhận")
        void ma_cho() {
            assertThat(zone(AdmissionKind.SEATED, 2, 2, null).seatCode(3, 12)).isEqualTo("A-3-12");
        }

        @Test
        @DisplayName("khu ngồi khổng lồ không được tràn số thành sức chứa âm")
        void khu_khong_lo_khong_tran_so() {
            // 50.000 × 50.000 = 2,5 tỉ > Integer.MAX_VALUE. Request chỉ có @Positive nên con số
            // này đi thẳng tới đây. Hành vi đúng là một trong hai: từ chối ngay khi dựng, hoặc trả
            // về sức chứa đúng. Hành vi hiện tại là tràn số: seatCount() âm, kéo theo tổng sức
            // chứa của địa điểm và của bảng điều khiển sai dấu.
            Throwable rejected = catchThrowable(() -> zone(AdmissionKind.SEATED, 50_000, 50_000, null));
            if (rejected != null) {
                assertThat(rejected).isInstanceOf(IllegalArgumentException.class);
                return;
            }

            VenueZone huge = zone(AdmissionKind.SEATED, 50_000, 50_000, null);
            assertThat(huge.seatCount()).as("sức chứa của khu 50.000 × 50.000").isPositive();
        }
    }

    @Nested
    @DisplayName("Địa điểm")
    class DiaDiem {

        @Test
        @DisplayName("tên và thành phố là bắt buộc")
        void ten_va_thanh_pho() {
            UUID org = UUID.randomUUID();

            assertThatThrownBy(() -> Venue.create(org, " ", "Hà Nội", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Venue.create(org, "Nhà hát", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sức chứa cộng cả khu ngồi lẫn khu đứng; chưa có khu thì 0 và chưa bán được")
        void suc_chua() {
            UUID venueId = UUID.randomUUID();
            Venue venue = new Venue(
                    venueId,
                    UUID.randomUUID(),
                    "Nhà hát",
                    "Hà Nội",
                    null,
                    List.of(zone(AdmissionKind.SEATED, 10, 10, null), zone(AdmissionKind.STANDING, null, null, 50)));

            assertThat(venue.capacity()).isEqualTo(150);
            assertThat(venue.hasZones()).isTrue();
            assertThat(Venue.create(UUID.randomUUID(), "Trống", "Huế", null).capacity())
                    .isZero();
        }

        @Test
        @DisplayName("địa điểm tự dựng không gắn khung; chưa khai sân khấu thì dùng sân khấu mặc định")
        void tu_dung() {
            Venue venue = Venue.create(UUID.randomUUID(), "Nhà hát", "Hà Nội", null);

            assertThat(venue.isFromTemplate()).isFalse();
            assertThat(venue.stage()).isNull();
            assertThat(venue.stageOrDefault()).isEqualTo(StageArea.DEFAULT);
        }

        @Test
        @DisplayName("danh sách khu là bất biến — không ai thêm khu lén qua getter")
        void khu_bat_bien() {
            Venue venue = Venue.create(UUID.randomUUID(), "Nhà hát", "Hà Nội", null);

            assertThatThrownBy(() -> venue.zones().add(zone(AdmissionKind.STANDING, null, null, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Sân khấu")
    class SanKhau {

        @Test
        @DisplayName("bề ngang ≤ 0 bị từ chối với mọi hình")
        void be_ngang() {
            assertThatThrownBy(() -> new StageArea(StageShape.CIRCLE, 0, 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StageArea(StageShape.RECTANGLE, 0, 0, -1, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sân khấu tròn bỏ qua chiều sâu và cao bằng đường kính")
        void san_khau_tron() {
            StageArea circle = new StageArea(StageShape.CIRCLE, 0, 0, 10, 0);

            assertThat(circle.effectiveHeight()).isEqualTo(10);
            assertThat(circle.minY()).isEqualTo(-5);
        }

        @Test
        @DisplayName("sân khấu chữ nhật chiều sâu 0 bị từ chối")
        void chu_nhat_khong_sau() {
            assertThatThrownBy(() -> new StageArea(StageShape.THRUST, 0, 0, 10, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("khu bàn tròn có bán kính bàn ≤ 0 bị từ chối")
        void ban_tron() {
            assertThatThrownBy(() -> ZoneLayout.tables(0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Vòng đời khung concert")
    class VongDoiKhung {

        @Test
        @DisplayName("nháp → mở → lưu trữ → về nháp → mở lại")
        void vong_doi_day_du() {
            ConcertTemplate template = ConcertTemplate.draft("ARENA", "Nhà thi đấu", "concert", null);
            template.replaceZones(List.of(templateZone("VIP")));

            template.activate();
            assertThat(template.status()).isEqualTo(TemplateStatus.ACTIVE);
            assertThat(template.status().isUsable()).isTrue();

            template.archive();
            assertThat(template.status()).isEqualTo(TemplateStatus.ARCHIVED);
            assertThat(template.status().isUsable()).isFalse();

            template.backToDraft();
            assertThat(template.status()).isEqualTo(TemplateStatus.DRAFT);
            assertThat(template.status().isUsable()).isFalse();

            template.activate();
            assertThat(template.status()).isEqualTo(TemplateStatus.ACTIVE);
        }

        @Test
        @DisplayName("khung phải có mã, tên và phân loại; mã được cắt khoảng trắng")
        void truong_bat_buoc() {
            assertThatThrownBy(() -> ConcertTemplate.draft(" ", "Tên", "concert", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ConcertTemplate.draft("A", null, "concert", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ConcertTemplate.draft("A", "Tên", "", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(ConcertTemplate.draft("  ARENA ", "Tên", "concert", null).code())
                    .isEqualTo("ARENA");
        }

        @Test
        @DisplayName("đổi tên: chuỗi rỗng không xoá tên/phân loại, nhưng xoá được mô tả")
        void doi_ten() {
            ConcertTemplate template = ConcertTemplate.draft("A", "Tên", "concert", "Mô tả");

            template.rename("", " ", "");

            assertThat(template.name()).isEqualTo("Tên");
            assertThat(template.category()).isEqualTo("concert");
            assertThat(template.description()).isEmpty();
        }

        @Test
        @DisplayName("giá gợi ý âm bị từ chối")
        void gia_goi_y_am() {
            assertThatThrownBy(() -> TemplateZone.create(
                            UUID.randomUUID(), "VIP", "VIP", AdmissionKind.STANDING, null, null, 10, 0, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("chép khu sang địa điểm giữ nguyên hình dạng và nhớ khu gốc")
        void chep_khu() {
            TemplateZone source = templateZone("VIP");
            UUID venueId = UUID.randomUUID();

            VenueZone copy = source.materialize(venueId);

            assertThat(copy.venueId()).isEqualTo(venueId);
            assertThat(copy.sourceTemplateZoneId()).isEqualTo(source.id());
            assertThat(copy.seatCount()).isEqualTo(source.seatCount());
            assertThat(copy.id()).isNotEqualTo(source.id());
        }
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static VenueZone zone(AdmissionKind kind, Integer rows, Integer seats, Integer capacity) {
        return new VenueZone(UUID.randomUUID(), UUID.randomUUID(), "A", "Khu A", kind, rows, seats, capacity, 0);
    }

    private static TemplateZone templateZone(String code) {
        return TemplateZone.create(UUID.randomUUID(), code, "Khu " + code, AdmissionKind.SEATED, 5, 4, null, 0, 1L);
    }
}
