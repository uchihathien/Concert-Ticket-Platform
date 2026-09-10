// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ledger.support.LedgerTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

/**
 * Bất biến 2 của sổ cái (ADR-1005): <b>chỉ ghi thêm</b>, và ép bằng quyền database chứ không bằng
 * quy ước.
 *
 * <h2>Vì sao test này tồn tại</h2>
 *
 * <p>V0100 đã viết {@code REVOKE UPDATE, DELETE, TRUNCATE ... FROM PUBLIC} từ đầu, và chính nó ghi
 * chú rằng lệnh đó chỉ có tác dụng khi service chạy bằng một role không phải owner. Điều kiện đó
 * chưa bao giờ được đáp ứng: initdb tạo database do chính role của service sở hữu. Owner bỏ qua mọi
 * {@code REVOKE ... FROM PUBLIC} trên bảng của mình, nên bất biến quan trọng nhất của hệ thống tiền
 * <b>chưa từng được ép</b> — trong khi tài liệu, migration và bộ test đều nói ngược lại.
 *
 * <p>Lớp lỗi ở đây không phải "thiếu một chốt chặn", mà là "một chốt chặn được tin là đang chạy".
 * Không có test nào phát hiện được vì không có test nào thử.
 *
 * <h2>Đối chứng dương là bắt buộc</h2>
 *
 * <p>Một test chỉ khẳng định "lệnh bị từ chối" sẽ xanh cả khi kết nối hỏng, bảng không tồn tại, hay
 * SQL gõ sai. Nên mỗi phép thử phủ định đi kèm một phép thử khẳng định: cùng role đó phải
 * {@code SELECT} được, và mã lỗi phải đúng {@code 42501 insufficient_privilege} chứ không phải một
 * lỗi bất kỳ.
 */
class LedgerAppendOnlyIT extends LedgerTestBase {

    /** insufficient_privilege — mã lỗi Postgres cho "permission denied". */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    @DisplayName("đối chứng: role runtime vẫn đọc được hai bảng bất biến")
    void doi_chung_van_doc_duoc() throws SQLException {
        // Không có phép thử này thì ba test bên dưới có thể xanh vì một lý do hoàn toàn khác —
        // sai mật khẩu, sai database, bảng chưa dựng.
        try (Connection conn = connectAsApp();
                Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM journal_entries")) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM postings")) {
                assertThat(rs.next()).isTrue();
            }
            try (ResultSet rs = st.executeQuery("SELECT current_user")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("ledger_app");
            }
        }
    }

    @Test
    @DisplayName("UPDATE postings bị database từ chối")
    void khong_sua_duoc_dinh_khoan() {
        // `WHERE false` cố ý: phép kiểm quyền xảy ra lúc phân tích câu lệnh, trước khi có hàng nào
        // được khớp. Nên test này không phụ thuộc vào việc database có dữ liệu hay không.
        assertPermissionDenied("UPDATE postings SET amount_vnd = amount_vnd + 1 WHERE false");
    }

    @Test
    @DisplayName("DELETE FROM journal_entries bị database từ chối")
    void khong_xoa_duoc_but_toan() {
        assertPermissionDenied("DELETE FROM journal_entries WHERE false");
    }

    @Test
    @DisplayName("UPDATE journal_entries và DELETE FROM postings cũng bị từ chối")
    void ca_hai_bang_deu_bat_bien() {
        assertPermissionDenied("UPDATE journal_entries SET memo = 'sua trom' WHERE false");
        assertPermissionDenied("DELETE FROM postings WHERE false");
    }

    @Test
    @DisplayName("TRUNCATE bị từ chối — xoá sạch lịch sử là dạng nguy hiểm nhất")
    void khong_truncate_duoc() {
        assertPermissionDenied("TRUNCATE postings");
        assertPermissionDenied("TRUNCATE journal_entries");
    }

    @Test
    @DisplayName("sửa sai vẫn làm được: role runtime INSERT được bút toán đảo")
    void van_ghi_them_duoc() throws SQLException {
        // Bất biến này KHÔNG được chặn đường sửa sai. Cách sửa đúng là ghi thêm một bút toán đảo
        // (JournalEntry.reverse), nên quyền INSERT phải còn nguyên — nếu nó cũng mất thì service
        // không ghi sổ được và ta đã đổi một lỗi lấy một lỗi tệ hơn.
        try (Connection conn = connectAsApp();
                Statement st = conn.createStatement()) {
            // Không chèn thật: chỉ cần chứng minh quyền INSERT tồn tại. `has_table_privilege` đọc
            // đúng bảng quyền mà Postgres dùng để quyết định, nên nó trả lời chính xác câu hỏi này
            // mà không để lại dữ liệu rác trong sổ cái.
            try (ResultSet rs = st.executeQuery(
                    """
                    SELECT has_table_privilege('ledger_app', 'journal_entries', 'INSERT'),
                           has_table_privilege('ledger_app', 'postings', 'INSERT'),
                           has_table_privilege('ledger_app', 'journal_entries', 'UPDATE'),
                           has_table_privilege('ledger_app', 'postings', 'DELETE')
                    """)) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("INSERT journal_entries").isTrue();
                assertThat(rs.getBoolean(2)).as("INSERT postings").isTrue();
                assertThat(rs.getBoolean(3)).as("UPDATE journal_entries").isFalse();
                assertThat(rs.getBoolean(4)).as("DELETE postings").isFalse();
            }
        }
    }

    private static void assertPermissionDenied(String sql) {
        assertThatThrownBy(() -> {
                    try (Connection conn = connectAsApp();
                            Statement st = conn.createStatement()) {
                        st.execute(sql);
                    }
                })
                .isInstanceOf(PSQLException.class)
                // Kiểm MÃ LỖI chứ không kiểm thông điệp: thông điệp đổi theo locale và theo phiên
                // bản Postgres, còn 42501 thì không. Một lỗi cú pháp sẽ mang mã khác và làm test đỏ
                // — đúng như mong muốn.
                .satisfies(e -> assertThat(((PSQLException) e).getSQLState())
                        .as("SQLState cho: %s", sql)
                        .isEqualTo(INSUFFICIENT_PRIVILEGE));
    }
}
