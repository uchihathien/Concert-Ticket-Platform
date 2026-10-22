// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.ticketing.application.CheckinAccess;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.application.query.CheckinHistoryQuery;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import com.nexaticket.ticketing.interfaces.rest.CheckinController;
import com.nexaticket.ticketing.interfaces.rest.OrganizationCheckinController;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Soát vé qua HTTP, với chuỗi bảo mật thật và {@link CheckinAccess} thật.
 *
 * <p>Chỉ {@link CheckInHandler} là giả: luật soát vé đã có {@code CheckInHandlerTest}. Ở đây kiểm
 * thứ chỉ tầng web trả lời được — ai lọt tới handler, handler nhận tổ chức và nhân viên từ đâu, và
 * lỗi trả về có đúng hình dạng RFC 7807 không.
 */
@WebMvcTest(controllers = {CheckinController.class, OrganizationCheckinController.class})
@Import(CheckinAccess.class)
class CheckinControllerWebTest extends WebSliceTest {

    private static final UUID ORG = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("60000000-0000-4000-8000-000000000009");
    private static final UUID SESSION = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final UUID EVENT = UUID.fromString("60000000-0000-4000-8000-000000000003");
    private static final UUID STAFF = UUID.fromString("60000000-0000-4000-8000-000000000004");
    private static final UUID TICKET = UUID.fromString("abcdef12-3456-4000-8000-000000000005");
    private static final String SCAN_URL = "/v1/organizations/" + ORG + "/sessions/" + SESSION + "/checkins";
    private static final String LEGACY_URL = "/v1/sessions/" + SESSION + "/checkins";
    private static final String QR_BODY = "{\"qrToken\":\"a.b.c\",\"deviceId\":\"may-1\",\"direction\":\"IN\"}";

    @MockitoBean
    CheckInHandler checkIn;

    @MockitoBean
    CheckinHistoryQuery history;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    CheckinAssignmentRepository assignments;

    @BeforeEach
    void setUp() {
        when(catalog.sessionInfo(SESSION))
                .thenReturn(Optional.of(
                        new CatalogPort.SessionInfo(EVENT, "Đêm nhạc", Instant.parse("2026-10-12T13:00:00Z"), null)));
        when(checkIn.handle(any())).thenReturn(accepted("ACCEPTED"));
    }

    @Nested
    @DisplayName("Xác thực và phạm vi tổ chức")
    class XacThuc {

