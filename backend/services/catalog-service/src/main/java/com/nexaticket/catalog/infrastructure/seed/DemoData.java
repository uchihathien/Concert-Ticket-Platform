// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.ZoneSpec.seated;
import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.ZoneSpec.standing;

import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.EventSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.PriceSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SessionSpec;
import com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.VenueSpec;
import java.util.List;

/**
 * Nội dung của catalog mẫu.
 *
 * <p>Tách khỏi {@link DemoCatalogSeeder} vì đây là <b>dữ liệu</b>, không phải logic: sửa nó không
 * cần đọc hiểu cách dựng, và đọc cách dựng không phải cuộn qua hai trăm dòng tên sự kiện.
 *
 * <p>Chọn dữ liệu có chủ đích để mọi đường đi của giao diện đều có thứ để hiển thị:
 *
 * <ul>
 *   <li>Hai thành phố, nên bộ lọc theo thành phố có ít nhất hai lựa chọn thật.
 *   <li>Đủ bốn phân loại của trang khách.
 *   <li>Có sự kiện nhiều suất và sự kiện một suất, để trang chi tiết lộ được cả hai kiểu bố cục.
 *   <li>Có địa điểm trộn khu ngồi với khu đứng — đường vé đứng của Inventory chỉ chạy khi tồn tại
 *       dữ liệu như vậy, và đó là đường dễ bị bỏ quên nhất khi thử tay.
 *   <li>Có đúng một sự kiện để ở trạng thái nháp, để màn hình quản trị không chỉ toàn màu xanh —
 *       checklist trước khi publish cần một ví dụ thật để nhìn.
 * </ul>
 */
final class DemoData {

    private DemoData() {}

    static final List<VenueSpec> VENUES = List.of(
            new VenueSpec(
                    "nha-hat-lon",
                    "Nhà hát Lớn Hà Nội",
                    "Hà Nội",
                    "1 Tràng Tiền, Hoàn Kiếm, Hà Nội",
                    List.of(
                            seated("A", "Tầng 1 — khu A", 12, 22),
                            seated("B", "Tầng 2 — khu B", 8, 20),
                            seated("C", "Ban công", 4, 14))),
            new VenueSpec(
                    "san-van-dong-quoc-gia",
                    "Sân vận động Quốc gia",
                    "Hà Nội",
                    "Đường Lê Quang Đạo, Nam Từ Liêm, Hà Nội",
                    List.of(
                            // Khu đứng sát sân khấu: đây là chỗ duy nhất trong dữ liệu mẫu chạm
                            // vào đường cấp phát vé đứng (FOR UPDATE SKIP LOCKED, ADR-1012).
                            standing("GA", "Sân trung tâm (vé đứng)", 3000),
                            seated("KA", "Khán đài A", 20, 30),
                            seated("KB", "Khán đài B", 20, 30))),
            new VenueSpec(
                    "nha-hat-tuoi-tre",
                    "Nhà hát Tuổi Trẻ",
                    "Hà Nội",
                    "11 Ngô Thì Nhậm, Hai Bà Trưng, Hà Nội",
                    List.of(seated("A", "Khu chính", 15, 18), seated("B", "Khu sau", 6, 18))),
            new VenueSpec(
                    "san-thong-nhat",
                    "Sân Thống Nhất",
                    "TP. Hồ Chí Minh",
                    "138 Đào Duy Từ, Quận 10, TP. Hồ Chí Minh",
                    List.of(
                            seated("A", "Khán đài A", 25, 40),
                            seated("B", "Khán đài B", 25, 40),
                            standing("C", "Khán đài C (vé đứng)", 5000))),
            new VenueSpec(
                    "trung-tam-hoi-nghi",
                    "Trung tâm Hội nghị Quốc gia",
                    "Hà Nội",
                    "57 Phạm Hùng, Nam Từ Liêm, Hà Nội",
                    List.of(seated("VIP", "Hàng đầu", 3, 20), seated("STD", "Khu tiêu chuẩn", 20, 24))));

