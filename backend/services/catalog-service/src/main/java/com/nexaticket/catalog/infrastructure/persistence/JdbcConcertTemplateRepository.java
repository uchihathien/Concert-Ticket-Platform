// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence cho khung concert.
 *
 * <p>Cùng khuôn với {@link JdbcVenueRepository} — một câu LEFT JOIN rồi gộp trong bộ nhớ — vì khung
 * và địa điểm có cùng hình dạng: một bản ghi cha với một tập khu con.
 */
@Repository
public class JdbcConcertTemplateRepository implements ConcertTemplateRepository {

    private final JdbcTemplate jdbc;

    public JdbcConcertTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ConcertTemplate template) {
        Object[] head = {
            template.id(),
            template.code(),
            template.name(),
            template.category(),
            template.description(),
            template.status().name()
        };
        jdbc.update(
                """
                INSERT INTO concert_templates (id, code, name, category, description, status, """
                        + LayoutColumns.STAGE_COLUMNS
                        + """
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                concat(head, LayoutColumns.stageParams(template.stage())));
    }

    @Override
    public void update(ConcertTemplate template) {
        Object[] head = {
            template.name(),
            template.category(),
            template.description(),
            template.status().name()
        };
        jdbc.update(
                """
                UPDATE concert_templates
                   SET name = ?, category = ?, description = ?, status = ?,
                       stage_shape = ?, stage_x = ?, stage_y = ?, stage_width = ?, stage_height = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                concat(concat(head, LayoutColumns.stageParams(template.stage())), new Object[] {template.id()}));
    }

    /**
     * Xoá hết rồi chèn lại bằng {@code batchUpdate}.
     *
     * <p>An toàn vì không có khoá ngoại nào trỏ vào {@code concert_template_zones} ngoài cột dấu
     * vết {@code venue_zones.source_template_zone_id}, và cột đó khai {@code ON DELETE SET NULL}:
     * sửa một khung không kéo sập địa điểm thật của tổ chức, chỉ làm mất dấu vết của những khu chép
     * từ phiên bản cũ — đúng nghĩa, vì phiên bản cũ đã không còn.
     */
    @Override
    public void replaceZones(ConcertTemplate template) {
        jdbc.update("DELETE FROM concert_template_zones WHERE template_id = ?", template.id());

        List<TemplateZone> zones = template.zones();
        if (zones.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(zones.size());
        for (TemplateZone zone : zones) {
            Object[] head = {
                zone.id(),
                template.id(),
                zone.zoneCode(),
                zone.name(),
                zone.kind().name(),
                zone.rowCount(),
                zone.seatsPerRow(),
                zone.capacity(),
                zone.sortOrder(),
                zone.suggestedPriceVnd()
            };
            rows.add(concat(head, LayoutColumns.zoneParams(zone.layout())));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO concert_template_zones (id, template_id, zone_code, name, kind,
                                                    row_count, seats_per_row, capacity,
                                                    sort_order, suggested_price_vnd, """
                        + LayoutColumns.ZONE_COLUMNS
                        + """
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                rows);
    }

    @Override
    public Optional<ConcertTemplate> findById(UUID templateId) {
        return hydrateAll(jdbc.query(
                        SELECT + " WHERE t.id = ? " + ORDER, JdbcConcertTemplateRepository::mapRow, templateId))
                .stream()
                .findFirst();
    }

    @Override
    public boolean codeExists(String code) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM concert_templates WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    public List<ConcertTemplate> findAll(TemplateStatus status) {
        if (status == null) {
            return hydrateAll(jdbc.query(SELECT + ORDER, JdbcConcertTemplateRepository::mapRow));
        }
        return hydrateAll(jdbc.query(
                SELECT + " WHERE t.status = ? " + ORDER, JdbcConcertTemplateRepository::mapRow, status.name()));
    }

    @Override
    public void delete(UUID templateId) {
        jdbc.update("DELETE FROM concert_templates WHERE id = ?", templateId);
    }

    @Override
    public boolean hasVenuesFromTemplate(UUID templateId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM venues WHERE source_template_id = ?", Integer.class, templateId);
        return count != null && count > 0;
    }

    private static final String SELECT =
            """
            SELECT t.id, t.code, t.name, t.category, t.description, t.status,
                   t.stage_shape, t.stage_x, t.stage_y, t.stage_width, t.stage_height,
                   z.id AS zone_id, z.zone_code, z.name AS zone_name, z.kind,
                   z.row_count, z.seats_per_row, z.capacity, z.sort_order, z.suggested_price_vnd,
                   z.layout_shape, z.layout_origin_x, z.layout_origin_y, z.layout_rotation_deg,
                   z.layout_inner_radius, z.layout_start_angle_deg, z.layout_end_angle_deg
              FROM concert_templates t
              LEFT JOIN concert_template_zones z ON z.template_id = t.id
            """;

    private static final String ORDER = " ORDER BY t.name, z.sort_order, z.zone_code";

    private record Row(
            UUID templateId,
            String code,
            String name,
            String category,
            String description,
            TemplateStatus status,
            StageArea stage,
            TemplateZone zone) {}

    private static Row mapRow(ResultSet rs, int index) throws SQLException {
        UUID templateId = rs.getObject("id", UUID.class);
        UUID zoneId = rs.getObject("zone_id", UUID.class);
        TemplateZone zone = zoneId == null
                ? null
                : new TemplateZone(
                        zoneId,
                        templateId,
                        rs.getString("zone_code"),
                        rs.getString("zone_name"),
                        AdmissionKind.valueOf(rs.getString("kind")),
                        nullableInt(rs, "row_count"),
                        nullableInt(rs, "seats_per_row"),
                        nullableInt(rs, "capacity"),
                        rs.getInt("sort_order"),
                        nullableLong(rs, "suggested_price_vnd"),
                        LayoutColumns.readZone(rs));
        return new Row(
                templateId,
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("description"),
                TemplateStatus.valueOf(rs.getString("status")),
                LayoutColumns.readStage(rs),
                zone);
    }

    /** Nối hai mảng tham số — xem {@code JdbcVenueRepository.concat}. */
    private static Object[] concat(Object[] head, Object[] tail) {
        Object[] all = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /** {@code getInt} trả 0 cho NULL, và 0 không hợp lệ ở những cột này — xem {@code JdbcVenueRepository}. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Giá 0 là hợp lệ (vé mời), nên phân biệt NULL với 0 ở đây là bắt buộc chứ không phải phòng thủ. */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static List<ConcertTemplate> hydrateAll(List<Row> rows) {
        Map<UUID, List<TemplateZone>> zonesByTemplate = new LinkedHashMap<>();
        Map<UUID, Row> headers = new LinkedHashMap<>();

        for (Row row : rows) {
            headers.putIfAbsent(row.templateId(), row);
            List<TemplateZone> zones = zonesByTemplate.computeIfAbsent(row.templateId(), key -> new ArrayList<>());
            if (row.zone() != null) {
                zones.add(row.zone());
            }
        }

        return headers.values().stream()
                .map(header -> new ConcertTemplate(
                        header.templateId(),
                        header.code(),
                        header.name(),
                        header.category(),
                        header.description(),
                        header.status(),
                        zonesByTemplate.get(header.templateId()),
                        header.stage()))
                .toList();
    }
}
