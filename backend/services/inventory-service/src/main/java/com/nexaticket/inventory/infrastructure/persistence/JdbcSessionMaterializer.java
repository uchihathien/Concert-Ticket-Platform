// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.persistence;

import com.nexaticket.inventory.domain.port.SessionMaterializer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Dựng {@code session_inventory} và {@code session_seats}.
 *
 * <p><b>Materialize lại một suất đã có tồn kho là KHÔNG được phép</b> và bị bỏ qua lặng lẽ. Lý do
 * không phải kỹ thuật: nếu suất đã bán vé, dựng lại danh sách chỗ sẽ xoá hoặc dịch chuyển những
 * chỗ mà khách đang giữ và đã trả tiền. Sửa một suất đang bán là một nghiệp vụ riêng, phức tạp
 * hơn nhiều so với "chạy lại materialize", và nó phải được thiết kế tường minh chứ không rơi ra
 * như tác dụng phụ của một message trùng.
 */
@Repository
public class JdbcSessionMaterializer implements SessionMaterializer {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionMaterializer.class);

    private final JdbcTemplate jdbc;

    public JdbcSessionMaterializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dựng tồn kho cho một suất, và <b>đồng bộ lại cấu hình</b> nếu suất đã có.
     *
     * <p>Bản trước dùng {@code ON CONFLICT DO NOTHING} cho cả dòng suất, nên cửa bán và các trần
     * mua vé chỉ được ghi đúng MỘT LẦN — lúc publish đầu tiên. Ban tổ chức sửa giờ mở bán rồi
     * publish lại thì Catalog đổi, màn hình quản trị nói đã đổi, còn Inventory — chỗ thật sự chặn
     * khách — vẫn giữ giá trị cũ vĩnh viễn. Không có lỗi nào hiện ra ở đâu cả.
     *
     * <p>{@code RETURNING (xmax = 0)} phân biệt INSERT với UPDATE, và phân biệt đó là BẮT BUỘC:
     * người gọi dùng kết quả {@code > 0} để biết "suất vừa dựng xong" rồi mới chạy bộ dữ liệu mẫu.
     * Trả 1 cho một lần UPDATE sẽ khiến bộ dữ liệu mẫu bán thêm một mớ ghế ngẫu nhiên vào một suất
     * có thể đã bán vé thật.
     */
    @Override
    public int materialize(SessionManifest manifest) {
        Boolean inserted = jdbc.queryForObject(
                """
                INSERT INTO session_inventory (
                    id, event_session_id, event_id, organization_id, sales_open_at, sales_close_at,
                    max_seated_per_hold, max_standing_per_hold, max_units_per_hold,
                    max_tickets_per_customer, materialized_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_session_id) DO UPDATE SET
                    sales_open_at            = EXCLUDED.sales_open_at,
                    sales_close_at           = EXCLUDED.sales_close_at,
                    max_seated_per_hold      = EXCLUDED.max_seated_per_hold,
                    max_standing_per_hold    = EXCLUDED.max_standing_per_hold,
                    max_units_per_hold       = EXCLUDED.max_units_per_hold,
                    max_tickets_per_customer = EXCLUDED.max_tickets_per_customer,
                    materialized_at          = EXCLUDED.materialized_at
                RETURNING (xmax = 0)
                """,
                Boolean.class,
                UUID.randomUUID(),
                manifest.eventSessionId(),
                manifest.eventId(),
                manifest.organizationId(),
                Timestamp.from(manifest.salesOpenAt()),
                Timestamp.from(manifest.salesCloseAt()),
                manifest.maxSeatedPerHold(),
                manifest.maxStandingPerHold(),
                manifest.maxUnitsPerHold(),
                manifest.maxTicketsPerCustomer(),
                Timestamp.from(Instant.now()));

        if (!Boolean.TRUE.equals(inserted)) {
            log.info(
                    "Suất {} đã có tồn kho: đã đồng bộ lại cửa bán và trần mua vé, không dựng lại chỗ",
                    manifest.eventSessionId());
            return 0;
        }

        List<Object[]> rows = new ArrayList<>();
        for (SeatLine seat : manifest.seats()) {
            rows.add(new Object[] {
                UUID.randomUUID(),
                manifest.eventSessionId(),
                seat.seatCode(),
                seat.zoneCode(),
                "SEATED",
                seat.sectionLabel(),
                seat.rowLabel(),
                seat.seatLabel(),
                seat.posX(),
                seat.posY(),
                seat.ticketTypeId(),
                seat.ticketTypeName(),
                seat.priceVnd(),
                // Chỗ bị chặn vẫn có mặt trong tồn kho để sơ đồ hiện đúng hình dạng khán phòng,
                // nhưng ở trạng thái BLOCKED nên không lọt vào đường giữ chỗ.
                seat.blocked() ? "BLOCKED" : "AVAILABLE"
            });
        }
        for (StandingBlock block : manifest.standingBlocks()) {
            rows.addAll(expandStanding(manifest.eventSessionId(), block));
        }

        jdbc.batchUpdate(
                """
                INSERT INTO session_seats (
                    id, event_session_id, seat_code, zone_code, admission_type,
                    section_label, row_label, seat_label, pos_x, pos_y,
                    ticket_type_id, ticket_type_name, price_vnd, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows);

        log.info("Đã dựng {} đơn vị tồn kho cho suất {}", rows.size(), manifest.eventSessionId());
        return rows.size();
    }

    /**
     * Sinh đơn vị ảo cho vé đứng (ADR-1012).
     *
     * <p>Mỗi người vào cửa cần một vé QR riêng để soát, nên vẫn phải có một đơn vị tồn kho cho mỗi
     * người — chỉ là danh tính đơn vị đó không có ý nghĩa với khách và không hiện trên sơ đồ.
     * Nhờ cách này, chốt chặn oversell là <b>y hệt</b> cho cả hai loại vé.
     */
    private static List<Object[]> expandStanding(UUID eventSessionId, StandingBlock block) {
        List<Object[]> rows = new ArrayList<>(block.quantity());
        for (int i = 1; i <= block.quantity(); i++) {
            rows.add(new Object[] {
                UUID.randomUUID(),
                eventSessionId,
                "%s-GA-%06d".formatted(block.zoneCode(), i),
                block.zoneCode(),
                "STANDING",
                null,
                null,
                null,
                null,
                null,
                block.ticketTypeId(),
                block.ticketTypeName(),
                block.priceVnd(),
                "AVAILABLE"
            });
        }
        return rows;
    }
}