    static final List<EventSpec> EVENTS = List.of(
            new EventSpec(
                    "dem-nhac-mua-thu",
                    "Đêm nhạc Mùa Thu",
                    "Đêm nhạc acoustic với dàn nhạc thính phòng.",
                    """
                    Một đêm nhạc acoustic kết hợp dàn dây thính phòng, không dùng nhạc nền thu sẵn.

                    Chương trình kéo dài khoảng 120 phút, không có giải lao.""",
                    "nhac-song",
                    "nha-hat-lon",
                    true,
                    List.of(
                            // Hai suất liền nhau: trang chi tiết phải chọn đúng suất còn ở phía
                            // trước, không phải suất sớm nhất.
                            new SessionSpec(
                                    18,
                                    19,
                                    120,
                                    30,
                                    List.of(
                                            new PriceSpec("A", "Hạng A", 1_200_000),
                                            new PriceSpec("B", "Hạng B", 800_000),
                                            new PriceSpec("C", "Ban công", 500_000))),
                            new SessionSpec(
                                    19,
                                    19,
                                    120,
                                    30,
                                    List.of(
                                            new PriceSpec("A", "Hạng A", 1_200_000),
                                            new PriceSpec("B", "Hạng B", 800_000),
                                            new PriceSpec("C", "Ban công", 500_000))))),
            new EventSpec(
                    "live-concert-thanh-pho",
                    "Live Concert · Thành Phố Không Ngủ",
                    "Sân khấu ngoài trời, khu vực đứng và khán đài đánh số.",
                    """
                    Sân vận động mở, gồm sân trung tâm vé đứng và hai khán đài ghế đánh số.

                    Cổng mở trước giờ diễn 90 phút.""",
                    "nhac-song",
                    "san-van-dong-quoc-gia",
                    true,
                    List.of(new SessionSpec(
                            35,
                            19,
                            180,
                            45,
                            List.of(
                                    new PriceSpec("GA", "Vé đứng sân trung tâm", 1_500_000),
                                    new PriceSpec("KA", "Khán đài A", 900_000),
                                    new PriceSpec("KB", "Khán đài B", 800_000))))),
            new EventSpec(
                    "vo-kich-nguoi-tot",
                    "Vở kịch: Người Tốt Của Thành Tứ Xuyên",
                    "Kịch nói, suất diễn cuối tuần.",
                    "Kịch nói dài 150 phút, có một lần giải lao 15 phút.",
                    "san-khau",
                    "nha-hat-tuoi-tre",
                    true,
                    List.of(new SessionSpec(
                            12,
                            20,
                            150,
                            21,
                            List.of(
                                    new PriceSpec("A", "Khu chính", 350_000),
                                    new PriceSpec("B", "Khu sau", 250_000))))),
            new EventSpec(
                    "derby-thanh-pho",
                    "Derby Thành Phố",
                    "Vòng 12 giải vô địch quốc gia.",
                    "Trận đấu vòng 12. Khán đài A và B là ghế đánh số, khán đài C vé đứng.",
                    "the-thao",
                    "san-thong-nhat",
                    true,
                    List.of(new SessionSpec(
                            9,
                            18,
                            120,
                            30,
                            List.of(
                                    new PriceSpec("A", "Khán đài A", 300_000),
                                    new PriceSpec("B", "Khán đài B", 250_000),
                                    new PriceSpec("C", "Khán đài C (đứng)", 150_000))))),
            new EventSpec(
                    "hoi-thao-cong-nghe",
                    "Hội thảo Công nghệ & Thanh toán",
                    "Một ngày, hai phiên song song.",
                    "Chương trình một ngày, hai phiên song song, có phiên hỏi đáp cuối ngày.",
                    "hoi-thao",
                    "trung-tam-hoi-nghi",
                    true,
                    List.of(new SessionSpec(
                            55,
                            8,
                            480,
                            60,
                            List.of(
                                    new PriceSpec("VIP", "Vé VIP", 3_000_000),
                                    new PriceSpec("STD", "Vé tiêu chuẩn", 1_200_000))))),
            // Cố ý để nháp: màn hình quản trị cần một sự kiện chưa publish để checklist trước khi
            // bán có thứ để hiện, và để nút Publish có chỗ bấm thử.
            new EventSpec(
                    "giao-huong-mua-dong",
                    "Hòa nhạc Giao hưởng Mùa Đông",
                    "Chương trình giao hưởng thường niên.",
                    "Dàn nhạc giao hưởng trình diễn chương trình thường niên.",
                    "san-khau",
                    "nha-hat-lon",
                    false,
                    List.of(new SessionSpec(90, 20, 110, 45, List.of()))));
}