        @Test
        @DisplayName("Không có token: 401, không tới handler")
        void khong_co_token() throws Exception {
            mvc.perform(scan(QR_BODY)).andExpect(status().isUnauthorized());

            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Token hợp lệ nhưng identity không trả lời: 401 UNAUTHENTICATED dạng RFC 7807")
        void identity_khong_tra_loi() throws Exception {
            mvc.perform(scan(QR_BODY).with(identityDown(STAFF)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/unauthenticated"));
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Khách mua vé (không thuộc tổ chức): 404 — không xác nhận tổ chức tồn tại")
        void khach_hang_404() throws Exception {
            mvc.perform(scan(QR_BODY).with(customer(UUID.randomUUID()))).andExpect(status().isNotFound());

            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Nhân viên soát vé của tổ chức KHÁC: 404")
        void nhan_vien_to_chuc_khac() throws Exception {
            mvc.perform(scan(QR_BODY).with(member(STAFF, OTHER_ORG, Role.ORG_OWNER)))
                    .andExpect(status().isNotFound());

            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Mã tổ chức mã hoá phần trăm để lách bộ lọc: 404")
        void ma_to_chuc_ma_hoa_phan_tram() throws Exception {
            String encoded =
                    "/v1/organizations/%36" + ORG.toString().substring(1) + "/sessions/" + SESSION + "/checkins";

            mvc.perform(post(URI.create(encoded))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(QR_BODY)
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Khách mua vé là thành viên CUSTOMER của tổ chức: 403, không có quyền soát vé")
        void vai_tro_khong_co_quyen_soat() throws Exception {
            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.CUSTOMER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            verifyNoInteractions(checkIn);
        }
    }

    @Nested
    @DisplayName("Gán sự kiện")
    class GanSuKien {

        @Test
        @DisplayName("Nhân viên được gán sự kiện: 200; tổ chức lấy từ đường dẫn, nhân viên lấy từ token")
        void nhan_vien_duoc_gan() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(true);

            mvc.perform(scan("{\"qrToken\":\"a.b.c\",\"deviceId\":\"may-1\",\"direction\":\"IN\",\"gate\":\"Cổng A\"}")
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("ACCEPTED"))
                    .andExpect(jsonPath("$.ticketCode").value("ABCDEF12"))
                    .andExpect(jsonPath("$.seatCode").value("A-1"))
                    .andExpect(jsonPath("$.holderName").value("Nguyễn Văn An"));

            CheckInHandler.Command cmd = sentCommand();
            assertThat(cmd.staffId()).isEqualTo(STAFF);
            assertThat(cmd.staffOrganizationId()).isEqualTo(ORG);
            assertThat(cmd.eventSessionId()).isEqualTo(SESSION);
            assertThat(cmd.qrToken()).isEqualTo("a.b.c");
            assertThat(cmd.gate()).isEqualTo("Cổng A");
            assertThat(cmd.override()).isFalse();
        }

        @Test
        @DisplayName("Nhân viên CHƯA được gán sự kiện: 403 NOT_YOUR_EVENT, không tới handler")
        void nhan_vien_chua_duoc_gan() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(false);

            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_YOUR_EVENT"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/not-your-event"))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Catalog không trả lời: nhân viên KHÔNG lọt qua bước kiểm quyền")
        void catalog_im_lang() throws Exception {
            when(catalog.sessionInfo(SESSION)).thenReturn(Optional.empty());

            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_YOUR_EVENT"));
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Người quản lý sự kiện: soát được mà không cần gán")
        void quan_ly_khong_can_gan() throws Exception {
            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.EVENT_MANAGER)))
                    .andExpect(status().isOk());

            verify(assignments, never()).isAssigned(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Cho vào thủ công")
    class ChoVaoThuCong {

        @Test
        @DisplayName("Nhân viên soát vé thường không được ghi đè: 403")
        void nhan_vien_khong_duoc_ghi_de() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(true);

            mvc.perform(scan("{\"qrToken\":\"a.b.c\",\"direction\":\"IN\",\"override\":true,\"reason\":\"quen quet\"}")
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Quản lý ghi đè mà không có lý do: 400 OVERRIDE_REASON_REQUIRED")
        void ghi_de_khong_ly_do() throws Exception {
            mvc.perform(scan("{\"qrToken\":\"a.b.c\",\"direction\":\"IN\",\"override\":true,\"reason\":\"  \"}")
                            .with(member(STAFF, ORG, Role.ORG_ADMIN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OVERRIDE_REASON_REQUIRED"));
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Quản lý ghi đè có lý do: handler nhận cờ ghi đè và lý do")
        void ghi_de_co_ly_do() throws Exception {
            mvc.perform(
                            scan("{\"qrToken\":\"a.b.c\",\"direction\":\"IN\",\"override\":true,\"reason\":\"Khách ra quên quét\"}")
                                    .with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isOk());

            CheckInHandler.Command cmd = sentCommand();
            assertThat(cmd.override()).isTrue();
            assertThat(cmd.reason()).isEqualTo("Khách ra quên quét");
        }
    }

    @Nested
    @DisplayName("Kiểm tra đầu vào")
    class DauVao {

        @Test
        @DisplayName("Hướng quét không phải IN/OUT: 400 VALIDATION_FAILED, chỉ ra trường sai")
        void huong_sai() throws Exception {
            mvc.perform(scan("{\"qrToken\":\"a.b.c\",\"direction\":\"in\"}").with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.direction").exists());
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Mã vé, cổng, lý do quá dài: 400 kèm đủ ba trường")
        void truong_qua_dai() throws Exception {
            String body = "{\"ticketCode\":\"" + "a".repeat(37) + "\",\"gate\":\"" + "g".repeat(41) + "\",\"reason\":\""
                    + "r".repeat(301) + "\"}";

            mvc.perform(scan(body).with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.ticketCode").exists())
                    .andExpect(jsonPath("$.meta.fields.gate").exists())
                    .andExpect(jsonPath("$.meta.fields.reason").exists());
        }

        @Test
        @DisplayName("JSON hỏng: 400 'Malformed request body', không phải 500")
        void json_hong() throws Exception {
            mvc.perform(scan("{\"qrToken\":").with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        @Test
        @DisplayName("Không có thân request: 400")
        void khong_co_than() throws Exception {
            mvc.perform(post(SCAN_URL).contentType(MediaType.APPLICATION_JSON).with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(checkIn);
        }

        @Test
        @DisplayName("Mã suất trên đường dẫn không phải UUID: 400, không phải 500")
        void ma_suat_khong_phai_uuid() throws Exception {
            mvc.perform(post("/v1/organizations/" + ORG + "/sessions/khong-phai-uuid/checkins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(QR_BODY)
                            .with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Nested
    @DisplayName("Tương thích máy soát vé đời đầu")
    class DoiDau {

        @Test
        @DisplayName("Không gửi hướng: NOT_OPEN_YET quy về WRONG_SESSION, lý do thật vẫn nằm trong note")
        void khong_gui_huong_quy_doi() throws Exception {
            when(checkIn.handle(any())).thenReturn(rejected("NOT_OPEN_YET", "Chưa tới giờ mở cổng"));

            mvc.perform(scan("{\"qrToken\":\"a.b.c\"}").with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("WRONG_SESSION"))
                    .andExpect(jsonPath("$.note").value("Chưa tới giờ mở cổng"));
        }

        @Test
        @DisplayName("Không gửi hướng: REENTRY_NOT_ALLOWED quy về ALREADY_CHECKED_IN")
        void vao_lai_quy_doi() throws Exception {
            when(checkIn.handle(any())).thenReturn(rejected("REENTRY_NOT_ALLOWED", "Sự kiện không cho phép vào lại"));

            mvc.perform(scan("{\"qrToken\":\"a.b.c\"}").with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(jsonPath("$.result").value("ALREADY_CHECKED_IN"));
        }

        @Test
        @DisplayName("Có gửi hướng: trả nguyên kết quả, không quy đổi")
        void co_gui_huong_khong_quy_doi() throws Exception {
            when(checkIn.handle(any())).thenReturn(rejected("SESSION_ENDED", "Suất diễn đã kết thúc"));

            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(jsonPath("$.result").value("SESSION_ENDED"));
        }

        @Test
        @DisplayName("Vé bị từ chối vẫn là 200 kèm lý do — máy ở cửa cần đọc được lý do")
        void tu_choi_van_200() throws Exception {
            when(checkIn.handle(any())).thenReturn(rejected("INVALID_TOKEN", "Mã không hợp lệ hoặc đã hết hạn"));

            mvc.perform(scan(QR_BODY).with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("INVALID_TOKEN"))
                    .andExpect(jsonPath("$.ticketCode").doesNotExist());
        }
    }

    @Nested
    @DisplayName("Đường soát vé không mang tổ chức")
    class DuongCu {

        @Test
        @DisplayName("Không có token: 401")
        void khong_token() throws Exception {
            mvc.perform(post(LEGACY_URL).contentType(MediaType.APPLICATION_JSON).content(QR_BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Nhân viên thuộc đúng một tổ chức, đã được gán: soát được qua /v1/sessions/{id}/checkins")
        void nhan_vien_mot_to_chuc_soat_duoc() throws Exception {
            // ts-sdk (scanTicket, useScanTicket) vẫn gọi đường này. Tổ chức lấy từ membership duy
            // nhất của nhân viên — CheckinController tự gọi requireActiveTenant và CheckinAccess.
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(true);

            mvc.perform(post(LEGACY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(QR_BODY)
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result").value("ACCEPTED"));
        }
    }

    @Nested
    @DisplayName("Đường cũ vẫn kiểm quyền (đã bỏ @RequiresPermission, không bỏ kiểm quyền)")
    class DuongCuVanKiemQuyen {

        @Test
        @DisplayName("Nhân viên chưa được gán sự kiện: 403 NOT_YOUR_EVENT, handler không chạy")
        void chua_duoc_gan() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(false);

            mvc.perform(post(LEGACY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(QR_BODY)
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_YOUR_EVENT"));
        }

        @Test
        @DisplayName("Vai trò không có quyền soát vé (EVENT_MANAGER hợp lệ nhưng khách không): khách bị từ chối")
        void khach_khong_soat_duoc() throws Exception {
            mvc.perform(post(LEGACY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(QR_BODY)
                            .with(customer(UUID.randomUUID())))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("Lịch sử quét")
    class LichSu {

        @Test
        @DisplayName("Nhân viên được gán: đọc được, tham số phân trang và tìm kiếm đi thẳng xuống query")
        void doc_duoc() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(true);
            when(history.forSession(ORG, SESSION, "An", 2, 20))
                    .thenReturn(new CheckinHistoryQuery.History(3, 5, List.of(), 2, 20));

            mvc.perform(get(SCAN_URL)
                            .param("query", "An")
                            .param("page", "2")
                            .param("size", "20")
                            .with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inside").value(3))
                    .andExpect(jsonPath("$.checkedIn").value(5));
        }

        @Test
        @DisplayName("Nhân viên chưa được gán: 403 — lịch sử mang tên khách, không lỏng hơn lượt quét")
        void chua_duoc_gan() throws Exception {
            when(assignments.isAssigned(ORG, STAFF, EVENT)).thenReturn(false);

            mvc.perform(get(SCAN_URL).with(member(STAFF, ORG, Role.CHECKIN_STAFF)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_YOUR_EVENT"));
            verify(history, never()).forSession(any(), any(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Trang không phải số: 400, không phải 500")
        void trang_khong_phai_so() throws Exception {
            mvc.perform(get(SCAN_URL).param("page", "mot").with(member(STAFF, ORG, Role.ORG_OWNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(history);
        }
    }

    private static MockHttpServletRequestBuilder scan(String body) {
        return post(SCAN_URL).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private CheckInHandler.Command sentCommand() {
        ArgumentCaptor<CheckInHandler.Command> captor = ArgumentCaptor.forClass(CheckInHandler.Command.class);
        verify(checkIn).handle(captor.capture());
        return captor.getValue();
    }

    private static CheckInHandler.Result accepted(String result) {
        return new CheckInHandler.Result(
                result,
                "IN",
                "A-1",
                "Hàng A ghế 1",
                "VIP",
                null,
                TICKET,
                "Nguyễn Văn An",
                "A",
                1,
                Instant.parse("2026-10-12T12:00:00Z"),
                "Cổng A",
                SESSION);
    }

    private static CheckInHandler.Result rejected(String result, String note) {
        return new CheckInHandler.Result(result, "IN", null, null, null, note, null, null, null, 0, null, null, null);
    }
}
