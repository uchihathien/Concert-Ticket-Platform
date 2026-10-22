// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.HandoverNote;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
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
            id, session_id, user_id, status, trigger_kind, intent, reason, details, last_question,
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
        // Thử lại vài lần: INSERT bị bỏ vì đụng phiếu mở, rồi phiếu ấy bị đóng (người trực, reaper)
        // TRƯỚC câu SELECT — lần sau INSERT sẽ vào được. Trước đây trường hợp này ném thẳng và khách
        // bấm "gặp nhân viên" nhận 500 (R-04).
        for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
            Optional<Handoff> opened = tryOpen(candidate);
            if (opened.isPresent()) {
                return opened.get();
            }
        }
        throw new IllegalStateException("Không mở được phiếu cho phiên " + candidate.sessionId());
    }

    private static final int OPEN_ATTEMPTS = 3;

    private Optional<Handoff> tryOpen(Handoff candidate) {
        int inserted = db.sql(
                        """
                        insert into chat_handoffs
                            (id, session_id, user_id, status, trigger_kind, intent, reason, details,
                             last_question, requested_at)
                        values (:id, :sessionId, :userId, :status, :trigger, :intent, :reason, :details,
                                :lastQuestion, :requestedAt)
                        on conflict do nothing
                        """)
                .param("id", candidate.id())
                .param("sessionId", candidate.sessionId())
                .param("userId", candidate.userId())
                .param("status", candidate.status().name())
                .param("trigger", candidate.trigger().name())
                .param("intent", candidate.intent().name())
                .param("reason", candidate.reason())
                .param("details", candidate.details())
                .param("lastQuestion", candidate.lastQuestion())
                .param("requestedAt", Timestamp.from(candidate.requestedAt()))
                .update();

        // Không chèn được mà cũng không có phiếu mở: có ai vừa đóng phiếu giữa hai lệnh — vòng ngoài
        // thử lại.
        return inserted > 0 ? Optional.of(candidate) : openBySession(candidate.sessionId());
    }

    /**
     * Vị từ "phiếu đang mở" viết ĐÚNG như vị từ của các index bộ phận ({@code idx_handoff_queue},
     * {@code idx_handoff_queue_intent}, {@code uq_handoff_open_per_session}).
     *
     * <p>Bản trước viết {@code status <> 'RESOLVED'}: cùng nghĩa với CHECK constraint, nhưng planner
     * của Postgres không dùng CHECK để chứng minh truy vấn nằm trong vị từ của index, nên cả ba index
     * bị bỏ qua và {@code openFor} — chạy ở MỌI lượt chat — quét toàn bảng (R-05).
     */
    private static final String OPEN = "status in ('WAITING', 'ASSIGNED')";

    @Override
    public Optional<Handoff> openBySession(UUID sessionId) {
        return db.sql("select " + COLUMNS + " from chat_handoffs where session_id = :sessionId and " + OPEN)
                .param("sessionId", sessionId)
                .query(JdbcHandoffRepository::map)
                .optional();
    }

    /**
     * Vị từ {@code intent = 'REFUND' and OPEN} khớp đúng index bộ phận {@code idx_handoff_queue_intent};
     * {@code details} là TEXT JSON do ObjectMapper ghi, nên ép {@code ::jsonb} an toàn, và
     * {@code null} chỉ cho ra {@code null}.
     */
    @Override
    public Optional<Handoff> findOpenRefund(UUID userId, UUID orderId) {
        return db.sql("select " + COLUMNS + " from chat_handoffs where user_id = :userId and intent = 'REFUND' and "
                        + OPEN + " and details::jsonb ->> 'orderId' = :orderId order by requested_at limit 1")
                .param("userId", userId)
                .param("orderId", orderId.toString())
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
            UUID mine,
            Set<HandoffStatus> statuses,
            SupportIntent intent,
            String query,
            boolean newestFirst,
            int limit,
            int offset) {
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
        if (intent != null) {
            sql.append(" and intent = :intent");
        }

        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            // Tìm trong lý do chuyển VÀ câu hỏi cuối của khách. Hai cột này là tất cả những gì hiện
            // trên một dòng của danh sách, nên tìm đúng những gì người ta đang nhìn.
            //
            // ILIKE: không phân biệt hoa thường, nhưng CÓ phân biệt dấu — "Trinh" không ra "Trịnh".
            // Chấp nhận được cho một ô tìm nội bộ; sửa được bằng extension `unaccent` nếu cần.
            //
            // `%`, `_`, `\` trong ô tìm là ký tự THẬT, không phải ký tự đại diện: thoát chúng, nếu
            // không `q=_` khớp mọi phiếu và biến ô tìm thành một lần quét toàn bảng (R-06).
            sql.append(" and (reason ilike :q escape '\\' or coalesce(last_question, '') ilike :q escape '\\')");
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
        if (intent != null) {
            statement = statement.param("intent", intent.name());
        }
        if (hasQuery) {
            statement = statement.param("q", "%" + escapeLike(query.strip()) + "%");
        }
        if (mine != null) {
            statement = statement.param("mine", mine);
        }
        return statement.query(JdbcHandoffRepository::map).list();
    }

    /** Thoát ký tự đại diện của LIKE — đi cùng {@code escape '\'} trong câu lệnh. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Handoff> queue(UUID mine, SupportIntent intent, int limit, int offset) {
        String scope = mine == null ? "" : " and assigned_agent_id = :mine";
        String byIntent = intent == null ? "" : " and intent = :intent";
        var query = db.sql("select " + COLUMNS
                        + " from chat_handoffs where " + OPEN
                        + scope
                        + byIntent
                        + " order by requested_at limit :limit offset :offset")
                .param("limit", limit)
                .param("offset", offset);
        if (mine != null) {
            query = query.param("mine", mine);
        }
        if (intent != null) {
            query = query.param("intent", intent.name());
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

    /**
     * {@code where status = :expected and assigned_agent_id is not distinct from :expectedAgent} —
     * cùng nguyên tắc với {@link #claim}: chốt chặn tranh chấp nằm trong chính câu UPDATE.
     *
     * <p>{@code is not distinct from} chứ không {@code =}: phiếu WAITING có {@code assigned_agent_id}
     * null, và {@code null = null} là null chứ không phải true — lệnh sẽ không bao giờ khớp.
     */
    @Override
    public boolean transition(UUID handoffId, HandoffStatus expectedStatus, UUID expectedAgentId, Handoff next) {
        int updated = db.sql(
                        """
                        update chat_handoffs
                           set status = :status, assigned_agent_id = :agentId,
                               assigned_at = :assignedAt, resolved_at = :resolvedAt
                         where id = :id
                           and status = :expected
                           and assigned_agent_id is not distinct from cast(:expectedAgent as uuid)
                        """)
                .param("status", next.status().name())
                .param("agentId", next.assignedAgentId())
                .param("assignedAt", next.assignedAt() == null ? null : Timestamp.from(next.assignedAt()))
                .param("resolvedAt", next.resolvedAt() == null ? null : Timestamp.from(next.resolvedAt()))
                .param("id", handoffId)
                .param("expected", expectedStatus.name())
                .param("expectedAgent", expectedAgentId == null ? null : expectedAgentId.toString())
                .update();
        return updated == 1;
    }

    /**
     * Biểu thức ghi {@code details.lastHandover}, dùng chung cho nhả/chuyển tay và nhả tự động.
     *
     * <p>{@code details} là TEXT; ép {@code ::jsonb} để nối {@code ||} — giữ mọi khoá cũ (mã đơn,
     * loại sự cố…), chỉ ghi đè {@code lastHandover} — rồi trả về {@code ::text}. Mọi tham số đều ép
     * kiểu tường minh: {@code jsonb_build_object} nhận kiểu "any", và một tham số null không kiểu làm
     * Postgres báo "could not determine data type".
     */
    private static final String WITH_HANDOVER_NOTE =
            """
            (coalesce(details::jsonb, '{}'::jsonb) || jsonb_build_object('lastHandover', jsonb_build_object(
                'action', cast(:action as text),
                'by', cast(:by as text),
                'from', %s,
                'to', cast(:to as text),
                'reason', cast(:reason as text),
                'at', cast(:now as timestamptz))))::text""";

    @Override
    public boolean handover(
            UUID handoffId,
            HandoffStatus expectedStatus,
            UUID expectedAgent,
            UUID newAgent,
            Instant now,
            HandoverNote note) {
        int updated = db.sql(
                        """
                        update chat_handoffs
                           set status = :status,
                               assigned_agent_id = cast(:newAgent as uuid),
                               assigned_at = :assignedAt,
                               details = %s
                         where id = :id
                           and status = :expected
                           and assigned_agent_id is not distinct from cast(:expectedAgent as uuid)
                        """
                                .formatted(WITH_HANDOVER_NOTE.formatted("cast(:from as text)")))
                .param("status", newAgent == null ? HandoffStatus.WAITING.name() : HandoffStatus.ASSIGNED.name())
                .param("newAgent", newAgent == null ? null : newAgent.toString())
                // ck_handoff_assigned: người giữ và mốc nhận cùng có hoặc cùng không.
                .param("assignedAt", newAgent == null ? null : Timestamp.from(now))
                .param("action", note.action())
                .param("by", note.by() == null ? null : note.by().toString())
                .param("from", note.from() == null ? null : note.from().toString())
                .param("to", note.to() == null ? null : note.to().toString())
                .param("reason", note.reason())
                .param("now", Timestamp.from(now))
                .param("id", handoffId)
                .param("expected", expectedStatus.name())
                .param("expectedAgent", expectedAgent == null ? null : expectedAgent.toString())
                .update();
        return updated == 1;
    }

    /**
     * Một UPDATE, có điều kiện {@code status = 'ASSIGNED'} — nhiều instance chạy cùng lúc vẫn an toàn
     * như {@link #closeAbandoned}. "Còn hoạt động" = có tin AGENT kể từ {@code before}; index
     * {@code idx_chat_messages_session (session_id, created_at desc, id desc)} phục vụ truy vấn con.
     */
    @Override
    public int releaseStaleAssigned(Instant before, Instant now) {
        return db.sql(
                        """
                        update chat_handoffs h
                           set status = 'WAITING',
                               assigned_agent_id = null,
                               assigned_at = null,
                               details = %s
                         where h.status = 'ASSIGNED'
                           and h.assigned_at < :before
                           and not exists (
                               select 1 from chat_messages m
                                where m.session_id = h.session_id
                                  and m.role = 'AGENT'
                                  and m.created_at >= :before)
                        """
                                .formatted(WITH_HANDOVER_NOTE.formatted("h.assigned_agent_id::text")))
                .param("action", HandoverNote.AUTO_RELEASE)
                .param("by", null)
                .param("to", null)
                .param("reason", null)
                .param("now", Timestamp.from(now))
                .param("before", Timestamp.from(before))
                .update();
    }

    @Override
    public Optional<Handoff> lockById(UUID handoffId) {
        return db.sql("select " + COLUMNS + " from chat_handoffs where id = :id for update")
                .param("id", handoffId)
                .query(JdbcHandoffRepository::map)
                .optional();
    }

    /**
     * Một lệnh UPDATE, không phải đọc-rồi-ghi từng phiếu.
     *
     * <p>`status = 'WAITING'` trong mệnh đề WHERE vừa là bộ lọc vừa là chốt chặn tranh chấp: một
     * người trực vừa bấm Nhận đúng lúc job chạy thì phiếu đã sang ASSIGNED và lệnh này không đụng
     * tới nó. Ghi thêm lý do vào `reason` chứ không xoá dấu vết — "phiếu này tự đóng vì quá hạn"
     * là dữ liệu cần để biết hàng đợi đang bỏ rơi bao nhiêu người.
     *
     * <p>Mốc tính quá hạn là lần cuối phiếu về hàng đợi ({@code lastHandover.at}) nếu có, không phải
     * lúc khách yêu cầu: nhả phiếu xoá {@code assigned_at}, và một phiếu yêu cầu từ 30 giờ trước vừa
     * được nhả lúc này mà tính theo {@code requested_at} thì bị đóng ngay ở lần chạy kế tiếp — đúng
     * điều việc "nhả, không đóng" muốn tránh.
     */
    @Override
    public int closeAbandoned(Instant before, Instant now) {
        return db.sql(
                        """
                        update chat_handoffs
                           set status = 'RESOLVED',
                               resolved_at = :now,
                               reason = reason || ' [tự đóng: quá hạn chờ]'
                         where status = 'WAITING'
                           and coalesce((details::jsonb #>> '{lastHandover,at}')::timestamptz, requested_at) < :before
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
                SupportIntent.valueOf(rs.getString("intent")),
                rs.getString("reason"),
                rs.getString("details"),
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
