// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.domain.model.AccountCode;
import com.nexaticket.ledger.domain.model.Direction;
import com.nexaticket.ledger.domain.model.JournalEntry;
import com.nexaticket.ledger.domain.model.Posting;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Các góc của mô hình sổ cái mà {@code JournalEntryTest} chưa phủ: bút toán nhiều dòng hai phía, các
 * trường bắt buộc, bút toán đảo của bút toán đảo, và ràng buộc của Posting/AccountCode/Direction.
 */
class LedgerModelEdgeCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static JournalEntry.Builder entry() {
        return JournalEntry.builder()
                .entryType("TEST")
                .occurredAt(NOW)
                .source(JournalEntry.SourceRef.payment(UUID.randomUUID()))
                .idempotencyKey("key-" + UUID.randomUUID())
                .createdBy("test");
    }

    @Nested
    @DisplayName("Cân bằng")
    class CanBang {

        @Test
        @DisplayName("Nhiều dòng Nợ và nhiều dòng Có: cân thì lập được, tổng là tổng một phía")
        void nhieu_dong_hai_phia() {
            JournalEntry result = entry().debit(A, Money.ofVnd(700))
                    .debit(B, Money.ofVnd(300))
                    .credit(C, Money.ofVnd(999))
                    .credit(D, Money.ofVnd(1))
                    .build();

            assertThat(result.total()).isEqualTo(Money.ofVnd(1_000));
            assertThat(result.postings()).extracting(Posting::lineNo).containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("Chỉ có dòng Có (hai dòng): không cân — Nợ 0 khác Có")
        void chi_co_dong_co() {
            assertThatThrownBy(() -> entry().credit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .isInstanceOf(JournalEntry.UnbalancedEntryException.class)
                    .hasMessageContaining("không cân");
        }

        @Test
        @DisplayName("Thừa bên Nợ một đồng cũng bị chặn — lệch chiều nào cũng là lệch")
        void thua_ben_no() {
            assertThatThrownBy(() -> entry().debit(A, Money.ofVnd(1_000_001))
                            .credit(B, Money.ofVnd(1_000_000))
                            .build())
                    .isInstanceOf(JournalEntry.UnbalancedEntryException.class);
        }

        @Test
        @DisplayName("Không có định khoản nào: bị chặn")
        void khong_co_dinh_khoan() {
            assertThatThrownBy(() -> entry().build())
                    .isInstanceOf(JournalEntry.UnbalancedEntryException.class)
                    .hasMessageContaining("có 0");
        }

        @Test
        @DisplayName("Số tiền cực lớn cộng tràn long: ném lỗi chứ không quay vòng thành số sai")
        void tran_so() {
            assertThatThrownBy(() -> entry().debit(A, Money.ofVnd(Long.MAX_VALUE))
                            .debit(B, Money.ofVnd(1))
                            .credit(C, Money.ofVnd(1))
                            .build())
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("Danh sách định khoản trả ra không sửa được")
        void postings_bat_bien() {
            JournalEntry result =
                    entry().debit(A, Money.ofVnd(5)).credit(B, Money.ofVnd(5)).build();

            assertThatThrownBy(() -> result.postings().add(Posting.debit(C, Money.ofVnd(1), 3)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Trường bắt buộc")
    class TruongBatBuoc {

        @Test
        @DisplayName("Thiếu idempotencyKey: không lập được — consumer retry sẽ ghi sổ hai lần")
        void thieu_idempotency_key() {
            assertThatThrownBy(() -> JournalEntry.builder()
                            .entryType("T")
                            .occurredAt(NOW)
                            .source(JournalEntry.SourceRef.payment(UUID.randomUUID()))
                            .createdBy("x")
                            .debit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("idempotencyKey");
        }

        @Test
        @DisplayName("Thiếu entryType, occurredAt, source hoặc createdBy: không lập được")
        void thieu_truong_khac() {
            assertThatThrownBy(() -> entry().entryType(null)
                            .debit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .hasMessageContaining("entryType");
            assertThatThrownBy(() -> entry().occurredAt(null)
                            .debit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .hasMessageContaining("occurredAt");
            assertThatThrownBy(() -> entry().source(null)
                            .debit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .hasMessageContaining("source");
            assertThatThrownBy(() -> entry().createdBy(null)
                            .debit(A, Money.ofVnd(1))
                            .credit(B, Money.ofVnd(1))
                            .build())
                    .hasMessageContaining("createdBy");
        }
    }

    @Nested
    @DisplayName("Bút toán đảo")
    class ButToanDao {

        @Test
        @DisplayName("Đảo: khoá chống trùng 'reversal:<id>', loại '_REVERSAL', giữ chứng từ gốc và tổ chức")
        void dao_giu_dau_vet() {
            UUID org = UUID.randomUUID();
            JournalEntry original = entry().organizationId(org)
                    .debit(A, Money.ofVnd(10))
                    .credit(B, Money.ofVnd(10))
                    .build();

            JournalEntry reversal = original.reverse("ghi nhầm", NOW.plusSeconds(60), "finance");

            assertThat(reversal.idempotencyKey()).isEqualTo("reversal:" + original.id());
            assertThat(reversal.entryType()).isEqualTo("TEST_REVERSAL");
            assertThat(reversal.source()).isEqualTo(original.source());
            assertThat(reversal.organizationId()).isEqualTo(org);
            assertThat(reversal.memo()).isEqualTo("ghi nhầm");
            assertThat(reversal.createdBy()).isEqualTo("finance");
            assertThat(reversal.occurredAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(reversal.id()).isNotEqualTo(original.id());
            // Cùng tài khoản, cùng số tiền, chiều ngược lại, id định khoản mới.
            for (int i = 0; i < original.postings().size(); i++) {
                Posting before = original.postings().get(i);
                Posting after = reversal.postings().get(i);
                assertThat(after.accountId()).isEqualTo(before.accountId());
                assertThat(after.amount()).isEqualTo(before.amount());
                assertThat(after.direction()).isEqualTo(before.direction().opposite());
                assertThat(after.id()).isNotEqualTo(before.id());
                assertThat(after.lineNo()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("Đảo hai lần quay về đúng chiều gốc, mỗi lần có khoá chống trùng riêng")
        void dao_cua_dao() {
            JournalEntry original =
                    entry().debit(A, Money.ofVnd(7)).credit(B, Money.ofVnd(7)).build();

            JournalEntry once = original.reverse("r1", NOW, "x");
            JournalEntry twice = once.reverse("r2", NOW, "x");

            assertThat(twice.postings())
                    .extracting(Posting::direction)
                    .containsExactlyElementsOf(
                            original.postings().stream().map(Posting::direction).toList());
            assertThat(twice.reversesEntryId()).isEqualTo(once.id());
            assertThat(List.of(original.idempotencyKey(), once.idempotencyKey(), twice.idempotencyKey()))
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Posting, AccountCode, Direction")
    class GiaTri {

        @ParameterizedTest(name = "lineNo={0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("lineNo nhỏ hơn 1: từ chối")
        void line_no_khong_hop_le(int lineNo) {
            assertThatThrownBy(() -> Posting.debit(A, Money.ofVnd(1), lineNo))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lineNo");
        }

        @Test
        @DisplayName("Posting thiếu tài khoản, chiều hoặc số tiền: từ chối")
        void posting_thieu_truong() {
            assertThatThrownBy(() -> Posting.credit(null, Money.ofVnd(1), 1)).hasMessageContaining("accountId");
            assertThatThrownBy(() -> new Posting(UUID.randomUUID(), A, null, Money.ofVnd(1), 1))
                    .hasMessageContaining("direction");
            assertThatThrownBy(() -> Posting.debit(A, null, 1)).hasMessageContaining("amount");
        }

        @Test
        @DisplayName("Số tiền âm không tồn tại trong sổ: chiều tiền là Direction, không phải dấu")
        void khong_co_tien_am() {
            assertThatThrownBy(() -> Money.ofVnd(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "101", "10100", "10a0", " 1010"})
        @DisplayName("Mã tài khoản không phải đúng 4 chữ số: từ chối")
        void ma_tai_khoan_sai(String value) {
            assertThatThrownBy(() -> new AccountCode(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Mã tài khoản null: từ chối; mã hợp lệ in ra đúng 4 chữ số")
        void ma_tai_khoan_null_va_hop_le() {
            assertThatThrownBy(() -> new AccountCode(null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(AccountCode.ORGANIZER_PAYABLE_HELD).hasToString("2011");
            assertThat(new AccountCode("1010")).isEqualTo(AccountCode.CASH_ESCROW);
        }

        @Test
        @DisplayName("Chiều tiền: đảo hai lần về chính nó; dấu dương khi trùng chiều số dư thường")
        void chieu_tien() {
            assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
            assertThat(Direction.CREDIT.opposite().opposite()).isEqualTo(Direction.CREDIT);
            // Tài sản (số dư thường bên Nợ) tăng khi ghi Nợ, giảm khi ghi Có.
            assertThat(Direction.DEBIT.signFor(Direction.DEBIT)).isEqualTo(1);
            assertThat(Direction.CREDIT.signFor(Direction.DEBIT)).isEqualTo(-1);
            // Nợ phải trả (số dư thường bên Có) tăng khi ghi Có.
            assertThat(Direction.CREDIT.signFor(Direction.CREDIT)).isEqualTo(1);
            assertThat(Direction.DEBIT.signFor(Direction.CREDIT)).isEqualTo(-1);
        }
    }
}
