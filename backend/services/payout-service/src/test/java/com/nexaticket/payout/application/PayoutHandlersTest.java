// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payout.application.command.ApprovePayoutHandler;
import com.nexaticket.payout.application.command.CreatePayoutBatchHandler;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutGate;
import com.nexaticket.payout.domain.model.PayoutStatus;
import com.nexaticket.payout.domain.port.LedgerPort;
import com.nexaticket.payout.domain.port.OrganizationPort;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.payout.domain.port.ReconciliationRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tạo và duyệt lô chi trả, với mọi cổng giả.
 *
 * <p>Ràng buộc của database ({@code FOR UPDATE}, CHECK bốn mắt) thuộc về {@code FourEyesIT}; ở đây
 * là thứ tự kiểm, mã lỗi trả ra, và việc KHÔNG ghi gì khi bị từ chối.
 */
class PayoutHandlersTest {

    /** 18:00 UTC ngày 12 = 01:00 sáng ngày 13 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");

    private static final UUID ORG = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final UUID CREATOR = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID APPROVER = UUID.fromString("b0000000-0000-4000-8000-000000000003");
    private static final String LEGAL = "Công ty TNHH Giải trí ABC";

    private PayoutRepository payouts;
    private LedgerPort ledger;
    private OrganizationPort organizations;
    private ReconciliationRepository reconciliation;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        payouts = mock(PayoutRepository.class);
        ledger = mock(LedgerPort.class);
        organizations = mock(OrganizationPort.class);
        reconciliation = mock(ReconciliationRepository.class);
    }

    @Nested
    @DisplayName("Tạo lô chi trả")
    class TaoLo {

        private final PayoutRepository.PayoutAccount account = new PayoutRepository.PayoutAccount(
                UUID.randomUUID(), ORG, "970422", "MB Bank", "0123456789", "CONG TY TNHH GIAI TRI ABC", true);

        @BeforeEach
        void allGatesOpen() {
            when(payouts.activeAccountOf(ORG)).thenReturn(Optional.of(account));
            when(ledger.balanceOf(ORG)).thenReturn(new LedgerPort.Balance(100_000_000L, 0, 0));
            when(payouts.inTransitVnd(ORG)).thenReturn(0L);
            when(organizations.profileOf(ORG)).thenReturn(new OrganizationPort.Profile(ORG, LEGAL, true));
            when(reconciliation.isClosed(any())).thenReturn(true);
        }

        @Test
        @DisplayName("Qua đủ sáu cổng: lưu lô chờ duyệt kèm đích chi trả, người tạo lấy từ lệnh")
        void qua_du_cong() {
            CreatePayoutBatchHandler.Result result = handler().handle(command(40_000_000L));

            assertThat(result.status()).isEqualTo("PENDING_APPROVAL");
            assertThat(result.amountVnd()).isEqualTo(40_000_000L);
            ArgumentCaptor<PayoutBatch> saved = ArgumentCaptor.forClass(PayoutBatch.class);
            verify(payouts).save(saved.capture(), eq("970422"), eq("0123456789"), eq("CONG TY TNHH GIAI TRI ABC"));
            assertThat(saved.getValue().id()).isEqualTo(result.batchId());
            assertThat(saved.getValue().createdBy()).isEqualTo(CREATOR);
            assertThat(saved.getValue().payoutAccountId()).isEqualTo(account.id());
        }

        @Test
        @DisplayName("Đối soát được hỏi cho ngày làm việc TRƯỚC theo giờ Việt Nam, không theo UTC")
        void ngay_doi_soat_theo_gio_viet_nam() {
            handler().handle(command(1_000L));

            verify(reconciliation).isClosed(LocalDate.of(2026, 10, 12));
        }

        @Test
        @DisplayName("Tiền đang trên đường chuyển bị trừ khỏi số dư: hai lô không cùng tiêu một số dư")
        void tru_tien_dang_chuyen() {
            when(payouts.inTransitVnd(ORG)).thenReturn(60_000_000L);

            assertThatThrownBy(() -> handler().handle(command(50_000_000L)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(PayoutErrorCode.PAYOUT_BLOCKED);
                        assertThat(blockersOf(e)).containsExactly(PayoutGate.INSUFFICIENT_BALANCE);
                    });
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Chi vừa đúng phần còn lại sau khi trừ tiền đang chuyển: cho")
        void vua_dung_phan_con_lai() {
            when(payouts.inTransitVnd(ORG)).thenReturn(60_000_000L);

            assertThat(handler().handle(command(40_000_000L)).status()).isEqualTo("PENDING_APPROVAL");
        }

        @Test
        @DisplayName("Tổ chức bị đình chỉ: 422 PAYOUT_BLOCKED liệt kê ORGANIZATION_SUSPENDED, không lưu gì")
        void to_chuc_bi_dinh_chi() {
            when(organizations.profileOf(ORG)).thenReturn(new OrganizationPort.Profile(ORG, LEGAL, false));

            assertThatThrownBy(() -> handler().handle(command(1_000L)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode().httpStatus()).isEqualTo(422);
                        assertThat(blockersOf(e)).containsExactly(PayoutGate.ORGANIZATION_SUSPENDED);
                    });
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Người vận hành chưa xác nhận hết hạn giữ tiền: chặn")
        void chua_het_han_giu() {
            assertThatThrownBy(
                            () -> handler().handle(new CreatePayoutBatchHandler.Command(ORG, 1_000L, CREATOR, false)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(blockersOf(e))
                            .containsExactly(PayoutGate.HOLD_PERIOD_NOT_ELAPSED));
        }

        @Test
        @DisplayName("Chưa có đích chi trả: 400 NO_PAYOUT_ACCOUNT, không hỏi sổ cái")
        void chua_co_dich_chi_tra() {
            when(payouts.activeAccountOf(ORG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler().handle(command(1_000L)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.NO_PAYOUT_ACCOUNT));
            verifyNoInteractions(ledger, organizations);
        }

        @Test
        @DisplayName("Sổ cái không đọc được: ném nguyên lỗi, không coi là số dư 0, không lưu gì")
        void so_cai_hong() {
            when(ledger.balanceOf(ORG)).thenThrow(new LedgerPort.LedgerUnavailableException("timeout", null));

            assertThatThrownBy(() -> handler().handle(command(1_000L)))
                    .isInstanceOf(LedgerPort.LedgerUnavailableException.class);
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Số tiền 0 (lọt qua controller): không tạo được lô nào")
        void so_tien_khong() {
            assertThatThrownBy(() -> handler().handle(command(0L))).isInstanceOf(RuntimeException.class);
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Số tiền âm (lọt qua controller): không tạo được lô nào")
        void so_tien_am() {
            assertThatThrownBy(() -> handler().handle(command(-5_000L))).isInstanceOf(RuntimeException.class);
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        private CreatePayoutBatchHandler handler() {
            return new CreatePayoutBatchHandler(payouts, ledger, organizations, reconciliation, clock);
        }

        private CreatePayoutBatchHandler.Command command(long amount) {
            return new CreatePayoutBatchHandler.Command(ORG, amount, CREATOR, true);
        }

        @SuppressWarnings("unchecked")
        private List<String> blockersOf(ApiException e) {
            return (List<String>) e.meta().get("blockers");
        }
    }

    @Nested
    @DisplayName("Duyệt, từ chối, xác nhận")
    class Duyet {

        private final UUID batchId = UUID.randomUUID();

        private ApprovePayoutHandler handler() {
            return new ApprovePayoutHandler(payouts, clock);
        }

        @Test
        @DisplayName("Người khác duyệt: APPROVED, ghi người duyệt và thời điểm theo đồng hồ")
        void nguoi_khac_duyet() {
            PayoutBatch batch = stored(PayoutStatus.PENDING_APPROVAL);

            handler().approve(batchId, APPROVER);

            assertThat(batch.status()).isEqualTo(PayoutStatus.APPROVED);
            assertThat(batch.approvedBy()).isEqualTo(APPROVER);
            assertThat(batch.approvedAt()).isEqualTo(NOW);
            verify(payouts).update(batch, NOW);
        }

        @Test
        @DisplayName("Người tạo tự duyệt: 403 SELF_APPROVAL_FORBIDDEN, không ghi gì")
        void tu_duyet() {
            stored(PayoutStatus.PENDING_APPROVAL);

            assertThatThrownBy(() -> handler().approve(batchId, CREATOR))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(PayoutErrorCode.SELF_APPROVAL_FORBIDDEN);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(403);
                    });
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Duyệt lần hai (lô đã APPROVED): 409 BATCH_NOT_IN_EXPECTED_STATE, không ghi đè người duyệt")
        void duyet_lan_hai() {
            stored(PayoutStatus.APPROVED);

            assertThatThrownBy(() -> handler().approve(batchId, UUID.randomUUID()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(409);
                    });
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Lô không tồn tại: 404 BATCH_NOT_FOUND cho cả ba thao tác")
        void lo_khong_ton_tai() {
            when(payouts.lockById(batchId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler().approve(batchId, APPROVER))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.BATCH_NOT_FOUND));
            assertThatThrownBy(() -> handler().reject(batchId, "x"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.BATCH_NOT_FOUND));
            assertThatThrownBy(() -> handler().markCompleted(batchId, "FT1"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.BATCH_NOT_FOUND));
        }

        @Test
        @DisplayName("Từ chối lô chờ duyệt: REJECTED kèm lý do, được ghi")
        void tu_choi() {
            PayoutBatch batch = stored(PayoutStatus.PENDING_APPROVAL);

            handler().reject(batchId, "Sai tên chủ tài khoản");

            assertThat(batch.status()).isEqualTo(PayoutStatus.REJECTED);
            assertThat(batch.rejectedReason()).isEqualTo("Sai tên chủ tài khoản");
            verify(payouts).update(batch, NOW);
        }

        @Test
        @DisplayName("Từ chối lô đã duyệt: 409, không ghi")
        void tu_choi_lo_da_duyet() {
            stored(PayoutStatus.APPROVED);

            assertThatThrownBy(() -> handler().reject(batchId, "muộn rồi"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE));
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Xác nhận đã chuyển cho lô CHƯA duyệt: 409 — không bỏ qua được bước duyệt")
        void xac_nhan_lo_chua_duyet() {
            stored(PayoutStatus.PENDING_APPROVAL);

            assertThatThrownBy(() -> handler().markCompleted(batchId, "FT1"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PayoutErrorCode.BATCH_NOT_IN_EXPECTED_STATE));
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Xác nhận lô đã duyệt: COMPLETED kèm mã giao dịch")
        void xac_nhan_lo_da_duyet() {
            PayoutBatch batch = stored(PayoutStatus.APPROVED);

            handler().markCompleted(batchId, "FT26101200001");

            assertThat(batch.status()).isEqualTo(PayoutStatus.COMPLETED);
            assertThat(batch.bankReference()).isEqualTo("FT26101200001");
            assertThat(batch.completedAt()).isEqualTo(NOW);
            verify(payouts).update(batch, NOW);
        }

        private PayoutBatch stored(PayoutStatus status) {
            PayoutBatch batch = PayoutBatch.rehydrate(
                    batchId,
                    ORG,
                    UUID.randomUUID(),
                    10_000_000L,
                    CREATOR,
                    status,
                    status == PayoutStatus.PENDING_APPROVAL ? null : APPROVER,
                    status == PayoutStatus.PENDING_APPROVAL ? null : NOW.minusSeconds(3600));
            when(payouts.lockById(batchId)).thenReturn(Optional.of(batch));
            return batch;
        }
    }
}
