// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.PaymentIntent;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ yêu cầu thanh toán. */
public interface PaymentIntentRepository {

    /**
     * Cấp một {@code orderCode} mới cho payOS.
     *
     * <p>Phải do database cấp, không sinh ở ứng dụng. payOS <b>từ chối một orderCode đã dùng</b>, nên
     * đây là một chuỗi số không được trùng kể cả giữa nhiều instance, kể cả sau khi restart, kể cả khi
     * một lần tạo link hỏng giữa đường. Một sequence là thứ duy nhất ở đây thoả cả ba mà không cần khoá.
     *
     * <p>Hệ quả đã biết: sequence <b>không lùi khi rollback</b>, nên mỗi lần tạo link hỏng sẽ bỏ trống
     * một số. Đó là đặc tính mong muốn — dùng lại số của một lần hỏng là cách chắc chắn nhất để gặp
     * "orderCode đã tồn tại" từ payOS.
     */
    long nextOrderCode();

    /**
     * Đóng các intent đã quá hạn mà vẫn còn PENDING.
     *
     * <p>Điều kiện {@code status = 'PENDING'} phải nằm trong chính câu UPDATE: một intent vừa được
     * webhook xác nhận trong cùng khoảnh khắc không được đánh dấu là hết hạn — đó là một khoản tiền
     * thật bị xoá dấu vết.
     *
     * @return số intent thực sự vừa đóng
     */
    int expirePending(java.time.Instant now, int batchSize);

    /**
     * Ghi một intent mới; đã có thì KHÔNG ghi đè.
     *
     * @return intent thực sự đang nằm trong database sau lời gọi này — bản vừa ghi, hoặc bản đã có
     *     từ trước. Saga checkout có thể chạy lại bước mở intent sau timeout mạng, và lần thứ hai
     *     phải trả về đúng link payOS lần đầu: khách đang nhìn màn hình đó.
     */
    PaymentIntent insertIfAbsent(PaymentIntent intent);

    Optional<PaymentIntent> findByOrder(UUID orderId);

    Optional<PaymentIntent> findByReference(String reference);

    /**
     * Tra intent theo mã link payOS.
     *
     * <p>Đây là đường vào của webhook, và là thứ thay cho việc rút mã đơn khỏi nội dung chuyển khoản
     * thời SePay: payOS trả về đúng {@code orderCode} ta đã cấp, trong một payload đã ký. Không còn
     * phải đoán giữa chuỗi chữ mà ngân hàng thêm vào, nên cũng không còn nhánh "gõ thiếu một ký tự".
     */
    Optional<PaymentIntent> findByPayosOrderCode(long payosOrderCode);

    /**
     * Giao dịch này của nhà cung cấp đã được ghi nhận cho đơn nào chưa.
     *
     * <p>Kiểm TRƯỚC khi ghi, chứ không bắt lỗi trùng khoá sau khi ghi. Lý do rất cụ thể với
     * PostgreSQL: một lệnh vi phạm ràng buộc làm hỏng cả transaction, nên sau đó không ghi nổi
     * dòng nhật ký webhook nữa — mà nhật ký chính là thứ duy nhất còn lại để một con người lần ra
     * chuyện gì đã xảy ra với một khoản tiền.
     *
     * <p>Unique index {@code uq_provider_txn} vẫn là chốt chặn cuối; câu đọc này chỉ để đường
     * thường không bao giờ chạm tới nó.
     */
    Optional<UUID> orderOfTransaction(String provider, String providerTxnId);

    /**
     * Ghi lại phần trạng thái, <b>chỉ khi intent còn đang PENDING</b>.
     *
     * <p>Số tiền, reference, mã QR và thông tin link payOS cố ý KHÔNG nằm trong câu UPDATE này: chúng
     * bất biến sau khi mở, và một câu UPDATE có thể đổi chúng là một câu UPDATE sẽ có ngày đổi chúng.
     *
     * <p>Điều kiện {@code PENDING} là thứ giữ cho hai tiến trình không ghi đè nhau. Tình huống thật:
     * job quét hết hạn và webhook payOS chạy cùng lúc trên cùng một đơn. Cả hai đọc thấy PENDING, rồi
     * cả hai ghi — và nếu job ghi sau, một khoản tiền đã vào sẽ bị đánh dấu EXPIRED trong im lặng. Đọc
     * rồi ghi không có điều kiện thì READ COMMITTED của PostgreSQL không chặn được việc đó.
     *
     * @return false nếu intent đã không còn PENDING — người gọi phải coi lượt ghi của mình là đã mất
     */
    boolean updateStatusIfPending(PaymentIntent intent);
}
