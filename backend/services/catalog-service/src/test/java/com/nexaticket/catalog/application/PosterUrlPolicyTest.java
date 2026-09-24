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
