// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.model.Direction;
import com.nexaticket.ledger.domain.model.JournalEntry;
import com.nexaticket.ledger.domain.model.Posting;
import com.nexaticket.ledger.domain.port.LedgerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Bút toán N1 (custodial-funds.md §3) với repository giả: canh <b>định khoản</b> — tài khoản nào, chiều
 * nào, bao nhiêu tiền — còn số dư thật trên PostgreSQL thì {@code RecordPaymentIT} canh.
 */
@ExtendWith(MockitoExtension.class)
class RecordPaymentHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ESCROW = UUID.fromString("00000000-0000-4000-8000-000000001010");
    private static final UUID REVENUE = UUID.fromString("00000000-0000-4000-8000-000000004010");
    private static final UUID HELD = UUID.fromString("00000000-0000-4000-8000-000000002011");

    @Mock
    LedgerRepository ledger;

    RecordPaymentHandler handler;

    UUID org;
    UUID orderId;

    @BeforeEach
    void setUp() {
        handler = new RecordPaymentHandler(ledger, CLOCK);
        org = UUID.randomUUID();
        orderId = UUID.randomUUID();
        lenient().when(ledger.platformAccountId(AccountCode.CASH_ESCROW)).thenReturn(ESCROW);
        lenient().when(ledger.platformAccountId(AccountCode.COMMISSION_REVENUE)).thenReturn(REVENUE);
        lenient()
                .when(ledger.organizationAccountId(AccountCode.ORGANIZER_PAYABLE_HELD, org))
                .thenReturn(HELD);
        lenient().when(ledger.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    }

    private JournalEntry appended() {
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(ledger).append(captor.capture());
        return captor.getValue();
    }

    private static Posting line(JournalEntry entry, UUID account) {
        return entry.postings().stream()
                .filter(p -> p.accountId().equals(account))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Không có định khoản trên tài khoản " + account));
    }

    @Nested
    @DisplayName("Định khoản")
    class DinhKhoan {

        @Test
        @DisplayName("Đơn 3.000.000, hoa hồng 150.000: Nợ 1010 / Có 2011 2.850.000 / Có 4010 150.000")
        void but_toan_n1() {
            UUID id = handler.handle(RecordPaymentHandler.Command.ofOrder(
                    orderId, org, Money.ofVnd(3_000_000), Money.ofVnd(150_000), 500, NOW));

            JournalEntry entry = appended();
            assertThat(id).isEqualTo(entry.id());
            assertThat(entry.postings()).hasSize(3);
            assertThat(line(entry, ESCROW).direction()).isEqualTo(Direction.DEBIT);
            assertThat(line(entry, ESCROW).amount()).isEqualTo(Money.ofVnd(3_000_000));
            assertThat(line(entry, HELD).direction()).isEqualTo(Direction.CREDIT);
            assertThat(line(entry, HELD).amount()).isEqualTo(Money.ofVnd(2_850_000));
            assertThat(line(entry, REVENUE).direction()).isEqualTo(Direction.CREDIT);
            assertThat(line(entry, REVENUE).amount()).isEqualTo(Money.ofVnd(150_000));
        }

        @Test
        @DisplayName("Đầu bút toán: loại PAYMENT_CONFIRMED, khoá 'order:<id>', tổ chức và thời điểm của chứng từ")
        void dau_but_toan() {
            Instant paidAt = Instant.parse("2026-09-01T12:34:56Z");

            handler.handle(RecordPaymentHandler.Command.ofOrder(
                    orderId, org, Money.ofVnd(100_000), Money.ofVnd(5_000), 500, paidAt));

            JournalEntry entry = appended();
            assertThat(entry.entryType()).isEqualTo("PAYMENT_CONFIRMED");
            assertThat(entry.idempotencyKey()).isEqualTo("order:" + orderId);
            assertThat(entry.organizationId()).isEqualTo(org);
            // Thời điểm của CHỨNG TỪ, không phải lúc consumer xử lý — message tới muộn vẫn ghi đúng kỳ.
            assertThat(entry.occurredAt()).isEqualTo(paidAt);
            assertThat(entry.source()).isEqualTo(JournalEntry.SourceRef.payment(orderId));
            assertThat(entry.createdBy()).isEqualTo("ledger-service");
        }

        @Test
        @DisplayName("Hoa hồng 0: hai dòng, không có định khoản 0 đồng trên 4010")
        void hoa_hong_khong() {
            handler.handle(
                    RecordPaymentHandler.Command.ofOrder(orderId, org, Money.ofVnd(1_000_000), Money.ZERO, 0, NOW));

            JournalEntry entry = appended();
            assertThat(entry.postings()).hasSize(2);
            assertThat(entry.postings()).noneMatch(p -> p.accountId().equals(REVENUE));
            verify(ledger, never()).platformAccountId(AccountCode.COMMISSION_REVENUE);
        }

        @Test
        @DisplayName("Hoa hồng lấy NGUYÊN từ chứng từ, không tính lại trên tổng")
        void hoa_hong_tu_chung_tu() {
            // 3 dòng 333.333đ ở 5%: Ordering cộng 16.666×3 = 49.998; tính lại trên tổng sẽ ra 49.999.
            handler.handle(RecordPaymentHandler.Command.ofOrder(
                    orderId, org, Money.ofVnd(999_999), Money.ofVnd(49_998), 500, NOW));

            JournalEntry entry = appended();
            assertThat(line(entry, REVENUE).amount()).isEqualTo(Money.ofVnd(49_998));
            assertThat(line(entry, HELD).amount()).isEqualTo(Money.ofVnd(950_001));
        }

        @ParameterizedTest(name = "gross={0}, bps={1}")
        @CsvSource({"999999,500", "1,500", "19,333", "1000001,1", "7,9999", "123456789,725"})
        @DisplayName("Hoa hồng suy từ tỷ lệ, làm tròn xuống: hai phần Có cộng lại đúng bằng tổng, không rơi đồng nào")
        void lam_tron_khong_roi_dong(long gross, int bps) {
            handler.handle(RecordPaymentHandler.Command.of(orderId, org, Money.ofVnd(gross), bps, CLOCK));

            JournalEntry entry = appended();
            Money credits = entry.postings().stream()
                    .filter(p -> !p.isDebit())
                    .map(Posting::amount)
                    .reduce(Money.ZERO, Money::plus);
            assertThat(credits).isEqualTo(Money.ofVnd(gross));
            assertThat(entry.total()).isEqualTo(Money.ofVnd(gross));
            assertThat(entry.idempotencyKey()).isEqualTo("payment:" + orderId);
            assertThat(entry.occurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName(
                "Hoa hồng 100% (Ordering cho phép commission_bps = 10000): ghi Nợ 1010 / Có 4010, không có dòng 0 đồng")
        void hoa_hong_tron_tong() {
            handler.handle(RecordPaymentHandler.Command.ofOrder(
                    orderId, org, Money.ofVnd(200_000), Money.ofVnd(200_000), 10_000, NOW));

            JournalEntry entry = appended();
            assertThat(entry.postings()).hasSize(2);
            assertThat(line(entry, REVENUE).amount()).isEqualTo(Money.ofVnd(200_000));
            assertThat(entry.postings()).noneMatch(p -> p.accountId().equals(HELD));
        }
    }

    @Nested
    @DisplayName("Idempotent và từ chối")
    class IdempotentVaTuChoi {

        @Test
        @DisplayName("Đã có bút toán cùng khoá: trả id cũ, KHÔNG ghi thêm, không tra tài khoản")
        void da_ghi_roi_thi_no_op() {
            JournalEntry existing = JournalEntry.builder()
                    .entryType("PAYMENT_CONFIRMED")
                    .occurredAt(NOW)
                    .source(JournalEntry.SourceRef.payment(orderId))
                    .idempotencyKey("order:" + orderId)
                    .createdBy("ledger-service")
                    .debit(ESCROW, Money.ofVnd(10))
                    .credit(HELD, Money.ofVnd(10))
                    .build();
            when(ledger.findByIdempotencyKey("order:" + orderId)).thenReturn(Optional.of(existing));

            UUID id = handler.handle(
                    RecordPaymentHandler.Command.ofOrder(orderId, org, Money.ofVnd(10), Money.ZERO, 0, NOW));

            assertThat(id).isEqualTo(existing.id());
            verify(ledger, never()).append(any());
            verify(ledger, never()).platformAccountId(any());
        }

        @Test
        @DisplayName("Hoa hồng lớn hơn tổng: từ chối, không ghi gì")
        void hoa_hong_lon_hon_tong() {
            assertThatThrownBy(() -> handler.handle(RecordPaymentHandler.Command.ofOrder(
                            orderId, org, Money.ofVnd(100), Money.ofVnd(101), 500, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(ledger, never()).append(any());
        }

        @Test
        @DisplayName("Tổng 0 đồng: từ chối, không ghi bút toán vô nghĩa")
        void tong_khong_dong() {
            assertThatThrownBy(() -> handler.handle(
                            RecordPaymentHandler.Command.ofOrder(orderId, org, Money.ZERO, Money.ZERO, 0, NOW)))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(ledger, never()).append(any());
        }

        @Test
        @DisplayName("Tỷ lệ hoa hồng ngoài 0..10000 bps: từ chối ngay khi dựng lệnh")
        void ty_le_ngoai_khoang() {
            assertThatThrownBy(() -> RecordPaymentHandler.Command.of(orderId, org, Money.ofVnd(100), 10_001, CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RecordPaymentHandler.Command.of(orderId, org, Money.ofVnd(100), -1, CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
