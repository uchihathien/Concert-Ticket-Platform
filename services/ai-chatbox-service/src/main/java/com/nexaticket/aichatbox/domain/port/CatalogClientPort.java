// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import java.util.List;
import java.util.Optional;

/**
 * Tra danh mục sự kiện ở catalog-service.
 *
 * <h2>Vì sao đi bằng đường công khai, không mang token của khách</h2>
 *
 * Trái ngược hẳn với {@link OrderingClientPort}, và sự trái ngược ấy là có lý: danh mục sự kiện là
 * dữ liệu <b>công khai</b> — bất kỳ ai mở trang chủ đều thấy đúng những gì ở đây. Không có gì thuộc
 * về riêng một khách để mà phân quyền, nên gắn token của khách vào lời gọi này chỉ tạo ra một chỗ
 * nữa để token đi lạc, mà không bảo vệ thêm được điều gì.
 *
 * <p>Hệ quả cần biết: trợ lý tra được sự kiện <b>đã xuất bản</b>. Bản nháp của ban tổ chức không
 * nằm trong đường công khai, nên trợ lý không thể vô tình tiết lộ một sự kiện chưa công bố.
 *
 * <h2>Vì sao tìm theo tên, không theo UUID</h2>
 *
 * Khách gõ "đêm nhạc Trịnh", không bao giờ gõ một UUID. Tool {@code getEventRules} đã tồn tại
 * trước đó đòi UUID sự kiện, và đó chính là lý do nó chưa bao giờ được gọi: mô hình không có cách
 * nào lấy được UUID từ câu hỏi của khách. Cổng này nhận chuỗi người gõ, và trả về slug — thứ vừa
 * tra được chi tiết vừa ghép được thành đường dẫn cho khách bấm.
 */
public interface CatalogClientPort {

    /**
     * Tìm sự kiện đã xuất bản theo tên, thành phố, nhóm.
     *
     * @param query chuỗi khách gõ; rỗng nghĩa là không lọc theo tên
     * @param city lọc theo thành phố, có thể null
     * @param category lọc theo nhóm, có thể null
     * @param limit trần số kết quả — giữ nhỏ, vì mỗi dòng trả về là token phải trả tiền và là một
     *     lựa chọn nữa để mô hình đọc sai
     * @return danh sách rỗng khi không có gì khớp — <b>không</b> phải lỗi
     * @throws RemoteCallException catalog-service quá hạn hoặc trả 5xx
     */
    List<EventBrief> searchEvents(String query, String city, String category, int limit);

    /**
     * @return {@link Optional#empty()} khi không có sự kiện nào mang slug ấy — không phải lỗi, mô
     *     hình thường tự dựng slug từ tên và đoán sai là chuyện bình thường
     * @throws RemoteCallException catalog-service quá hạn hoặc trả 5xx
     */
    Optional<EventDetail> findEventBySlug(String slug);
}
