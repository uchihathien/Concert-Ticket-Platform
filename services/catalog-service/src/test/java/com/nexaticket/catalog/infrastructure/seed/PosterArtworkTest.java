// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ảnh bìa tự vẽ cho sự kiện mẫu.
 *
 * <p>Cách hỏng của một file SVG rất đặc biệt: trình duyệt <b>bỏ hẳn ảnh</b> và không báo gì. Không
 * có thông báo lỗi, không có ô vỡ — chỉ là một khoảng trống. Nên bài kiểm chính ở đây là "phân
 * tích được bằng bộ đọc XML thật", chứ không phải so chuỗi.
 */
class PosterArtworkTest {

    @Test
    @DisplayName("kết quả là XML hợp lệ")
    void la_xml_hop_le() {
        String svg = PosterArtwork.svg("Đại nhạc hội Mùa Hè", "nhac-song", "Đà Nẵng", "dai-nhac-hoi");

        assertThatCode(() -> parse(svg)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dấu & trong tên không làm vỡ cả file")
    void thoat_ky_tu_xml() {
        // Đây là lý do hàm escape tồn tại. Một tên như "Rock & Roll" chưa thoát là đủ để SVG thành
        // XML sai cú pháp, và hậu quả là tấm ảnh biến mất trong im lặng.
        String svg = PosterArtwork.svg("Rock & Roll <Live>", "nhac-song", "Hà Nội", "rock");

        assertThatCode(() -> parse(svg)).doesNotThrowAnyException();
        assertThat(svg).contains("Rock &amp; Roll &lt;Live&gt;").doesNotContain("Rock & Roll <Live>");
    }

    @Test
    @DisplayName("mỗi thể loại một bảng màu — nhìn là nhận ra loại sự kiện")
    void moi_the_loai_mot_mau() {
        String nhac = PosterArtwork.svg("A", "nhac-song", "Hà Nội", "a");
        String hoiThao = PosterArtwork.svg("A", "hoi-thao", "Hà Nội", "a");
        String theThao = PosterArtwork.svg("A", "the-thao", "Hà Nội", "a");

        // Cùng tên, cùng seed, khác thể loại ⇒ phải khác nhau. Giống nhau nghĩa là bảng màu không
        // được áp, và cả trang danh sách trở thành một dải ảnh đơn sắc.
        assertThat(nhac).isNotEqualTo(hoiThao);
        assertThat(hoiThao).isNotEqualTo(theThao);
    }

    @Test
    @DisplayName("thể loại lạ vẫn vẽ ra ảnh, không vỡ")
    void the_loai_la_van_ve_duoc() {
        // Cột `category` là TEXT tự do, không ràng buộc — một sự kiện cũ có thể mang giá trị không
        // nằm trong danh sách nào.
        assertThatCode(() -> parse(PosterArtwork.svg("A", "mot-the-loai-la", "Huế", "x")))
                .doesNotThrowAnyException();
        assertThatCode(() -> parse(PosterArtwork.svg("A", null, "Huế", "x"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cùng một sự kiện luôn cho ra cùng một ảnh")
    void on_dinh_theo_seed() {
        // Ổn định quan trọng hơn đẹp: ảnh đổi sau mỗi lần dựng lại database sẽ làm mọi ảnh chụp
        // màn hình trong tài liệu lệch đi, và bộ dựng mẫu thì chạy lại ở mỗi lần khởi động.
        String first = PosterArtwork.svg("Đêm nhạc", "nhac-song", "Hà Nội", "dem-nhac");
        String second = PosterArtwork.svg("Đêm nhạc", "nhac-song", "Hà Nội", "dem-nhac");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("tên dài xuống dòng theo từ và dừng ở ba dòng")
    void ten_dai_cat_o_ba_dong() {
        String svg = PosterArtwork.svg(
                "Đại nhạc hội mùa hè quy tụ sáu nghệ sĩ và một ban nhạc khách mời đặc biệt",
                "nhac-song",
                "Đà Nẵng",
                "dai");

        // Dòng thứ tư đè lên phần thông tin phía dưới. Ba dòng tiêu đề + một dòng thành phố +
        // dòng thương hiệu = 5 phần tử <text>.
        assertThat(svg.split("<text", -1).length - 1).isEqualTo(5);
    }

    @Test
    @DisplayName("không có thành phố thì bỏ hẳn dòng đó, không để trống")
    void thieu_thanh_pho_thi_bo_dong() {
        String svg = PosterArtwork.svg("Đêm nhạc", "nhac-song", null, "x");

        // Một dòng trống chừa chỗ cho dữ liệu không có làm bố cục trông như bị lỗi căn lề.
        assertThat(svg.split("<text", -1).length - 1).isEqualTo(2);
    }

    @Test
    @DisplayName("đội hình nghệ sĩ hiện lên poster, cắt ở câu đầu")
    void doi_hinh_nghe_si_hien_len() {
        String svg = PosterArtwork.svg(
                "Đêm nhạc phòng trà",
                "nhac-song",
                "TP. Hồ Chí Minh",
                "Mây Lặng, Hạ Vũ và ban nhạc Sông Cạn. Ngồi bàn, phục vụ đồ uống tại chỗ.",
                "dem-nhac");

        // Câu đầu là chỗ tên nghệ sĩ nằm; phần còn lại viết cho trang chi tiết, không có chỗ trên
        // poster.
        assertThat(svg).contains("Mây Lặng, Hạ Vũ và ban nhạc Sông Cạn").doesNotContain("phục vụ đồ uống");
    }

    @Test
    @DisplayName("không có đội hình thì bỏ hẳn dòng đó")
    void khong_co_doi_hinh_thi_bo_dong() {
        // Một dòng trống chừa chỗ cho dữ liệu không có làm bố cục trông như lỗi căn lề.
        String withLineup = PosterArtwork.svg("Đêm nhạc", "nhac-song", "Huế", "Mây Lặng.", "x");
        String without = PosterArtwork.svg("Đêm nhạc", "nhac-song", "Huế", null, "x");

        assertThat(count(withLineup, "<text")).isEqualTo(count(without, "<text") + 1);
    }

    @Test
    @DisplayName("chữ không đè lên nhau: mọi dòng cách nhau ít nhất 24 đơn vị")
    void chu_khong_de_len_nhau() {
        // Poster dựng từ đáy lên, và mỗi phần tự dịch chỗ theo phần dưới nó. Một phép trừ sai ở đó
        // cho ra hai dòng chữ chồng khít — vẫn là SVG hợp lệ, vẫn không có lỗi nào.
        String svg = PosterArtwork.svg(
                "Đại nhạc hội mùa hè quy tụ sáu nghệ sĩ",
                "nhac-song",
                "Đà Nẵng",
                "Bão Giông, Lê Trăng, DJ Mộc và dàn nhạc ba mươi người.",
                "dai");

        java.util.List<Integer> ys = baselines(svg);

        assertThat(ys).hasSizeGreaterThan(3);
        for (int i = 1; i < ys.size(); i++) {
            assertThat(ys.get(i) - ys.get(i - 1))
                    .as("khoảng cách giữa dòng %d và %d", i - 1, i)
                    .isGreaterThanOrEqualTo(24);
        }
    }

    /** Toạ độ y của mọi dòng chữ, đã sắp xếp. Tách chuỗi chứ không regex — ít lớp thoát hơn. */
    private static java.util.List<Integer> baselines(String svg) {
        java.util.List<Integer> ys = new java.util.ArrayList<>();
        String[] chunks = svg.split("<text ");
        // Bỏ phần tử 0: đó là mọi thứ TRƯỚC thẻ <text đầu tiên, và nó chứa y= của gradient.
        for (int c = 1; c < chunks.length; c++) {
            // Chỉ đọc trong phạm vi chính thẻ ấy, không lấn sang phần sau nó.
            String chunk = chunks[c].substring(0, chunks[c].indexOf('>'));
            int at = chunk.indexOf("y=\"");
            if (at < 0) {
                continue;
            }
            int from = at + 3;
            int to = chunk.indexOf('"', from);
            ys.add(Integer.parseInt(chunk.substring(from, to)));
        }
        java.util.Collections.sort(ys);
        return ys;
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static void parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Tắt DTD: ta chỉ kiểm cú pháp, và một bộ đọc XML đi tải DTD ngoài là một lỗ hổng XXE
        // kinh điển — kể cả trong test.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
