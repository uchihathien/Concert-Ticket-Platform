// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.concurrent;

import com.nexaticket.aichatbox.application.agent.AgentProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool luồng riêng cho những lượt chat, tách khỏi pool của Tomcat.
 *
 * <h2>Vấn đề nó giải</h2>
 *
 * <p>Một lượt chat giữ trọn một luồng suốt thời gian gọi mô hình: tối đa {@code maxToolIterations}
 * vòng, mỗi vòng có hạn đọc tới 120 giây với mô hình chạy tại chỗ. Tomcat mặc định có 200 luồng và
 * <b>mọi endpoint dùng chung 200 luồng đó</b>. Nên một đợt khách vào hỏi cùng lúc không chỉ làm
 * chậm chính họ — nó làm treo cả {@code GET /v1/support/handoffs}, tức là bàn hỗ trợ của người trực
 * đứng hình <i>đúng lúc</i> hàng đợi đang dồn lên. Đường đắt kéo đường rẻ chết theo.
 *
 * <p>Tách pool làm hai việc cùng lúc: luồng Tomcat được nhả ngay sau khi nhận request (controller
 * trả {@code CompletableFuture}), và số lượt chat chạy song song có một con số <b>khai tường minh</b>
 * thay vì bằng đúng số luồng của web server một cách tình cờ.
 *
 * <h2>Hàng đợi hữu hạn, và từ chối thì từ chối thẳng</h2>
 *
 * <p>{@code queueCapacity} hữu hạn là chốt chặn nhận tải. Hàng đợi vô hạn nghe có vẻ tử tế hơn
 * nhưng nó chỉ đổi "bị từ chối" thành "chờ tới khi hết hạn rồi mới bị từ chối" — tệ hơn cho khách
 * và tốn hơn cho ta, vì mỗi request đang chờ vẫn giữ một kết nối.
 *
 * <p>{@code AbortPolicy} chứ KHÔNG {@code CallerRunsPolicy}: chính sách kia chạy việc bị từ chối
 * trên luồng người gọi — tức luồng Tomcat — và xoá sạch lý do tách pool này ra.
 */
@Configuration
public class AgentExecutorConfig {

    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(AgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // core == max: những luồng này phần lớn thời gian nằm chờ mạng chứ không tính toán, nên
        // không có lý do gì để pool co lại rồi phải dựng luồng mới đúng lúc tải lên.
        executor.setCorePoolSize(properties.maxConcurrentTurns());
        executor.setMaxPoolSize(properties.maxConcurrentTurns());
        executor.setQueueCapacity(properties.maxQueuedTurns());
        executor.setThreadNamePrefix("agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // Tắt êm: chờ lượt đang chạy nói xong câu của nó. Hạn ngắn hơn hạn của mô hình là cố ý —
        // một lần triển khai không được treo vì một lượt chat đang chờ nhà cung cấp hết 120 giây.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
