// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Phiếu chuyển tiếp trong {@code ai_chatbox_db}. */
@Repository
public class JdbcHandoffRepository implements HandoffRepository {

    private static final String COLUMNS =
            """
            id, session_id, user_id, status, trigger_kind, reason, last_question,
            assigned_agent_id, requested_at, assigned_at, resolved_at
            """;

    private final JdbcClient db;

    public JdbcHandoffRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Chèn, và nếu phiên đã có phiếu đang mở thì trả lại phiếu ấy.
     *
     * <p>{@code on conflict do nothing} dựa vào unique index bộ phận
     * {@code uq_handoff_open_per_session}, rồi đọc lại. Hai bước, nhưng bước hai chỉ chạy khi bước
     * một không chèn được — và lúc đó phiếu chắc chắn đã tồn tại, nên không có khoảng trống nào
     * giữa hai bước để một request khác chen vào.
     *
     * <p>Cách sai là "đọc xem có chưa, chưa thì chèn": hai request đến cùng lúc đều đọc thấy chưa
     * có, và cái thứ hai vỡ ở unique index dưới dạng 500 thay vì trả về phiếu đang chờ.
     */
    @Override
    public Handoff openOrExisting(Handoff candidate) {
        int inserted = db.sql(
                        """
                        insert into chat_handoffs
                            (id, session_id, user_id, status, trigger_kind, reason, last_question, requested_at)
                        values (:id, :sessionId, :userId, :status, :trigger, :reason, :lastQuestion, :requestedAt)
                        on conflict do nothing
                        """)
                .param("id", candidate.id())
                .param("sessionId", candidate.sessionId())
                .param("userId", candidate.userId())
                .param("status", candidate.status().name())
                .param("trigger", candidate.trigger().name())
                .param("reason", candidate.reason())
                .param("lastQuestion", candidate.lastQuestion())
                .param("requestedAt", Timestamp.from(candidate.requestedAt()))
                .update();

        return inserted > 0
                ? candidate
                : openBySession(candidate.sessionId())
                        .orElseThrow(() ->
                                // Không chèn được mà cũng không có phiếu mở: chỉ xảy ra nếu có ai
                                // vừa đóng phiếu giữa hai lệnh. Ném để nó lộ ra thay vì trả null.
                                new IllegalStateException("Không mở được phiếu cho phiên " + candidate.sessionId()));
    }

    @Override
    public Optional<Handoff> openBySession(UUID sessionId) {
        return db.sql("select " + COLUMNS
                        + " from chat_handoffs where session_id = :sessionId and status <> 'RESOLVED'")
                .param("sessionId", sessionId)
                .query(JdbcHandoffRepository::map)
                .optional();
    }

    @Override
    public Optional<Handoff> findById(UUID handoffId) {
        return db.sql("select " + COLUMNS + " from chat_handoffs where id = :id")
                .param("id", handoffId)
                .query(JdbcHandoffRepository::map)
                .optional();
    }

