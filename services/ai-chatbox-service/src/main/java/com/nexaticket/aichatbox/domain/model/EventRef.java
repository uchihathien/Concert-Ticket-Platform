// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Một sự kiện mà lượt chat này <b>thật sự</b> tra được, để ghép thành đường dẫn cho khách bấm.
 *
 * <h2>Vì sao không để mô hình tự viết đường dẫn</h2>
 *
 * Nó viết được, nhưng không đáng tin theo cả hai chiều. Chiều thứ nhất: phần lớn lượt trả lời nó
 * <b>không</b> viết gì cả — ba lượt đo liên tiếp đều chỉ có chữ, không có link, nên khách đọc xong
 * không có đường nào đi tiếp ngoài việc tự tìm lại trong danh mục. Chiều thứ hai, tệ hơn: khi nó có
 * viết thì nó <b>tự dựng</b> slug từ tên sự kiện, và slug tự dựng dẫn tới trang 404.
 *
 * <p>Đường dẫn ghép từ đây thì slug đến trực tiếp từ catalog-service qua kết quả tool — nó tồn tại,
 * vì chính catalog vừa trả về nó trong lượt này.
 */
public record EventRef(String slug, String title) {}
