// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.JournalEntry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bất biến cân bằng ở tầng domain.
 *
 * <p>Đây là lớp báo lỗi sớm với thông điệp rõ ràng. Chốt chặn thật nằm ở constraint trigger của
 * database — xem {@code LedgerInvariantIT}.
 */
class JournalEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID CASH = UUID.randomUUID();
    private static final UUID PAYABLE = UUID.randomUUID();
    private static final UUID REVENUE = UUID.randomUUID();

    private static JournalEntry.Builder entry() {
        return JournalEntry.builder()
                .entryType("TEST")
                .occurredAt(NOW)
                .source(JournalEntry.SourceRef.payment(UUID.randomUUID()))
                .idempotencyKey("key-" + UUID.randomUUID())
                .createdBy("test");
    }

    @Test
    @DisplayName("Bút toán cân thì lập được")
    void but_toan_can() {
        JournalEntry result = entry().debit(CASH, Money.ofVnd(3_000_000))
                .credit(PAYABLE, Money.ofVnd(2_850_000))
                .credit(REVENUE, Money.ofVnd(150_000))
                .build();

        assertThat(result.postings()).hasSize(3);
        assertThat(result.total()).isEqualTo(Money.ofVnd(3_000_000));
    }

    @Test
    @DisplayName("Lệch một đồng cũng bị từ chối")
    void lech_mot_dong_cung_tu_choi() {
        assertThatThrownBy(() -> entry().debit(CASH, Money.ofVnd(3_000_000))
                        .credit(PAYABLE, Money.ofVnd(2_850_000))
                        .credit(REVENUE, Money.ofVnd(149_999))
                        .build())
                .isInstanceOf(JournalEntry.UnbalancedEntryException.class)
                .hasMessageContaining("không cân");
    }

    @Test
    @DisplayName("Một định khoản thì không có gì để đối ứng")
    void mot_dinh_khoan_bi_tu_choi() {
        assertThatThrownBy(() -> entry().debit(CASH, Money.ofVnd(100_000)).build())
                .isInstanceOf(JournalEntry.UnbalancedEntryException.class)
                .hasMessageContaining("ít nhất 2 định khoản");
    }

    @Test
    @DisplayName("Định khoản 0 đồng bị từ chối ngay khi tạo")
    void dinh_khoan_khong_dong() {
        assertThatThrownBy(() -> entry().debit(CASH, Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 đồng");
    }

    @Test
    @DisplayName("Bút toán đảo lật chiều mọi dòng và vẫn cân")
    void but_toan_dao_lat_chieu() {
        JournalEntry original = entry().debit(CASH, Money.ofVnd(1_000_000))
                .credit(PAYABLE, Money.ofVnd(1_000_000))
                .build();

        JournalEntry reversal = original.reverse("ghi nhầm", NOW, "finance");

        assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
        assertThat(reversal.postings().get(0).isDebit())
                .as("dòng Nợ gốc thành Có ở bút toán đảo")
                .isFalse();
        assertThat(reversal.postings().get(1).isDebit()).isTrue();
        assertThat(reversal.total()).isEqualTo(original.total());
    }

    @Test
    @DisplayName("Hoa hồng 5% của 3.000.000 là 150.000, làm tròn xuống")
    void tinh_hoa_hong_theo_basis_point() {
        assertThat(Money.ofVnd(3_000_000).percentOf(500)).isEqualTo(Money.ofVnd(150_000));
        assertThat(Money.ofVnd(999).percentOf(500)).isEqualTo(Money.ofVnd(49));
    }
}
