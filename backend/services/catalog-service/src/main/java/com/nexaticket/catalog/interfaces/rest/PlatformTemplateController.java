// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.command.ManageTemplateHandler;
import com.nexaticket.catalog.application.command.ReplaceTemplateZonesHandler;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.application.query.TemplateViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khung concert — khu vực Tổng công ty.
 *
 * <p>Nằm dưới {@code /v1/platform/} chứ không dưới {@code /v1/organizations/{id}/}, và đó là một
 * quyết định về bảo mật chứ không phải về thẩm mỹ: {@code TenantFilter} bỏ qua bước lấy tenant cho
 * đúng tiền tố này, vì superadmin — theo thiết kế — không phải thành viên của tổ chức nào. Đặt
 * nhầm chỗ thì chính người có toàn quyền nhận 404.
 *
 * <p>Kiểm quyền nằm trong handler ({@code PlatformAccess}), không ở đây: cùng lý do với
 * {@code AdminCatalogController} — một cửa quyền chỉ đóng ở tầng web là một cửa quyền đi vòng
 * được.
 */
@RestController
@RequestMapping("/v1/platform/concert-templates")
public class PlatformTemplateController {

    private final ManageTemplateHandler manage;
    private final ReplaceTemplateZonesHandler replaceZones;
    private final TemplateQueries queries;

    public PlatformTemplateController(
            ManageTemplateHandler manage, ReplaceTemplateZonesHandler replaceZones, TemplateQueries queries) {
        this.manage = manage;
        this.replaceZones = replaceZones;
        this.queries = queries;
    }

    @GetMapping
    public List<TemplateViews.TemplateRow> list(@RequestParam(required = false) String status) {
        return queries.all(status);
    }

    @GetMapping("/{templateId}")
    public TemplateViews.TemplateDetail detail(@PathVariable UUID templateId) {
        return queries.byId(templateId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateViews.TemplateDetail create(@Valid @RequestBody TemplateRequest request) {
        UUID id = manage.create(request.code(), request.name(), request.category(), request.description());
        return queries.byId(id);
    }

    /** Mọi trường tuỳ chọn: null nghĩa là "giữ nguyên". Mã khung thì không sửa được — nó là định danh. */
    @PatchMapping("/{templateId}")
    public TemplateViews.TemplateDetail update(
            @PathVariable UUID templateId, @Valid @RequestBody TemplatePatch request) {
        manage.update(templateId, request.name(), request.category(), request.description());
        return queries.byId(templateId);
    }

    /**
     * Khai cả tập khu trong một lần.
     *
     * <p>{@code PUT} chứ không {@code POST}: đây là phép thay thế toàn bộ, không phải phép thêm.
     * Gọi lại với cùng body cho ra cùng kết quả.
     */
    @PutMapping("/{templateId}/zones")
    public TemplateViews.TemplateDetail zones(@PathVariable UUID templateId, @Valid @RequestBody ZonesRequest request) {
        return replaceZones.handle(
                templateId,
                request.zones().stream()
                        .map(z -> new ReplaceTemplateZonesHandler.ZoneSpec(
                                z.zoneCode(),
                                z.name(),
                                z.kind(),
                                z.rowCount(),
                                z.seatsPerRow(),
                                z.capacity(),
                                z.sortOrder(),
                                z.suggestedPriceVnd()))
                        .toList());
    }

    @PostMapping("/{templateId}/activate")
    public TemplateViews.TemplateDetail activate(@PathVariable UUID templateId) {
        manage.activate(templateId);
        return queries.byId(templateId);
    }

    /** Ngừng cho tạo sự kiện mới. Sự kiện đã dựng từ khung không đổi — khu đã được chép. */
    @PostMapping("/{templateId}/archive")
    public TemplateViews.TemplateDetail archive(@PathVariable UUID templateId) {
        manage.archive(templateId);
        return queries.byId(templateId);
    }

    /** Đưa về nháp để sửa sơ đồ; tổ chức lập tức không thấy khung này nữa. */
    @PostMapping("/{templateId}/draft")
    public TemplateViews.TemplateDetail backToDraft(@PathVariable UUID templateId) {
        manage.backToDraft(templateId);
        return queries.byId(templateId);
    }

    /** Chỉ xoá được khung chưa tổ chức nào dùng; 204 vì sau đó không còn gì để trả về. */
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId) {
        manage.delete(templateId);
    }

    // --- Hình dạng request -------------------------------------------------

    public record TemplateRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 50) String category,
            @Size(max = 2000) String description) {}

    public record TemplatePatch(
            @Size(max = 200) String name, @Size(max = 50) String category, @Size(max = 2000) String description) {}

    /**
     * Tập khu phải không rỗng.
     *
     * <p>Một khung rỗng không có nghĩa gì, và cách duy nhất người gọi có thể muốn nó là gõ nhầm.
     * Muốn xoá sạch khu thì xoá khung.
     */
    public record ZonesRequest(@NotEmpty @Valid List<ZoneRequest> zones) {}

    /**
     * Hình dạng khu không kiểm chéo bằng annotation ở đây — cùng lý do với
     * {@code AdminCatalogController.ZoneRequest}: luật "khu ngồi phải có số hàng, khu đứng phải có
     * sức chứa" đã nằm trong constructor của {@code TemplateZone} và trong ràng buộc
     * {@code ck_template_zone_shape}. Bản sao thứ ba là chỗ thứ ba để lệch.
     */
    public record ZoneRequest(
            @NotBlank @Size(max = 16) String zoneCode,
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "SEATED|STANDING") String kind,
            @Positive Integer rowCount,
            @Positive Integer seatsPerRow,
            @Positive Integer capacity,
            Integer sortOrder,
            @PositiveOrZero Long suggestedPriceVnd) {}
}
