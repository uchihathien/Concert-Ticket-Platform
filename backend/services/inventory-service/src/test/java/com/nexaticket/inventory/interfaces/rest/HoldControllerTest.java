// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.inventory.application.InventoryErrorCode;
import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.command.ReleaseHoldHandler;
import com.nexaticket.inventory.support.WebSliceTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code POST /v1/sessions/{id}/holds} và {@code DELETE /v1/holds/{id}} qua chuỗi bảo mật thật.
 *
 * <p>Handler là mock: luật nghiệp vụ đã có test riêng. Ở đây kiểm danh tính lấy từ đâu, request
 * hỏng bị chặn ở đâu, và lỗi nghiệp vụ ra ngoài với hình dạng RFC 7807 nào.
 */
@WebMvcTest(HoldController.class)
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(
        com.nexaticket.platform.idempotency.IdempotencyAutoConfiguration.class)
class HoldControllerTest extends WebSliceTestBase {

    private static final String KEY = "test-key-0001";

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final UUID SEAT_1 = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID SEAT_2 = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final Instant EXPIRES = Instant.parse("2026-10-22T10:10:00Z");

    @MockitoBean
    PlaceHoldHandler placeHold;

    @MockitoBean
    ReleaseHoldHandler releaseHold;

    @Nested
    @DisplayName("Idempotency-Key")
    class IdempotencyKey {

