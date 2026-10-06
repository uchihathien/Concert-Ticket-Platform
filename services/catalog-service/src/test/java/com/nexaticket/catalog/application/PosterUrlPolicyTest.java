// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.application.media.PosterUrlPolicy;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import com.nexaticket.platform.web.error.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Luật của {@code events.poster_url}.
 *
 * <p>Đây là chỗ duy nhất quyết định một đường dẫn ảnh có được lưu hay không, và nó canh ba thứ
 * khác nhau: scheme, quyền của host, và vật thể thật trong kho. Kiểm bằng cổng giả — không cần
 * MinIO, chạy trong mili giây.
 */
class PosterUrlPolicyTest {

    private static final String PUBLIC_BASE = "https://cdn.nexaticket.vn";
    private static final String BUCKET = "nexaticket-media";
    private static final String OWN_PREFIX = PUBLIC_BASE + "/" + BUCKET + "/";

    @Test
    @DisplayName("null là không đổi gì, chuỗi rỗng là xoá ảnh — hai thứ KHÁC nhau")
    void null_khac_chuoi_rong() {
        PosterUrlPolicy policy = policy(props(true), new FakeStorage());

        // Gộp hai giá trị này thành null sẽ làm nút "xoá ảnh bìa" im lặng không có tác dụng, vì
        // Event.rename chỉ ghi đè khi giá trị khác null.
        assertThat(policy.validate(null)).isNull();
        assertThat(policy.validate("")).isEmpty();
        assertThat(policy.validate("   ")).isEmpty();
    }

