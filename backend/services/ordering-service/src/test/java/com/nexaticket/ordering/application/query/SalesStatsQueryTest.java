// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** Kiểm tham số và chuẩn hoá của thống kê bán hàng — phần không cần database. */
class SalesStatsQueryTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final SalesStats.Totals EMPTY = new SalesStats.Totals(0, 0, 0, 0, 0, 0, 0, 0, null, 0);

    private SalesStatsReader reader;
    private SalesStatsQuery query;

    @BeforeEach
    void setUp() {
        reader = mock(SalesStatsReader.class);
        query = new SalesStatsQuery(reader);
        when(reader.totals(any())).thenReturn(EMPTY);
        when(reader.series(any(), anyString())).thenReturn(List.of());
    }

    private static SalesStatsQuery.Request range(LocalDate from, LocalDate to) {
        return new SalesStatsQuery.Request(ORG, from, to, null, null, null);
    }

    private static void assertValidationFailed(Throwable e) {
        assertThat(e).isInstanceOf(ApiException.class);
        assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.Common.VALIDATION_FAILED);
    }

    @Nested
    @DisplayName("Kỳ báo cáo")
    class Period {

        @Test
        @DisplayName("Thiếu from hoặc to: 400, không chạm database")
        void thieu_moc() {
            assertThatThrownBy(() -> query.summary(range(null, SEP_1), false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
            assertThatThrownBy(() -> query.summary(range(SEP_1, null), false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("to trước from: 400")
        void to_truoc_from() {
            assertThatThrownBy(() -> query.summary(range(SEP_1, SEP_1.minusDays(1)), false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
        }

        @Test
        @DisplayName("Một ngày (from = to) hợp lệ")
        void mot_ngay() {
            assertThat(query.summary(range(SEP_1, SEP_1), false).period())
                    .isEqualTo(new SalesStats.Period(SEP_1, SEP_1));
        }

        @Test
        @DisplayName("Đúng 731 ngày được, 732 ngày bị chặn")
        void tran_731_ngay() {
            assertThat(query.summary(range(SEP_1, SEP_1.plusDays(730)), false)).isNotNull();
            assertThatThrownBy(() -> query.summary(range(SEP_1, SEP_1.plusDays(731)), false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
        }

        @Test
        @DisplayName("Gom theo giờ: 14 ngày được, 15 ngày bị chặn")
        void tran_theo_gio() {
            assertThat(query.series(range(SEP_1, SEP_1.plusDays(13)), "hour", false)
                            .current())
                    .hasSize(14 * 24);
            assertThatThrownBy(() -> query.series(range(SEP_1, SEP_1.plusDays(14)), "hour", false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
        }

        @Test
        @DisplayName("Ngày hiểu theo giờ Việt Nam: [00:00 ngày đầu, 00:00 ngày sau ngày cuối) quy ra UTC")
        void ranh_gioi_theo_gio_viet_nam() {
            query.summary(range(SEP_1, SEP_1.plusDays(6)), false);

            ArgumentCaptor<SalesStats.Filter> filter = ArgumentCaptor.forClass(SalesStats.Filter.class);
            verify(reader).totals(filter.capture());
            assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-08-31T17:00:00Z"));
            assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-09-07T17:00:00Z"));
            assertThat(filter.getValue().organizationId()).isEqualTo(ORG);
        }

        @Test
        @DisplayName("So sánh: kỳ trước liền kề và cùng độ dài; không so sánh thì chỉ một lượt đọc")
        void ky_truoc_cung_do_dai() {
            SalesStats.Summary withCompare = query.summary(range(SEP_1, SEP_1.plusDays(6)), true);

            assertThat(withCompare.previousPeriod())
                    .isEqualTo(new SalesStats.Period(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 31)));
            verify(reader, times(2)).totals(any());

            SalesStats.Summary without = query.summary(range(SEP_1, SEP_1.plusDays(6)), false);
            assertThat(without.previousPeriod()).isNull();
            assertThat(without.previous()).isNull();
            verify(reader, times(3)).totals(any());
        }
    }

    @Nested
    @DisplayName("Bộ lọc mã")
    class Ids {

        @Test
        @DisplayName("Đúng 200 mã được, 201 mã bị chặn")
        void tran_200_ma() {
            List<UUID> ok = new ArrayList<>(Collections.nCopies(200, UUID.randomUUID()));
            List<UUID> tooMany = new ArrayList<>(Collections.nCopies(201, UUID.randomUUID()));

            assertThat(query.heatmap(new SalesStatsQuery.Request(ORG, SEP_1, SEP_1, ok, null, null)))
                    .isNotNull();
            assertThatThrownBy(() -> query.heatmap(new SalesStatsQuery.Request(ORG, SEP_1, SEP_1, null, tooMany, null)))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
        }

        @Test
        @DisplayName("null nghĩa là không lọc: đi xuống database thành danh sách rỗng")
        void null_la_khong_loc() {
            query.distribution(range(SEP_1, SEP_1));

            ArgumentCaptor<SalesStats.Filter> filter = ArgumentCaptor.forClass(SalesStats.Filter.class);
            verify(reader).ticketsPerOrder(filter.capture());
            assertThat(filter.getValue().eventIds()).isEmpty();
            assertThat(filter.getValue().sessionIds()).isEmpty();
            assertThat(filter.getValue().ticketTypeIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Chuỗi thời gian")
    class Series {

        @Test
        @DisplayName("bucket lạ: 400")
        void bucket_la() {
            assertThatThrownBy(() -> query.series(range(SEP_1, SEP_1), "month", false))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
        }

        @Test
        @DisplayName("bucket không phân biệt hoa thường; null là day")
        void bucket_mac_dinh() {
            assertThat(query.series(range(SEP_1, SEP_1.plusDays(2)), "DAY", false)
                            .bucket())
                    .isEqualTo("day");
            assertThat(query.series(range(SEP_1, SEP_1.plusDays(2)), null, false)
                            .current())
                    .hasSize(3);
        }

        @Test
        @DisplayName("Lấp ô trống bằng 0 và giữ nguyên ô có dữ liệu, đúng thứ tự thời gian")
        void lap_o_trong() {
            SalesStats.Point sep2 = new SalesStats.Point(LocalDateTime.of(2026, 9, 2, 0, 0), 500_000, 2, 1, 1, 0, 0);
            when(reader.series(any(), eq("day"))).thenReturn(List.of(sep2));

            List<SalesStats.Point> points =
                    query.series(range(SEP_1, SEP_1.plusDays(2)), "day", false).current();

            assertThat(points)
                    .extracting(SalesStats.Point::bucket)
                    .containsExactly(
                            LocalDateTime.of(2026, 9, 1, 0, 0),
                            LocalDateTime.of(2026, 9, 2, 0, 0),
                            LocalDateTime.of(2026, 9, 3, 0, 0));
            assertThat(points.get(0)).isEqualTo(SalesStats.Point.empty(LocalDateTime.of(2026, 9, 1, 0, 0)));
            assertThat(points.get(1)).isSameAs(sep2);
        }

        @Test
        @DisplayName("Gom theo tuần bắt đầu từ thứ Hai của tuần chứa ngày đầu kỳ")
        void tuan_bat_dau_thu_hai() {
            // 2026-09-02 là thứ Tư ⇒ ô đầu là thứ Hai 2026-08-31.
            List<SalesStats.Point> points = query.series(
                            range(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 15)), "week", false)
                    .current();

            assertThat(points)
                    .extracting(SalesStats.Point::bucket)
                    .containsExactly(
                            LocalDateTime.of(2026, 8, 31, 0, 0),
                            LocalDateTime.of(2026, 9, 7, 0, 0),
                            LocalDateTime.of(2026, 9, 14, 0, 0));
        }

        @Test
        @DisplayName("So sánh: kỳ trước cũng được lấp đủ ô")
        void so_sanh_cung_lap_o() {
            SalesStats.Series series = query.series(range(SEP_1, SEP_1.plusDays(1)), "day", true);

            assertThat(series.previousPeriod())
                    .isEqualTo(new SalesStats.Period(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 31)));
            assertThat(series.previous()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Danh sách đơn")
    class Orders {

        @Test
        @DisplayName("Trạng thái lạ: 400, không đọc database")
        void trang_thai_la() {
            assertThatThrownBy(() -> query.orders(range(SEP_1, SEP_1), List.of("PAID", "DELETED"), null, 0, 20))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
            verify(reader, never()).orders(any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Trạng thái chữ thường được chuẩn hoá và bỏ trùng")
        void trang_thai_chuan_hoa() {
            query.orders(range(SEP_1, SEP_1), List.of("paid", "PAID", "expired"), null, 0, 20);

            verify(reader).orders(any(), eq(List.of("PAID", "EXPIRED")), eq(null), eq(20), eq(0));
        }

        @Test
        @DisplayName("Từ khoá: cắt khoảng trắng; rỗng là không tìm; quá 40 ký tự bị chặn")
        void tu_khoa() {
            query.orders(range(SEP_1, SEP_1), null, "  NT-2609  ", 0, 20);
            verify(reader).orders(any(), eq(List.of()), eq("NT-2609"), eq(20), eq(0));

            query.orders(range(SEP_1, SEP_1), null, "   ", 0, 20);
            verify(reader).countOrders(any(), eq(List.of()), eq(null));

            assertThatThrownBy(() -> query.orders(range(SEP_1, SEP_1), null, "N".repeat(41), 0, 20))
                    .satisfies(SalesStatsQueryTest::assertValidationFailed);
            assertThat(query.orders(range(SEP_1, SEP_1), null, "N".repeat(40), 0, 20))
                    .isNotNull();
        }

        @ParameterizedTest(name = "page={0}, size={1} → page={2}, size={3}")
        @CsvSource({"0, 0, 0, 1", "-5, -1, 0, 1", "3, 1000, 3, 100", "500, 20, 100, 20", "2, 25, 2, 25"})
        @DisplayName("Phân trang bị kẹp trong biên, offset = trang × cỡ trang")
        void kep_phan_trang(int page, int size, int expectedPage, int expectedSize) {
            when(reader.countOrders(any(), any(), any())).thenReturn(7L);

            SalesStats.OrderPage result = query.orders(range(SEP_1, SEP_1), null, null, page, size);

            assertThat(result.page()).isEqualTo(expectedPage);
            assertThat(result.size()).isEqualTo(expectedSize);
            assertThat(result.total()).isEqualTo(7L);
            verify(reader).orders(any(), any(), any(), eq(expectedSize), eq(expectedPage * expectedSize));
        }
    }

    @Test
    @DisplayName("Khách hàng: người mua mới = tổng người mua − người quay lại")
    void khach_hang_moi() {
        when(reader.buyerCounts(any())).thenReturn(new long[] {10, 4, 3});
        when(reader.ordersPerBuyer(any())).thenReturn(List.of(new SalesStats.SizeBucket(1, 7)));

        SalesStats.Customers customers = query.customers(range(SEP_1, SEP_1));

        assertThat(customers.buyers()).isEqualTo(10);
        assertThat(customers.newBuyers()).isEqualTo(6);
        assertThat(customers.returning()).isEqualTo(4);
        assertThat(customers.repeatInPeriod()).isEqualTo(3);
        assertThat(customers.ordersPerBuyer()).containsExactly(new SalesStats.SizeBucket(1, 7));
    }
}
