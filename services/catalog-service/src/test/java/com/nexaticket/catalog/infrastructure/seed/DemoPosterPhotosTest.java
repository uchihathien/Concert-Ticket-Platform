// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bảng ảnh chụp cho sự kiện mẫu.
 *
 * <p>Cách hỏng của bảng này là <b>im lặng</b>: một slug viết sai không làm gì vỡ, nó chỉ khiến sự
 * kiện ấy lặng lẽ nhận bìa tự vẽ, và không ai nhận ra cho tới lúc nhìn kỹ trang chủ trong buổi bảo
 * vệ. Nên phần kiểm ở đây đối chiếu bảng với {@link DemoData} chứ không kiểm nội dung ảnh.
 *
 * <p>Không có bài nào gọi mạng. Ảnh có tải được hay không là chuyện của máy chạy, và
 * {@link DemoPosterSeeder} đã có đường tụt xuống bìa tự vẽ cho chuyện đó.
 */
class DemoPosterPhotosTest {

    @Test
    @DisplayName("mọi slug trong bảng đều là sự kiện mẫu có thật")
    void khong_co_slug_viet_sai() {
        List<String> demoSlugs =
                DemoData.EVENTS.stream().map(DemoCatalogSeeder.EventSpec::slug).toList();

        // Đây là chiều nguy hiểm. Chiều ngược lại — sự kiện chưa chọn ảnh — là hợp lệ và có đường
        // xử lý riêng, nên không kiểm.
        assertThat(DemoPosterPhotos.all().keySet()).allSatisfy(slug -> assertThat(demoSlugs)
                .as("slug %s không có trong DemoData", slug)
                .contains(slug));
    }

    @Test
    @DisplayName("không sự kiện nào dùng chung ảnh với sự kiện khác")
    void moi_su_kien_mot_anh_rieng() {
        List<String> ids = DemoPosterPhotos.all().values().stream()
                .map(DemoPosterPhotos.Photo::id)
                .toList();

        // Trùng ảnh không làm gì vỡ, nhưng hai thẻ giống nhau cạnh nhau trên trang chủ trông như
        // dữ liệu bị lặp — đúng ấn tượng không nên để lại.
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("bốn thể loại của dữ liệu mẫu đều có ảnh")
    void du_bon_the_loai() {
        Map<String, String> categoryBySlug = DemoData.EVENTS.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DemoCatalogSeeder.EventSpec::slug, DemoCatalogSeeder.EventSpec::category));

        List<String> covered = DemoPosterPhotos.all().keySet().stream()
                .map(categoryBySlug::get)
                .distinct()
                .toList();

        // Thiếu cả một thể loại thì trang chủ có một dải thẻ toàn bìa tự vẽ nằm giữa những thẻ có
        // ảnh — dễ bị hiểu thành lỗi tải ảnh chứ không phải một khoảng trống trong dữ liệu mẫu.
        assertThat(covered).containsExactlyInAnyOrder("nhac-song", "san-khau", "the-thao", "hoi-thao");
    }

    @Test
    @DisplayName("địa chỉ tải về đã cắt sẵn khung 16:9 và hạn chất lượng")
    void url_cat_san_anh() {
        String url =
                DemoPosterPhotos.forSlug("dem-nhac-trinh").orElseThrow().url().toString();

        // Thiếu mấy tham số này thì mỗi tấm là ảnh gốc vài MB: vượt trần kho ảnh, và kéo về xong
        // cũng bị bỏ đi gần hết vì mọi khung dùng trong hệ thống đều rộng tối đa 1200px.
        assertThat(url)
                .startsWith("https://images.unsplash.com/photo-")
                .contains("w=1200")
                .contains("h=675")
                .contains("fit=crop")
                .contains("fm=jpg");
    }

    @Test
    @DisplayName("ảnh nào cũng ghi tên tác giả")
    void luon_co_ten_tac_gia() {
        // Giấy phép Unsplash không buộc ghi nguồn, nhưng một mã ảnh trần trụi thì sáu tháng sau
        // không truy được nó đến từ đâu — mà đó lại đúng là lúc cần trả lời câu hỏi về bản quyền.
        assertThat(DemoPosterPhotos.all().values())
                .allSatisfy(photo -> assertThat(photo.credit()).isNotBlank());
    }

    @Test
    @DisplayName("sự kiện chưa chọn ảnh trả về rỗng chứ không ném")
    void su_kien_chua_chon_anh() {
        assertThat(DemoPosterPhotos.forSlug("mot-su-kien-moi-them")).isEmpty();
    }
}
