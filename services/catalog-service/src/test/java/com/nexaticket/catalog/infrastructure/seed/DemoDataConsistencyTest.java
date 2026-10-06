// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.EventSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.PriceSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SessionSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.VenueSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.ZoneSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tính nhất quán của dữ liệu mẫu, kiểm lúc dựng thay vì lúc khởi động.
 *
 * <h2>Vì sao lớp này tồn tại</h2>
 *
 * <p>Một bảng giá trỏ vào mã khu không có ở địa điểm của nó là lỗi khoá ngoại
 * ({@code ticket_types_venue_zone_id_fkey}), và nó chỉ nổ ra lúc bộ dựng chạy — tức là <b>giữa lúc
 * khởi động service</b>, sau khi đã ghi xong một phần dữ liệu. Triệu chứng là service không lên
 * được và database ở trạng thái dựng dở.
 *
 * <p>Chuyện này đã xảy ra thật: hai sự kiện thêm vào dùng mã khu {@code "A"} và {@code "GA"} trong
 * khi địa điểm của chúng khai {@code VIP/STD} và {@code A/B/C}. Không có gì trong trình biên dịch
 * bắt được — mã khu là chuỗi tự do ở cả hai đầu.
 *
 * <p>Những phép kiểm ở đây chạy trong vài mili giây và không cần database nào.
 */
class DemoDataConsistencyTest {

    private static final Map<String, VenueSpec> VENUES =
            DemoData.VENUES.stream().collect(Collectors.toMap(VenueSpec::key, Function.identity()));

    @Test
    @DisplayName("mọi bảng giá trỏ vào một khu CÓ THẬT ở địa điểm của sự kiện")
    void moi_bang_gia_tro_vao_khu_co_that() {
        for (EventSpec event : DemoData.EVENTS) {
            VenueSpec venue = VENUES.get(event.venueKey());
            assertThat(venue)
                    .as(
                            "Sự kiện '%s' trỏ vào địa điểm '%s' không có trong DemoData.VENUES",
                            event.slug(), event.venueKey())
                    .isNotNull();

            Set<String> zones = venue.zones().stream().map(ZoneSpec::code).collect(Collectors.toSet());

            for (SessionSpec session : event.sessions()) {
                for (PriceSpec price : session.prices()) {
                    assertThat(zones)
                            .as(
                                    "Sự kiện '%s' bán hạng vé ở khu '%s', nhưng '%s' chỉ có các khu %s",
                                    event.slug(), price.zoneCode(), venue.name(), zones)
                            .contains(price.zoneCode());
                }
            }
        }
    }

    @Test
    @DisplayName("mã khu không trùng nhau trong cùng một địa điểm")
    void ma_khu_khong_trung_trong_mot_dia_diem() {
        // Trùng mã thì `uq_seat_code` của inventory sẽ từ chối, nhưng chỉ ở bước materialize suất
        // diễn — xa chỗ gây lỗi thêm một bậc nữa.
        for (VenueSpec venue : DemoData.VENUES) {
            List<String> codes = venue.zones().stream().map(ZoneSpec::code).toList();

            assertThat(Set.copyOf(codes))
                    .as("Địa điểm '%s' có mã khu trùng: %s", venue.name(), codes)
                    .hasSameSizeAs(codes);
        }
    }

    @Test
    @DisplayName("slug sự kiện không trùng nhau")
    void slug_su_kien_khong_trung() {
        // Bộ dựng idempotent THEO SLUG: hai sự kiện cùng slug thì cái thứ hai bị bỏ qua lặng lẽ,
        // và nó sẽ trông như "sự kiện tôi vừa thêm không hiện ra".
        List<String> slugs = DemoData.EVENTS.stream().map(EventSpec::slug).toList();

        assertThat(Set.copyOf(slugs)).as("Có slug trùng trong DemoData.EVENTS").hasSameSizeAs(slugs);
    }

    @Test
    @DisplayName("sự kiện ĐÃ XUẤT BẢN nào cũng bán được thứ gì đó")
    void su_kien_da_xuat_ban_deu_ban_duoc() {
        // Chỉ xét sự kiện đã xuất bản. Bản NHÁP có quyền thiếu bảng giá — `giao-huong-mua-dong` cố
        // ý để trống đúng chỗ ấy, để thử nhánh "nháp chưa đủ điều kiện xuất bản". Siết cả bản nháp
        // là xoá mất một fixture có chủ đích.
        for (EventSpec event : DemoData.EVENTS) {
            if (event.status() != DemoCatalogSeeder.SeedStatus.PUBLISHED) {
                continue;
            }
            assertThat(event.sessions())
                    .as("Sự kiện '%s' đã xuất bản nhưng không có suất diễn nào", event.slug())
                    .isNotEmpty();

            for (SessionSpec session : event.sessions()) {
                assertThat(session.prices())
                        .as("Một suất của '%s' đã xuất bản nhưng không có hạng vé nào", event.slug())
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("khu bàn tròn và khu cung đều có mặt trong dữ liệu mẫu")
    void ba_hinh_bo_cuc_deu_co_cho_nhin_thay() {
        // Không có dữ liệu dùng tới một hình nghĩa là hình ấy chưa từng được vẽ ra màn hình nào, và
        // một lỗi hình học ở đó sẽ ngủ yên tới lúc có khách hàng thật dựng sơ đồ thật.
        Set<String> shapes = DemoData.VENUES.stream()
                .flatMap(venue -> venue.zones().stream())
                .map(ZoneSpec::layout)
                .filter(layout -> layout != null)
                .map(layout -> layout.shape().name())
                .collect(Collectors.toSet());

        assertThat(shapes).contains("GRID", "ARC", "TABLE");
    }
}
