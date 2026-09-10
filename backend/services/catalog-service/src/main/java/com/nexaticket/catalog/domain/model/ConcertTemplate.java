// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Khung concert của Tổng công ty: một sơ đồ chuẩn để các tổ chức thành viên dùng lại.
 *
 * <p>Aggregate root, và khu là entity con — cùng lý do với {@link Venue}: bất biến "mã khu không
 * trùng nhau trong một khung" chỉ kiểm được khi nhìn cả tập.
 *
 * <p>Khung <b>không</b> thuộc tổ chức nào và không có {@code organizationId}. Đó là toàn bộ khác
 * biệt giữa nó và {@link Venue}: cùng nội dung, khác chủ. Nên áp khung là một phép chép
 * ({@link TemplateZone#materialize}), không phải một phép dịch.
 */
public final class ConcertTemplate {

    private final UUID id;
    private final String code;
    private String name;
    private String category;
    private String description;
    private TemplateStatus status;
    private final List<TemplateZone> zones;

    public ConcertTemplate(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            TemplateStatus status,
            List<TemplateZone> zones) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.category = category;
        this.description = description;
        this.status = status;
        this.zones = new ArrayList<>(zones);
    }

    public static ConcertTemplate draft(String code, String name, String category, String description) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Khung phải có mã");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Khung phải có tên");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Khung phải có phân loại");
        }
        return new ConcertTemplate(
                UUID.randomUUID(), code.trim(), name, category, description, TemplateStatus.DRAFT, List.of());
    }

    /**
     * Thay toàn bộ tập khu.
     *
     * <p>Thay cả tập chứ không thêm từng khu: một khung là một sơ đồ, và sơ đồ chỉ có nghĩa khi
     * nhìn trọn vẹn. Sửa từng khu qua nhiều lời gọi cũng để lại những trạng thái trung gian mà
     * tổ chức có thể áp phải — một khung đang sửa dở nhìn không khác gì một khung đã xong.
     */
    public void replaceZones(List<TemplateZone> replacement) {
        Set<String> codes = new LinkedHashSet<>();
        for (TemplateZone zone : replacement) {
            if (!codes.add(zone.zoneCode())) {
                throw new IllegalArgumentException("Mã khu trùng trong cùng một khung: " + zone.zoneCode());
            }
        }
        zones.clear();
        zones.addAll(replacement);
    }

    public void rename(String newName, String newCategory, String newDescription) {
        if (newName != null && !newName.isBlank()) {
            name = newName;
        }
        if (newCategory != null && !newCategory.isBlank()) {
            category = newCategory;
        }
        if (newDescription != null) {
            description = newDescription;
        }
    }

    /**
     * Mở cho tổ chức dùng.
     *
     * <p>Đòi có khu là ràng buộc thật, không phải thủ tục: khung rỗng sinh ra sự kiện không bán
     * được gì, và lỗi sẽ hiện ra ở bước publish của tổ chức chứ không ở đây.
     */
    public void activate() {
        if (zones.isEmpty()) {
            throw new IllegalStateException("Khung chưa khai khu nào");
        }
        status = TemplateStatus.ACTIVE;
    }

    /**
     * Ngừng cho tạo sự kiện mới.
     *
     * <p>Không đụng tới sự kiện đã tạo: lúc áp khung, các khu đã được chép sang địa điểm của tổ
     * chức. Đó cũng là lý do lưu trữ được thiết kế thay cho xoá.
     */
    public void archive() {
        status = TemplateStatus.ARCHIVED;
    }

    /** Đưa về nháp để sửa. Sự kiện đã tạo không bị ảnh hưởng, vì khu đã được chép. */
    public void backToDraft() {
        status = TemplateStatus.DRAFT;
    }

    /** Tổng sức chứa của khung, cộng cả khu ngồi lẫn khu đứng. */
    public int capacity() {
        return zones.stream().mapToInt(TemplateZone::seatCount).sum();
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    public TemplateStatus status() {
        return status;
    }

    public List<TemplateZone> zones() {
        return List.copyOf(zones);
    }
}
