// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.interfaces.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.ledger.application.query.LedgerQueries;
import com.nexaticket.ledger.support.WebSlice;
import com.nexaticket.ledger.support.WebSlice.Caller;
import com.nexaticket.platform.security.SecurityAutoConfiguration;
import com.nexaticket.platform.web.WebAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sổ cái nền tảng qua chuỗi bảo mật thật: chỉ superadmin đọc được, kể cả chủ của chính tổ chức đang được
 * hỏi số dư cũng không (ADR-1010).
 */
@WebMvcTest(controllers = PlatformLedgerController.class)
@ImportAutoConfiguration({SecurityAutoConfiguration.class, WebAutoConfiguration.class})
@Import(WebSlice.class)
class PlatformLedgerControllerWebTest {

    private static final String TRIAL = "/v1/platform/trial-balance";
    private static final String BALANCE = "/v1/platform/organizations/" + WebSlice.OWN_ORG + "/balance";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LedgerQueries queries;

    @AfterEach
    void reset() {
        WebSlice.reset();
    }

    private static MockHttpServletRequestBuilder authed(String path) {
        return get(path).header("Authorization", "Bearer " + WebSlice.VALID_TOKEN);
    }

    @Nested
    @DisplayName("Xác thực")
    class XacThuc {

        @Test
        @DisplayName("Không có token: 401, không đọc sổ")
        void khong_token() throws Exception {
            mvc.perform(get(TRIAL)).andExpect(status().isUnauthorized());
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("Token hỏng: 401 kèm WWW-Authenticate, không đọc sổ")
        void token_hong() throws Exception {
            mvc.perform(get(TRIAL).header("Authorization", "Bearer gia-mao"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"));
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("Token hợp lệ nhưng identity không biết người này: 401 UNAUTHENTICATED dạng RFC 7807")
        void nguoi_la() throws Exception {
            WebSlice.actAs(Caller.UNKNOWN);

            mvc.perform(authed(TRIAL))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            verifyNoInteractions(queries);
        }
    }

    @Nested
    @DisplayName("Phân quyền")
    class PhanQuyen {

        @Test
        @DisplayName("Chủ tổ chức hỏi bảng cân đối: 403 FORBIDDEN")
        void chu_to_chuc_can_doi_403() throws Exception {
            mvc.perform(authed(TRIAL))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/forbidden"));
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("Chủ tổ chức hỏi số dư CỦA CHÍNH tổ chức mình: vẫn 403 — sổ cái là dữ liệu nền tảng")
        void chu_to_chuc_so_du_cua_minh_403() throws Exception {
            mvc.perform(authed(BALANCE)).andExpect(status().isForbidden());
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("Superadmin: bảng cân đối 200 với đủ ba trường")
        void superadmin_can_doi() throws Exception {
            WebSlice.actAs(Caller.SUPER_ADMIN);
            when(queries.trialBalance()).thenReturn(new LedgerQueries.TrialBalanceView(10, 9, false));

            mvc.perform(authed(TRIAL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalDebitVnd").value(10))
                    .andExpect(jsonPath("$.totalCreditVnd").value(9))
                    .andExpect(jsonPath("$.balanced").value(false));
        }

        @Test
        @DisplayName(
                "Superadmin: số dư một tổ chức bất kỳ (không phải thành viên) — 200, không bị TenantFilter chặn 404")
        void superadmin_so_du_to_chuc_khac() throws Exception {
            WebSlice.actAs(Caller.SUPER_ADMIN);
            UUID other = UUID.fromString("22222222-2222-4222-a222-222222222222");
            when(queries.organizationBalance(other))
                    .thenReturn(new LedgerQueries.OrganizationBalanceView(other.toString(), 1, 2, 3, 4));

            mvc.perform(authed("/v1/platform/organizations/" + other + "/balance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.organizationId").value(other.toString()))
                    .andExpect(jsonPath("$.heldVnd").value(1))
                    .andExpect(jsonPath("$.availableVnd").value(2))
                    .andExpect(jsonPath("$.refundReserveVnd").value(3))
                    .andExpect(jsonPath("$.inTransitVnd").value(4));
            verify(queries).organizationBalance(other);
        }
    }

    @Nested
    @DisplayName("Tham số")
    class ThamSo {

        @Test
        @DisplayName("organizationId không phải UUID: 400, không đọc sổ")
        void id_khong_phai_uuid() throws Exception {
            WebSlice.actAs(Caller.SUPER_ADMIN);

            mvc.perform(authed("/v1/platform/organizations/khong-phai-uuid/balance"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(queries);
        }
    }
}
