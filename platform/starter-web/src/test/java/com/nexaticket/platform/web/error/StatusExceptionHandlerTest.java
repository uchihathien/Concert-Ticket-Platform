// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ngoại lệ do Spring ném ra, đã mang sẵn mã trạng thái.
 *
 * <h2>Lỗi mà lớp này ghim lại</h2>
 *
 * <p>Spring có <b>hai lớp {@code NoResourceFoundException} trùng tên</b>: một trong
 * {@code web.servlet.resource} (MVC), một trong {@code web.reactive.resource} (WebFlux). Bản trước
 * của {@link GlobalExceptionHandler} chỉ import lớp của MVC, nên ở api-gateway — chạy reactive —
 * nó không khớp: mọi đường dẫn không tồn tại rơi xuống bộ bắt {@code Exception} chung và trả về
 * <b>500 kèm stack trace ghi ở mức ERROR</b>.
 *
 * <p>Chỉ lộ ra khi chạy thật. Trình biên dịch không thấy gì sai, và không service MVC nào bị ảnh
 * hưởng nên mọi test cũ vẫn xanh.
 *
 * <p>Hậu quả thứ hai tệ hơn hậu quả thứ nhất: một người dò đường dẫn bơm được log ERROR không giới
 * hạn vào đúng chỗ người vận hành tìm lỗi thật.
 */
class StatusExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("404 của Spring đi ra thành 404, không phải 500")
    void bon_khong_bon_giu_nguyen() {
        ResponseEntity<ApiError> response = handler.handleStatusException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No static resource x"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.Common.NOT_FOUND.code());
    }

    @Test
    @DisplayName("thân phản hồi KHÔNG nhắc lại đường dẫn người gọi vừa gõ")
    void khong_lo_duong_dan_noi_bo() {
        // `getReason()` của WebFlux là "No static resource internal/reservations" — một câu nói ra
        // cấu trúc đường dẫn nội bộ cho bất kỳ ai gõ thử. Lấy nó làm `detail` là biến bộ xử lý lỗi
        // thành công cụ dò đường.
        ResponseEntity<ApiError> response = handler.handleStatusException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No static resource internal/reservations"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().detail()).doesNotContain("internal/reservations");
    }

    @Test
    @DisplayName("hai lớp NoResourceFoundException có HAI cây kế thừa khác nhau")
    void hai_lop_hai_cay_ke_thua() throws Exception {
        // Đây là lý do phải có HAI handler thay vì một. Tôi từng giả định cả hai cùng kế thừa
        // ResponseStatusException và gộp chúng lại — việc đó làm hỏng đường 404 của mọi service MVC
        // mà không có gì báo, và chính bài kiểm này bắt được ngay.
        Class<?> mvc = Class.forName("org.springframework.web.servlet.resource.NoResourceFoundException");

        assertThat(ResponseStatusException.class.isAssignableFrom(mvc))
                .as("Lớp của MVC KHÔNG kế thừa ResponseStatusException — nó phải ở lại handleNotFound")
                .isFalse();
        assertThat(org.springframework.web.ErrorResponse.class)
                .as("Lớp của MVC mang mã trạng thái qua ErrorResponse")
                .isAssignableFrom(mvc);
    }

    @Test
    @DisplayName("5xx của Spring vẫn là 5xx")
    void nam_xx_giu_nguyen() {
        ResponseEntity<ApiError> response = handler.handleStatusException(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream down"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.Common.INTERNAL_ERROR.code());
    }

    @Test
    @DisplayName("4xx khác 404 thành lỗi của người gọi, không thành lỗi server")
    void bon_xx_khac_khong_thanh_loi_server() {
        ResponseEntity<ApiError> response =
                handler.handleStatusException(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "nope"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.Common.VALIDATION_FAILED.code());
    }
}
