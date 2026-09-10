// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.application.query.TemplateViews;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khai các khu cố định của một khung — chỉ Tổng công ty gọi được.
 *
 * <p>Nhận <b>cả tập</b> khu chứ không thêm từng khu, khác với {@code AddZoneHandler} của địa điểm.
 * Lý do là ai dùng nó: địa điểm được ban tổ chức dựng dần trên màn hình, còn khung là một sơ đồ
 * chuẩn mà nền tảng khai một lần rồi mở ra. Với sơ đồ chuẩn thì trạng thái sửa dở là trạng thái
 * không ai được thấy, và cách chắc chắn nhất để không ai thấy là không tạo ra nó.
 */
@Service
public class ReplaceTemplateZonesHandler {

    private final ConcertTemplateRepository templates;
    private final PlatformAccess access;

    public ReplaceTemplateZonesHandler(ConcertTemplateRepository templates, PlatformAccess access) {
        this.templates = templates;
        this.access = access;
    }

    /**
     * @param zones tập khu mới, thay hoàn toàn tập cũ
     * @return khung sau khi thay, đã ở hình dạng của đường đọc: người gọi dựng được phản hồi mà
     *     không phải đọc lại, và không phải nhận một kiểu của domain — tầng interfaces không được
     *     chạm vào domain (ArchitectureRules.hexagonalLayers)
     */
    @Transactional
    public TemplateViews.TemplateDetail handle(UUID templateId, List<ZoneSpec> zones) {
        access.requireSuperAdmin();

        ConcertTemplate template = templates
                .findById(templateId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Template not found"));

        List<TemplateZone> replacement = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            ZoneSpec spec = zones.get(i);
            replacement.add(TemplateZone.create(
                    template.id(),
                    spec.zoneCode(),
                    spec.name(),
                    parseKind(spec.kind()),
                    spec.rowCount(),
                    spec.seatsPerRow(),
                    spec.capacity(),
                    // Thứ tự trên màn hình = thứ tự trong request khi người gọi không nói gì khác.
                    // Bắt họ tự đánh số là bắt họ đánh lại từ đầu mỗi lần chèn một khu vào giữa.
                    spec.sortOrder() == null ? i : spec.sortOrder(),
                    spec.suggestedPriceVnd()));
        }

        // Bất biến "mã khu không trùng" kiểm ở aggregate, trước khi chạm database: ràng buộc UNIQUE
        // sẽ bắt được, nhưng nó nói bằng tiếng của Postgres chứ không bằng tiếng của người dùng.
        template.replaceZones(replacement);
        templates.replaceZones(template);
        return TemplateQueries.toDetail(template);
    }

    /**
     * Hình dạng đầu vào của một khu, ở tầng application.
     *
     * <p>Kiểu chuỗi cho {@code kind} chứ không phải enum của domain: tầng interfaces không được
     * chạm vào domain (ArchitectureRules.hexagonalLayers).
     */
    public record ZoneSpec(
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            Integer sortOrder,
            Long suggestedPriceVnd) {}

    private static AdmissionKind parseKind(String kind) {
        try {
            return AdmissionKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Loại khu không hợp lệ: " + kind);
        }
    }
}
