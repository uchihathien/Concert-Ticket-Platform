// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.analytics.application.command.ApplySalesDeltaHandler;
import com.nexaticket.analytics.application.query.SalesQueries;
import com.nexaticket.analytics.domain.model.SalesDelta;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository.EventPopularity;
import com.nexaticket.analytics.domain.port.SalesReadModelRepository.SessionSales;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** {@link SalesDelta}, {@link ApplySalesDeltaHandler} và {@link SalesQueries} với read model giả. */
class SalesApplicationTest {

    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID ORG = UUID.fromString("33333333-3333-4333-a333-333333333333");

    private final SalesReadModelRepository readModel = mock(SalesReadModelRepository.class);

    private static SessionSales row(UUID session, int tickets, long gross, int paid, int expired, int cancelled) {
        return new SessionSales(session, EVENT, ORG, tickets, gross, paid, expired, cancelled);
    }

    @Nested
    @DisplayName("SalesDelta")
    class Delta {

        @Test
        @DisplayName("orderPaid: cộng vé và tiền, đúng một đơn đã trả")
        void order_paid() {
            SalesDelta d = SalesDelta.orderPaid(SESSION, EVENT, ORG, 3, 1_500_000);

            assertThat(d).isEqualTo(new SalesDelta(SESSION, EVENT, ORG, 3, 1_500_000, 1, 0, 0));
        }

        @Test
        @DisplayName("orderExpired và orderCancelled: chỉ đếm đơn, không đụng tới vé và tiền")
        void expired_cancelled() {
            assertThat(SalesDelta.orderExpired(SESSION, EVENT, ORG))
                    .isEqualTo(new SalesDelta(SESSION, EVENT, ORG, 0, 0, 0, 1, 0));
            assertThat(SalesDelta.orderCancelled(SESSION, EVENT, ORG))
                    .isEqualTo(new SalesDelta(SESSION, EVENT, ORG, 0, 0, 0, 0, 1));
        }

        @Test
        @DisplayName("refunded là ĐỐI của orderPaid: cộng hai delta lại ra 0 ở mọi trường")
        void refunded_la_doi_cua_paid() {
            SalesDelta paid = SalesDelta.orderPaid(SESSION, EVENT, ORG, 4, 2_000_000);
            SalesDelta refund = SalesDelta.refunded(SESSION, EVENT, ORG, 4, 2_000_000);

            assertThat(paid.ticketsDelta() + refund.ticketsDelta()).isZero();
            assertThat(paid.grossDeltaVnd() + refund.grossDeltaVnd()).isZero();
            assertThat(paid.ordersPaidDelta() + refund.ordersPaidDelta()).isZero();
        }

        @Test
        @DisplayName("delta không có trường hoa hồng — ranh giới ADR-1010")
        void khong_co_truong_hoa_hong() {
            assertThat(SalesDelta.class.getRecordComponents())
                    .extracting(c -> c.getName().toLowerCase())
                    .noneMatch(name -> name.contains("commission") || name.contains("fee") || name.contains("payout"));
        }
    }

    @Nested
    @DisplayName("ApplySalesDeltaHandler")
    class Apply {

        private final ApplySalesDeltaHandler handler = new ApplySalesDeltaHandler(readModel);

        @Test
        @DisplayName("lần đầu: chuyển nguyên delta cho read model và trả true")
        void lan_dau() {
            UUID event = UUID.randomUUID();
            SalesDelta delta = SalesDelta.orderPaid(SESSION, EVENT, ORG, 1, 500_000);
            when(readModel.applyIfNew(event, "order.paid", delta)).thenReturn(true);

            assertThat(handler.handle(event, "order.paid", delta)).isTrue();
            verify(readModel).applyIfNew(event, "order.paid", delta);
        }

        @Test
        @DisplayName("trùng: trả false, không ném")
        void trung() {
            UUID event = UUID.randomUUID();
            SalesDelta delta = SalesDelta.orderExpired(SESSION, EVENT, ORG);
            when(readModel.applyIfNew(event, "order.expired", delta)).thenReturn(false);

            assertThat(handler.handle(event, "order.expired", delta)).isFalse();
        }
    }

