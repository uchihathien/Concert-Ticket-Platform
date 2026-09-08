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
                INSERT INTO venues (id, organization_id, name, city, address)
                VALUES (?, ?, ?, ?, ?)
                """,
                venue.id(),
                venue.organizationId(),
                venue.name(),
                venue.city(),
                venue.address());
    }

    @Override
    public void addZone(VenueZone zone) {
        jdbc.update(
                """
                INSERT INTO venue_zones (id, venue_id, zone_code, name, kind,
                                         row_count, seats_per_row, capacity, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                zone.id(),
                zone.venueId(),
                zone.zoneCode(),
                zone.name(),
                zone.kind().name(),
                zone.rowCount(),
                zone.seatsPerRow(),
                zone.capacity(),
                zone.sortOrder());
    }

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
            SELECT v.id, v.organization_id, v.name, v.city, v.address,
                   z.id AS zone_id, z.zone_code, z.name AS zone_name, z.kind,
                   z.row_count, z.seats_per_row, z.capacity, z.sort_order
              FROM venues v
              LEFT JOIN venue_zones z ON z.venue_id = v.id
            """;

    private static final String ORDER = " ORDER BY v.name, z.sort_order, z.zone_code";

    /** Một hàng của kết quả join: phần địa điểm luôn có, phần khu có thể rỗng. */
    private record Row(UUID venueId, UUID organizationId, String name, String city, String address, VenueZone zone) {}

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
                        rs.getInt("sort_order"));
        return new Row(
                venueId,
                rs.getObject("organization_id", UUID.class),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("address"),
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
                        zonesByVenue.get(header.venueId())))
                .toList();
    }
}
