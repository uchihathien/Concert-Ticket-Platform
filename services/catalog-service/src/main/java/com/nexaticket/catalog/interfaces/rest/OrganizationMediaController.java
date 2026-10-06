// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.media.PosterUploadUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấp lượt tải ảnh bìa lên.
 *
 * <h3>Endpoint này KHÔNG nhận file</h3>
 *
 * <p>Nó trả về một URL đã ký để trình duyệt {@code PUT} thẳng lên kho vật thể. Byte ảnh không đi
 * qua service này, không qua gateway — xem {@code ObjectStoragePort} để biết vì sao.
 *
 * <p>Hệ quả cho phía gọi: <b>ba bước</b>, không phải một.
 *
 * <ol>
 *   <li>{@code POST …/uploads/poster} → nhận {@code uploadUrl} và {@code publicUrl}.
 *   <li>{@code PUT uploadUrl} kèm đúng header {@code Content-Type} đã khai ở bước 1. Gửi kiểu khác
 *       thì kho vật thể từ chối, vì kiểu ấy nằm trong chữ ký.
 *   <li>Lưu {@code publicUrl} vào sự kiện. Đây mới là lúc ảnh được kiểm kích thước và kiểu thật —
 *       xem {@code PosterUrlPolicy}.
 * </ol>
 *
 * <p>Bước 3 là thứ khiến việc xin URL rồi bỏ đó không gây hại: chừng nào chưa có sự kiện nào trỏ
 * tới, vật thể đó chỉ là một file mồ côi.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/uploads")
public class OrganizationMediaController {

    private final PosterUploadUseCase uploads;

    public OrganizationMediaController(PosterUploadUseCase uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/poster")
    public PosterUploadUseCase.Ticket poster(
            @PathVariable UUID organizationId, @Valid @RequestBody PosterUploadRequest request) {
        return uploads.issue(organizationId, request.contentType());
    }

    /**
     * @param contentType kiểu ảnh trình duyệt sắp gửi. Bắt buộc, vì nó đi vào chữ ký — không có nó
     *     thì URL ký sẵn sẽ nhận bất cứ thứ gì, kể cả một file thực thi mang tên {@code .jpg}.
     */
    public record PosterUploadRequest(@NotBlank @Size(max = 100) String contentType) {}
}
