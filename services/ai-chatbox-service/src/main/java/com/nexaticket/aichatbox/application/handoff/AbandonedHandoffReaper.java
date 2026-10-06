// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đóng những phiếu chờ mà không ai còn ở đầu bên kia.
 *
 * <h2>Vì sao cần</h2>
 *
 * <p>Chat hỗ trợ không có kết nối thường trực, nên <b>không có sự kiện "khách đã rời đi"</b>. Khách
 * bấm "gặp nhân viên" rồi đóng tab là chuyện thường ngày, và phiếu ấy nằm lại hàng đợi mãi mãi.
 * Hai hậu quả, cái thứ hai tệ hơn: người trực đọc một hàng đợi mà phần lớn là người đã bỏ đi; và
 * con số "đang chờ" — thứ duy nhất nói lên bàn hỗ trợ có kham nổi không — trở nên vô nghĩa.
 *
 * <p>Với chính khách thì đóng phiếu là <b>khôi phục</b> chứ không phải cắt bỏ: phiếu mở là công
 * tắc tắt trợ lý, nên một phiếu treo vĩnh viễn nghĩa là người quay lại sau ba ngày vẫn chỉ nhận
 * được câu "đang chờ nhân viên". Đóng nó đi thì trợ lý trả lời trở lại ngay lượt sau.
 *
 * <h2>Chỉ đụng phiếu WAITING</h2>
 *
 * <p>Phiếu đã có người nhận là việc của người ấy, kể cả khi họ chậm. Tự đóng phiếu đang được trả
 * lời là cắt ngang một cuộc hội thoại đang diễn ra — sai nặng hơn nhiều so với việc để một phiếu
 * chậm nằm lại.
 *
 * <h2>Nhiều instance cùng chạy thì sao</h2>
 *
 * <p>Không sao, và cố ý không thêm khoá phân tán cho việc này. Lệnh dọn là một UPDATE có điều kiện
 * {@code status = 'WAITING'}: instance thứ hai chạy cùng lúc chỉ thấy 0 dòng. Cái giá của một
 * lịch trùng ở đây là một truy vấn thừa mỗi giờ, rẻ hơn hẳn việc nuôi thêm một cơ chế khoá.
 */
@Component
@ConditionalOnProperty(name = "nexaticket.aichatbox.handoff.reaper.enabled", matchIfMissing = true)
public class AbandonedHandoffReaper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedHandoffReaper.class);

    private final HandoffRepository handoffs;
    private final Clock clock;

    /**
     * Chờ bao lâu thì coi là đã bỏ đi.
     *
     * <p>24 giờ, không phải 30 phút: bàn hỗ trợ làm việc theo giờ hành chính, và một phiếu mở lúc
     * 22h đêm phải sống qua đêm để người trực ca sáng còn thấy. Ngưỡng ngắn biến job này từ chỗ dọn
     * rác thành chỗ đánh rơi khách.
     */
    private final Duration abandonedAfter;

    public AbandonedHandoffReaper(
            HandoffRepository handoffs,
            Clock clock,
            @Value("${nexaticket.aichatbox.handoff.abandoned-after:24h}") Duration abandonedAfter) {
        this.handoffs = handoffs;
        this.clock = clock;
        this.abandonedAfter = abandonedAfter;
    }

    /**
     * Mỗi giờ một lần.
     *
     * <p>{@code fixedDelay} chứ không {@code fixedRate}: rate xếp hàng các lần chạy bị chậm rồi
     * bắn liên tiếp để "đuổi kịp", một hành vi không ai muốn ở một job dọn dẹp.
     */
    @Scheduled(fixedDelayString = "${nexaticket.aichatbox.handoff.reaper.interval:1h}", initialDelay = 60_000)
    public void closeAbandoned() {
        Instant now = clock.instant();
        int closed = handoffs.closeAbandoned(now.minus(abandonedAfter), now);

        if (closed > 0) {
            // INFO chứ không DEBUG: con số này tăng đều nghĩa là khách đang bỏ đi trước khi có
            // người trả lời, và đó là tin về bàn hỗ trợ chứ không phải về job dọn dẹp.
            log.info("Đã tự đóng {} phiếu chờ quá {}", closed, abandonedAfter);
        }
    }
}
