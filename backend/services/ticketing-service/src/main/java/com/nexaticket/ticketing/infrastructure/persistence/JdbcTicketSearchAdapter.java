// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.persistence;

import com.nexaticket.ticketing.domain.port.TicketSearchPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tra cứu vé bằng SQL dựng động.
 *
 * <h3>Vì sao dựng chuỗi thay vì viết sẵn tám câu lệnh</h3>
 *
 * <p>Bộ lọc có năm chiều độc lập, tức là 32 tổ hợp. Viết sẵn thì hoặc có 32 câu lệnh, hoặc có một
 * câu với {@code (? IS NULL OR cot = ?)} lặp năm lần — và cách thứ hai làm Postgres không dùng
 * được index nào, vì nó phải lập một kế hoạch đúng cho cả trường hợp tham số là NULL.
 *
 * <p>Nối chuỗi ở đây an toàn vì <b>không giá trị nào của người dùng đi vào câu lệnh</b>: chỉ có
 * mệnh đề cố định được nối, còn mọi giá trị đều là tham số {@code ?}. Đó là ranh giới phải giữ —
 * ngày nào có người nối thẳng một giá trị vào đây thì đó là SQL injection.
 */
@Repository
public class JdbcTicketSearchAdapter implements TicketSearchPort {

    private static final String COLUMNS =
            """
            id, order_id, event_session_id, seat_code, zone_code, seat_label, ticket_type_name,
            holder_name, status, payment_status, issued_at, checked_in_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcTicketSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Hai câu truy vấn: một đếm, một lấy trang.
     *
     * <p>Không dùng {@code count(*) OVER ()} gộp vào một câu, vì nó bắt Postgres dựng <b>toàn bộ</b>
     * tập kết quả rồi mới cắt {@code LIMIT} — với một sự kiện 20.000 vé thì mỗi lần lật trang là
     * một lần đọc 20.000 dòng. Hai câu thì câu lấy trang dừng lại ở dòng thứ 50.
     */
    @Override
    public Page search(Criteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(criteria.organizationId());

        if (criteria.eventSessionId() != null) {
            where.append(" AND event_session_id = ?");
            params.add(criteria.eventSessionId());
        }
        if (criteria.zoneCode() != null) {
            where.append(" AND zone_code = ?");
            params.add(criteria.zoneCode());
        }
        if (criteria.status() != null) {
            where.append(" AND status = ?");
            params.add(criteria.status());
        }
        if (criteria.paymentStatus() != null) {
            where.append(" AND payment_status = ?");
            params.add(criteria.paymentStatus());
        }
        if (criteria.text() != null) {
            // Ba cột, một ô nhập. `id::text` để tra được cả khi khách đọc mã vé qua điện thoại —
            // họ sẽ đọc vài ký tự đầu chứ không đọc hết 36 ký tự của một UUID.
            //
            // ILIKE '%…%' không dùng được index, và ở đây điều đó chấp nhận được: mệnh đề tổ chức
            // (và thường cả suất diễn) đã thu tập về vài nghìn dòng trước khi tới đây. Xem
            // V0101__ticket_search.sql để biết khi nào lựa chọn này hết đúng.
            where.append(" AND (id::text ILIKE ? OR seat_code ILIKE ? OR holder_name ILIKE ?)");
            String escaped = escapeLike(criteria.text());
            String contains = "%" + escaped + "%";
            // Mã vé khớp theo TIỀN TỐ, hai cột còn lại khớp theo chuỗi con: khách đọc mã vé từ đầu,
            // còn tên thì người trực gõ mỗi chữ "Lan" giữa họ tên đầy đủ.
            params.add(escaped + "%");
            params.add(contains);
            params.add(contains);
        }

        Integer total = jdbc.queryForObject("SELECT count(*) FROM tickets" + where, Integer.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(criteria.limit());
        pageParams.add(criteria.offset());

        List<Row> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM tickets" + where + " ORDER BY issued_at DESC, id LIMIT ? OFFSET ?",
                JdbcTicketSearchAdapter::map,
                pageParams.toArray());

        return new Page(rows, total == null ? 0 : total);
    }

    /**
     * Chỉ ghi khi giá trị thật sự khác — điều kiện {@code payment_status <> ?} là thứ giữ cho
     * đường đọc không sinh ra một lượt ghi ở <b>mỗi</b> lần mở trang.
     */
    @Override
    public int repairPaymentStatus(UUID orderId, String paymentStatus) {
        return jdbc.update(
                "UPDATE tickets SET payment_status = ? WHERE order_id = ? AND payment_status <> ?",
                paymentStatus,
                orderId,
                paymentStatus);
    }

    /**
     * Trung hoà ký tự đại diện trong chuỗi người dùng gõ.
     *
     * <p>Không phải chốt chặn bảo mật — giá trị đã đi qua tham số nên không có injection nào. Đây
     * là chuyện đúng/sai kết quả: tìm "50%" mà không thoát thì {@code %} thành "khớp mọi thứ", và
     * màn hình trả về toàn bộ vé của sự kiện thay vì không có dòng nào.
     */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Row map(ResultSet rs, int index) throws SQLException {
        Timestamp checkedInAt = rs.getTimestamp("checked_in_at");
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("event_session_id", UUID.class),
                rs.getString("seat_code"),
                rs.getString("zone_code"),
                rs.getString("seat_label"),
                rs.getString("ticket_type_name"),
                rs.getString("holder_name"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getTimestamp("issued_at").toInstant(),
                checkedInAt == null ? null : checkedInAt.toInstant());
    }
}
