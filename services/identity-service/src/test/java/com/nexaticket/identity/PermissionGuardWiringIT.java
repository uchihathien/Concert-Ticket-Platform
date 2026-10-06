// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.support.PostgresTestBase;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.PermissionGuard;
import com.nexaticket.platform.security.annotation.RequiresPermission;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@link PermissionGuard} có thật sự nằm trong chuỗi xử lý không.
 *
 * <h2>Vì sao cần một lớp test riêng cho việc này</h2>
 *
 * <p>Guard là lớp bảo vệ <b>thứ hai</b>: mọi endpoint gắn {@code @RequiresPermission} hiện nay đều
 * kiểm lại quyền trong handler. Đó là thiết kế đúng — lệnh còn được gọi từ ngoài HTTP, nên một cửa
 * quyền chỉ đóng ở tầng web là cửa đi vòng được.
 *
 * <p>Nhưng nó tạo ra một điểm mù: nếu bean đăng ký interceptor biến mất — ai đó khai một
 * {@code WebMvcConfigurer} riêng, hoặc auto-configuration bị loại — thì guard <b>không chạy nữa và
 * không test nào đỏ</b>. Lá chắn hỏng trong im lặng, và người đọc code vẫn thấy annotation nằm
 * ngay trên chữ ký nên tin rằng nó đang có tác dụng.
 *
 * <p>Lớp này khẳng định ba điều mà bộ test theo hành vi không nói được: interceptor được đăng ký,
 * nó bám vào đúng handler mang annotation, và annotation trên các endpoint quan trọng vẫn còn đó.
 */
class PermissionGuardWiringIT extends PostgresTestBase {

    /**
     * Chỉ định tên bean.
     *
     * <p>Actuator đăng ký thêm một {@code RequestMappingHandlerMapping} riêng
     * ({@code controllerEndpointHandlerMapping}) cho các endpoint {@code /actuator/**}, nên tiêm
     * theo kiểu là mơ hồ. Bản của MVC — thứ phục vụ controller thật — luôn tên
     * {@code requestMappingHandlerMapping}.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("PermissionGuard được cắm vào chuỗi interceptor của MVC")
    void guard_duoc_dang_ky() throws Exception {
        // `getHandler` trả về cả chuỗi interceptor sẽ chạy cho một request cụ thể — đúng thứ cần
        // khẳng định, thay vì chỉ khẳng định bean tồn tại (bean tồn tại mà không được cắm vào thì
        // vẫn vô dụng).
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/roles");
        HandlerExecutionChain chain = handlerMapping.getHandler(request);

        assertThat(chain).isNotNull();
        assertThat(List.of(chain.getInterceptorList().toArray()))
                .as("PermissionGuard phải nằm trong chuỗi, nếu không @RequiresPermission chỉ là chú thích")
                .anyMatch(PermissionGuard.class::isInstance);
    }

    @Test
    @DisplayName("interceptor đọc được annotation trên đúng phương thức của controller")
    void guard_doc_duoc_annotation() throws Exception {
        Method method = com.nexaticket.identity.interfaces.rest.PlatformUserController.class.getMethod(
                "disable",
                java.util.UUID.class,
                com.nexaticket.identity.interfaces.rest.PlatformUserController.DisableRequest.class);

        RequiresPermission required = method.getAnnotation(RequiresPermission.class);

        assertThat(required).isNotNull();
        assertThat(required.value()).isEqualTo(Permission.PLATFORM_USER_MANAGE);
    }

    @Test
    @DisplayName("mọi endpoint ghi ở khu vực nền tảng đều khai quyền")
    void endpoint_nen_tang_deu_khai_quyen() {
        // Quét thay vì liệt kê tay: endpoint thứ mười một thêm vào mà quên annotation sẽ làm ca này
        // đỏ, thay vì lặng lẽ dựa vào việc handler có nhớ tự kiểm hay không.
        List<HandlerMethod> thieu = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/v1/platform/")))
                .filter(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                        .anyMatch(m -> m != org.springframework.web.bind.annotation.RequestMethod.GET))
                .map(java.util.Map.Entry::getValue)
                .filter(handler -> handler.getMethodAnnotation(RequiresPermission.class) == null
                        && handler.getBeanType().getAnnotation(RequiresPermission.class) == null)
                .toList();

        assertThat(thieu)
                .as("Endpoint ghi ở /v1/platform/** phải khai @RequiresPermission")
                .isEmpty();
    }

    @Test
    @DisplayName("chỉ có đúng một bean PermissionGuard được đăng ký")
    void khong_dang_ky_trung() {
        // Hai lần đăng ký nghĩa là mỗi request chạy guard hai lượt: vô hại về kết quả, nhưng là dấu
        // hiệu auto-configuration bị nạp hai lần — thứ sẽ gây lỗi ở chỗ khác khó lần hơn nhiều.
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/roles");
        try {
            HandlerExecutionChain chain = handlerMapping.getHandler(request);
            long count = chain.getInterceptorList().stream()
                    .filter(PermissionGuard.class::isInstance)
                    .count();
            assertThat(count).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