    @Test
    @DisplayName("http bị từ chối — trình duyệt chặn im lặng ảnh http trên trang https")
    void tu_choi_http() {
        PosterUrlPolicy policy = policy(props(true), new FakeStorage());

        assertThatThrownBy(() -> policy.validate("http://anh-ngoai.example/poster.jpg"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("ảnh ngoài bị từ chối khi cấu hình tắt")
    void tu_choi_anh_ngoai_khi_tat() {
        // Ở production nên tắt: mọi khách xem trang tải ảnh thẳng từ host đó, nên host ấy thấy
        // được IP của từng người.
        PosterUrlPolicy policy = policy(props(false), new FakeStorage());

        assertThatThrownBy(() -> policy.validate("https://anh-ngoai.example/poster.jpg"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("ảnh ngoài đi qua khi cấu hình bật, và KHÔNG bị hỏi kho vật thể")
    void cho_anh_ngoai_khi_bat() {
        FakeStorage storage = new FakeStorage();
        PosterUrlPolicy policy = policy(props(true), storage);

        assertThat(policy.validate("https://anh-ngoai.example/poster.jpg"))
                .isEqualTo("https://anh-ngoai.example/poster.jpg");
        // Hỏi kho về một khoá không thuộc kho là một lời gọi mạng chắc chắn không tìm thấy gì.
        assertThat(storage.statCalls).isEmpty();
    }

    @Test
    @DisplayName("ảnh của mình nhưng chưa ai tải lên thì bị từ chối")
    void tu_choi_khi_chua_tai_len() {
        // Đường này không cần ác ý: xin được URL tải lên rồi mạng rớt giữa chừng là đủ. Không
        // chặn thì sự kiện lên trang với một tấm ảnh 404.
        PosterUrlPolicy policy = policy(props(true), new FakeStorage());

        assertThatThrownBy(() -> policy.validate(OWN_PREFIX + "posters/org/abc.jpg"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Chưa có ảnh");
    }

    @Test
    @DisplayName("ảnh vượt trần bị từ chối VÀ bị xoá khỏi kho")
    void xoa_anh_qua_co() {
        FakeStorage storage = new FakeStorage();
        String key = "posters/org/to-qua.jpg";
        storage.put(key, 20L * 1024 * 1024, "image/jpeg");

        PosterUrlPolicy policy = policy(props(true), storage);

        assertThatThrownBy(() -> policy.validate(OWN_PREFIX + key)).isInstanceOf(ApiException.class);
        // Xoá là phần quan trọng: vật thể này không còn gì trỏ tới, để lại là để rác tích tụ mà
        // không ai biết đường dọn.
        assertThat(storage.deleted).containsExactly(key);
    }

    @Test
    @DisplayName("định dạng lạ bị từ chối và cũng bị xoá")
    void xoa_dinh_dang_la() {
        FakeStorage storage = new FakeStorage();
        String key = "posters/org/khong-phai-anh.jpg";
        // Đuôi file nói là .jpg nhưng kiểu thật thì không — đuôi do người dùng đặt, kiểu thật do
        // kho vật thể ghi lại.
        storage.put(key, 1024, "application/x-msdownload");

        PosterUrlPolicy policy = policy(props(true), storage);

        assertThatThrownBy(() -> policy.validate(OWN_PREFIX + key)).isInstanceOf(ApiException.class);
        assertThat(storage.deleted).containsExactly(key);
    }

    @Test
    @DisplayName("ảnh hợp lệ của mình đi qua nguyên vẹn")
    void chap_nhan_anh_hop_le() {
        FakeStorage storage = new FakeStorage();
        String key = "posters/org/ok.png";
        storage.put(key, 2L * 1024 * 1024, "image/png");

        PosterUrlPolicy policy = policy(props(true), storage);

        assertThat(policy.validate(OWN_PREFIX + key)).isEqualTo(OWN_PREFIX + key);
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    @DisplayName("ảnh SVG hệ thống tự vẽ đi qua — và KHÔNG bị xoá")
    void chap_nhan_svg_tu_sinh() {
        FakeStorage storage = new FakeStorage();
        String key = "posters/demo/dem-nhac.svg";
        storage.put(key, 4096, MediaProperties.GENERATED_CONTENT_TYPE);

        PosterUrlPolicy policy = policy(props(true), storage);

        // Bộ dựng dữ liệu mẫu đẩy lên SVG. Nếu chính sách coi đó là định dạng lạ thì nó xoá vật
        // thể rồi ném lỗi — nghĩa là hệ thống tự tay xoá tấm ảnh mà chính nó vừa vẽ ra, và sự
        // kiện mẫu mất ảnh ngay lần đầu có ai lưu lại nó.
        assertThat(policy.validate(OWN_PREFIX + key)).isEqualTo(OWN_PREFIX + key);
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    @DisplayName("SVG vẫn KHÔNG được phép tải lên từ trình duyệt")
    void svg_khong_nam_trong_danh_sach_tai_len() {
        // Hai danh sách phải tách nhau. SVG là tài liệu XML, mang được <script>, và nó chạy dưới
        // origin của kho ảnh với bất kỳ ai mở thẳng đường dẫn. Cho tải lên là mở một đường XSS
        // lưu trữ; chỉ cho tồn tại thì vật thể SVG chỉ có thể do máy chủ tự tạo.
        MediaProperties properties = props(true);

        assertThat(properties.isAllowedType(MediaProperties.GENERATED_CONTENT_TYPE))
                .isFalse();
        assertThat(properties.isStorableType(MediaProperties.GENERATED_CONTENT_TYPE))
                .isTrue();
        assertThat(properties.isStorableType("application/x-msdownload")).isFalse();
    }

    @Test
    @DisplayName("ảnh SVG quá cỡ vẫn bị chặn như mọi ảnh khác")
    void svg_van_chiu_tran_kich_thuoc() {
        // Miễn kiểm định dạng không có nghĩa là miễn kiểm kích thước.
        FakeStorage storage = new FakeStorage();
        String key = "posters/demo/khong-lo.svg";
        storage.put(key, 20L * 1024 * 1024, MediaProperties.GENERATED_CONTENT_TYPE);

        PosterUrlPolicy policy = policy(props(true), storage);

        assertThatThrownBy(() -> policy.validate(OWN_PREFIX + key)).isInstanceOf(ApiException.class);
        assertThat(storage.deleted).containsExactly(key);
    }

    // --- dựng dữ liệu -------------------------------------------------------

    private static PosterUrlPolicy policy(MediaProperties properties, ObjectStoragePort storage) {
        return new PosterUrlPolicy(storage, properties);
    }

    private static MediaProperties props(boolean allowExternal) {
        return new MediaProperties(
                "http://minio:9000",
                PUBLIC_BASE,
                BUCKET,
                "key",
                "secret",
                "us-east-1",
                8L * 1024 * 1024,
                List.of("image/jpeg", "image/png", "image/webp"),
                allowExternal);
    }

    private static final class FakeStorage implements ObjectStoragePort {
        private final Map<String, StoredObject> objects = new HashMap<>();
        final List<String> statCalls = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();

        void put(String key, long size, String contentType) {
            objects.put(key, new StoredObject(size, contentType));
        }

        @Override
        public PresignedUpload presignUpload(String key, String contentType) {
            return new PresignedUpload(URI.create("https://upload.example/" + key), key, Instant.now());
        }

        @Override
        public void put(String key, String contentType, byte[] content) {
            objects.put(key, new StoredObject(content.length, contentType));
        }

        @Override
        public Optional<StoredObject> stat(String key) {
            statCalls.add(key);
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void delete(String key) {
            deleted.add(key);
        }

        @Override
        public URI publicUrl(String key) {
            return URI.create(OWN_PREFIX + key);
        }

        @Override
        public boolean isOwnUrl(String url) {
            return url != null && url.startsWith(OWN_PREFIX);
        }
    }
}
