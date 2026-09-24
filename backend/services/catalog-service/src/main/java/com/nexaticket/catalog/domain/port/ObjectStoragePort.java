// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * Kho vật thể cho ảnh bìa sự kiện.
 *
 * <h2>Byte ảnh KHÔNG đi qua service này</h2>
 *
 * <p>Cổng này không có phương thức nào nhận {@code InputStream}, và đó là quyết định chính. Trình
 * duyệt tải ảnh <b>thẳng</b> lên kho vật thể bằng một URL đã ký; catalog chỉ ký URL đó rồi sau đó
 * kiểm lại vật thể.
 *
 * <p>Lý do rất cụ thể: catalog là service <b>đọc nhiều ghi ít</b> (xem ghi chú đầu V0100), và một
 * poster 4 MB đi qua gateway rồi qua JVM này là 4 MB nằm trong heap của đúng service đang phục vụ
 * trang danh sách công khai. Mười ban tổ chức cùng tải ảnh là 40 MB, cộng với việc giữ luồng
 * Tomcat trong suốt thời gian truyền — trên một đường mạng mà ta không kiểm soát tốc độ.
 *
 * <p>Đánh đổi phải trả: kho vật thể buộc phải gọi được từ trình duyệt, nên nó cần CORS. Chấp nhận
 * được vì ảnh <b>vốn đã</b> phải đọc được công khai — khách xem trang sự kiện tải chúng trực tiếp.
 */
public interface ObjectStoragePort {

    /**
     * Ký một lượt tải lên.
     *
     * <p>{@code contentType} được <b>gắn vào chữ ký</b>: trình duyệt gửi kiểu khác thì kho vật thể
     * từ chối. Đó là chốt chặn duy nhất chạy trước khi byte được ghi — mọi kiểm tra còn lại đều
     * diễn ra sau, lúc {@link #stat} được gọi.
     *
     * @param key khoá vật thể, do người gọi quyết định (không phải do người dùng)
     * @return URL để {@code PUT} thẳng từ trình duyệt, kèm hạn dùng
     */
    PresignedUpload presignUpload(String key, String contentType);

    /**
     * Kích thước và kiểu thật của vật thể sau khi đã tải lên.
     *
     * <p>Đây là chỗ <b>duy nhất</b> biết được ảnh thật sự nặng bao nhiêu. URL ký sẵn kiểu
     * {@code PUT} không chặn được kích thước — chỉ {@code POST} kèm policy mới làm được, và cách
     * đó đổi hẳn hình dạng request phía trình duyệt. Nên luật kích thước được thi hành ở đây, sau
     * khi byte đã nằm trên đĩa, và vật thể quá cỡ bị xoá đi.
     *
     * @return rỗng khi vật thể không tồn tại — nghĩa là chưa ai tải lên, hoặc khoá bị bịa ra
     */
    Optional<StoredObject> stat(String key);

    /** Xoá. Gọi khi vật thể vừa tải lên không qua được phần kiểm. */
    void delete(String key);

    /**
     * Địa chỉ công khai của một vật thể.
     *
     * <p>Tách khỏi endpoint nội bộ: trong container, catalog nói chuyện với kho vật thể qua tên
     * service (ví dụ {@code http://minio:9000}), còn trình duyệt thì không phân giải được tên ấy.
     * Dùng chung một địa chỉ cho cả hai sẽ cho ra một URL lưu vào database mà không máy khách nào
     * mở được — và lỗi chỉ lộ ra khi có người nhìn trang sự kiện.
     */
    URI publicUrl(String key);

    /** Địa chỉ này có thuộc kho vật thể của mình không — dùng để phân biệt ảnh của ta với ảnh ngoài. */
    boolean isOwnUrl(String url);

    record PresignedUpload(URI uploadUrl, String key, Instant expiresAt) {}

    record StoredObject(long sizeBytes, String contentType) {}
}
