// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SeedStatus.CANCELLED;
import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SeedStatus.DRAFT;
import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SeedStatus.PUBLISHED;
import static com.nexaticket.catalog.infrastructure.seed.DemoCatalogSeeder.SeedStatus.UNPUBLISHED;
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
 * cần đọc hiểu cách dựng, và đọc cách dựng không phải cuộn qua vài trăm dòng tên sự kiện.
 *
 * <p>Chọn dữ liệu có chủ đích để mọi đường đi của giao diện đều có thứ để hiển thị:
 *
 * <ul>
 *   <li>Năm thành phố, nên bộ lọc theo thành phố có lựa chọn thật chứ không phải một dòng duy nhất.
 *   <li>Đủ bốn phân loại của trang khách, mỗi phân loại nhiều hơn một sự kiện — một phân loại chỉ
 *       có một sự kiện thì không phân biệt được "bộ lọc chạy đúng" với "bộ lọc trả về tất cả".
 *   <li>Có sự kiện nhiều suất và sự kiện một suất, để trang chi tiết lộ được cả hai kiểu bố cục.
 *   <li>Có địa điểm trộn khu ngồi với khu đứng, và một địa điểm <b>toàn khu đứng</b> — đường vé
 *       đứng của Inventory chỉ chạy khi tồn tại dữ liệu như vậy, và đó là đường dễ bị bỏ quên nhất
 *       khi thử tay.
 *   <li>Đủ cả bốn trạng thái sự kiện: đang bán, nháp, đã rút xuống, đã huỷ. Màn hình quản trị lọc
 *       theo trạng thái, và một bảng toàn màu xanh không cho biết bộ lọc đó có chạy hay không.
 *   <li>Có suất <b>đã diễn qua</b>, có suất <b>chưa tới ngày mở bán</b>, và có suất đang bán. Ba
 *       trạng thái cửa bán này đi ba nhánh khác nhau ở Inventory, nên phải có dữ liệu cho cả ba.
 *   <li>Có đúng hai bản nháp, cố ý khác nhau: một bản chưa khai hạng vé nào (checklist trước khi
 *       publish có thứ để hiện) và một bản đã đủ điều kiện (nút Publish có chỗ bấm thử thành công).
 * </ul>
 *
 * <p><b>Thứ tự trong danh sách này là thứ tự dựng</b>, và trang công khai sắp xếp theo
 * {@code published_at DESC} — nên sự kiện nằm càng cuối danh sách càng hiện lên đầu trang. Vì vậy
 * mấy sự kiện "đinh" được xếp xuống cuối.
 */
final class DemoData {

    private DemoData() {}

    /**
     * Ảnh bìa của một sự kiện mẫu.
     *
     * <p>Dùng ảnh sinh theo {@code seed} nên cùng một sự kiện luôn ra cùng một ảnh: chụp màn hình
     * trong tài liệu không đổi sau mỗi lần dựng lại database, và không sự kiện nào trùng ảnh với
     * sự kiện khác.
     *
     * <p>Đây là ảnh <b>minh hoạ</b>, không phải poster thật. Thay bằng poster của bạn chỉ cần đổi
     * chuỗi trả về ở đây (hoặc điền thẳng URL vào từng {@link EventSpec}) — {@code poster_url} chỉ
     * là một chuỗi, backend không tải ảnh về và không kiểm tra gì.
     */
    private static String poster(String seed) {
        return "https://picsum.photos/seed/nexaticket-" + seed + "/1200/675";
    }

