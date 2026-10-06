// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Ảnh chụp thật cho từng sự kiện mẫu.
 *
 * <h2>Vì sao có bảng này bên cạnh {@link PosterArtwork}</h2>
 *
 * <p>{@code PosterArtwork} vẽ ra một tấm bìa <b>chắc chắn có</b>: không cần mạng, không cần giấy
 * phép, mỗi sự kiện một dải màu riêng. Nó giải quyết bài toán "trang không được trống". Nhưng nó
 * không giải quyết được bài toán còn lại: người xem demo cần thấy hệ thống trông thế nào <b>khi đã
 * có ảnh thật</b> — bố cục thẻ sự kiện, dải nền mờ ở đầu trang chi tiết, dải tranh trên vé xuất ra,
 * tất cả đều được thiết kế cho ảnh chụp chứ không cho hình khối phẳng.
 *
 * <p>Nên hai đường cùng tồn tại: bảng này là đường thứ nhất, {@code PosterArtwork} là đường dự
 * phòng khi không tải được (xem {@link DemoPosterSeeder}).
 *
 * <h2>Giấy phép</h2>
 *
 * <p>Toàn bộ ảnh ở đây thuộc <b>Unsplash License</b>: dùng được cho cả mục đích thương mại, không
 * cần xin phép, không buộc ghi nguồn — nhưng tên tác giả vẫn được ghi trong {@code credit} vì giấy
 * phép có khuyến khích, và vì một mã ảnh trần trụi thì sáu tháng sau không ai truy được nó đến từ
 * đâu.
 *
 * <p><b>Cố ý KHÔNG lấy poster của nghệ sĩ hay của sự kiện có thật.</b> Những ảnh ấy có bản quyền,
 * và một đồ án đem chúng đi trình bày là mang theo rủi ro không cần thiết — trong khi ảnh sân khấu,
 * ảnh khán giả ở đây phục vụ đúng cùng một mục đích trình bày.
 *
 * <h2>Vì sao tải về kho của mình chứ không nhúng thẳng URL</h2>
 *
 * <p>Ghi thẳng {@code images.unsplash.com} vào {@code poster_url} thì mỗi khách xem trang là một
 * lượt gọi ra host ngoài — host ấy đếm được IP của mọi người xem, còn mình thì phụ thuộc vào việc
 * họ còn phục vụ đường dẫn đó. Nó cũng bị {@code PosterUrlPolicy} từ chối khi
 * {@code MEDIA_ALLOW_EXTERNAL_URLS=false}. Nên byte được tải <b>một lần lúc dựng dữ liệu</b> rồi
 * nằm trong kho vật thể của hệ thống, giống hệt ảnh do ban tổ chức tải lên.
 *
 * <h2>Ghép ảnh với sự kiện theo họ thể loại, không theo từng khung hình</h2>
 *
 * <p>Nhạc phòng trà, jazz, acoustic nhận ảnh sân khấu gần; đại nhạc hội, EDM, rock nhận ảnh biển
 * khán giả; sân khấu nhận ảnh nhà hát; thể thao nhận ảnh sân vận động; hội thảo nhận ảnh diễn giả.
 * Không sự kiện nào dùng chung ảnh với sự kiện khác — nên trong báo cáo không có hai thẻ trông
 * giống nhau.
 */
final class DemoPosterPhotos {

    private DemoPosterPhotos() {}

    /**
     * Một tấm ảnh.
     *
     * @param id mã ảnh Unsplash, ví dụ {@code photo-1459749411175-04bf5292ceea}
     * @param credit tên tác giả, để truy nguồn về sau
     */
    record Photo(String id, String credit) {

        /**
         * Địa chỉ tải về.
         *
         * <p>Tham số cắt ảnh nằm ở phía Unsplash chứ không cắt lại bằng Java: ảnh gốc thường rộng
         * 4000px và nặng vài MB, mà mọi chỗ dùng trong hệ thống đều là khung 16:9 rộng tối đa
         * 1200px. Tải bản đã cắt về nghĩa là không phải xử lý ảnh trong JVM, và cũng không kéo về
         * 3,8 MB để rồi bỏ đi gần hết.
         */
        URI url() {
            return URI.create("https://images.unsplash.com/%s?w=1200&h=675&fit=crop&q=75&fm=jpg".formatted(id));
        }
    }

    /** Kiểu nội dung của thứ {@link Photo#url()} trả về — do tham số {@code fm=jpg} quyết định. */
    static final String CONTENT_TYPE = "image/jpeg";

