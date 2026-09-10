// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vòng đời khung concert — chỉ Tổng công ty gọi được.
 *
 * <p>Gom năm thao tác vào một handler vì chúng dùng chung đúng một cặp bước mở đầu: kiểm superadmin
 * rồi nạp khung. Tách thành năm class là năm chỗ để một trong hai bước bị quên, và bước bị quên sẽ
 * là bước đầu.
 */
@Service
public class ManageTemplateHandler {

    private final ConcertTemplateRepository templates;
    private final PlatformAccess access;

    public ManageTemplateHandler(ConcertTemplateRepository templates, PlatformAccess access) {
        this.templates = templates;
        this.access = access;
    }

    /** @return id của khung vừa tạo. Khung ra đời ở trạng thái DRAFT và chưa có khu nào. */
    @Transactional
    public UUID create(String code, String name, String category, String description) {
        access.requireSuperAdmin();

        String normalized = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized != null && templates.codeExists(normalized)) {
            throw new ApiException(CatalogErrorCode.TEMPLATE_CODE_TAKEN, "Mã khung đã có: " + normalized);
        }

        ConcertTemplate template = ConcertTemplate.draft(normalized, name, category, description);
        templates.insert(template);
        return template.id();
    }

    @Transactional
    public void update(UUID templateId, String name, String category, String description) {
        ConcertTemplate template = require(templateId);
        template.rename(name, category, description);
        templates.update(template);
    }

    /**
     * Mở khung cho các tổ chức dùng.
     *
     * <p>{@code IllegalStateException} của domain được dịch thành mã lỗi nghiệp vụ ở đây chứ không
     * để rơi ra thành 500: "khung chưa khai khu nào" là một câu trả lời cho người dùng, không phải
     * một sự cố.
     */
    @Transactional
    public void activate(UUID templateId) {
        ConcertTemplate template = require(templateId);
        if (template.zones().isEmpty()) {
            throw new ApiException(CatalogErrorCode.TEMPLATE_WITHOUT_ZONE, "Khai khu vực trước khi mở khung");
        }
        template.activate();
        templates.update(template);
    }

    /** Ngừng cho tạo sự kiện mới. Sự kiện đã tạo không đổi — khu đã được chép sang địa điểm của tổ chức. */
    @Transactional
    public void archive(UUID templateId) {
        ConcertTemplate template = require(templateId);
        template.archive();
        templates.update(template);
    }

    /** Đưa về nháp để sửa sơ đồ. Tổ chức lập tức không thấy khung này nữa. */
    @Transactional
    public void backToDraft(UUID templateId) {
        ConcertTemplate template = require(templateId);
        template.backToDraft();
        templates.update(template);
    }

    /**
     * Xoá hẳn — chỉ khi chưa tổ chức nào dựng địa điểm từ khung này.
     *
     * <p>Đã có địa điểm thì chặn, dù {@code ON DELETE SET NULL} khiến việc xoá không làm hỏng dữ
     * liệu: mất dấu vết nghĩa là những địa điểm đó bỗng thành "tổ chức tự dựng" và sửa được — một
     * kết cấu cố định lặng lẽ trở thành kết cấu tuỳ chỉnh. Lưu trữ làm đúng việc mà người gọi
     * muốn, không kèm tác dụng phụ đó.
     */
    @Transactional
    public void delete(UUID templateId) {
        require(templateId);
        if (templates.hasVenuesFromTemplate(templateId)) {
            throw new ApiException(
                    CatalogErrorCode.TEMPLATE_IN_USE, "Đã có sự kiện dựng từ khung này; hãy lưu trữ thay vì xoá");
        }
        templates.delete(templateId);
    }

    private ConcertTemplate require(UUID templateId) {
        access.requireSuperAdmin();
        return templates
                .findById(templateId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Template not found"));
    }
}
