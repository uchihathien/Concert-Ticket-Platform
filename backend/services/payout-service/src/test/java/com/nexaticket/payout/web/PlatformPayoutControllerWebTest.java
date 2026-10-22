// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.payout.application.command.ApprovePayoutHandler;
import com.nexaticket.payout.application.command.CreatePayoutBatchHandler;
import com.nexaticket.payout.domain.model.PayoutBatch;
import com.nexaticket.payout.domain.model.PayoutStatus;
import com.nexaticket.payout.domain.port.LedgerPort;
import com.nexaticket.payout.domain.port.OrganizationPort;
import com.nexaticket.payout.domain.port.PayoutRepository;
import com.nexaticket.payout.domain.port.ReconciliationRepository;
import com.nexaticket.payout.interfaces.rest.PlatformPayoutController;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.web.WebAutoConfiguration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Chi trả qua HTTP — chỉ superadmin, với chuỗi bảo mật thật của starter-security, handler thật và
 * kho dữ liệu giả.
 *
 * <p>Nằm ngoài bốn tầng hexagonal (gói {@code web}) vì nó chạm cả controller lẫn port của domain.
 */
@WebMvcTest(controllers = PlatformPayoutController.class)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import({CreatePayoutBatchHandler.class, ApprovePayoutHandler.class})
class PlatformPayoutControllerWebTest {

    private static final UUID ORG = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_A = UUID.fromString("d0000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_B = UUID.fromString("d0000000-0000-4000-8000-000000000003");
    private static final String BASE = "/v1/platform/payouts";
    private static final String LEGAL = "Công ty TNHH Giải trí ABC";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MembershipLookup memberships;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PayoutRepository payouts;

    @MockitoBean
    LedgerPort ledger;

    @MockitoBean
    OrganizationPort organizations;

    @MockitoBean
    ReconciliationRepository reconciliation;

    @BeforeEach
    void allGatesOpen() {
        when(payouts.activeAccountOf(ORG))
                .thenReturn(Optional.of(new PayoutRepository.PayoutAccount(
                        UUID.randomUUID(), ORG, "970422", "MB Bank", "0123456789", "CONG TY TNHH GIAI TRI ABC", true)));
        when(ledger.balanceOf(ORG)).thenReturn(new LedgerPort.Balance(100_000_000L, 0, 0));
        when(organizations.profileOf(ORG)).thenReturn(new OrganizationPort.Profile(ORG, LEGAL, true));
        when(reconciliation.isClosed(any())).thenReturn(true);
    }

    @Nested
    @DisplayName("Chỉ superadmin")
    class ChiSuperAdmin {

        @Test
        @DisplayName("Không có token: 401")
        void khong_token() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG + "\",\"amountVnd\":1000,\"holdPeriodElapsed\":true}"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(payouts);
        }

        @Test
        @DisplayName("Chủ tổ chức (kể cả của chính tổ chức nhận tiền): 403 — tổ chức không có endpoint ở đây")
        void chu_to_chuc_403() throws Exception {
            mvc.perform(create(validCreate()).with(as(ADMIN_A, Map.of(TenantId.of(ORG), Role.ORG_OWNER), false)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/forbidden"));
            verifyNoInteractions(payouts, ledger);
        }

        @Test
        @DisplayName("Khách mua vé: 403 cho cả bốn thao tác")
        void khach_hang_403_moi_thao_tac() throws Exception {
            UUID batch = UUID.randomUUID();
            RequestPostProcessor customer = as(UUID.randomUUID(), Map.of(), false);

            mvc.perform(create(validCreate()).with(customer)).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/" + batch + "/approve").with(customer)).andExpect(status().isForbidden());
            mvc.perform(json(post(BASE + "/" + batch + "/reject"), "{\"reason\":\"x\"}")
                            .with(customer))
                    .andExpect(status().isForbidden());
            mvc.perform(json(post(BASE + "/" + batch + "/mark-completed"), "{\"bankReference\":\"FT1\"}")
                            .with(customer))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(payouts);
        }

        @Test
        @DisplayName("Identity không trả lời: 401 UNAUTHENTICATED, không phải 403")
        void identity_khong_tra_loi() throws Exception {
            mvc.perform(create(validCreate())
                            .with(jwt().jwt(t -> t.subject(UUID.randomUUID().toString()))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Nested
    @DisplayName("Tạo lô")
    class TaoLo {

        @Test
        @DisplayName("Superadmin tạo lô: 201, người tạo lấy từ token chứ không từ thân request")
        void tao_lo() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG + "\",\"amountVnd\":40000000,\"holdPeriodElapsed\":true,"
                                    + "\"createdBy\":\"" + ADMIN_B + "\"}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.amountVnd").value(40_000_000))
                    .andExpect(jsonPath("$.batchId").isNotEmpty());

            ArgumentCaptor<PayoutBatch> saved = ArgumentCaptor.forClass(PayoutBatch.class);
            verify(payouts).save(saved.capture(), anyString(), anyString(), anyString());
            assertThat(saved.getValue().createdBy()).isEqualTo(ADMIN_A);
        }

        @Test
        @DisplayName("Số tiền 0: 400 VALIDATION_FAILED chỉ ra amountVnd")
        void so_tien_khong() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG + "\",\"amountVnd\":0,\"holdPeriodElapsed\":true}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.amountVnd").exists());
            verifyNoInteractions(ledger);
        }

        @Test
        @DisplayName("Số tiền âm: 400")
        void so_tien_am() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG + "\",\"amountVnd\":-1,\"holdPeriodElapsed\":true}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(ledger);
        }

