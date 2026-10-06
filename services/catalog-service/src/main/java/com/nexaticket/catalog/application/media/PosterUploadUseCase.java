// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.media;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Cấp một lượt tải ảnh bìa lên.
 *
 * <p>Trả về URL đã ký để trình duyệt {@code PUT} thẳng lên kho vật thể — byte không đi qua service
 * này (xem {@link ObjectStoragePort}). Kèm luôn địa chỉ công khai <b>tương lai</b> của ảnh, để
 * trình duyệt lưu nó vào sự kiện ngay sau khi tải xong mà không phải hỏi lại.
 *
 * <p>Địa chỉ ấy chưa có giá trị gì cho tới khi ảnh thật sự được tải lên: {@link PosterUrlPolicy}
 * kiểm lại vật thể ở bước lưu sự kiện, nên một URL xin về rồi bỏ đó sẽ bị từ chối.
 */
@Service
public class PosterUploadUseCase {

    /**
     * Phần mở rộng theo kiểu ảnh.
     *
     * <p>Khoá vật thể mang đuôi file không phải để đẹp: nhiều CDN và trình duyệt đoán kiểu từ đuôi
     * khi header thiếu, và một ảnh không có đuôi đôi khi bị tải về thay vì hiển thị.
     */
    private static final Map<String, String> EXTENSIONS =
            Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final ObjectStoragePort storage;
    private final MediaProperties properties;
    private final CatalogAccess access;

    public PosterUploadUseCase(ObjectStoragePort storage, MediaProperties properties, CatalogAccess access) {
        this.storage = storage;
        this.properties = properties;
        this.access = access;
    }

    /**
     * @param contentType kiểu ảnh trình duyệt sẽ gửi. Đi vào chữ ký, nên khai một đằng gửi một nẻo
     *     thì kho vật thể từ chối.
     */
    public Ticket issue(UUID organizationId, String contentType) {
        access.requireCatalogManager(organizationId);

        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!properties.isAllowedType(normalized)) {
            throw new ApiException(
                    CatalogErrorCode.POSTER_URL_INVALID,
                    "Chỉ nhận " + String.join(", ", properties.allowedContentTypes()));
        }

        // organizationId nằm TRONG khoá: nhìn một vật thể là biết nó của ai mà không phải tra
        // database. Tên file do người dùng đặt thì KHÔNG dùng — nó là dữ liệu chưa tin được, và
        // một cái tên chứa `../` là đường đi ra ngoài thư mục dự tính.
        String key = "posters/%s/%s.%s".formatted(organizationId, UUID.randomUUID(), EXTENSIONS.get(normalized));

        ObjectStoragePort.PresignedUpload presigned = storage.presignUpload(key, normalized);

        return new Ticket(
                presigned.uploadUrl().toString(),
                storage.publicUrl(key).toString(),
                normalized,
                properties.maxBytes(),
                presigned.expiresAt());
    }

    /**
     * @param uploadUrl trình duyệt {@code PUT} thẳng vào đây, kèm header {@code Content-Type} đúng
     *     bằng {@code contentType}
     * @param publicUrl địa chỉ để lưu vào sự kiện <b>sau khi</b> tải xong
     * @param maxBytes trần kích thước, gửi xuống để giao diện từ chối sớm thay vì để khách chờ tải
     *     xong một file 40 MB rồi mới bị từ chối
     */
    public record Ticket(String uploadUrl, String publicUrl, String contentType, long maxBytes, Instant expiresAt) {}
}