    private static final Map<String, Photo> BY_SLUG = Map.ofEntries(
            // --- Nhạc sống: sân khấu gần -----------------------------------------------------
            Map.entry("dem-jazz-cuoi-nam", new Photo("photo-1522158637959-30385a09e0da", "Rachel Coyne")),
            Map.entry("indie-night-song-huong", new Photo("photo-1563841930606-67e2bce48b78", "Muneeb S")),
            Map.entry(
                    "bolero-chuyen-tinh-khong-di-vang", new Photo("photo-1509824227185-9c5a01ceba0d", "Jordon Conner")),
            Map.entry("dem-nhac-trinh", new Photo("photo-1450044804117-534ccd6e6a3a", "Abigail Lynn")),
            Map.entry("acoustic-dem-ha-noi", new Photo("photo-1511671782779-c97d3d27a1d4", "israel palacio")),
            Map.entry("live-concert-thanh-pho", new Photo("photo-1470229722913-7c0e2dbbafd3", "Yvette de Wit")),
            Map.entry("dem-nhac-phong-tra-thang-muoi", new Photo("photo-1590721791974-d6c8ca43f6bc", "Kelvin Moquete")),
            Map.entry("live-concert-bao-giong", new Photo("photo-1493225457124-a3eb161ffa5f", "Austin Neill")),
            Map.entry("dem-nhac-trinh-mua-dong", new Photo("photo-1524368535928-5b5e00ddc76b", "Vishnu R Nair")),
            Map.entry("dem-nhac-acoustic-hai-phong", new Photo("photo-1540039155733-5bb30b53aa14", "ActionVance")),
            Map.entry("gala-cuoi-nam-phong-tra", new Photo("photo-1459749411175-04bf5292ceea", "Nainoa Shizuru")),

            // --- Nhạc sống: biển khán giả ----------------------------------------------------
            Map.entry("dai-nhac-hoi-mua-he", new Photo("photo-1501386761578-eac5c94b800a", "Nicholas Green")),
            Map.entry("edm-countdown-night", new Photo("photo-1520095972714-909e91b038e5", "Krists Luhaers")),
            Map.entry("dem-nhac-mua-thu", new Photo("photo-1472653816316-3ad6f10a6592", "Aranxa Esteve")),
            Map.entry("rock-fest-bien-dong", new Photo("photo-1619229725920-ac8b63b0631a", "Colin Lloyd")),
            Map.entry("rock-fest-mien-trung", new Photo("photo-1454908027598-28c44b1716c1", "Joey Thompson")),

            // --- Sân khấu --------------------------------------------------------------------
            Map.entry("giao-huong-mua-dong", new Photo("photo-1503095396549-807759245b35", "Kyle Head")),
            Map.entry("kich-thieu-nhi-tam-cam", new Photo("photo-1610890690846-5149750c8634", "Paolo Chiabrando")),
            Map.entry("vo-kich-nguoi-tot", new Photo("photo-1571173069043-82a7a13cee9f", "antonio molinari")),
            Map.entry("cai-luong-tieng-trong-me-linh", new Photo("photo-1576544403918-c47d52572a9a", "Cyrus Crossan")),
            Map.entry("stand-up-comedy-cuoi-vo-bung", new Photo("photo-1651437524278-b37b83a6e6d3", "Muha Ajjan")),
            Map.entry("ballet-ho-thien-nga", new Photo("photo-1621873493371-9aea49f66b9b", "Wesley Pribadi")),
            Map.entry("hoa-nhac-mua-thu", new Photo("photo-1571689298871-7ab64cebbc5f", "Hulki Okan Tabak")),
            Map.entry("kich-noi-nguoi-o-lai", new Photo("photo-1690074430713-8d2516e65a63", "Don Starkey")),

            // --- Thể thao --------------------------------------------------------------------
            Map.entry("giao-huu-quoc-te", new Photo("photo-1522778119026-d647f0596c20", "Vienna Reyes")),
            Map.entry("esports-chung-ket-mua-xuan", new Photo("photo-1629217855633-79a6925d6c47", "Krzysztof Dubiel")),
            Map.entry("chung-ket-bong-ro-vba", new Photo("photo-1431324155629-1a6deb1dec8d", "Abigail Keenan")),
            Map.entry("marathon-da-nang", new Photo("photo-1599158150601-1417ebbaafdd", "dominik hofbauer")),
            Map.entry("derby-thanh-pho", new Photo("photo-1489944440615-453fc2b6a9a9", "Mario Klassen")),
            Map.entry("chung-ket-giai-bong-ro", new Photo("photo-1434648957308-5e6a859697e8", "Fancy Crave")),

            // --- Hội thảo --------------------------------------------------------------------
            Map.entry("hoi-thao-fintech-2026", new Photo("photo-1540575467063-178a50c2df87", "Headway")),
            Map.entry("workshop-nhiep-anh", new Photo("photo-1626125345510-4603468eedfb", "Terren Hurst")),
            Map.entry(
                    "dien-dan-khoi-nghiep-mien-trung",
                    new Photo("photo-1587825140708-dfaf72ae4b04", "Alexandre Pellaes")),
            Map.entry("hoi-thao-cong-nghe", new Photo("photo-1475721027785-f74eccf877e2", "Kane Reinholdtsen")),
            Map.entry("hoi-thao-ai-doanh-nghiep", new Photo("photo-1594122230689-45899d9e6f69", "Wan San Yip")),
            Map.entry("hoi-thao-cong-nghe-am-thanh", new Photo("photo-1582192730841-2a682d7375f9", "Product School")));

    /**
     * Ảnh của một sự kiện mẫu.
     *
     * <p>Rỗng là trường hợp <b>bình thường</b>, không phải lỗi: một sự kiện mẫu mới thêm vào
     * {@link DemoData} mà chưa chọn ảnh vẫn phải dựng được, và nó rơi về bìa tự vẽ.
     */
    static Optional<Photo> forSlug(String slug) {
        return Optional.ofNullable(BY_SLUG.get(slug));
    }

    /** Dùng cho phần kiểm: không sự kiện nào được dùng chung ảnh với sự kiện khác. */
    static Map<String, Photo> all() {
        return BY_SLUG;
    }
}
