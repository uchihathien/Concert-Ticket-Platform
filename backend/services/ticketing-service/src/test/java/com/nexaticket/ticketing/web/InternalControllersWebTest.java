// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.platform.security.tenant.InternalApiFilter;
import com.nexaticket.ticketing.application.CheckinAssignments;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.interfaces.rest.InternalCheckinAssignmentController;
import com.nexaticket.ticketing.interfaces.rest.InternalTicketController;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Open Host Service của ticketing ({@code /internal/**}) — chỉ service nội bộ mang đúng bí mật dùng
 * chung mới gọi được. Đây là đường phát hành vé: lọt ra ngoài là ai cũng tự in vé cho mình.
 */
@WebMvcTest(controllers = {InternalTicketController.class, InternalCheckinAssignmentController.class})
class InternalControllersWebTest extends WebSliceTest {

    private static final UUID ORG = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID STAFF = UUID.fromString("90000000-0000-4000-8000-000000000002");

    @MockitoBean
    IssueTicketsHandler issueTickets;

    @MockitoBean
    CheckinAssignments assignments;

    @Nested
    @DisplayName("Bí mật dùng chung")
    class BiMat {

        @Test
        @DisplayName("Không mang X-Internal-Token: 404 — không xác nhận endpoint tồn tại")
        void khong_mang_token() throws Exception {
            mvc.perform(post("/internal/tickets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(issueBody()))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(issueTickets);
        }

        @Test
        @DisplayName("Sai bí mật: 404")
        void sai_bi_mat() throws Exception {
            mvc.perform(post("/internal/tickets")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET + "x")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(issueBody()))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(issueTickets);
        }

        @Test
        @DisplayName("Người dùng đăng nhập bình thường (có JWT) nhưng không có bí mật: vẫn 404")
        void jwt_khong_thay_duoc_bi_mat() throws Exception {
            mvc.perform(post("/internal/tickets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(issueBody())
                            .with(customer(UUID.randomUUID())))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(issueTickets);
        }
    }

    @Nested
    @DisplayName("Phát vé nội bộ")
    class PhatVe {

        @Test
        @DisplayName("Đúng bí mật: phát vé, không tra tên người mua, trả số vé phát mới")
        void phat_ve() throws Exception {
            when(issueTickets.handle(any())).thenReturn(2);

            mvc.perform(internal(post("/internal/tickets")).content(issueBody()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.issued").value(2));

            ArgumentCaptor<IssueTicketsHandler.Command> cmd =
                    ArgumentCaptor.forClass(IssueTicketsHandler.Command.class);
            verify(issueTickets).handle(cmd.capture());
            assertThat(cmd.getValue().holderName()).isNull();
            assertThat(cmd.getValue().seats()).hasSize(1);
        }

        @Test
        @DisplayName("Thiếu orderId (@NotNull): 400 VALIDATION_FAILED, không phát vé")
        void thieu_order_id() throws Exception {
            String body = "{\"eventSessionId\":\"" + UUID.randomUUID() + "\",\"organizationId\":\"" + ORG
                    + "\",\"userId\":\"" + UUID.randomUUID() + "\",\"seats\":[]}";

            mvc.perform(internal(post("/internal/tickets")).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(issueTickets);
        }

        @Test
        @DisplayName("Thiếu danh sách ghế: 400, không phải 500")
        void thieu_danh_sach_ghe() throws Exception {
            String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"eventSessionId\":\"" + UUID.randomUUID()
                    + "\",\"organizationId\":\"" + ORG + "\",\"userId\":\"" + UUID.randomUUID() + "\"}";

            mvc.perform(internal(post("/internal/tickets")).content(body)).andExpect(status().isBadRequest());
            verifyNoInteractions(issueTickets);
        }
    }

    @Nested
    @DisplayName("Gán nhân viên soát vé")
    class GanNhanVien {

        @Test
        @DisplayName("Đọc việc gán của một nhân viên")
        void doc_viec_gan() throws Exception {
            UUID event = UUID.randomUUID();
            when(assignments.list(ORG, STAFF)).thenReturn(List.of(new CheckinAssignments.Assigned(STAFF, event)));

            mvc.perform(internal(get("/internal/checkin-assignments/" + ORG)).param("userId", STAFF.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventId").value(event.toString()));
        }

        @Test
        @DisplayName("Đặt lại danh sách sự kiện: truyền đúng người gán, trả trạng thái sau khi ghi")
        void dat_lai() throws Exception {
            UUID event = UUID.randomUUID();
            UUID admin = UUID.randomUUID();
            when(assignments.replace(ORG, STAFF, List.of(event), admin))
                    .thenReturn(List.of(new CheckinAssignments.Assigned(STAFF, event)));

            mvc.perform(internal(put("/internal/checkin-assignments/" + ORG + "/users/" + STAFF))
                            .content("{\"eventIds\":[\"" + event + "\"],\"assignedBy\":\"" + admin + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].userId").value(STAFF.toString()));
        }

        @Test
        @DisplayName("Danh sách rỗng là gỡ khỏi mọi sự kiện — hợp lệ")
        void danh_sach_rong() throws Exception {
            mvc.perform(internal(put("/internal/checkin-assignments/" + ORG + "/users/" + STAFF))
                            .content("{\"eventIds\":[]}"))
                    .andExpect(status().isOk());

            verify(assignments).replace(ORG, STAFF, List.of(), null);
        }

        @Test
        @DisplayName("Thiếu eventIds: 400 — không được hiểu nhầm thành 'gỡ hết'")
        void thieu_event_ids() throws Exception {
            mvc.perform(internal(put("/internal/checkin-assignments/" + ORG + "/users/" + STAFF))
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.eventIds").exists());
            verifyNoInteractions(assignments);
        }

        @Test
        @DisplayName("Quá 200 sự kiện một lần: 400")
        void qua_200_su_kien() throws Exception {
            String ids = IntStream.range(0, 201)
                    .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(Collectors.joining(","));

            mvc.perform(internal(put("/internal/checkin-assignments/" + ORG + "/users/" + STAFF))
                            .content("{\"eventIds\":[" + ids + "]}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(assignments);
        }
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiFilter.HEADER, INTERNAL_SECRET).contentType(MediaType.APPLICATION_JSON);
    }

    private static String issueBody() {
        return "{\"orderId\":\"" + UUID.randomUUID() + "\",\"eventSessionId\":\"" + UUID.randomUUID()
                + "\",\"organizationId\":\"" + ORG + "\",\"userId\":\"" + UUID.randomUUID()
                + "\",\"seats\":[{\"orderItemId\":\"" + UUID.randomUUID() + "\",\"seatCode\":\"A-1\"}]}";
    }
}
