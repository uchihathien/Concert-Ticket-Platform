// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Truy vấn phiếu mở dùng index — đo bằng {@code EXPLAIN} trên Postgres thật với 10.000 phiếu
 * RESOLVED (R-05), và ô tìm coi {@code %}/{@code _} là ký tự thật (R-06).
 *
 * <p>Trước đây vị từ {@code status <> 'RESOLVED'} khiến planner bỏ qua mọi index bộ phận: bảng chỉ
 * lớn lên, và {@code openFor} chạy ở mọi lượt chat.
 */
class HandoffQueryPlanIT extends AiChatboxTestBase {

    private static final int RESOLVED_ROWS = 10_000;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HandoffRepository repository;

    @BeforeEach
    void seed() {
        // Phiên + phiếu RESOLVED hàng loạt bằng SQL thuần: dựng 10.000 phiếu qua use case mất vài phút.
        jdbc.sql(
                        """
                        insert into chat_sessions (id, user_id)
                        select gen_random_uuid(), gen_random_uuid() from generate_series(1, :n)
                        """)
                .param("n", RESOLVED_ROWS)
                .update();
        jdbc.sql(
                        """
                        insert into chat_handoffs
                            (id, session_id, user_id, status, trigger_kind, intent, reason, requested_at, resolved_at)
                        select gen_random_uuid(), s.id, s.user_id, 'RESOLVED', 'CUSTOMER_REQUEST', 'GENERAL',
                               'đã xong', now() - interval '2 days', now() - interval '1 day'
                          from chat_sessions s
                        """)
                .update();
        jdbc.sql("analyze chat_handoffs").update();
    }

    private String plan(String sql, Object... params) {
        var statement = jdbc.sql("explain " + sql);
        for (int i = 0; i < params.length; i += 2) {
            statement = statement.param((String) params[i], params[i + 1]);
        }
        return String.join("\n", statement.query(String.class).list());
    }

    private String sqlOf(String where) {
        return "select id from chat_handoffs where " + where;
    }

    @Test
    void phieu_mo_cua_phien_khong_quet_toan_bang() {
        String plan = plan(sqlOf("session_id = :s and status in ('WAITING', 'ASSIGNED')"), "s", UUID.randomUUID());

        assertThat(plan).doesNotContain("Seq Scan on chat_handoffs");
    }

    @Test
    void hang_doi_khong_quet_toan_bang() {
        String plan = plan(sqlOf("status in ('WAITING', 'ASSIGNED') order by requested_at limit 50"));

        assertThat(plan).doesNotContain("Seq Scan on chat_handoffs");
    }

    @Test
    void hang_doi_theo_y_dinh_dung_index_intent() {
        // Hàng đợi thật có phiếu đang mở thuộc nhiều ý định. Không có chúng thì hai index bộ phận
        // đều rỗng, chi phí bằng nhau, và planner chọn cái nào cũng được — phép đo vô nghĩa.
        jdbc.sql(
                        """
                        with s as (
                            insert into chat_sessions (id, user_id)
                            select gen_random_uuid(), gen_random_uuid() from generate_series(1, 4000)
                            returning id, user_id)
                        insert into chat_handoffs
                            (id, session_id, user_id, status, trigger_kind, intent, reason, requested_at)
                        select gen_random_uuid(), s.id, s.user_id, 'WAITING', 'CUSTOMER_REQUEST',
                               (array['GENERAL','ORDER_STATUS','BOOKING','REFUND','INCIDENT'])[1 + (random() * 4)::int],
                               'đang chờ', now() - (random() * interval '10 hours')
                          from s
                        """)
                .update();
        jdbc.sql("analyze chat_handoffs").update();

        String plan = plan(
                sqlOf("status in ('WAITING', 'ASSIGNED') and intent = :i order by requested_at limit 50"),
                "i",
                "REFUND");

        // Planner được chọn GIỮA hai index bộ phận của hàng đợi: với LIMIT 50 và ~20% phiếu cùng ý
        // định, đi theo thứ tự `idx_handoff_queue` rồi lọc rẻ ngang `idx_handoff_queue_intent`. Điều
        // phải đúng là KHÔNG quét toàn bảng và dùng một index của phiếu đang mở.
        assertThat(plan)
                .doesNotContain("Seq Scan on chat_handoffs")
                .containsAnyOf("idx_handoff_queue_intent", "idx_handoff_queue ");
    }

    /** Đường thật của repository vẫn chạy đúng sau khi đổi vị từ. */
    @Test
    void repository_van_tra_dung_phieu_mo() {
        assertThat(repository.queue(null, null, 50, 0)).isEmpty();
        assertThat(repository.queue(null, SupportIntent.REFUND, 50, 0)).isEmpty();
    }

    @Test
    void ky_tu_dai_dien_trong_o_tim_la_ky_tu_that() {
        UUID session = UUID.randomUUID();
        jdbc.sql("insert into chat_sessions (id, user_id) values (:s, :u)")
                .param("s", session)
                .param("u", UUID.randomUUID())
                .update();
        jdbc.sql(
                        """
                        insert into chat_handoffs
                            (id, session_id, user_id, status, trigger_kind, intent, reason, requested_at, resolved_at)
                        values (gen_random_uuid(), :s, gen_random_uuid(), 'RESOLVED', 'CUSTOMER_REQUEST', 'GENERAL',
                                'giảm 50% cho mã_ABC', now(), now())
                        """)
                .param("s", session)
                .update();
        Set<HandoffStatus> all = Set.of();

        List<?> underscore = repository.search(null, all, null, "_", true, 100, 0);
        List<?> percent = repository.search(null, all, null, "%", true, 100, 0);
        List<?> literal = repository.search(null, all, null, "mã_ABC", true, 100, 0);

        // 10.000 phiếu "đã xong" không có `_` hay `%` — chỉ đúng một phiếu khớp.
        assertThat(underscore).hasSize(1);
        assertThat(percent).hasSize(1);
        assertThat(literal).hasSize(1);
    }
}
