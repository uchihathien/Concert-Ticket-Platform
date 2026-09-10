// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.VenueRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Adapter persistence cho địa điểm.
 *
 * <p>Dùng {@code JdbcTemplate} thay vì JPA, giống identity: catalog có logic nghiệp vụ mỏng, và
 * cách này giữ domain hoàn toàn thuần mà không phải viết lớp entity cùng mapper song song
 * (tactical-ddd.md §1).
 */
@Repository
public class JdbcVenueRepository implements VenueRepository {

    private final JdbcTemplate jdbc;

    public JdbcVenueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Venue venue) {
        jdbc.update(
                """
                INSERT INTO venues (id, organization_id, name, city, address, source_template_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                venue.id(),
                venue.organizationId(),
                venue.name(),
                venue.city(),
                venue.address(),
                venue.sourceTemplateId());
    }

    @Override
    public void addZone(VenueZone zone) {
        jdbc.update(INSERT_ZONE, zoneRow(zone));
    }

    /**
     * Địa điểm + toàn bộ khu, với phần khu gộp thành một {@code batchUpdate}.
     *
     * <p>Một khung concert có hàng chục khu; gọi {@link #addZone} trong vòng lặp là hàng chục lần
     * đi-về mạng cho một thao tác mà người dùng đang đứng chờ.
     */
    @Override
    public void saveWithZones(Venue venue, List<VenueZone> zones) {
        save(venue);
        if (zones.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                INSERT_ZONE, zones.stream().map(JdbcVenueRepository::zoneRow).toList());
    }

    /**
     * Thay cả sơ đồ: upsert theo {@code (venue_id, zone_code)}, rồi dọn khu không còn trong tập mới.
     *
     * <p>Upsert chứ không xoá-rồi-chèn, vì id của khu là thứ {@code ticket_types.venue_zone_id} trỏ
     * vào. Sửa "VIP: 100 → 150 ghế" mà xoá khu VIP đi sẽ kéo theo cả mức giá đã khai cho nó, và ban
     * tổ chức phải nhập lại một thứ họ không hề đụng tới.
     *
     * <p>Hạng vé trỏ vào khu <b>bị bỏ</b> thì phải xoá trước, nếu không khoá ngoại chặn. Ở đây điều
     * đó an toàn: lệnh này chỉ chạy khi địa điểm chưa có sự kiện nào từng lên bán, nên không có tồn
     * kho hay vé nào ở service khác dựng từ những khu này.
     */
    @Override
    public ZoneReplacement replaceZones(UUID venueId, List<VenueZone> zones) {
        int before = countZones(venueId);
        if (!zones.isEmpty()) {
            jdbc.batchUpdate(
                    UPSERT_ZONE,
                    zones.stream().map(JdbcVenueRepository::zoneRow).toList());
        }

        // Mảng SQL thay vì IN (?, ?, …) nối chuỗi: số khu do người dùng quyết định, và một câu lệnh
        // có số tham số thay đổi thì mỗi lần gọi là một lần parse mới, không dùng lại được plan.
        String[] kept = zones.stream().map(VenueZone::zoneCode).toArray(String[]::new);
        int removedTypes = jdbc.update(
                """
                DELETE FROM ticket_types tt
                 USING venue_zones z
                 WHERE tt.venue_zone_id = z.id
                   AND z.venue_id = ?
                   AND NOT (z.zone_code = ANY (?))
                """,
                venueId,
                kept);
        int removedZones =
                jdbc.update("DELETE FROM venue_zones WHERE venue_id = ? AND NOT (zone_code = ANY (?))", venueId, kept);

        int inserted = countZones(venueId) - (before - removedZones);
        return new ZoneReplacement(inserted, zones.size() - inserted, removedZones, removedTypes);
    }

    private int countZones(UUID venueId) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM venue_zones WHERE venue_id = ?", Integer.class, venueId);
        return count == null ? 0 : count;
    }

    private static Object[] zoneRow(VenueZone zone) {
        return new Object[] {
            zone.id(),
            zone.venueId(),
            zone.zoneCode(),
            zone.name(),
            zone.kind().name(),
            zone.rowCount(),
            zone.seatsPerRow(),
            zone.capacity(),
            zone.sortOrder(),
            zone.sourceTemplateZoneId()
        };
    }

    private static final String INSERT_ZONE =
            """
            INSERT INTO venue_zones (id, venue_id, zone_code, name, kind,
                                     row_count, seats_per_row, capacity, sort_order, source_template_zone_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPSERT_ZONE = INSERT_ZONE
            + """
            ON CONFLICT (venue_id, zone_code) DO UPDATE SET
                name          = EXCLUDED.name,
                kind          = EXCLUDED.kind,
                row_count     = EXCLUDED.row_count,
                seats_per_row = EXCLUDED.seats_per_row,
                capacity      = EXCLUDED.capacity,
                sort_order    = EXCLUDED.sort_order
            """;

    /**
     * Một câu truy vấn với LEFT JOIN, không phải một câu cho địa điểm rồi một câu cho khu.
     *
     * <p>LEFT chứ không phải INNER: địa điểm vừa tạo chưa có khu nào, và nó vẫn phải hiện ra để
     * người dùng thêm khu vào. INNER JOIN sẽ làm nó biến mất khỏi màn hình ngay sau khi tạo.
     */
    @Override
    public Optional<Venue> findById(UUID organizationId, UUID venueId) {
        return hydrateAll(jdbc.query(
                        SELECT_VENUE_WITH_ZONES + " WHERE v.organization_id = ? AND v.id = ? " + ORDER,
                        JdbcVenueRepository::mapRow,
                        organizationId,
                        venueId))
                .stream()
                .findFirst();
    }

    @Override
    public List<Venue> findAllByOrganization(UUID organizationId) {
        return hydrateAll(jdbc.query(
                SELECT_VENUE_WITH_ZONES + " WHERE v.organization_id = ? " + ORDER,
                JdbcVenueRepository::mapRow,
                organizationId));
    }

    private static final String SELECT_VENUE_WITH_ZONES =
            """
            SELECT v.id, v.organization_id, v.name, v.city, v.address, v.source_template_id,
                   z.id AS zone_id, z.zone_code, z.name AS zone_name, z.kind,
                   z.row_count, z.seats_per_row, z.capacity, z.sort_order, z.source_template_zone_id
              FROM venues v
              LEFT JOIN venue_zones z ON z.venue_id = v.id
            """;

    private static final String ORDER = " ORDER BY v.name, z.sort_order, z.zone_code";

    /** Một hàng của kết quả join: phần địa điểm luôn có, phần khu có thể rỗng. */
    private record Row(
            UUID venueId,
            UUID organizationId,
            String name,
            String city,
            String address,
            UUID sourceTemplateId,
            VenueZone zone) {}

    private static Row mapRow(ResultSet rs, int index) throws SQLException {
        UUID venueId = rs.getObject("id", UUID.class);
        UUID zoneId = rs.getObject("zone_id", UUID.class);
        VenueZone zone = zoneId == null
                ? null
                : new VenueZone(
                        zoneId,
                        venueId,
                        rs.getString("zone_code"),
                        rs.getString("zone_name"),
                        AdmissionKind.valueOf(rs.getString("kind")),
                        nullableInt(rs, "row_count"),
                        nullableInt(rs, "seats_per_row"),
                        nullableInt(rs, "capacity"),
                        rs.getInt("sort_order"),
                        rs.getObject("source_template_zone_id", UUID.class));
        return new Row(
                venueId,
                rs.getObject("organization_id", UUID.class),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("address"),
                rs.getObject("source_template_id", UUID.class),
                zone);
    }

    /**
     * {@code getInt} trả 0 cho NULL, và 0 là một giá trị hợp lệ ở những cột này.
     *
     * <p>Không có bước kiểm {@code wasNull}, một khu ngồi đọc lên sẽ có {@code capacity = 0} thay
     * vì null, và constructor của {@link VenueZone} ném lỗi ngay — đọc lại thứ vừa ghi thành công
     * lại hỏng.
     */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** Gộp các hàng join lại thành địa điểm. {@link LinkedHashMap} để giữ thứ tự ORDER BY. */
    private static List<Venue> hydrateAll(List<Row> rows) {
        Map<UUID, List<VenueZone>> zonesByVenue = new LinkedHashMap<>();
        Map<UUID, Row> headers = new LinkedHashMap<>();

        for (Row row : rows) {
            headers.putIfAbsent(row.venueId(), row);
            List<VenueZone> zones = zonesByVenue.computeIfAbsent(row.venueId(), key -> new ArrayList<>());
            if (row.zone() != null) {
                zones.add(row.zone());
            }
        }

        return headers.values().stream()
                .map(header -> new Venue(
                        header.venueId(),
                        header.organizationId(),
                        header.name(),
                        header.city(),
                        header.address(),
                        zonesByVenue.get(header.venueId()),
                        header.sourceTemplateId()))
                .toList();
    }
}