        @Test
        @DisplayName(
                "Thiếu khoá: 400 IDEMPOTENCY_KEY_REQUIRED, handler không chạy — khách bấm hai lần không tự ăn hết hạn mức")
        void thieu_khoa() throws Exception {
            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(USER)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("Khoá có khoảng trắng hoặc ký tự điều khiển: 400, không đi vào log hay khoá DB")
        void khoa_sai_khuon() throws Exception {
            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", "co khoang trang")
                            .header(HttpHeaders.AUTHORIZATION, bearer(customerToken(USER)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("DELETE /holds không đòi khoá: nhả chỗ vốn đã idempotent")
        void nha_cho_khong_can_khoa() throws Exception {
            mvc.perform(delete("/v1/holds/{id}", HOLD).header(HttpHeaders.AUTHORIZATION, bearer(customerToken(USER))))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("Giữ chỗ — đường thành công")
    class GiuChoThanhCong {

        @Test
        @DisplayName("201 kèm holdId, hạn, danh sách chỗ và version; userId lấy từ danh tính, không từ body")
        void giu_cho_201() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any()))
                    .thenReturn(new PlaceHoldHandler.Result(HOLD, EXPIRES, List.of(SEAT_1, SEAT_2), 42L));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"seatIds": ["%s"],
                                     "seatedZones": [{"zoneCode": "A", "quantity": 1}],
                                     "standing": [{"zoneCode": "GA", "quantity": 2}],
                                     "userId": "%s"}
                                    """
                                            .formatted(SEAT_1, UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.holdId").value(HOLD.toString()))
                    .andExpect(jsonPath("$.expiresAt").value("2026-10-22T10:10:00Z"))
                    .andExpect(jsonPath("$.seatIds[0]").value(SEAT_1.toString()))
                    .andExpect(jsonPath("$.seatIds[1]").value(SEAT_2.toString()))
                    .andExpect(jsonPath("$.availabilityVersion").value(42));

            ArgumentCaptor<PlaceHoldHandler.Command> captor = ArgumentCaptor.forClass(PlaceHoldHandler.Command.class);
            verify(placeHold).handle(captor.capture());
            PlaceHoldHandler.Command cmd = captor.getValue();
            assertThat(cmd.eventSessionId()).isEqualTo(SESSION);
            assertThat(cmd.userId()).isEqualTo(USER);
            assertThat(cmd.seatIds()).containsExactly(SEAT_1);
            assertThat(cmd.seatedZones()).containsExactly(new PlaceHoldHandler.Command.ZoneLine("A", 1));
            assertThat(cmd.standing()).containsExactly(new PlaceHoldHandler.Command.StandingLine("GA", 2));
        }

        @Test
        @DisplayName("Body chỉ có vé đứng: seatIds và seatedZones thành danh sách rỗng, không null")
        void danh_sach_vang_mat_la_rong() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any())).thenReturn(new PlaceHoldHandler.Result(HOLD, EXPIRES, List.of(SEAT_1), 1L));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"standing\": [{\"zoneCode\": \"GA\", \"quantity\": 1}]}"))
                    .andExpect(status().isCreated());

            ArgumentCaptor<PlaceHoldHandler.Command> captor = ArgumentCaptor.forClass(PlaceHoldHandler.Command.class);
            verify(placeHold).handle(captor.capture());
            assertThat(captor.getValue().seatIds()).isNotNull().isEmpty();
            assertThat(captor.getValue().seatedZones()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Giữ chỗ — kiểm tra request")
    class KiemTraRequest {

        @Test
        @DisplayName("Số lượng vé đứng bằng 0: 400 VALIDATION_FAILED chỉ đúng trường, handler không được gọi")
        void so_luong_khong() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"standing\": [{\"zoneCode\": \"GA\", \"quantity\": 0}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/validation-failed"))
                    .andExpect(jsonPath("$.meta.fields['standing[0].quantity']").exists());
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("Số lượng vé ngồi theo khu âm: 400")
        void so_luong_am() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatedZones\": [{\"zoneCode\": \"A\", \"quantity\": -2}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.meta.fields['seatedZones[0].quantity']").exists());
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("Mã khu trắng: 400 chỉ ra trường zoneCode")
        void ma_khu_trang() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatedZones\": [{\"zoneCode\": \"  \", \"quantity\": 1}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.meta.fields['seatedZones[0].zoneCode']").exists());
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("JSON hỏng: 400 Malformed request body, không lộ thông điệp parser")
        void json_hong() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": ["))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("seatIds chứa chuỗi không phải UUID: 400, không tới handler")
        void seat_id_khong_phai_uuid() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"ghe-so-1\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("Thiếu body: 400")
        void thieu_body() throws Exception {
            String token = customerToken(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(placeHold);
        }
    }

    @Nested
    @DisplayName("Giữ chỗ — xác thực")
    class XacThuc {

        @Test
        @DisplayName("Không có token: 401 kèm WWW-Authenticate Bearer, handler không được gọi")
        void khong_token() throws Exception {
            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("Token giả mạo (decoder từ chối): 401")
        void token_gia() throws Exception {
            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer("forged"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(placeHold);
        }

        @Test
        @DisplayName("JWT hợp lệ nhưng identity-service không trả lời: 401 UNAUTHENTICATED dạng RFC 7807")
        void identity_khong_tra_loi() throws Exception {
            String token = tokenWithoutIdentity(USER);

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            verifyNoInteractions(placeHold);
        }
    }

    @Nested
    @DisplayName("Giữ chỗ — lỗi nghiệp vụ ra ngoài")
    class LoiNghiepVu {

        @Test
        @DisplayName("Ghế vừa bị chiếm: 409 SEAT_UNAVAILABLE kèm danh sách ghế mất trong meta")
        void ghe_bi_chiem() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any()))
                    .thenThrow(new ApiException(
                            InventoryErrorCode.SEAT_UNAVAILABLE,
                            "One or more seats were just taken",
                            Map.of("unavailableSeatIds", List.of(SEAT_2))));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\", \"%s\"]}".formatted(SEAT_1, SEAT_2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/seat-unavailable"))
                    .andExpect(jsonPath("$.title").value("seat unavailable"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
                    .andExpect(jsonPath("$.detail").value("One or more seats were just taken"))
                    .andExpect(jsonPath("$.meta.unavailableSeatIds[0]").value(SEAT_2.toString()));
        }

        @Test
        @DisplayName("Vượt trần cộng dồn: 409 CUSTOMER_LIMIT_EXCEEDED kèm số vé còn mua được")
        void vuot_tran_cong_don() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any()))
                    .thenThrow(new ApiException(
                            InventoryErrorCode.CUSTOMER_LIMIT_EXCEEDED,
                            "Per-customer ticket limit exceeded",
                            Map.of("limit", 10, "used", 9, "remaining", 1)));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\", \"%s\"]}".formatted(SEAT_1, SEAT_2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CUSTOMER_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.meta.remaining").value(1));
        }

        @Test
        @DisplayName("Redis chết: 503 INVENTORY_UNAVAILABLE để client thử lại")
        void redis_chet() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any()))
                    .thenThrow(new ApiException(
                            InventoryErrorCode.INVENTORY_UNAVAILABLE, "Seat gate temporarily unavailable"));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));
        }

        @Test
        @DisplayName("Lỗi không lường trước: 500 với thông điệp chung, KHÔNG lộ thông điệp gốc")
        void loi_khong_luong_truoc() throws Exception {
            String token = customerToken(USER);
            when(placeHold.handle(any()))
                    .thenThrow(new IllegalStateException("jdbc:postgresql://db-internal:5432 refused"));

            mvc.perform(post("/v1/sessions/{id}/holds", SESSION)
                            .header("Idempotency-Key", KEY)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seatIds\": [\"%s\"]}".formatted(SEAT_1)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(content().string(not(containsString("db-internal"))));
        }
    }

    @Nested
    @DisplayName("Bỏ giữ chỗ")
    class BoGiuCho {

        @Test
        @DisplayName("Chủ sở hữu bỏ giữ chỗ: 204, handler nhận userId từ danh tính")
        void bo_giu_cho_204() throws Exception {
            String token = customerToken(USER);

            mvc.perform(delete("/v1/holds/{id}", HOLD).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(releaseHold).handle(HOLD, USER);
        }

        @Test
        @DisplayName("Giữ chỗ không có / của người khác: 404 HOLD_NOT_FOUND")
        void khong_thay_404() throws Exception {
            String token = customerToken(USER);
            doThrow(new ApiException(InventoryErrorCode.HOLD_NOT_FOUND, "Hold not found"))
                    .when(releaseHold)
                    .handle(HOLD, USER);

            mvc.perform(delete("/v1/holds/{id}", HOLD).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        }

        @Test
        @DisplayName("Không đăng nhập: 401, không ai bỏ được giữ chỗ của người khác bằng cách ẩn danh")
        void khong_dang_nhap() throws Exception {
            mvc.perform(delete("/v1/holds/{id}", HOLD)).andExpect(status().isUnauthorized());

            verifyNoInteractions(releaseHold);
        }
    }
}
