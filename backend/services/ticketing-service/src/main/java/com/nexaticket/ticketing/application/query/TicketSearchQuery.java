// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.ticketing.application.TicketingAccess;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.domain.port.TicketSearchPort;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra cứu vé cho ban tổ chức: theo mã vé, tên khách, khu, trạng thái soát, trạng thái thanh toán.
 *
 * <h3>Trạng thái thanh toán được vá lại lúc đọc</h3>
 *
 * <p>{@code tickets.payment_status} là bản chụp, và hiện chưa có sự kiện {@code order.refunded}
 * nào đẩy thay đổi sang đây. Nên sau khi lấy một trang, ta hỏi ordering trạng thái thật của đúng
 * những đơn <b>trên trang đó</b> — một lời gọi cho tối đa 50 đơn — rồi ghi đè lại dòng nào lệch.
 *
 * <p>Hệ quả phải nói thẳng: <b>bộ lọc</b> chạy trên cột đã lưu, nên một vé vừa được hoàn tiền mà
 * chưa ai mở tới vẫn hiện là PAID khi lọc theo PAID. Nó tự đúng ngay lần đầu có người nhìn vào
 * trang chứa nó. Đổi lại, không có bảng đồng bộ nào để lệch và không có sự kiện mới nào phải
 * thêm vào ordering.
 *
 * <p>Ordering im lặng thì bỏ qua phần vá và trả bản chụp — màn hình tra cứu không được sập vì một
 * service phụ.
 */
@Service
public class TicketSearchQuery {

    /** Trần cứng: không ai đọc 500 dòng một lúc, nhưng có người sẽ thử. */
    private static final int MAX_PAGE_SIZE = 100;

    private final TicketSearchPort search;
    private final OrderingPort ordering;
    private final TicketingAccess access;

    public TicketSearchQuery(TicketSearchPort search, OrderingPort ordering, TicketingAccess access) {
        this.search = search;
        this.ordering = ordering;
        this.access = access;
    }

    @Transactional
    public TicketSearchViews.TicketPage search(UUID organizationId, TicketSearchViews.Filter filter) {
        access.requireTicketReader(organizationId);

        int limit = Math.clamp(filter.size(), 1, MAX_PAGE_SIZE);
        int offset = Math.max(filter.page(), 0) * limit;

        TicketSearchPort.Page page = search.search(new TicketSearchPort.Criteria(
                organizationId,
                filter.eventSessionId(),
                blankToNull(filter.query()),
                blankToNull(filter.zoneCode()),
                upperOrNull(filter.status()),
                upperOrNull(filter.paymentStatus()),
                limit,
                offset));

        List<TicketSearchViews.TicketRow> rows = reconciled(page.rows());

        return new TicketSearchViews.TicketPage(rows, page.total(), Math.max(filter.page(), 0), limit);
    }

    /**
     * Hỏi ordering trạng thái thật của những đơn trên trang này, rồi vá bản chụp lệch.
     *
     * <p>Tập đơn là {@link LinkedHashSet}: 50 vé của một sự kiện thường thuộc chưa tới 20 đơn — một
     * khách mua 4 ghế là một đơn. Gửi 50 id trong khi chỉ có 20 đơn khác nhau là trả giá cho một
     * phép lặp mà {@code distinct} xoá được miễn phí.
     */
    private List<TicketSearchViews.TicketRow> reconciled(List<TicketSearchPort.Row> rows) {
        Set<UUID> orderIds = new LinkedHashSet<>();
        for (TicketSearchPort.Row row : rows) {
            orderIds.add(row.orderId());
        }

        Map<UUID, String> actual = ordering.statusesOf(orderIds);

        List<TicketSearchViews.TicketRow> out = new ArrayList<>(rows.size());
        for (TicketSearchPort.Row row : rows) {
            String payment = paymentStatusOf(row, actual.get(row.orderId()));
            out.add(new TicketSearchViews.TicketRow(
                    row.ticketId(),
                    row.orderId(),
                    row.eventSessionId(),
                    row.seatCode(),
                    row.zoneCode(),
                    row.seatLabel(),
                    row.ticketTypeName(),
                    row.holderName(),
                    row.status(),
                    payment,
                    row.issuedAt(),
                    row.checkedInAt()));
        }
        return out;
    }

    /**
     * Ordering là nguồn chân lý; bản chụp chỉ thắng khi không hỏi được.
     *
     * <p>Chỉ hai giá trị đi vào cột này ({@code ck_ticket_payment_status}), nên mọi trạng thái đơn
     * khác REFUNDED đều quy về PAID — vé chỉ tồn tại cho đơn đã trả tiền, và một đơn PAID chuyển
     * sang CANCELLED mà không hoàn tiền là chuyện không xảy ra trong luồng nào.
     */
    private String paymentStatusOf(TicketSearchPort.Row row, String orderStatus) {
        if (orderStatus == null) {
            return row.paymentStatus();
        }
        String actual = "REFUNDED".equals(orderStatus) ? "REFUNDED" : "PAID";
        if (!actual.equals(row.paymentStatus())) {
            search.repairPaymentStatus(row.orderId(), actual);
        }
        return actual;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Bộ lọc gõ thường vẫn phải chạy: người dùng không biết giá trị trong database viết hoa. */
    private static String upperOrNull(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
