// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ khung concert của nền tảng. */
public interface ConcertTemplateRepository {

    void insert(ConcertTemplate template);

    /** Ghi lại phần thuộc chính khung (tên, phân loại, trạng thái), không đụng tới khu. */
    void update(ConcertTemplate template);

    /**
     * Thay toàn bộ tập khu bằng một lần ghi.
     *
     * <p>Xoá hết rồi chèn lại chứ không so từng khu: khung chưa ACTIVE thì không có gì trỏ vào khu
     * của nó, nên id của khu không cần ổn định. Đổi lại là một đường ghi duy nhất thay vì ba nhánh
     * thêm/sửa/xoá — ba nhánh mà mỗi nhánh là một chỗ để sai.
     */
    void replaceZones(ConcertTemplate template);

    Optional<ConcertTemplate> findById(UUID templateId);

    boolean codeExists(String code);

    /**
     * @param status lọc theo trạng thái; {@code null} thì lấy hết. Tổ chức luôn gọi với
     *     {@link TemplateStatus#ACTIVE} — khung nháp là việc nội bộ của nền tảng.
     */
    List<ConcertTemplate> findAll(TemplateStatus status);

    /** Xoá hẳn. Chỉ dùng cho khung chưa từng ACTIVE — xem {@code ArchiveTemplateHandler}. */
    void delete(UUID templateId);

    /** Đã có sự kiện nào dựng từ khung này chưa. Quyết định giữa "xoá được" và "chỉ lưu trữ được". */
    boolean hasVenuesFromTemplate(UUID templateId);
}