    /**
     * Cũ nhất trước: hàng đợi hỗ trợ là FIFO, và sắp theo thứ khác nghĩa là có người chờ mãi không
     * tới lượt.
     */
    @Override
    public List<Handoff> search(
            UUID mine, Set<HandoffStatus> statuses, String query, boolean newestFirst, int limit, int offset) {
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from chat_handoffs where 1 = 1");

        // Danh sách tham số rời cho mỗi trạng thái, KHÔNG phải một tham số dạng mảng: JdbcClient gắn
        // `in (:statuses)` được, nhưng viết tay từng tham số thì câu lệnh sinh ra đọc giống hệt câu
        // lệnh chạy, và đó là thứ cứu thời gian khi phải soi log truy vấn chậm.
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" and status in (");
            int i = 0;
            for (HandoffStatus ignored : statuses) {
                sql.append(i++ == 0 ? ":s" : ", :s").append(i - 1);
            }
            sql.append(')');
        }

        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            // Tìm trong lý do chuyển VÀ câu hỏi cuối của khách. Hai cột này là tất cả những gì hiện
            // trên một dòng của danh sách, nên tìm đúng những gì người ta đang nhìn.
            //
            // ILIKE: không phân biệt hoa thường, nhưng CÓ phân biệt dấu — "Trinh" không ra "Trịnh".
            // Chấp nhận được cho một ô tìm nội bộ; sửa được bằng extension `unaccent` nếu cần.
            sql.append(" and (reason ilike :q or coalesce(last_question, '') ilike :q)");
        }
        if (mine != null) {
            sql.append(" and assigned_agent_id = :mine");
        }
        sql.append(" order by requested_at ")
                .append(newestFirst ? "desc" : "asc")
                .append(" limit :limit offset :offset");

        var statement = db.sql(sql.toString()).param("limit", limit).param("offset", offset);
        if (statuses != null && !statuses.isEmpty()) {
            int i = 0;
            for (HandoffStatus status : statuses) {
                statement = statement.param("s" + i++, status.name());
            }
        }
        if (hasQuery) {
            statement = statement.param("q", "%" + query.strip() + "%");
        }
        if (mine != null) {
            statement = statement.param("mine", mine);
        }
        return statement.query(JdbcHandoffRepository::map).list();
    }

    @Override
    public List<Handoff> queue(UUID mine, int limit, int offset) {
        String scope = mine == null ? "" : " and assigned_agent_id = :mine";
        var query = db.sql("select " + COLUMNS
                        + " from chat_handoffs where status <> 'RESOLVED'"
                        + scope
                        + " order by requested_at limit :limit offset :offset")
                .param("limit", limit)
                .param("offset", offset);
        if (mine != null) {
            query = query.param("mine", mine);
        }
        return query.query(JdbcHandoffRepository::map).list();
    }

    /**
     * {@code where status = 'WAITING'} là phần quan trọng nhất của câu lệnh này.
     *
     * <p>Nó biến "nhận phiếu" thành một phép nguyên tử: hai người trực bấm cùng lúc thì một người
     * cập nhật được 1 dòng và một người cập nhật được 0 dòng. Đọc-kiểm-rồi-ghi để cả hai cùng
     * thắng, và khách nhận hai câu chào khác nhau.
     */
    @Override
    public Optional<Handoff> claim(UUID handoffId, UUID agentId, Instant now) {
        int updated = db.sql(
                        """
                        update chat_handoffs
                           set status = 'ASSIGNED', assigned_agent_id = :agentId, assigned_at = :now
                         where id = :id and status = 'WAITING'
                        """)
                .param("agentId", agentId)
                .param("now", Timestamp.from(now))
                .param("id", handoffId)
                .update();

        return updated == 0 ? Optional.empty() : findById(handoffId);
    }

    @Override
    public void save(Handoff handoff) {
        db.sql(
                        """
                        update chat_handoffs
                           set status = :status, assigned_agent_id = :agentId,
                               assigned_at = :assignedAt, resolved_at = :resolvedAt
                         where id = :id
                        """)
                .param("status", handoff.status().name())
                .param("agentId", handoff.assignedAgentId())
                .param("assignedAt", handoff.assignedAt() == null ? null : Timestamp.from(handoff.assignedAt()))
                .param("resolvedAt", handoff.resolvedAt() == null ? null : Timestamp.from(handoff.resolvedAt()))
                .param("id", handoff.id())
                .update();
    }

    /**
     * Một lệnh UPDATE, không phải đọc-rồi-ghi từng phiếu.
     *
     * <p>`status = 'WAITING'` trong mệnh đề WHERE vừa là bộ lọc vừa là chốt chặn tranh chấp: một
     * người trực vừa bấm Nhận đúng lúc job chạy thì phiếu đã sang ASSIGNED và lệnh này không đụng
     * tới nó. Ghi thêm lý do vào `reason` chứ không xoá dấu vết — "phiếu này tự đóng vì quá hạn"
     * là dữ liệu cần để biết hàng đợi đang bỏ rơi bao nhiêu người.
     */
    @Override
    public int closeAbandoned(Instant before, Instant now) {
        return db.sql(
                        """
                        update chat_handoffs
                           set status = 'RESOLVED',
                               resolved_at = :now,
                               reason = reason || ' [tự đóng: quá hạn chờ]'
                         where status = 'WAITING' and requested_at < :before
                        """)
                .param("now", Timestamp.from(now))
                .param("before", Timestamp.from(before))
                .update();
    }

    private static Handoff map(ResultSet rs, int rowNum) throws SQLException {
        return new Handoff(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                HandoffStatus.valueOf(rs.getString("status")),
                HandoffTrigger.valueOf(rs.getString("trigger_kind")),
                rs.getString("reason"),
                rs.getString("last_question"),
                rs.getObject("assigned_agent_id", UUID.class),
                rs.getTimestamp("requested_at").toInstant(),
                instantOrNull(rs.getTimestamp("assigned_at")),
                instantOrNull(rs.getTimestamp("resolved_at")));
    }

    private static Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
