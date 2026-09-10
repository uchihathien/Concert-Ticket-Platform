// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đường đọc khung concert, dựng từ aggregate chứ không từ SQL riêng.
 *
 * <p>Cùng lựa chọn với {@link AdminCatalogQuery} và cùng lý do: sức chứa của một khu là
 * {@code rowCount × seatsPerRow} cho khu ngồi và {@code capacity} cho khu đứng — một quy tắc nghiệp
 * vụ đã nằm trong {@code TemplateZone.seatCount()}. Viết lại nó bằng SQL là tạo bản sao thứ hai,
 * và bản sai sẽ là bản người dùng nhìn thấy khi chọn khung.
 *
 * <p>Số lượng khung là hàng chục, không phải hàng vạn: cái giá của việc dựng aggregate ở đây bằng
 * không.
 *
 * <h3>Hai cửa quyền cho cùng một dữ liệu</h3>
 *
 * <p>Nền tảng thấy mọi khung ở mọi trạng thái; tổ chức chỉ thấy khung ACTIVE. Bộ lọc trạng thái
 * nằm trong chính phương thức của tổ chức chứ không phải một tham số người gọi truyền vào — tham
 * số thì controller sẽ quên, và quên nghĩa là lộ khung nháp của nền tảng ra ngoài.
 */
@Service
public class TemplateQueries {

    private final ConcertTemplateRepository templates;
    private final PlatformAccess platformAccess;
    private final CatalogAccess catalogAccess;

    public TemplateQueries(
            ConcertTemplateRepository templates, PlatformAccess platformAccess, CatalogAccess catalogAccess) {
        this.templates = templates;
        this.platformAccess = platformAccess;
        this.catalogAccess = catalogAccess;
    }

    /** Toàn bộ khung, mọi trạng thái — khu vực Tổng công ty. */
    @Transactional(readOnly = true)
    public List<TemplateViews.TemplateRow> all(String status) {
        platformAccess.requireSuperAdmin();
        return templates.findAll(parseStatus(status)).stream()
                .map(TemplateQueries::toRow)
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateViews.TemplateDetail byId(UUID templateId) {
        platformAccess.requireSuperAdmin();
        return toDetail(require(templateId));
    }

    /** Khung dùng được, cho tổ chức chọn. Chỉ ACTIVE — khung nháp là việc nội bộ của nền tảng. */
    @Transactional(readOnly = true)
    public List<TemplateViews.TemplateRow> usableBy(UUID organizationId) {
        catalogAccess.requireCatalogManager(organizationId);
        return templates.findAll(TemplateStatus.ACTIVE).stream()
                .map(TemplateQueries::toRow)
                .toList();
    }

    /**
     * Chi tiết một khung, như tổ chức thấy.
     *
     * <p>Khung không ACTIVE trả 404 chứ không 403: với ban tổ chức thì một khung nháp của nền tảng
     * là thứ không tồn tại, và 403 sẽ xác nhận rằng nó có.
     */
    @Transactional(readOnly = true)
    public TemplateViews.TemplateDetail usableById(UUID organizationId, UUID templateId) {
        catalogAccess.requireCatalogManager(organizationId);
        ConcertTemplate template = require(templateId);
        if (!template.status().isUsable()) {
            throw new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Template not found");
        }
        return toDetail(template);
    }

    /** Dùng chung cho các handler ghi để trả về hình dạng giống hệt đường đọc. */
    public static TemplateViews.TemplateDetail toDetail(ConcertTemplate template) {
        return new TemplateViews.TemplateDetail(
                template.id(),
                template.code(),
                template.name(),
                template.category(),
                template.description(),
                template.status().name(),
                template.capacity(),
                template.zones().stream().map(TemplateQueries::toZone).toList());
    }

    private ConcertTemplate require(UUID templateId) {
        return templates
                .findById(templateId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Template not found"));
    }

    private static TemplateViews.TemplateRow toRow(ConcertTemplate template) {
        return new TemplateViews.TemplateRow(
                template.id(),
                template.code(),
                template.name(),
                template.category(),
                template.description(),
                template.status().name(),
                template.zones().size(),
                template.capacity());
    }

    private static TemplateViews.TemplateZoneView toZone(TemplateZone zone) {
        return new TemplateViews.TemplateZoneView(
                zone.id(),
                zone.zoneCode(),
                zone.name(),
                zone.kind().name(),
                zone.rowCount(),
                zone.seatsPerRow(),
                zone.capacity(),
                zone.seatCount(),
                zone.sortOrder(),
                zone.suggestedPriceVnd());
    }

    /** Trạng thái lạ bị từ chối thay vì bị bỏ qua: bỏ qua sẽ trả về mọi khung cho một bộ lọc gõ sai. */
    private static TemplateStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TemplateStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Trạng thái không hợp lệ: " + status);
        }
    }
}
