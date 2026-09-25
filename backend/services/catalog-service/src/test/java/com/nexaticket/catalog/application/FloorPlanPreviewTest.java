// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.application.query.FloorPlanQuery;
import com.nexaticket.catalog.application.query.FloorPlanViews;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.VenueRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Xem trước một sơ đồ chưa lưu.
 *
 * <p>Endpoint này tồn tại để trình sửa sơ đồ <b>không</b> phải chép công thức toạ độ sang
 * TypeScript. Nó chỉ giữ được lời hứa đó nếu hai điều đúng: nó đọc <b>bản nháp</b> chứ không đọc
 * database, và nó <b>không ghi gì</b>. Hai điều đó là nội dung của file này.
 */
class FloorPlanPreviewTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    @DisplayName("xem trước đọc BẢN NHÁP, không đọc khu đã lưu")
    void doc_ban_nhap_khong_doc_database() {
        // Đây là bài kiểm quan trọng nhất: địa điểm đã lưu có khu "CU", bản nháp khai khu "MOI".
        // Nếu kết quả trả về "CU" thì màn hình sửa đang hiện database chứ không hiện thứ người
        // dùng vừa sửa — và không ai nhận ra cho tới khi bấm lưu.
        FakeVenues venues = new FakeVenues(venueWith(zone("CU", 5, 10)));
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        var plan = query.preview(ORG, venues.venue.id(), null, List.of(draft("MOI", 3, 8, null)));

        assertThat(plan.zones()).extracting(FloorPlanViews.Zone::zoneCode).containsExactly("MOI");
    }

    @Test
    @DisplayName("không ghi một dòng nào")
    void khong_ghi_gi() {
        FakeVenues venues = new FakeVenues(venueWith(zone("A", 5, 10)));
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        query.preview(ORG, venues.venue.id(), null, List.of(draft("A", 5, 10, null)));

        // Một lần xem trước lỡ tay ghi đè sơ đồ thật là kiểu hỏng tệ nhất ở màn hình này: người
        // dùng chưa bấm Lưu, và họ tin rằng mình chưa thay đổi gì.
        assertThat(venues.writes).isEmpty();
    }

    @Test
    @DisplayName("khu chưa đặt vị trí vẫn nhận được bố cục ĐÃ GIẢI")
    void khu_tu_xep_van_co_bo_cuc() {
        // Đây là thứ cho phép trình sửa ghim một khu tại đúng chỗ nó đang đứng ngay lần kéo đầu
        // tiên. Trả null ở đây thì khu tự xếp sẽ nhảy về gốc toạ độ khi vừa chạm vào.
        FakeVenues venues = new FakeVenues(venueWith(zone("A", 5, 10)));
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        var plan = query.preview(ORG, venues.venue.id(), null, List.of(draft("A", 5, 10, null)));

        var layout = plan.zones().get(0).layout();
        assertThat(layout).isNotNull();
        assertThat(layout.shape()).isEqualTo("GRID");
        // Xếp xuống DƯỚI sân khấu. So với mép dưới của chính sân khấu chứ không so với 0: sân
        // khấu mặc định nằm ở y = -7, nên "dưới nó" vẫn là một số âm. Gắn cứng số 0 ở đây là một
        // test sẽ đỏ vào ngày ai đó dời sân khấu mặc định mà không làm gì sai cả.
        assertThat(layout.originY())
                .isGreaterThan(plan.stage().y() + plan.stage().height() / 2);
    }

    @Test
    @DisplayName("khu hình cung trong bản nháp cho ra đường bao cong, không phải chữ nhật")
    void ban_nhap_cung_ra_hinh_cung() {
        FakeVenues venues = new FakeVenues(venueWith(zone("A", 3, 12)));
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        var arc = new LayoutSpecs.ZoneLayoutSpec("ARC", 0.0, 0.0, null, 12.0, 20.0, 160.0);
        var plan = query.preview(ORG, venues.venue.id(), null, List.of(draft("A", 3, 12, arc)));

        assertThat(plan.zones().get(0).layout().shape()).isEqualTo("ARC");
        // Đường bao cung lấy nhiều điểm hơn bốn góc của một chữ nhật — đó là cách phân biệt rẻ
        // nhất giữa "đã áp hình cung" và "vẫn là khối chữ nhật".
        assertThat(plan.zones().get(0).outline().size()).isGreaterThan(4);
    }

    @Test
    @DisplayName("sân khấu trong bản nháp thắng sân khấu đã lưu")
    void san_khau_ban_nhap_thang() {
        // Địa điểm đã lưu sân khấu chữ nhật; bản nháp đổi sang tròn. Xem trước phải hiện cái tròn,
        // nếu không thì đổi hình sân khấu là thao tác không có phản hồi nào.
        Venue saved = new Venue(
                UUID.randomUUID(),
                ORG,
                "Nhà thi đấu",
                "Hà Nội",
                null,
                List.of(zone("A", 3, 10)),
                null,
                new StageArea(com.nexaticket.catalog.domain.model.StageShape.RECTANGLE, 0, -7, 24, 4));
        FakeVenues venues = new FakeVenues(saved);
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        var stage = new LayoutSpecs.StageSpec("CIRCLE", 0.0, 0.0, 10.0, null);
        var plan = query.preview(ORG, saved.id(), stage, List.of(draft("A", 3, 10, null)));

        assertThat(plan.stage().shape()).isEqualTo("CIRCLE");
        // Sân khấu tròn cao bằng đường kính — backend giải sẵn để frontend không mang luật
        // "CIRCLE thì bỏ qua height".
        assertThat(plan.stage().height()).isEqualTo(10);
    }

    @Test
    @DisplayName("bản xem trước mang theo toạ độ từng ghế")
    void co_toa_do_tung_ghe() {
        // Trình sửa chạy TRƯỚC khi publish, nên inventory chưa có ghế nào. Không có toạ độ ở đây
        // thì màn hình không vẽ được ghế nào cả.
        FakeVenues venues = new FakeVenues(venueWith(zone("A", 2, 3)));
        FloorPlanQuery query = new FloorPlanQuery(venues, allowAll());

        var plan = query.preview(ORG, venues.venue.id(), null, List.of(draft("A", 2, 3, null)));

        assertThat(plan.zones().get(0).seats()).hasSize(6);
        assertThat(plan.zones().get(0).seats().get(0).seatCode()).isEqualTo("A-1-1");
    }

    // --- dựng dữ liệu -------------------------------------------------------

    private static CatalogAccess allowAll() {
        return new CatalogAccess() {
            @Override
            public UUID requireCatalogManager(UUID organizationId) {
                return organizationId;
            }
        };
    }

    private static FloorPlanQuery.ZoneDraft draft(
            String code, int rows, int seatsPerRow, LayoutSpecs.ZoneLayoutSpec layout) {
        return new FloorPlanQuery.ZoneDraft(code, "Khu " + code, "SEATED", rows, seatsPerRow, null, 0, layout);
    }

    private static VenueZone zone(String code, int rows, int seatsPerRow) {
        return new VenueZone(
                UUID.randomUUID(),
                UUID.randomUUID(),
                code,
                "Khu " + code,
                AdmissionKind.SEATED,
                rows,
                seatsPerRow,
                null,
                0);
    }

    private static Venue venueWith(VenueZone... zones) {
        return new Venue(UUID.randomUUID(), ORG, "Nhà thi đấu", "Hà Nội", null, List.of(zones));
    }

    /** Cổng giả ghi lại mọi lời gọi GHI — đó là thứ test này đi kiểm. */
    private static final class FakeVenues implements VenueRepository {
        private final Venue venue;
        final List<String> writes = new ArrayList<>();

        FakeVenues(Venue venue) {
            this.venue = venue;
        }

        @Override
        public Optional<Venue> findById(UUID organizationId, UUID venueId) {
            return Optional.of(venue);
        }

        @Override
        public void save(Venue value) {
            writes.add("save");
        }

        @Override
        public void addZone(VenueZone value) {
            writes.add("addZone");
        }

        @Override
        public void updateStage(UUID venueId, StageArea stage) {
            writes.add("updateStage");
        }

        @Override
        public int updateSeatMapImage(UUID organizationId, UUID venueId, String imageUrl) {
            writes.add("updateSeatMapImage");
            return 1;
        }

        @Override
        public void saveWithZones(Venue value, List<VenueZone> zones) {
            writes.add("saveWithZones");
        }

        @Override
        public ZoneReplacement replaceZones(UUID venueId, List<VenueZone> zones) {
            writes.add("replaceZones");
            return new ZoneReplacement(0, 0, 0, 0);
        }

        @Override
        public List<Venue> findAllByOrganization(UUID organizationId) {
            return List.of(venue);
        }

        @Override
        public Optional<Venue> findByPublishedEventSlug(String slug) {
            return Optional.empty();
        }
    }
}
