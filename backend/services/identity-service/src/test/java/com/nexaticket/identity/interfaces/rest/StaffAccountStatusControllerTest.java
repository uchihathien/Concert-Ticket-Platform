// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /v1/staff-accounts/status}: màn đăng nhập của app Staff hỏi vì sao tài khoản không vào được.
 *
 * <p>KHÔNG cần đăng nhập (người hỏi chính là người chưa đăng nhập được). Handler quyết định nói gì; ở đây
 * kiểm phần tầng web chịu trách nhiệm: đường này mở thật, tham số đi đúng vào handler, và thân trả về
 * chỉ có một trường {@code status} — không kèm tên, email hay lý do khoá.
 */
class StaffAccountStatusControllerTest extends RestSliceTestBase {

    @Test
    @DisplayName("Không token vẫn hỏi được: 200 và đúng giá trị handler trả về")
    void khong_can_dang_nhap() throws Exception {
        when(staffAccounts.publicStatusOf("nv-0001")).thenReturn("LOCKED");

        mvc.perform(get("/v1/staff-accounts/status").param("username", "nv-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.length()").value(1));

        verify(staffAccounts).publicStatusOf("nv-0001");
    }

    @Test
    @DisplayName("Thiếu tham số username: vẫn 200 (handler trả UNKNOWN), không phải 400/500")
    void thieu_username() throws Exception {
        when(staffAccounts.publicStatusOf(null)).thenReturn("UNKNOWN");

        mvc.perform(get("/v1/staff-accounts/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }
}