        @Test
        @DisplayName("Thiếu tổ chức: 400 chỉ ra organizationId")
        void thieu_to_chuc() throws Exception {
            mvc.perform(create("{\"amountVnd\":1000,\"holdPeriodElapsed\":true}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.organizationId").exists());
        }

        @Test
        @DisplayName("Số tiền không phải số: 400 'Malformed request body'")
        void so_tien_khong_phai_so() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG + "\",\"amountVnd\":\"mot trieu\"}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        @Test
        @DisplayName("Vượt số dư: 422 PAYOUT_BLOCKED kèm danh sách cổng chặn trong meta")
        void vuot_so_du() throws Exception {
            mvc.perform(create("{\"organizationId\":\"" + ORG
                                    + "\",\"amountVnd\":100000001,\"holdPeriodElapsed\":false}")
                            .with(superAdmin(ADMIN_A)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PAYOUT_BLOCKED"))
                    .andExpect(jsonPath("$.meta.blockers.length()").value(2))
                    .andExpect(jsonPath("$.meta.blockers[0]").value("HOLD_PERIOD_NOT_ELAPSED"))
                    .andExpect(jsonPath("$.meta.blockers[1]").value("INSUFFICIENT_BALANCE"));
            verify(payouts, never()).save(any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Chưa có đích chi trả: 400 NO_PAYOUT_ACCOUNT")
        void chua_co_dich() throws Exception {
            when(payouts.activeAccountOf(ORG)).thenReturn(Optional.empty());

            mvc.perform(create(validCreate()).with(superAdmin(ADMIN_A)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NO_PAYOUT_ACCOUNT"));
        }
    }

    @Nested
    @DisplayName("Bốn mắt")
    class BonMat {

        private final UUID batchId = UUID.randomUUID();

        @Test
        @DisplayName("Superadmin khác duyệt: 204, người duyệt lấy từ token")
        void nguoi_khac_duyet() throws Exception {
            stored(PayoutStatus.PENDING_APPROVAL, ADMIN_A);

            mvc.perform(post(BASE + "/" + batchId + "/approve").with(superAdmin(ADMIN_B)))
                    .andExpect(status().isNoContent());

            verify(payouts).update(argThat(b -> ADMIN_B.equals(b.approvedBy())), any());
        }

        @Test
        @DisplayName("Người tạo tự duyệt: 403 SELF_APPROVAL_FORBIDDEN, không ghi gì")
        void tu_duyet() throws Exception {
            stored(PayoutStatus.PENDING_APPROVAL, ADMIN_A);

            mvc.perform(post(BASE + "/" + batchId + "/approve").with(superAdmin(ADMIN_A)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Duyệt lần hai: 409 BATCH_NOT_IN_EXPECTED_STATE")
        void duyet_lan_hai() throws Exception {
            stored(PayoutStatus.APPROVED, ADMIN_A);

            mvc.perform(post(BASE + "/" + batchId + "/approve").with(superAdmin(ADMIN_B)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BATCH_NOT_IN_EXPECTED_STATE"));
        }

        @Test
        @DisplayName("Lô không tồn tại: 404 BATCH_NOT_FOUND")
        void lo_khong_ton_tai() throws Exception {
            when(payouts.lockById(batchId)).thenReturn(Optional.empty());

            mvc.perform(post(BASE + "/" + batchId + "/approve").with(superAdmin(ADMIN_B)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        }

        @Test
        @DisplayName("Từ chối thiếu lý do hoặc lý do trắng: 400")
        void tu_choi_thieu_ly_do() throws Exception {
            mvc.perform(json(post(BASE + "/" + batchId + "/reject"), "{\"reason\":\"   \"}")
                            .with(superAdmin(ADMIN_B)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.reason").exists());
            mvc.perform(json(post(BASE + "/" + batchId + "/reject"), "{}").with(superAdmin(ADMIN_B)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(payouts);
        }

        @Test
        @DisplayName("Xác nhận chuyển khoản thiếu mã giao dịch: 400")
        void xac_nhan_thieu_ma() throws Exception {
            mvc.perform(json(post(BASE + "/" + batchId + "/mark-completed"), "{\"bankReference\":\"\"}")
                            .with(superAdmin(ADMIN_B)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.bankReference").exists());
            verifyNoInteractions(payouts);
        }

        @Test
        @DisplayName("Xác nhận chuyển khoản cho lô chưa duyệt: 409")
        void xac_nhan_lo_chua_duyet() throws Exception {
            stored(PayoutStatus.PENDING_APPROVAL, ADMIN_A);

            mvc.perform(json(post(BASE + "/" + batchId + "/mark-completed"), "{\"bankReference\":\"FT1\"}")
                            .with(superAdmin(ADMIN_B)))
                    .andExpect(status().isConflict());
            verify(payouts, never()).update(any(), any());
        }

        @Test
        @DisplayName("Xác nhận chuyển khoản cho lô đã duyệt: 204")
        void xac_nhan_lo_da_duyet() throws Exception {
            stored(PayoutStatus.APPROVED, ADMIN_A);

            mvc.perform(json(post(BASE + "/" + batchId + "/mark-completed"), "{\"bankReference\":\"FT1\"}")
                            .with(superAdmin(ADMIN_B)))
                    .andExpect(status().isNoContent());
            verify(payouts).update(argThat(b -> "FT1".equals(b.bankReference())), any());
        }

        private void stored(PayoutStatus status, UUID creator) {
            PayoutBatch batch = PayoutBatch.rehydrate(
                    batchId,
                    ORG,
                    UUID.randomUUID(),
                    10_000_000L,
                    creator,
                    status,
                    status == PayoutStatus.PENDING_APPROVAL ? null : UUID.randomUUID(),
                    status == PayoutStatus.PENDING_APPROVAL ? null : java.time.Instant.parse("2026-10-01T00:00:00Z"));
            when(payouts.lockById(batchId)).thenReturn(Optional.of(batch));
        }
    }

    private static String validCreate() {
        return "{\"organizationId\":\"" + ORG + "\",\"amountVnd\":1000,\"holdPeriodElapsed\":true}";
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return json(post(BASE), body);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestPostProcessor superAdmin(UUID userId) {
        return as(userId, Map.of(), true);
    }

    private RequestPostProcessor as(UUID userId, Map<TenantId, Role> roles, boolean superAdmin) {
        String subject = userId.toString();
        when(memberships.resolve(argThat(claims -> claims != null && subject.equals(claims.subject()))))
                .thenReturn(new MembershipLookup.Principal(UserId.of(userId), roles, superAdmin));
        return jwt().jwt(token -> token.subject(subject));
    }
}
