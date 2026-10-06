// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình kho ảnh.
 *
 * @param endpoint địa chỉ catalog dùng để nói chuyện với kho vật thể. Trong container đây là tên
 *     service ({@code http://minio:9000}); trình duyệt không phân giải được nó — xem
 *     {@code publicBaseUrl}.
 * @param publicBaseUrl gốc địa chỉ mà <b>trình duyệt</b> dùng. Phải tách khỏi {@code endpoint},
 *     nếu không URL lưu vào database sẽ là URL không máy khách nào mở được.
 * @param maxBytes trần kích thước ảnh. Thi hành <b>sau</b> khi tải lên (xem
 *     {@code ObjectStoragePort.stat}), nên nó là luật dọn dẹp chứ không phải luật chặn trước.
 * @param allowedContentTypes kiểu ảnh <b>trình duyệt được phép gửi lên</b>. Danh sách trắng chứ
 *     không phải danh sách đen: thêm một định dạng là một quyết định, còn quên chặn một định dạng
 *     thì không ai nhận ra. Đây KHÔNG phải danh sách kiểu được phép tồn tại trong kho — xem
 *     {@link #isStorableType(String)}.
 * @param allowExternalUrls cho phép ban tổ chức dán URL ảnh ở ngoài hay không. Bật thì tiện lúc
 *     chuyển đổi dữ liệu cũ; tắt thì mọi ảnh đều nằm trong tay mình — không phụ thuộc host lạ,
 *     không để host ấy đếm được IP của mọi khách xem trang.
 */
@ConfigurationProperties(prefix = "nexaticket.catalog.media")
public record MediaProperties(
        String endpoint,
        String publicBaseUrl,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        long maxBytes,
        List<String> allowedContentTypes,
        boolean allowExternalUrls) {

    public MediaProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            // Mặc định bằng endpoint: ở máy phát triển thì trình duyệt và service cùng nhìn thấy
            // localhost, nên hai địa chỉ trùng nhau và không ai phải khai gì thêm.
            publicBaseUrl = endpoint;
        }
        if (bucket == null || bucket.isBlank()) {
            bucket = "nexaticket-media";
        }
        if (region == null || region.isBlank()) {
            // MinIO không quan tâm vùng, nhưng SDK thì BẮT BUỘC phải có một giá trị — thiếu nó là
            // lỗi lúc khởi tạo client, không phải lúc gọi.
            region = "us-east-1";
        }
        if (maxBytes <= 0) {
            // 8 MB: đủ cho một poster 2000px chất lượng cao, đủ chặt để một lần kéo nhầm file RAW
            // không nằm lại trên đĩa.
            maxBytes = 8L * 1024 * 1024;
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp");
        }
    }

    /**
     * Kiểu ảnh hệ thống <b>tự vẽ</b>, không đến từ ai bên ngoài.
     *
     * <p>Cố ý nằm ngoài {@code allowedContentTypes} chứ không được thêm vào đó. Hai danh sách này
     * trả lời hai câu hỏi khác nhau: một cái gác cổng <b>tải lên</b> (ai cũng gọi được), cái kia
     * gác chuyện <b>tồn tại</b> trong kho. Gộp lại thì ban tổ chức tải lên được SVG tuỳ ý — mà SVG
     * là tài liệu XML, mang được {@code <script>}, và nó sẽ chạy dưới origin của kho ảnh với bất
     * kỳ ai mở thẳng đường dẫn đó. Tách ra thì vật thể SVG chỉ có thể do {@code PosterArtwork} tạo
     * ở phía máy chủ.
     */
    public static final String GENERATED_CONTENT_TYPE = "image/svg+xml";

    public Set<String> allowedTypes() {
        return Set.copyOf(allowedContentTypes);
    }

    /** Trình duyệt có được gửi kiểu này lên không. Dùng ở đường xin URL tải lên. */
    public boolean isAllowedType(String contentType) {
        return contentType != null && allowedTypes().contains(contentType.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Kiểu này có được phép nằm trong kho không — rộng hơn {@link #isAllowedType} đúng một mục.
     *
     * <p>Thiếu hàm này thì bộ dựng dữ liệu mẫu và chính sách ảnh bìa mâu thuẫn nhau: bộ dựng đẩy
     * lên SVG, còn chính sách gặp SVG thì <b>xoá vật thể rồi báo lỗi</b>. Triệu chứng là sự kiện
     * mẫu tự mất ảnh ngay lần đầu có người lưu lại nó kèm đường dẫn ảnh cũ.
     */
    public boolean isStorableType(String contentType) {
        return isAllowedType(contentType) || GENERATED_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }
}