    // -----------------------------------------------------------------------
    // Địa điểm
    //
    // Kích thước khu lấy theo một khán phòng cùng cỡ ngoài đời, không phải số tròn cho đẹp: tồn
    // kho sinh ra từ đây (rowCount × seatsPerRow dòng mỗi suất), nên một địa điểm khai 100×100 sẽ
    // dựng 10.000 chỗ và làm mọi thao tác thử tay chậm đi mà không thêm thông tin gì.
    // -----------------------------------------------------------------------
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
                            // Khu đứng sát sân khấu: một trong ba chỗ trong dữ liệu mẫu chạm vào
                            // đường cấp phát vé đứng (FOR UPDATE SKIP LOCKED, ADR-1012).
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
                    "trung-tam-hoi-nghi",
                    "Trung tâm Hội nghị Quốc gia",
                    "Hà Nội",
                    "57 Phạm Hùng, Nam Từ Liêm, Hà Nội",
                    List.of(seated("VIP", "Hàng đầu", 3, 20), seated("STD", "Khu tiêu chuẩn", 20, 24))),
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
                    "nha-hat-hoa-binh",
                    "Nhà hát Hòa Bình",
                    "TP. Hồ Chí Minh",
                    "240 Đường 3 Tháng 2, Quận 10, TP. Hồ Chí Minh",
                    List.of(
                            seated("A", "Tầng trệt — khu A", 18, 26),
                            seated("B", "Tầng lửng — khu B", 10, 24),
                            seated("C", "Ban công", 5, 20))),
            new VenueSpec(
                    "nha-van-hoa-thanh-nien",
                    "Nhà Văn hóa Thanh niên",
                    "TP. Hồ Chí Minh",
                    "4 Phạm Ngọc Thạch, Quận 1, TP. Hồ Chí Minh",
                    List.of(standing("GA", "Sân trước sân khấu (vé đứng)", 1200), seated("A", "Khán đài A", 8, 20))),
            new VenueSpec(
                    "cung-the-thao-tien-son",
                    "Cung Thể thao Tiên Sơn",
                    "Đà Nẵng",
                    "Đường 2 Tháng 9, Hải Châu, Đà Nẵng",
                    List.of(
                            standing("GA", "Sàn trung tâm (vé đứng)", 1500),
                            seated("KA", "Khán đài A", 20, 28),
                            seated("KB", "Khán đài B", 20, 28))),
            // Địa điểm ngoài trời, KHÔNG có khu ngồi nào. Sơ đồ chỗ của suất ở đây rỗng hoàn toàn
            // và giao diện phải rơi về màn hình chọn số lượng — một nhánh riêng của frontend, không
            // có dữ liệu như thế này thì không ai thử tới.
            new VenueSpec(
                    "cong-vien-bien-dong",
                    "Công viên Biển Đông",
                    "Đà Nẵng",
                    "Đường Võ Nguyên Giáp, Sơn Trà, Đà Nẵng",
                    List.of(standing("VIP", "Khu VIP sát sân khấu", 500), standing("GA", "Khu phổ thông", 4000))),
            new VenueSpec(
                    "nha-hat-song-huong",
                    "Nhà hát Sông Hương",
                    "Huế",
                    "1 Lê Lợi, Phú Hội, Huế",
                    List.of(seated("A", "Khu chính", 14, 22), seated("B", "Khu sau", 8, 20))),
            new VenueSpec(
                    "cung-viet-tiep",
                    "Cung Văn hóa Hữu nghị Việt Tiệp",
                    "Hải Phòng",
                    "53 Đinh Tiên Hoàng, Hồng Bàng, Hải Phòng",
                    List.of(seated("A", "Khu chính", 16, 24), seated("B", "Ban công", 8, 22))));

    // -----------------------------------------------------------------------
    // Sự kiện
    // -----------------------------------------------------------------------
    static final List<EventSpec> EVENTS = List.of(

            // --- Những sự kiện KHÔNG ở trạng thái đang bán bình thường ------------------------
            // Xếp lên đầu vì chúng nằm cuối trang công khai (hoặc không hiện), nên đọc file từ
            // trên xuống là đi từ trường hợp lạ tới trường hợp thường.

            // Đã diễn qua: `next_session_at` là NULL, thẻ sự kiện phải xử lý được chuyện đó mà
            // không vỡ, và bộ lọc "sắp diễn ra" phải loại nó ra.
            new EventSpec(
                    "dai-nhac-hoi-mua-he",
                    "Đại nhạc hội Mùa Hè",
                    "Đêm nhạc mở màn mùa hè tại Đà Nẵng.",
                    """
                    Đêm nhạc mở màn mùa hè, quy tụ sáu nghệ sĩ và một ban nhạc khách mời.

                    Sự kiện đã diễn ra. Trang này giữ lại để tra cứu lịch sử.""",
                    "nhac-song",
                    "cung-the-thao-tien-son",
                    poster("dai-nhac-hoi-mua-he"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            -20,
                            19,
                            210,
                            45,
                            List.of(
                                    new PriceSpec("GA", "Vé đứng sàn trung tâm", 850_000),
                                    new PriceSpec("KA", "Khán đài A", 650_000),
                                    new PriceSpec("KB", "Khán đài B", 550_000))))),

            // Đã huỷ: trạng thái cuối, không quay lại được. Biến khỏi trang công khai nhưng vẫn
            // còn trong khu vực quản trị.
            new EventSpec(
                    "hoi-thao-fintech-2026",
                    "Hội thảo Fintech Duyên hải 2026",
                    "Đã huỷ do trùng lịch với sự kiện của thành phố.",
                    "Hội thảo một ngày về thanh toán số cho doanh nghiệp vừa và nhỏ. Đã huỷ.",
                    "hoi-thao",
                    "cung-viet-tiep",
                    poster("hoi-thao-fintech"),
                    CANCELLED,
                    List.of(new SessionSpec(
                            42,
                            8,
                            420,
                            50,
                            List.of(
                                    new PriceSpec("A", "Vé tham dự", 500_000),
                                    new PriceSpec("B", "Vé sinh viên", 150_000))))),

            // Đã rút xuống: từng lên bán (nên Inventory ĐÃ có tồn kho) rồi tạm dừng. Khác "huỷ" ở
            // chỗ bán lại được.
            new EventSpec(
                    "giao-huu-quoc-te",
                    "Giao hữu Quốc tế · Việt Nam - Nhật Bản",
                    "Tạm dừng bán, chờ chốt lịch với liên đoàn.",
                    "Trận giao hữu quốc tế. Cửa bán tạm dừng trong lúc chờ xác nhận lịch thi đấu.",
                    "the-thao",
                    "san-van-dong-quoc-gia",
                    poster("giao-huu-quoc-te"),
                    UNPUBLISHED,
                    List.of(new SessionSpec(
                            40,
                            19,
                            120,
                            30,
                            List.of(
                                    new PriceSpec("GA", "Vé đứng sân trung tâm", 400_000),
                                    new PriceSpec("KA", "Khán đài A", 700_000),
                                    new PriceSpec("KB", "Khán đài B", 600_000))))),

            // Nháp CHƯA đủ điều kiện: không có hạng vé nào, nên preflight trả về
            // SESSION_WITHOUT_TICKET_TYPE và checklist trước khi publish có thứ để hiện.
            new EventSpec(
                    "giao-huong-mua-dong",
                    "Hòa nhạc Giao hưởng Mùa Đông",
                    "Chương trình giao hưởng thường niên.",
                    "Dàn nhạc giao hưởng trình diễn chương trình thường niên.",
                    "san-khau",
                    "nha-hat-lon",
                    poster("giao-huong-mua-dong"),
                    DRAFT,
                    List.of(new SessionSpec(90, 20, 110, 45, List.of()))),

            // Nháp ĐÃ đủ điều kiện: bấm Publish là lên bán được thật. Không có mẫu này thì không
            // thử được nhánh publish thành công mà không phải tự nhập liệu từ đầu.
            new EventSpec(
                    "dem-jazz-cuoi-nam",
                    "Đêm Jazz Cuối Năm",
                    "Bộ tứ jazz, hai set diễn.",
                    """
                    Bộ tứ jazz với hai set diễn, mỗi set 45 phút, giải lao 20 phút ở giữa.

                    Bản nháp: lịch và giá đã chốt, chờ duyệt trước khi mở bán.""",
                    "nhac-song",
                    "nha-hat-song-huong",
                    poster("dem-jazz-cuoi-nam"),
                    DRAFT,
                    List.of(new SessionSpec(
                            75,
                            20,
                            110,
                            40,
                            List.of(
                                    new PriceSpec("A", "Khu chính", 550_000),
                                    new PriceSpec("B", "Khu sau", 380_000))))),

            // --- Sân khấu ---------------------------------------------------------------------

            new EventSpec(
                    "kich-thieu-nhi-tam-cam",
                    "Kịch thiếu nhi · Tấm Cám Ngày Nay",
                    "Bốn suất cuối tuần, phù hợp trẻ từ 4 tuổi.",
                    """
                    Bản dựng mới của Tấm Cám cho khán giả nhỏ, thời lượng 75 phút, không giải lao.

                    Trẻ dưới 1 mét vào cùng người lớn không cần vé riêng nhưng ngồi chung ghế.""",
                    "san-khau",
                    "nha-hat-tuoi-tre",
                    poster("kich-thieu-nhi"),
                    PUBLISHED,
                    List.of(
                            // Bốn suất trong hai cuối tuần: trang chi tiết phải gom được nhiều suất
                            // mà không biến thành một danh sách dài vô tận.
                            new SessionSpec(
                                    3,
                                    9,
                                    75,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 180_000),
                                            new PriceSpec("B", "Khu sau", 120_000))),
                            new SessionSpec(
                                    3,
                                    15,
                                    75,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 180_000),
                                            new PriceSpec("B", "Khu sau", 120_000))),
                            new SessionSpec(
                                    10,
                                    9,
                                    75,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 180_000),
                                            new PriceSpec("B", "Khu sau", 120_000))),
                            new SessionSpec(
                                    10,
                                    15,
                                    75,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 180_000),
                                            new PriceSpec("B", "Khu sau", 120_000))))),
            new EventSpec(
                    "vo-kich-nguoi-tot",
                    "Vở kịch: Người Tốt Của Thành Tứ Xuyên",
                    "Kịch nói, suất diễn cuối tuần.",
                    "Kịch nói dài 150 phút, có một lần giải lao 15 phút.",
                    "san-khau",
                    "nha-hat-tuoi-tre",
                    poster("vo-kich-nguoi-tot"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            12,
                            20,
                            150,
                            21,
                            List.of(
                                    new PriceSpec("A", "Khu chính", 350_000),
                                    new PriceSpec("B", "Khu sau", 250_000))))),
            new EventSpec(
                    "cai-luong-tieng-trong-me-linh",
                    "Cải lương · Tiếng Trống Mê Linh",
                    "Bản dựng kỷ niệm, một suất duy nhất.",
                    """
                    Bản dựng kỷ niệm với dàn nghệ sĩ ba thế hệ, thời lượng 165 phút.

                    Một suất duy nhất, không diễn lại.""",
                    "san-khau",
                    "nha-hat-hoa-binh",
                    poster("cai-luong"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            21,
                            19,
                            165,
                            35,
                            List.of(
                                    new PriceSpec("A", "Tầng trệt", 700_000),
                                    new PriceSpec("B", "Tầng lửng", 480_000),
                                    new PriceSpec("C", "Ban công", 300_000))))),
            new EventSpec(
                    "stand-up-comedy-cuoi-vo-bung",
                    "Stand-up Comedy · Cười Vỡ Bụng",
                    "Hai đêm diễn, giới hạn 18+.",
                    """
                    Đêm hài độc thoại với bốn diễn viên, mỗi người 25 phút.

                    Giới hạn 18 tuổi trở lên, có kiểm tra giấy tờ tuỳ thân ở cửa.""",
                    "san-khau",
                    "cung-viet-tiep",
                    poster("stand-up-comedy"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(
                                    14,
                                    20,
                                    120,
                                    25,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 400_000),
                                            new PriceSpec("B", "Ban công", 280_000))),
                            new SessionSpec(
                                    15,
                                    20,
                                    120,
                                    25,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 400_000),
                                            new PriceSpec("B", "Ban công", 280_000))))),
            new EventSpec(
                    "ballet-ho-thien-nga",
                    "Ballet · Hồ Thiên Nga",
                    "Hai đêm, dàn nhạc chơi trực tiếp.",
                    """
                    Toàn bộ bốn màn, dàn nhạc chơi trực tiếp trong hố nhạc, không dùng bản thu.

                    Thời lượng 145 phút kể cả hai lần giải lao.""",
                    "san-khau",
                    "nha-hat-lon",
                    poster("ballet-ho-thien-nga"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(
                                    28,
                                    20,
                                    145,
                                    40,
                                    List.of(
                                            new PriceSpec("A", "Hạng A", 1_500_000),
                                            new PriceSpec("B", "Hạng B", 1_000_000),
                                            new PriceSpec("C", "Ban công", 600_000))),
                            new SessionSpec(
                                    29,
                                    20,
                                    145,
                                    40,
                                    List.of(
                                            new PriceSpec("A", "Hạng A", 1_500_000),
                                            new PriceSpec("B", "Hạng B", 1_000_000),
                                            new PriceSpec("C", "Ban công", 600_000))))),

            // --- Hội thảo ---------------------------------------------------------------------

            // Chỉ bán MỘT trong hai khu của địa điểm: hạng vé không bắt buộc phủ hết địa điểm, và
            // sơ đồ chỗ phải vẽ đúng phần bán được thay vì cả khán phòng.
            new EventSpec(
                    "workshop-nhiep-anh",
                    "Workshop Nhiếp ảnh Đường phố",
                    "Lớp nhỏ, hai buổi, mỗi buổi 160 chỗ.",
                    """
                    Buổi sáng học bố cục và ánh sáng, buổi chiều đi chụp thực địa quanh Quận 1.

                    Học viên tự mang máy. Lớp giới hạn để mỗi người đều được nhận xét ảnh.""",
                    "hoi-thao",
                    "nha-van-hoa-thanh-nien",
                    poster("workshop-nhiep-anh"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(8, 8, 300, 20, List.of(new PriceSpec("A", "Vé học viên", 850_000))),
                            new SessionSpec(22, 8, 300, 30, List.of(new PriceSpec("A", "Vé học viên", 850_000))))),
            new EventSpec(
                    "dien-dan-khoi-nghiep-mien-trung",
                    "Diễn đàn Khởi nghiệp Miền Trung",
                    "Một ngày, ba phiên, có khu kết nối đầu tư.",
                    """
                    Ba phiên nội dung và một khu kết nối đầu tư mở suốt ngày.

                    Vé tham dự gồm ăn trưa và tài liệu hội nghị.""",
                    "hoi-thao",
                    "cung-the-thao-tien-son",
                    poster("dien-dan-khoi-nghiep"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            38,
                            8,
                            480,
                            45,
                            List.of(
                                    new PriceSpec("KA", "Vé tham dự", 600_000),
                                    new PriceSpec("KB", "Vé sinh viên", 200_000))))),
            new EventSpec(
                    "hoi-thao-cong-nghe",
                    "Hội thảo Công nghệ & Thanh toán",
                    "Một ngày, hai phiên song song.",
                    "Chương trình một ngày, hai phiên song song, có phiên hỏi đáp cuối ngày.",
                    "hoi-thao",
                    "trung-tam-hoi-nghi",
                    poster("hoi-thao-cong-nghe"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            55,
                            8,
                            480,
                            60,
                            List.of(
                                    new PriceSpec("VIP", "Vé VIP", 3_000_000),
                                    new PriceSpec("STD", "Vé tiêu chuẩn", 1_200_000))))),
            new EventSpec(
                    "hoi-thao-ai-doanh-nghiep",
                    "Hội thảo AI cho Doanh nghiệp",
                    "Nửa ngày, tập trung vào ứng dụng thực tế.",
                    """
                    Bốn bài trình bày về ứng dụng AI trong vận hành, kèm một phiên hỏi đáp mở.

                    Vé VIP ngồi ba hàng đầu và có suất ăn trưa cùng diễn giả.""",
                    "hoi-thao",
                    "trung-tam-hoi-nghi",
                    poster("hoi-thao-ai"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            27,
                            13,
                            240,
                            30,
                            List.of(
                                    new PriceSpec("VIP", "Vé VIP", 2_200_000),
                                    new PriceSpec("STD", "Vé tiêu chuẩn", 900_000))))),

            // --- Thể thao ---------------------------------------------------------------------

            new EventSpec(
                    "esports-chung-ket-mua-xuan",
                    "Chung kết Esports Mùa Xuân",
                    "Vé đứng khu fanzone và khán đài đánh số.",
                    """
                    Trận chung kết bốn ván thắng ba, có khu fanzone đứng ngay trước sân khấu.

                    Cổng mở trước giờ thi đấu 120 phút.""",
                    "the-thao",
                    "nha-van-hoa-thanh-nien",
                    poster("esports-chung-ket"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            33,
                            17,
                            240,
                            35,
                            List.of(
                                    new PriceSpec("GA", "Fanzone (vé đứng)", 350_000),
                                    new PriceSpec("A", "Khán đài A", 550_000))))),
            new EventSpec(
                    "chung-ket-bong-ro-vba",
                    "Chung kết Bóng rổ VBA",
                    "Trận quyết định chức vô địch.",
                    "Trận chung kết lượt về. Sàn trung tâm là vé đứng, hai khán đài ghế đánh số.",
                    "the-thao",
                    "cung-the-thao-tien-son",
                    poster("bong-ro-vba"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            16,
                            19,
                            140,
                            25,
                            List.of(
                                    new PriceSpec("GA", "Sàn trung tâm (đứng)", 500_000),
                                    new PriceSpec("KA", "Khán đài A", 400_000),
                                    new PriceSpec("KB", "Khán đài B", 300_000))))),
            // Giải chạy: "hạng vé" ở đây là cự ly. Ví dụ tốt cho thấy hạng vé không nhất thiết là
            // chỗ ngồi đẹp hay xấu — nó chỉ là một khu của địa điểm với một mức giá.
            new EventSpec(
                    "marathon-da-nang",
                    "Giải chạy Marathon Đà Nẵng",
                    "Hai cự ly, xuất phát từ Công viên Biển Đông.",
                    """
                    Cự ly 21km xuất phát 4h30, cự ly 5km xuất phát 5h30, cùng về đích tại Công viên Biển Đông.

                    Vé gồm áo, số BIB, huy chương về đích và nước dọc đường.""",
                    "the-thao",
                    "cong-vien-bien-dong",
                    poster("marathon-da-nang"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            50,
                            5,
                            300,
                            60,
                            List.of(
                                    new PriceSpec("VIP", "Cự ly 21km", 800_000),
                                    new PriceSpec("GA", "Cự ly 5km", 350_000))))),
            new EventSpec(
                    "derby-thanh-pho",
                    "Derby Thành Phố",
                    "Vòng 12 giải vô địch quốc gia.",
                    "Trận đấu vòng 12. Khán đài A và B là ghế đánh số, khán đài C vé đứng.",
                    "the-thao",
                    "san-thong-nhat",
                    poster("derby-thanh-pho"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            9,
                            18,
                            120,
                            30,
                            List.of(
                                    new PriceSpec("A", "Khán đài A", 300_000),
                                    new PriceSpec("B", "Khán đài B", 250_000),
                                    new PriceSpec("C", "Khán đài C (đứng)", 150_000))))),

            // --- Nhạc sống --------------------------------------------------------------------

            new EventSpec(
                    "indie-night-song-huong",
                    "Indie Night · Sông Hương",
                    "Ba ban nhạc độc lập, một đêm.",
                    """
                    Ba ban nhạc độc lập chơi liên tiếp, mỗi ban 40 phút, không nghỉ giữa các set.

                    Sân khấu nhỏ, âm thanh mộc.""",
                    "nhac-song",
                    "nha-hat-song-huong",
                    poster("indie-night"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            15,
                            20,
                            140,
                            25,
                            List.of(
                                    new PriceSpec("A", "Khu chính", 420_000),
                                    new PriceSpec("B", "Khu sau", 300_000))))),
            new EventSpec(
                    "bolero-chuyen-tinh-khong-di-vang",
                    "Live Show Bolero · Chuyện Tình Không Dĩ Vãng",
                    "Hai đêm liên tiếp, dàn nhạc sống.",
                    """
                    Chương trình bolero với dàn nhạc sống mười hai nhạc công và ba khách mời.

                    Thời lượng 135 phút, có giải lao 15 phút.""",
                    "nhac-song",
                    "nha-hat-hoa-binh",
                    poster("bolero"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(
                                    31,
                                    19,
                                    135,
                                    40,
                                    List.of(
                                            new PriceSpec("A", "Tầng trệt", 1_100_000),
                                            new PriceSpec("B", "Tầng lửng", 750_000),
                                            new PriceSpec("C", "Ban công", 450_000))),
                            new SessionSpec(
                                    32,
                                    19,
                                    135,
                                    40,
                                    List.of(
                                            new PriceSpec("A", "Tầng trệt", 1_100_000),
                                            new PriceSpec("B", "Tầng lửng", 750_000),
                                            new PriceSpec("C", "Ban công", 450_000))))),
            new EventSpec(
                    "dem-nhac-trinh",
                    "Đêm nhạc Trịnh · Ru Đời Đi Nhé",
                    "Chương trình tưởng niệm, dàn dây và piano.",
                    """
                    Hai mươi ca khúc quen thuộc, phối lại cho dàn dây và piano.

                    Thời lượng 120 phút, không giải lao.""",
                    "nhac-song",
                    "nha-hat-hoa-binh",
                    poster("dem-nhac-trinh"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            25,
                            19,
                            120,
                            35,
                            List.of(
                                    new PriceSpec("A", "Tầng trệt", 950_000),
                                    new PriceSpec("B", "Tầng lửng", 680_000),
                                    new PriceSpec("C", "Ban công", 400_000))))),
            new EventSpec(
                    "acoustic-dem-ha-noi",
                    "Acoustic · Đêm Hà Nội",
                    "Ba đêm liên tiếp, sân khấu nhỏ.",
                    """
                    Chương trình acoustic ba đêm liên tiếp cùng danh sách bài, mỗi đêm một khách mời khác.

                    Sân khấu nhỏ, không dùng màn hình LED.""",
                    "nhac-song",
                    "nha-hat-tuoi-tre",
                    poster("acoustic-dem-ha-noi"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(
                                    6,
                                    20,
                                    100,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 480_000),
                                            new PriceSpec("B", "Khu sau", 330_000))),
                            new SessionSpec(
                                    7,
                                    20,
                                    100,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 480_000),
                                            new PriceSpec("B", "Khu sau", 330_000))),
                            new SessionSpec(
                                    8,
                                    20,
                                    100,
                                    20,
                                    List.of(
                                            new PriceSpec("A", "Khu chính", 480_000),
                                            new PriceSpec("B", "Khu sau", 330_000))))),

            // CHƯA TỚI NGÀY MỞ BÁN: cửa bán mở trước giờ diễn 20 ngày, mà giờ diễn còn 70 ngày nữa
            // — nên hôm nay Inventory từ chối mọi yêu cầu giữ chỗ với SALES_CLOSED. Giao diện phải
            // hiện "mở bán ngày …" thay vì nút Mua vé, và không có dữ liệu như thế này thì nhánh đó
            // không bao giờ được nhìn thấy.
            new EventSpec(
                    "edm-countdown-night",
                    "EDM Countdown Night",
                    "Chưa mở bán — sự kiện cuối năm.",
                    """
                    Đêm nhạc điện tử với bốn DJ, khu fanzone đứng ngay trước sân khấu.

                    Cửa bán mở trước giờ diễn 20 ngày.""",
                    "nhac-song",
                    "nha-van-hoa-thanh-nien",
                    poster("edm-countdown"),
                    PUBLISHED,
                    List.of(new SessionSpec(
                            70,
                            21,
                            300,
                            20,
                            List.of(
                                    new PriceSpec("GA", "Fanzone (vé đứng)", 600_000),
                                    new PriceSpec("A", "Khán đài A", 900_000))))),
            new EventSpec(
                    "dem-nhac-mua-thu",
                    "Đêm nhạc Mùa Thu",
                    "Đêm nhạc acoustic với dàn nhạc thính phòng.",
                    """
                    Một đêm nhạc acoustic kết hợp dàn dây thính phòng, không dùng nhạc nền thu sẵn.

                    Chương trình kéo dài khoảng 120 phút, không có giải lao.""",
                    "nhac-song",
                    "nha-hat-lon",
                    poster("dem-nhac-mua-thu"),
                    PUBLISHED,
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
                    poster("live-concert-thanh-pho"),
                    PUBLISHED,
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
                    "rock-fest-bien-dong",
                    "Rock Fest · Biển Đông",
                    "Hai ngày ngoài trời, toàn bộ là vé đứng.",
                    """
                    Hai ngày, mười hai ban nhạc, hai sân khấu chạy luân phiên từ 18h tới nửa đêm.

                    Toàn bộ khu vực là vé đứng — không có sơ đồ chỗ, khách chọn số lượng vé theo khu.""",
                    "nhac-song",
                    "cong-vien-bien-dong",
                    poster("rock-fest-bien-dong"),
                    PUBLISHED,
                    List.of(
                            new SessionSpec(
                                    44,
                                    18,
                                    360,
                                    50,
                                    List.of(
                                            new PriceSpec("VIP", "Vé VIP ngày 1", 1_800_000),
                                            new PriceSpec("GA", "Vé phổ thông ngày 1", 750_000))),
                            new SessionSpec(
                                    45,
                                    18,
                                    360,
                                    50,
                                    List.of(
                                            new PriceSpec("VIP", "Vé VIP ngày 2", 1_800_000),
                                            new PriceSpec("GA", "Vé phổ thông ngày 2", 750_000))))));
}
