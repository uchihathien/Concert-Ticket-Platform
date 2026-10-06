// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;

/**
 * Một sự kiện trong kết quả tìm kiếm, gọn đủ để mô hình đọc mà chọn.
 *
 * <p><b>Có {@code slug}, không có id.</b> Slug là thứ khách nhìn thấy trên đường dẫn và là thứ
 * {@code getEventDetails} nhận vào, nên nó là mã duy nhất agent cần biết. Mang thêm UUID vào đây
 * chỉ tạo ra một cơ hội để mô hình đọc to một mã nội bộ lên cho khách.
 */
public record EventBrief(
        String slug,
        String title,
        String category,
        String city,
        String venueName,
        Instant nextSessionAt,
        Long fromPriceVnd,
        int sessionCount) {}
