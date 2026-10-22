// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexaticket.ordering.application.OrderingErrorCode;
import com.nexaticket.ordering.domain.model.Order;
import com.nexaticket.ordering.domain.model.OrderStatus;
import com.nexaticket.ordering.domain.port.OrderRepository;
import com.nexaticket.ordering.support.OrderFixtures;
import com.nexaticket.platform.web.error.ApiException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Đường đọc đơn hàng: ai xem được gì, và bản nào mang hoa hồng. */
class OrderQueriesTest {

    private final UUID owner = UUID.randomUUID();
    private OrderRepository orders;
    private OrderQueries queries;
    private Order order;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        queries = new OrderQueries(orders);
        order = OrderFixtures.inStatus(OrderStatus.AWAITING_PAYMENT, owner);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
    }

    private static OrderingErrorCode codeOf(Throwable e) {
        return (OrderingErrorCode) ((ApiException) e).errorCode();
    }

    @Nested
    @DisplayName("Khách xem đơn")
    class Customer {

        @Test
        @DisplayName("Chủ đơn xem được đơn của mình, số tiền tính từ dòng")
        void chu_don_xem_duoc() {
            OrderView view = queries.byIdForUser(order.id(), owner);

            assertThat(view.id()).isEqualTo(order.id());
            assertThat(view.status()).isEqualTo("AWAITING_PAYMENT");
            assertThat(view.subtotalVnd()).isEqualTo(800_000);
            assertThat(view.discountVnd()).isEqualTo(100_000);
            assertThat(view.totalVnd()).isEqualTo(700_000);
            assertThat(view.checkoutUrl()).isEqualTo(order.checkoutUrl());
            assertThat(view.items()).hasSize(2);
        }

        @Test
        @DisplayName("Người khác đọc đơn: 404 giống hệt đơn không tồn tại — không lộ đơn có thật")
        void nguoi_khac_nhan_404() {
            UUID missing = UUID.randomUUID();
            when(orders.findById(missing)).thenReturn(Optional.empty());

            Throwable foreign = org.assertj.core.api.Assertions.catchThrowable(
                    () -> queries.byIdForUser(order.id(), UUID.randomUUID()));
            Throwable absent =
                    org.assertj.core.api.Assertions.catchThrowable(() -> queries.byIdForUser(missing, owner));

            assertThat(codeOf(foreign)).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND);
            assertThat(codeOf(absent)).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND);
            assertThat(foreign.getMessage()).isEqualTo(absent.getMessage());
        }

        @Test
        @DisplayName("Bản của khách KHÔNG có trường hoa hồng (ADR-1010)")
        void ban_cua_khach_khong_co_hoa_hong() {
            List<String> fields = Arrays.stream(OrderView.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            List<String> itemFields = Arrays.stream(OrderView.Item.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(fields).noneMatch(f -> f.toLowerCase().contains("commission"));
            assertThat(itemFields).noneMatch(f -> f.toLowerCase().contains("commission"));
        }

        @Test
        @DisplayName("Danh sách đơn của tôi chỉ hỏi kho theo đúng userId, limit, offset")
        void danh_sach_cua_toi() {
            when(orders.findByUser(owner, 20, 40)).thenReturn(List.of(order));

            assertThat(queries.forUser(owner, 20, 40)).extracting(OrderView::id).containsExactly(order.id());
        }
    }

    @Nested
    @DisplayName("Open Host Service nội bộ")
    class Internal {

        @Test
        @DisplayName("Bản nội bộ CÓ hoa hồng cộng từ dòng, số vé và dòng để phát vé")
        void ban_noi_bo_co_hoa_hong() {
            OrderQueries.InternalOrderView view = queries.internalById(order.id());

            assertThat(view.commissionBps()).isEqualTo(500);
            assertThat(view.commissionVnd()).isEqualTo(35_000);
            assertThat(view.totalVnd()).isEqualTo(700_000);
            assertThat(view.ticketCount()).isEqualTo(2);
            assertThat(view.userId()).isEqualTo(owner);
            assertThat(view.items())
                    .extracting(OrderQueries.InternalOrderItem::orderItemId)
                    .containsExactlyElementsOf(
                            order.items().stream().map(i -> i.id()).toList());
        }

        @Test
        @DisplayName("Dòng đơn nội bộ không mang giá — vé không in giá")
        void dong_noi_bo_khong_co_gia() {
            List<String> fields = Arrays.stream(OrderQueries.InternalOrderItem.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(fields).noneMatch(f -> f.toLowerCase().contains("price") || f.contains("Vnd"));
        }

        @Test
        @DisplayName("Đơn nội bộ không tồn tại: 404")
        void noi_bo_khong_ton_tai() {
            UUID missing = UUID.randomUUID();
            when(orders.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> queries.internalById(missing))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(OrderingErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("Trạng thái hàng loạt chuyển nguyên map — đơn vắng mặt vẫn vắng mặt")
        void trang_thai_hang_loat() {
            UUID missing = UUID.randomUUID();
            when(orders.statusesOf(List.of(order.id(), missing))).thenReturn(Map.of(order.id(), "PAID"));

            assertThat(queries.statusesOf(List.of(order.id(), missing)))
                    .containsExactly(Map.entry(order.id(), "PAID"))
                    .doesNotContainKey(missing);
        }
    }
}
