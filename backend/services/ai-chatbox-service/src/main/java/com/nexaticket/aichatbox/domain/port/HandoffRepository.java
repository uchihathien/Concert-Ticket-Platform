// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.Handoff;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho phiếu chuyển tiếp sang người thật. */
public interface HandoffRepository {

    /**
     * Mở phiếu, hoặc trả lại phiếu đang mở nếu phiên đã có.
     *
     * <p>Idempotent theo phiên, và điều đó <b>bắt buộc</b>: khách bấm "gặp nhân viên" ba lần, hoặc
     * trợ lý gọi tool chuyển tiếp rồi lượt sau vẫn không trả lời được. Không có tính chất này thì
     * ba phiếu ra đời, ba người trực nhận, và cả ba cùng trả lời vào một cuộc hội thoại.
     *
     * <p>Thực thi bằng unique index bộ phận {@code uq_handoff_open_per_session}, không bằng một
     * lần đọc-rồi-ghi ở tầng trên: hai request đến cùng lúc thì cả hai đều đọc thấy "chưa có".
     */
    Handoff openOrExisting(Handoff candidate);

    /** Phiếu đang mở của một phiên, nếu có. */
    Optional<Handoff> openBySession(UUID sessionId);

    Optional<Handoff> findById(UUID handoffId);

    /**
     * Hàng đợi của người trực: phiếu chưa xong, cũ nhất trước.
     *
     * @param mine {@code null} lấy cả hàng đợi; khác {@code null} chỉ lấy phiếu của chính người
     *     trực đó — hai câu hỏi khác nhau mà cùng một màn hình đặt ra
     */
    List<Handoff> queue(UUID mine, int limit, int offset);

    /**
     * Nhận phiếu, thành công đúng <b>một</b> lần.
     *
     * <p>{@code UPDATE ... WHERE status = 'WAITING'} nguyên tử, không phải đọc-kiểm-rồi-ghi. Hai
     * người trực bấm "Nhận" trong cùng một giây là chuyện bình thường ở giờ cao điểm, và cách kia
     * để cả hai cùng thắng.
     *
     * @return phiếu sau khi nhận; rỗng nghĩa là người khác đã nhận trước
     */
    Optional<Handoff> claim(UUID handoffId, UUID agentId, java.time.Instant now);

    void save(Handoff handoff);
}
