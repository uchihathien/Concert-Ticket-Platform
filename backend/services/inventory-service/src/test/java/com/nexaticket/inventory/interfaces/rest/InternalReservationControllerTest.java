// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.application.command.ReserveSeatsHandler;
import com.nexaticket.inventory.application.command.SettleReservationHandler;
import com.nexaticket.inventory.support.WebSliceTestBase;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Open Host Service cho saga checkout, với bí mật nội bộ ĐANG BẬT — đúng cấu hình production.
 *
 * <p>JSON trả về là hợp đồng với {@code InventoryHttpAdapter.ReserveResponse} của ordering-service:
 * test khẳng định từng tên field, vì lệch tên là checkout hỏng ở runtime mà không bộ test nào báo.
 */
@WebMvcTest(
        controllers = InternalReservationController.class,
        properties = "nexaticket.internal.shared-secret=" + WebSliceTestBase.INTERNAL_SECRET)
class InternalReservationControllerTest extends WebSliceTestBase {

    private static final UUID ORDER = UUID.fromString("00000000-0000-4000-a000-0000000000d1");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-a000-0000000000e1");
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f1");
    private static final UUID SEAT = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID TYPE = UUID.fromString("00000000-0000-4000-a000-000000000011");

    private static final String BODY =
            "{\"orderId\": \"%s\", \"holdId\": \"%s\", \"userId\": \"%s\"}".formatted(ORDER, HOLD, USER);

    @MockitoBean
    ReserveSeatsHandler reserveSeats;

    @MockitoBean
    SettleReservationHandler settleReservation;

    @Nested
    @DisplayName("Bí mật nội bộ")
    class BiMatNoiBo {

        @Test
        @DisplayName("Không có X-Internal-Token: 404 — không xác nhận endpoint tồn tại")
        void thieu_token_noi_bo() throws Exception {
            mvc.perform(post("/internal/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(reserveSeats, settleReservation);
        }

        @Test
        @DisplayName("Token nội bộ sai: 404")
        void token_noi_bo_sai() throws Exception {
            mvc.perform(delete("/internal/reservations/{id}", ORDER).header(InternalApiFilter.HEADER, "doan-mo"))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(settleReservation);
        }

        @Test
        @DisplayName("JWT hợp lệ của khách KHÔNG thay được token nội bộ: vẫn 404")
        void jwt_khach_khong_vao_duoc() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/internal/reservations/{id}/settle", ORDER)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(settleReservation);
        }
    }

    @Nested
    @DisplayName("Đặt chỗ")
    class DatCho {

        @Test
        @DisplayName("Token đúng, không JWT: 200 với đúng tên field Ordering đọc")
        void dat_cho_200() throws Exception {
            when(reserveSeats.handle(new ReserveSeatsHandler.Command(ORDER, HOLD, USER)))
                    .thenReturn(new ReserveSeatsHandler.Result(
                            ORDER,
                            SESSION,
                            EVENT,
                            ORG,
                            List.of(new ReserveSeatsHandler.ReservedSeat(
                                    SEAT, "A-1-1", "A", "SEATED", "1", TYPE, "Ve ngoi", 1_500_000L))));

            mvc.perform(post("/internal/reservations")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(ORDER.toString()))
                    .andExpect(jsonPath("$.eventSessionId").value(SESSION.toString()))
                    .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                    .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                    .andExpect(jsonPath("$.seats[0].sessionSeatId").value(SEAT.toString()))
                    .andExpect(jsonPath("$.seats[0].seatCode").value("A-1-1"))
                    .andExpect(jsonPath("$.seats[0].zoneCode").value("A"))
                    .andExpect(jsonPath("$.seats[0].admissionType").value("SEATED"))
                    .andExpect(jsonPath("$.seats[0].seatLabel").value("1"))
                    .andExpect(jsonPath("$.seats[0].ticketTypeId").value(TYPE.toString()))
                    .andExpect(jsonPath("$.seats[0].ticketTypeName").value("Ve ngoi"))
                    .andExpect(jsonPath("$.seats[0].priceVnd").value(1_500_000))
                    .andExpect(jsonPath("$.seatIds").doesNotExist());
        }

        @Test
        @DisplayName("Thiếu orderId: 400 VALIDATION_FAILED — @NotNull trên ReserveRequest phải có tác dụng")
        void thieu_order_id() throws Exception {
            mvc.perform(post("/internal/reservations")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\": \"%s\", \"userId\": \"%s\"}".formatted(HOLD, USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(reserveSeats);
        }

        @Test
        @DisplayName("JSON hỏng: 400")
        void json_hong() throws Exception {
            mvc.perform(post("/internal/reservations")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        @Test
        @DisplayName("Giữ chỗ của người khác: 403 HOLD_NOT_OWNED")
        void giu_cho_nguoi_khac() throws Exception {
            when(reserveSeats.handle(any()))
                    .thenThrow(new ApiException(InventoryErrorCode.HOLD_NOT_OWNED, "Hold belongs to another user"));

            mvc.perform(post("/internal/reservations")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_OWNED"));
        }

        @Test
        @DisplayName("Giữ chỗ hết hạn: 409 HOLD_EXPIRED")
        void giu_cho_het_han() throws Exception {
            when(reserveSeats.handle(any()))
                    .thenThrow(new ApiException(InventoryErrorCode.HOLD_EXPIRED, "Hold is no longer active"));

            mvc.perform(post("/internal/reservations")
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        }
    }

    @Nested
    @DisplayName("Bù trừ và chốt bán")
    class BuTruVaChotBan {

        @Test
        @DisplayName("Huỷ đặt chỗ không tồn tại: vẫn 204 — saga không được kẹt ở COMPENSATION_PENDING")
        void huy_khong_ton_tai_204() throws Exception {
            when(settleReservation.cancel(ORDER)).thenReturn(false);

            mvc.perform(delete("/internal/reservations/{id}", ORDER).header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                    .andExpect(status().isNoContent());

            verify(settleReservation).cancel(ORDER);
        }

        @Test
        @DisplayName("Chốt bán tay: 204 kể cả khi đã chốt trước đó")
        void chot_ban_204() throws Exception {
            when(settleReservation.settle(ORDER)).thenReturn(false);

            mvc.perform(post("/internal/reservations/{id}/settle", ORDER)
                            .header(InternalApiFilter.HEADER, INTERNAL_SECRET))
                    .andExpect(status().isNoContent());

            verify(settleReservation).settle(ORDER);
        }
    }
}
