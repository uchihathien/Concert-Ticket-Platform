// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.application.query.TicketSearchQuery;
import com.nexaticket.ticketing.application.query.TicketSearchViews;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.domain.port.TicketSearchPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phần vá trạng thái thanh toán lúc đọc.
 *
 * <p>Đây là chỗ duy nhất trong service biết rằng {@code tickets.payment_status} là một bản chụp có
 * thể cũ. Luật ở đây không cần database để đúng, nên nó được kiểm bằng cổng giả — chạy trong mili
 * giây, và chạy được ở máy không có Docker.
 */
class TicketSearchQueryTest {

    private static final UUID ORG = UUID.randomUUID();

    @Test
    @DisplayName("ordering nói đơn đã hoàn tiền thì màn hình nói vậy, và bản chụp được sửa lại")
    void hoan_tien_duoc_va_lai() {
        UUID orderId = UUID.randomUUID();
        FakeSearch search = new FakeSearch(row(orderId, "PAID"));
        FakeOrdering ordering = new FakeOrdering(Map.of(orderId, "REFUNDED"));

        var page = query(search, ordering).search(ORG, filter());

        // Người dùng thấy sự thật của ordering, không thấy bản chụp cũ.
        assertThat(page.rows().get(0).paymentStatus()).isEqualTo("REFUNDED");
        // Và dòng được sửa lại, nên lần lọc sau theo REFUNDED sẽ tìm thấy nó.
        assertThat(search.repairs).containsExactly(Map.entry(orderId, "REFUNDED"));
    }

    @Test
    @DisplayName("bản chụp đã đúng thì không ghi gì — đường đọc không được sinh ghi ở mỗi lần mở trang")
    void dung_roi_thi_khong_ghi() {
        UUID orderId = UUID.randomUUID();
        FakeSearch search = new FakeSearch(row(orderId, "PAID"));
        FakeOrdering ordering = new FakeOrdering(Map.of(orderId, "PAID"));

        query(search, ordering).search(ORG, filter());

        assertThat(search.repairs).isEmpty();
    }

    @Test
    @DisplayName("ordering im lặng thì vẫn trả bản chụp, không làm sập cả màn hình")
    void ordering_im_lang_van_tra_ket_qua() {
        // Cổng trả map rỗng khi hỏng (xem OrderingPort.statusesOf) — đánh đổi có chủ đích: mất một
        // cột còn hơn mất cả trang tra cứu vì một service phụ.
        UUID orderId = UUID.randomUUID();
        FakeSearch search = new FakeSearch(row(orderId, "PAID"));

        var page = query(search, new FakeOrdering(Map.of())).search(ORG, filter());

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).paymentStatus()).isEqualTo("PAID");
        assertThat(search.repairs).isEmpty();
    }

    @Test
    @DisplayName("nhiều vé cùng một đơn chỉ hỏi ordering một lần")
    void gop_don_trung_nhau() {
        // Khách mua 4 ghế là một đơn. Gửi 4 id giống nhau sang ordering là trả giá cho một phép
        // lặp mà distinct xoá được miễn phí.
        UUID orderId = UUID.randomUUID();
        FakeSearch search = new FakeSearch(row(orderId, "PAID"), row(orderId, "PAID"), row(orderId, "PAID"));
        FakeOrdering ordering = new FakeOrdering(Map.of(orderId, "PAID"));

        query(search, ordering).search(ORG, filter());

        assertThat(ordering.asked).containsExactly(List.of(orderId));
    }

    @Test
    @DisplayName("trạng thái đơn khác REFUNDED đều quy về PAID")
    void trang_thai_khac_quy_ve_paid() {
        // Vé chỉ tồn tại cho đơn đã trả tiền, và cột chỉ nhận hai giá trị. Một trạng thái lạ lọt
        // vào đây sẽ làm UPDATE vi phạm ck_ticket_payment_status — tức là một màn hình đọc làm
        // hỏng chính nó.
        UUID orderId = UUID.randomUUID();
        FakeSearch search = new FakeSearch(row(orderId, "PAID"));
        FakeOrdering ordering = new FakeOrdering(Map.of(orderId, "MANUAL_REVIEW"));

        var page = query(search, ordering).search(ORG, filter());

        assertThat(page.rows().get(0).paymentStatus()).isEqualTo("PAID");
        assertThat(search.repairs).isEmpty();
    }

    @Test
    @DisplayName("kích thước trang bị chặn trên, dù người gọi xin bao nhiêu")
    void kich_thuoc_trang_bi_chan() {
        FakeSearch search = new FakeSearch();

        query(search, new FakeOrdering(Map.of()))
                .search(ORG, new TicketSearchViews.Filter(null, null, null, null, null, 0, 100_000));

        assertThat(search.lastCriteria.limit()).isEqualTo(100);
    }

    @Test
    @DisplayName("bộ lọc gõ thường vẫn chạy — người dùng không biết database viết hoa")
    void bo_loc_khong_phan_biet_hoa_thuong() {
        FakeSearch search = new FakeSearch();

        query(search, new FakeOrdering(Map.of()))
                .search(ORG, new TicketSearchViews.Filter(null, "  ", null, "checked_in", "paid", 0, 50));

        assertThat(search.lastCriteria.status()).isEqualTo("CHECKED_IN");
        assertThat(search.lastCriteria.paymentStatus()).isEqualTo("PAID");
        // Ô tìm kiếm chỉ có khoảng trắng nghĩa là không lọc, không phải lọc theo khoảng trắng.
        assertThat(search.lastCriteria.text()).isNull();
    }

    // --- dựng dữ liệu -------------------------------------------------------

    private static TicketSearchQuery query(FakeSearch search, FakeOrdering ordering) {
        return new TicketSearchQuery(search, ordering, new AllowAll());
    }

    private static TicketSearchViews.Filter filter() {
        return new TicketSearchViews.Filter(null, null, null, null, null, 0, 50);
    }

    private static TicketSearchPort.Row row(UUID orderId, String paymentStatus) {
        return new TicketSearchPort.Row(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                "A-1-1",
                "A",
                "1",
                "Hạng A",
                "Nguyễn Văn A",
                "VALID",
                paymentStatus,
                Instant.now(),
                null);
    }

    /** Quyền đã được kiểm ở chỗ khác; ở đây ta kiểm luật vá trạng thái. */
    private static final class AllowAll extends TicketingAccess {
        @Override
        public UUID requireTicketReader(UUID organizationId) {
            return organizationId;
        }
    }

    private static final class FakeSearch implements TicketSearchPort {
        private final List<Row> rows;
        final List<Map.Entry<UUID, String>> repairs = new ArrayList<>();
        Criteria lastCriteria;

        FakeSearch(Row... rows) {
            this.rows = List.of(rows);
        }

        @Override
        public Page search(Criteria criteria) {
            lastCriteria = criteria;
            return new Page(rows, rows.size());
        }

        @Override
        public int repairPaymentStatus(UUID orderId, String paymentStatus) {
            repairs.add(Map.entry(orderId, paymentStatus));
            return 1;
        }
    }

    private static final class FakeOrdering implements OrderingPort {
        private final Map<UUID, String> statuses;
        final List<List<UUID>> asked = new ArrayList<>();

        FakeOrdering(Map<UUID, String> statuses) {
            this.statuses = new HashMap<>(statuses);
        }

        @Override
        public Map<UUID, String> statusesOf(Collection<UUID> orderIds) {
            asked.add(List.copyOf(orderIds));
            return statuses;
        }

        @Override
        public PaidOrder fetch(UUID orderId) {
            throw new UnsupportedOperationException("đường tra cứu không phát vé");
        }
    }
}
