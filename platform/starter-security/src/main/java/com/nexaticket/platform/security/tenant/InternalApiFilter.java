// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bí mật dùng chung cho Open Host Service ({@code /internal/**}).
 *
 * <h2>Vì sao cần</h2>
 *
 * <p>Chuỗi filter mặc định cho {@code permitAll} toàn bộ {@code /internal/**}, với lý do "gateway
 * không route ra ngoài". Điều đó đúng, nhưng nó là tính chất của <b>cấu hình gateway</b>, không
 * phải của service: ai đứng được trong mạng nội bộ — một pod khác, một container bị chiếm, một
 * cổng lỡ mở ra ngoài — đều gọi thẳng được, và không có bước xác thực nào.
 *
 * <p>Với identity-service thì hậu quả không dừng ở đọc dữ liệu. {@code /internal/memberships} tạo
 * bản ghi người dùng ở lần chạm đầu tiên với {@code idpSubject} và {@code email} do người gọi tự
 * đặt, còn {@code SuperAdminBootstrap} cấp {@code SUPER_ADMIN} theo email. Ghép hai thứ lại: người
 * gọi tự chọn được cặp (subject của mình, email trong danh sách superadmin).
 *
 * <h2>Vì sao trả 404 chứ không 401</h2>
 *
 * <p>401 xác nhận endpoint tồn tại và chỉ thiếu thông tin xác thực — một lời mời dò tìm. 404 nói
 * đúng thứ người gọi không có quyền biết: ở đây không có gì cả.
 *
 * <h2>Mặc định</h2>
 *
 * <p>Không khai {@code nexaticket.internal.shared-secret} thì filter này không được cắm vào, và
 * hệ thống chạy y như trước. Cố ý: đặt sẵn một giá trị mặc định trong repo còn tệ hơn không có gì,
 * vì nó biến "bí mật" thành một chuỗi ai cũng đọc được. Đổi lại, {@code SecurityAutoConfiguration}
 * kêu to lúc khởi động khi khoá này trống.
 */
public class InternalApiFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private static final String PREFIX = "/internal/";

    private final byte[] expected;

    public InternalApiFilter(String sharedSecret) {
        this.expected = sharedSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        // So sánh thời gian hằng định: so bằng `equals` để lộ độ dài tiền tố khớp qua thời gian
        // phản hồi, và bí mật này không xoay vòng nên có cả ngày để dò.
        if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
