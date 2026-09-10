// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.correlation;

import com.nexaticket.kernel.id.CorrelationContext;
import java.io.IOException;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Gắn {@code X-Correlation-Id} vào MỌI lời gọi HTTP đi ra.
 *
 * <h3>Vì sao cần</h3>
 *
 * {@link CorrelationIdFilter} nhận hoặc sinh id ở biên vào, và trả nó lại trong phản hồi. Nhưng
 * lời gọi ĐI RA thì trước đây không mang gì cả — nên khi ordering gọi sang inventory, filter ở
 * inventory không thấy header nào và sinh một id MỚI.
 *
 * <p>Hậu quả không nhìn ra được từ một service: mỗi service tự nhất quán, log của nó có đủ id, mọi
 * thứ trông đúng. Chỉ khi đi tìm một request cụ thể xuyên bốn service người ta mới phát hiện là
 * bốn service ghi bốn id khác nhau cho cùng một hành động của cùng một người — và không có gì nối
 * chúng lại. Đúng cái việc mà correlation id sinh ra để làm.
 *
 * <h3>Vì sao là RestClientCustomizer chứ không phải addInterceptor từng chỗ</h3>
 *
 * Bean này được Spring áp vào {@code RestClient.Builder} tự cấu hình. Nên điều kiện để một client
 * được truyền id là nó dựng từ builder ĐƯỢC TIÊM VÀO, không phải từ {@code RestClient.builder()}
 * tĩnh. Đó cũng chính là điều kiện để nó có trace span của Micrometer — hai thứ đi cùng nhau, và
 * gọi builder tĩnh sẽ lặng lẽ mất cả hai.
 */
public final class CorrelationPropagation implements ClientHttpRequestInterceptor, RestClientCustomizer {

    @Override
    public ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String current = CorrelationContext.current();
        // Không ghi đè: lời gọi đã tự khai id thì tôn trọng nó.
        //
        // Và KHÔNG gửi `UNKNOWN`. `current()` cố ý không bao giờ trả null — nó trả chuỗi "unknown"
        // để log luôn có gì đó để ghi. Nhưng gửi chuỗi đó lên dây thì service nhận sẽ coi đây là
        // một id có thật và ghi nó vào log của nó; sau vài hop, mọi job nền trong cả hệ thống dùng
        // chung một "id" duy nhất, và nó không dẫn tới đâu cả. Không có header còn tốt hơn: bên
        // nhận tự sinh một id mới, và ít nhất id đó là của riêng nó.
        boolean usable = current != null && !current.isBlank() && !CorrelationContext.UNKNOWN.equals(current);
        if (usable && !request.getHeaders().containsKey(CorrelationIdFilter.HEADER)) {
            request.getHeaders().add(CorrelationIdFilter.HEADER, current);
        }
        return execution.execute(request, body);
    }

    @Override
    public void customize(org.springframework.web.client.RestClient.Builder builder) {
        builder.requestInterceptor(this);
    }
}
