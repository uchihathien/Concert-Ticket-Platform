// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.media;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import com.nexaticket.platform.web.error.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Luật cho {@code events.poster_url}, áp ở <b>một</b> chỗ cho cả hai đường vào.
 *
 * <h2>Vì sao không kiểm bằng annotation</h2>
 *
 * <p>Trước đây trường này chỉ bị chặn độ dài ({@code @Size(max = 500)}). Nhưng nó là một URL sẽ
 * được nhét thẳng vào {@code <img src>} trên trang công khai, nên độ dài là thứ ít quan trọng
 * nhất. Hai hệ quả thật của việc không kiểm:
 *
 * <ul>
 *   <li><b>URL {@code http://} trên trang {@code https}</b> — trình duyệt chặn vì nội dung hỗn
 *       hợp. Ảnh không hiện, không có thông báo nào, và ban tổ chức tưởng mình dán sai.
 *   <li><b>Host lạ</b> — mọi khách xem trang tải ảnh thẳng từ đó, nên host ấy thấy được IP của
 *       từng người. Ảnh cũng biến mất vào ngày chủ host dọn dẹp, mà không ai báo.
 * </ul>
 *
 * <p>Annotation không làm được phần việc chính ở đây: với ảnh của chính mình, luật là "vật thể
 * phải tồn tại, đúng kiểu, đúng cỡ" — một câu hỏi phải đi hỏi kho vật thể.
 *
 * <h2>Đây cũng là chỗ thi hành trần kích thước</h2>
 *
 * <p>URL ký sẵn kiểu {@code PUT} không chặn được kích thước. Nên trần ấy được thi hành ở đây, sau
 * khi byte đã nằm trên đĩa — và vật thể quá cỡ bị <b>xoá</b> ngay, chứ không để lại làm rác mà
 * không có gì trỏ tới.
 */
@Component
public class PosterUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(PosterUrlPolicy.class);

    private final ObjectStoragePort storage;
    private final MediaProperties properties;

    public PosterUrlPolicy(ObjectStoragePort storage, MediaProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Kiểm một URL ảnh bìa trước khi lưu.
     *
     * <h3>Ba giá trị vào, ba giá trị ra — và {@code null} KHÁC chuỗi rỗng</h3>
     *
     * <p>Phân biệt này bắt buộc phải giữ vì {@code Event.rename} là phép <b>patch</b>: nó chỉ ghi
     * đè khi giá trị khác {@code null}. Gộp hai thứ lại thành {@code null} sẽ làm lệnh "xoá ảnh
     * bìa" im lặng không có tác dụng, và ban tổ chức bấm xoá mãi mà ảnh cũ vẫn còn đó.
     *
     * <ul>
     *   <li>{@code null} → {@code null}: không đổi gì (lúc sửa), hoặc không có ảnh (lúc tạo).
     *   <li>chuỗi rỗng/trắng → {@code ""}: <b>xoá</b> ảnh bìa.
     *   <li>URL → chính nó, sau khi đã kiểm.
     * </ul>
     *
     * @throws ApiException khi URL không dùng được
     */
    public String validate(String posterUrl) {
        if (posterUrl == null) {
            return null;
        }
        String trimmed = posterUrl.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        URI uri = parse(trimmed);
        requireHttps(uri, trimmed);

        if (storage.isOwnUrl(trimmed)) {
            return validateOwn(trimmed);
        }
        if (!properties.allowExternalUrls()) {
            throw new ApiException(
                    CatalogErrorCode.POSTER_URL_INVALID,
                    "Ảnh bìa phải được tải lên qua hệ thống, không dán đường dẫn từ nơi khác");
        }
        return trimmed;
    }

    /**
     * Ảnh của chính mình: vật thể phải có thật, đúng kiểu, đúng cỡ.
     *
     * <p>Không có bước này thì ban tổ chức xin được một URL tải lên rồi lưu thẳng URL công khai mà
     * <b>không tải gì cả</b> — sự kiện lên trang với một ảnh 404. Đường ấy không cần ác ý: mạng
     * rớt giữa chừng là đủ.
     */
    private String validateOwn(String url) {
        String key = keyOf(url);

        Optional<ObjectStoragePort.StoredObject> stored = storage.stat(key);
        if (stored.isEmpty()) {
            throw new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "Chưa có ảnh nào được tải lên ở đường dẫn này");
        }

        ObjectStoragePort.StoredObject object = stored.get();
        if (!properties.isAllowedType(object.contentType())) {
            // Xoá trước khi ném: vật thể này không có gì trỏ tới nữa, và để lại là để rác tích tụ
            // mà không ai biết đường dọn.
            storage.delete(key);
            throw new ApiException(
                    CatalogErrorCode.POSTER_URL_INVALID, "Định dạng ảnh không được hỗ trợ: " + object.contentType());
        }
        if (object.sizeBytes() > properties.maxBytes()) {
            storage.delete(key);
            throw new ApiException(
                    CatalogErrorCode.POSTER_TOO_LARGE,
                    "Ảnh nặng %d MB, vượt trần %d MB"
                            .formatted(toMegabytes(object.sizeBytes()), toMegabytes(properties.maxBytes())));
        }
        return url;
    }

    /**
     * Phần khoá nằm sau {@code /bucket/} trong URL công khai.
     *
     * <p>Cắt theo tiền tố chứ không phân tích đường dẫn: {@link ObjectStoragePort#isOwnUrl} đã
     * khẳng định URL bắt đầu đúng bằng tiền tố ấy, nên phần còn lại chính là khoá.
     */
    private String keyOf(String url) {
        String prefix = storage.publicUrl("").toString();
        return url.substring(prefix.length());
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "Đường dẫn ảnh không hợp lệ");
        }
    }

    /**
     * Chỉ nhận {@code https}.
     *
     * <p>{@code http} bị chặn không phải vì nguyên tắc mà vì hậu quả cụ thể: trang công khai chạy
     * {@code https}, và trình duyệt <b>chặn im lặng</b> ảnh {@code http} trên đó. Cho lưu thì chỗ
     * duy nhất phát hiện ra là mắt của khách.
     *
     * <p>Ngoại lệ cho máy phát triển: kho vật thể ở local chạy {@code http://localhost}. Chặn nó
     * nghĩa là tính năng này không thử được ở bất cứ máy nào chưa có chứng chỉ.
     */
    private void requireHttps(URI uri, String original) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && storage.isOwnUrl(original)) {
            log.debug("Chấp nhận ảnh http của kho nội bộ — chỉ đúng ở môi trường phát triển");
            return;
        }
        throw new ApiException(
                CatalogErrorCode.POSTER_URL_INVALID, "Đường dẫn ảnh phải dùng https, nhận được: " + scheme);
    }

    /** Làm tròn lên: nói "8 MB" cho một file 7,6 MB dễ hiểu hơn "7 MB" rồi vẫn bị từ chối. */
    private static long toMegabytes(long bytes) {
        return Math.max(1, Math.round(bytes / 1024.0 / 1024.0));
    }
}
