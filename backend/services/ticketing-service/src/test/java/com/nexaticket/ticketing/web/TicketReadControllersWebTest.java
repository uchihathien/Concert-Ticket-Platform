// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.ticketing.application.TicketingAccess;
import com.nexaticket.ticketing.application.query.TicketQueries;
import com.nexaticket.ticketing.application.query.TicketSearchQuery;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSearchPort;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.interfaces.rest.MyTicketsController;
import com.nexaticket.ticketing.interfaces.rest.OrganizationTicketController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/** Ví vé của khách và màn tra cứu vé của ban tổ chức qua HTTP — query thật, kho dữ liệu giả. */
@WebMvcTest(controllers = {MyTicketsController.class, OrganizationTicketController.class})
@Import({TicketQueries.class, TicketSearchQuery.class, TicketingAccess.class})
class TicketReadControllersWebTest extends WebSliceTest {

    private static final UUID ORG = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID ME = UUID.fromString("80000000-0000-4000-8000-000000000002");
    private static final String SEARCH = "/v1/organizations/" + ORG + "/tickets";

    @MockitoBean
    TicketRepository tickets;

    @MockitoBean
    TicketSigner signer;

    @MockitoBean
    TicketSearchPort search;

    @MockitoBean
    OrderingPort ordering;

    @BeforeEach
    void setUp() {
        when(signer.sign(any())).thenReturn("h.p.s");
        when(search.search(any())).thenReturn(new TicketSearchPort.Page(List.of(), 0));
        when(ordering.statusesOf(any())).thenReturn(Map.of());
    }

    @Nested
    @DisplayName("Ví vé của khách")
    class ViVe {

        @Test
        @DisplayName("Không đăng nhập: 401")
        void khong_dang_nhap() throws Exception {
            mvc.perform(get("/v1/me/tickets")).andExpect(status().isUnauthorized());
            verifyNoInteractions(tickets);
        }

        @Test
        @DisplayName("Identity không trả lời: 401 UNAUTHENTICATED")
        void identity_khong_tra_loi() throws Exception {
            mvc.perform(get("/v1/me/tickets").with(identityDown(ME)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("Khách xem ví của mình: người dùng lấy từ token, mỗi vé có token QR mới ký")
        void xem_vi_cua_minh() throws Exception {
            Ticket mine = ticket(ME);
            when(tickets.findByUser(ME, 50, 0)).thenReturn(List.of(mine));

            mvc.perform(get("/v1/me/tickets").with(customer(ME)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(mine.id().toString()))
                    .andExpect(jsonPath("$[0].qrToken").value("h.p.s"))
                    .andExpect(jsonPath("$[0].holderName").doesNotExist());
        }

        @Test
        @DisplayName("limit quá lớn bị kẹp về 100")
        void limit_bi_kep() throws Exception {
            mvc.perform(get("/v1/me/tickets").param("limit", "5000").with(customer(ME)))
                    .andExpect(status().isOk());

            verify(tickets).findByUser(ME, 100, 0);
        }

        @Test
        @DisplayName("limit/offset âm: bị chặn ở cửa (400) hoặc kẹp về không âm — không được đi xuống câu SQL")
        void limit_am() throws Exception {
            // PostgreSQL từ chối "LIMIT -1"/"OFFSET -1": giá trị âm lọt xuống JdbcTicketRepository
            // thành 500 cho một lỗi của người gọi.
            MvcResult result = mvc.perform(get("/v1/me/tickets")
                            .param("limit", "-1")
                            .param("offset", "-10")
                            .with(customer(ME)))
                    .andReturn();

            if (result.getResponse().getStatus() != 400) {
                ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
                ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
                verify(tickets).findByUser(any(), limit.capture(), offset.capture());
                assertThat(limit.getValue()).as("limit gửi xuống SQL").isNotNegative();
                assertThat(offset.getValue()).as("offset gửi xuống SQL").isNotNegative();
            }
        }

        @Test
        @DisplayName("Vé của một đơn: chỉ trả vé thuộc người đang hỏi, dù đơn chứa vé của người khác")
        void ve_cua_don_chi_cua_minh() throws Exception {
            UUID order = UUID.randomUUID();
            Ticket mine = ticket(ME);
            when(tickets.findByOrder(order)).thenReturn(List.of(mine, ticket(UUID.randomUUID())));

            mvc.perform(get("/v1/orders/" + order + "/tickets").with(customer(ME)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(mine.id().toString()));
        }
    }

    @Nested
    @DisplayName("Tra cứu vé của ban tổ chức")
    class TraCuu {

        @Test
        @DisplayName("Nhân viên soát vé không tra được danh bạ khách: 403")
        void nhan_vien_soat_ve_403() throws Exception {
            mvc.perform(get(SEARCH).with(member(ME, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(search);
        }

        @Test
        @DisplayName("Người quản lý sự kiện tra được; bộ lọc gõ thường được viết hoa, trang bị kẹp 100")
        void quan_ly_tra_duoc() throws Exception {
            mvc.perform(get(SEARCH)
                            .param("status", "checked_in")
                            .param("paymentStatus", "refunded")
                            .param("query", "  Lan  ")
                            .param("size", "999")
                            .with(member(ME, ORG, Role.EVENT_MANAGER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(100))
                    .andExpect(jsonPath("$.total").value(0));

            ArgumentCaptor<TicketSearchPort.Criteria> criteria =
                    ArgumentCaptor.forClass(TicketSearchPort.Criteria.class);
            verify(search).search(criteria.capture());
            assertThat(criteria.getValue().organizationId()).isEqualTo(ORG);
            assertThat(criteria.getValue().status()).isEqualTo("CHECKED_IN");
            assertThat(criteria.getValue().paymentStatus()).isEqualTo("REFUNDED");
            assertThat(criteria.getValue().text()).isEqualTo("Lan");
            assertThat(criteria.getValue().limit()).isEqualTo(100);
        }

        @Test
        @DisplayName("Trạng thái vé không có trong danh sách: 400 VALIDATION_FAILED kèm tên trường")
        void trang_thai_la() throws Exception {
            mvc.perform(get(SEARCH).param("status", "USED").with(member(ME, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(search);
        }

        @Test
        @DisplayName("Trạng thái thanh toán không có trong danh sách: 400")
        void trang_thai_thanh_toan_la() throws Exception {
            mvc.perform(get(SEARCH).param("paymentStatus", "PENDING").with(member(ME, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(search);
        }

        @Test
        @DisplayName("Thành viên tổ chức khác: 404, không chạm database")
        void to_chuc_khac() throws Exception {
            mvc.perform(get(SEARCH).with(member(ME, UUID.randomUUID(), Role.ORG_OWNER)))
                    .andExpect(status().isNotFound());
            assertThat(mockingDetails(search).getInvocations()).isEmpty();
        }

        @Test
        @DisplayName("Superadmin không phải thành viên: route tổ chức vẫn đòi membership thật — 404")
        void superadmin_khong_phai_thanh_vien() throws Exception {
            mvc.perform(get(SEARCH).with(superAdmin(ME))).andExpect(status().isNotFound());
            verify(search, never()).search(any());
        }
    }

    private static Ticket ticket(UUID owner) {
        return Ticket.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ORG,
                owner,
                null,
                "A-1",
                "A",
                "SEATED",
                "1",
                "VIP",
                "Nguyễn Văn An",
                TicketStatus.VALID,
                null,
                null);
    }
}