    @Nested
    @DisplayName("SalesQueries")
    class Queries {

        private final SalesQueries queries = new SalesQueries(readModel);

        @Test
        @DisplayName("forOrganization: cộng vé và tiền của mọi suất; giữ nguyên từng suất")
        void tong_to_chuc() {
            UUID other = UUID.randomUUID();
            when(readModel.byOrganization(ORG))
                    .thenReturn(List.of(row(SESSION, 10, 5_000_000, 4, 1, 0), row(other, 5, 2_500_000, 2, 0, 1)));

            SalesQueries.OrganizationSummary summary = queries.forOrganization(ORG);

            assertThat(summary.organizationId()).isEqualTo(ORG);
            assertThat(summary.totalTicketsSold()).isEqualTo(15);
            assertThat(summary.totalGrossVnd()).isEqualTo(7_500_000L);
            assertThat(summary.sessions()).hasSize(2);
            assertThat(summary.sessions().get(0).ordersExpired()).isEqualTo(1);
            assertThat(summary.sessions().get(1).ordersCancelled()).isEqualTo(1);
        }

        @Test
        @DisplayName("tổ chức chưa bán gì: tổng bằng 0 và danh sách rỗng")
        void chua_ban_gi() {
            when(readModel.byOrganization(ORG)).thenReturn(List.of());

            SalesQueries.OrganizationSummary summary = queries.forOrganization(ORG);

            assertThat(summary.totalTicketsSold()).isZero();
            assertThat(summary.totalGrossVnd()).isZero();
            assertThat(summary.sessions()).isEmpty();
        }

        @Test
        @DisplayName("tổng tiền cộng bằng long: vài tỷ đồng không tràn int")
        void tong_tien_khong_tran_int() {
            when(readModel.byOrganization(ORG))
                    .thenReturn(List.of(
                            row(SESSION, 1, 2_000_000_000L, 1, 0, 0),
                            row(UUID.randomUUID(), 1, 2_000_000_000L, 1, 0, 0)));

            assertThat(queries.forOrganization(ORG).totalGrossVnd()).isEqualTo(4_000_000_000L);
        }

        @Test
        @DisplayName("forEvent: ánh xạ từng suất, không cộng dồn")
        void theo_su_kien() {
            when(readModel.byEvent(EVENT)).thenReturn(List.of(row(SESSION, 3, 900_000, 1, 0, 0)));

            List<SalesQueries.SessionSalesView> views = queries.forEvent(EVENT);

            assertThat(views)
                    .singleElement()
                    .isEqualTo(new SalesQueries.SessionSalesView(SESSION, EVENT, 3, 900_000, 1, 0, 0));
        }

        @ParameterizedTest(name = "limit={0} -> hỏi repository {1}")
        @CsvSource({"-5, 1", "0, 1", "1, 1", "8, 8", "24, 24", "25, 24", "100000, 24", "2147483647, 24"})
        @DisplayName("trending: kẹp limit vào [1, 24] trước khi chạm database")
        void trending_kep_limit(int requested, int expected) {
            when(readModel.trendingEvents(anyInt())).thenReturn(List.of());

            queries.trending(requested);

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(readModel).trendingEvents(limit.capture());
            assertThat(limit.getValue()).isEqualTo(expected);
        }

        @Test
        @DisplayName("trending: giữ thứ tự repository trả về")
        void trending_giu_thu_tu() {
            UUID second = UUID.randomUUID();
            when(readModel.trendingEvents(8))
                    .thenReturn(List.of(new EventPopularity(EVENT, 90), new EventPopularity(second, 40)));

            assertThat(queries.trending(8))
                    .containsExactly(
                            new SalesQueries.TrendingView(EVENT, 90), new SalesQueries.TrendingView(second, 40));
        }
    }
}
