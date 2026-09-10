// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu thanh toán của một đơn hàng.
 *
 * <p>Aggregate mỏng, và nó mỏng có lý do: service này không giữ tiền, không chuyển tiền, không chạm
 * dữ liệu thẻ. Việc của nó là mở một link thanh toán payOS rồi trả lời đúng một câu — <b>tiền đã vào
 * chưa</b>. Mọi thứ nặng hơn thế thuộc về Ledger.
 *
 * <p>Bất biến quan trọng nhất nằm ở {@link #confirm}: <b>số tiền phải khớp</b>. Không có nó, một khách
 * chuyển 10.000đ cho đơn 3.000.000đ vẫn được ghi nhận là đã trả — và ticketing sẽ phát vé thật. Đây là
 * chỗ mà một dòng kiểm tra thiếu biến thành mất tiền thật, nên nó nằm ở domain chứ không ở controller.
 *
 * <p>Bất biến thứ hai, mới từ ADR-0016: <b>số tiền payOS ghi trên link phải bằng số tiền của đơn</b>.
 * Kiểm ở {@link #open} chứ không ở adapter. Nếu lệch, mã QR khách quét sẽ ra một số tiền khác số phải
 * trả, và tuỳ chiều lệch thì hoặc khách trả thiếu rồi không có vé, hoặc ta thu quá tay.
 */
public final class PaymentIntent {

    private final UUID orderId;
    private final UUID organizationId;
    private final PaymentReference reference;
    private final long amountVnd;

    /**
     * Chuỗi EMVCo payOS sinh cho tài khoản ảo của link này.
     *
     * <p>Vẫn gọi là "vietQrPayload" vì đúng là một mã VietQR và cột database cũng tên vậy — chỉ khác
     * là trước đây nền tảng tự dựng chuỗi, giờ payOS dựng. Lưu lại chứ không dựng lại mỗi lần đọc:
     * chuỗi khách đã chụp màn hình phải quét ra đúng tài khoản và đúng số tiền cũ.
     */
    private final String vietQrPayload;

    private final String bankBin;
    private final String bankAccountNumber;
    private final String bankAccountName;
    private final long payosOrderCode;
    private final String payosPaymentLinkId;
    private final String checkoutUrl;
    private final Instant expiresAt;

    private PaymentStatus status;
    private String provider;
    private String providerTxnId;
    private Long paidAmountVnd;
    private Instant confirmedAt;
    private Instant cancelledAt;

    @SuppressWarnings("java:S107") // Rehydrate từ database cần đủ mọi cột.
    private PaymentIntent(
            UUID orderId,
            UUID organizationId,
            PaymentReference reference,
            long amountVnd,
            String vietQrPayload,
            String bankBin,
            String bankAccountNumber,
            String bankAccountName,
            long payosOrderCode,
            String payosPaymentLinkId,
            String checkoutUrl,
            Instant expiresAt,
            PaymentStatus status) {
        if (amountVnd <= 0) {
            throw new IllegalArgumentException("Số tiền phải dương: " + amountVnd);
        }
        this.orderId = orderId;
        this.organizationId = organizationId;
        this.reference = reference;
        this.amountVnd = amountVnd;
        this.vietQrPayload = vietQrPayload;
        this.bankBin = bankBin;
        this.bankAccountNumber = bankAccountNumber;
        this.bankAccountName = bankAccountName;
        this.payosOrderCode = payosOrderCode;
        this.payosPaymentLinkId = payosPaymentLinkId;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    /**
     * Mở một yêu cầu thanh toán quanh một link payOS <b>đã tạo xong</b>.
     *
     * <p>Thứ tự này là cố ý: gọi payOS trước, dựng aggregate sau. Phương án ngược lại — ghi intent rồi
     * mới tạo link — để lại một intent không có gì để quét khi payOS hỏng, và khách sẽ nhìn một màn
     * hình thanh toán trống mà không ai biết tại sao.
     *
     * @param reference phải là reference của chính {@code link.orderCode()}
     */
    public static PaymentIntent open(
            UUID orderId,
            UUID organizationId,
            long amountVnd,
            Instant expiresAt,
            PaymentReference reference,
            PayosLink link) {

        if (link.orderCode() != reference.orderCode()) {
            throw new IllegalArgumentException("Reference %s không thuộc orderCode %d của link payOS"
                    .formatted(reference.value(), link.orderCode()));
        }
        // Link ghi một số tiền khác đơn nghĩa là mã QR khách quét ra sai số. Chặn trước khi nó vào
        // database: sau đó thì đã có một chuỗi QR sai nằm trong đơn và khách có thể đã chụp màn hình.
        if (link.amountVnd() != amountVnd) {
            throw new IllegalArgumentException(
                    "payOS ghi %dđ trên link nhưng đơn cần %dđ".formatted(link.amountVnd(), amountVnd));
        }

        return new PaymentIntent(
                orderId,
                organizationId,
                reference,
                amountVnd,
                link.qrCode(),
                link.bin(),
                link.accountNumber(),
                link.accountName(),
                link.orderCode(),
                link.paymentLinkId(),
                link.checkoutUrl(),
                expiresAt,
                PaymentStatus.PENDING);
    }

    @SuppressWarnings("java:S107")
    public static PaymentIntent rehydrate(
            UUID orderId,
            UUID organizationId,
            String reference,
            long amountVnd,
            String vietQrPayload,
            String bankBin,
            String bankAccountNumber,
            String bankAccountName,
            long payosOrderCode,
            String payosPaymentLinkId,
            String checkoutUrl,
            Instant expiresAt,
            PaymentStatus status,
            String provider,
            String providerTxnId,
            Long paidAmountVnd,
            Instant confirmedAt,
            Instant cancelledAt) {

        PaymentIntent intent = new PaymentIntent(
                orderId,
                organizationId,
                new PaymentReference(reference),
                amountVnd,
                vietQrPayload,
                bankBin,
                bankAccountNumber,
                bankAccountName,
                payosOrderCode,
                payosPaymentLinkId,
                checkoutUrl,
                expiresAt,
                status);
        intent.provider = provider;
        intent.providerTxnId = providerTxnId;
        intent.paidAmountVnd = paidAmountVnd;
        intent.confirmedAt = confirmedAt;
        intent.cancelledAt = cancelledAt;
        return intent;
    }

    /**
     * Ghi nhận tiền đã vào.
     *
     * @param paidAmountVnd số tiền payOS báo về — <b>không</b> phải số tiền của đơn
     * @return kết quả đối chiếu; chỉ {@link Settlement#CONFIRMED} mới được báo sang Ordering
     */
    public Settlement confirm(String provider, String providerTxnId, long paidAmountVnd, Instant now) {
        if (status == PaymentStatus.CONFIRMED) {
            // Cùng một giao dịch tới lần thứ hai là bình thường — mọi cổng đều retry cho tới khi
            // nhận 2xx. Nhưng một giao dịch KHÁC trên cùng một intent nghĩa là khách đã chuyển
            // tiền hai lần, và đó là việc của con người xử lý, không phải của webhook.
            return providerTxnId.equals(this.providerTxnId) ? Settlement.DUPLICATE : Settlement.ALREADY_CONFIRMED;
        }
        if (status != PaymentStatus.PENDING) {
            return Settlement.NOT_PENDING;
        }
        // Trả THIẾU thì không phát vé. Trả THỪA thì vẫn ghi nhận và hoàn phần dư ngoài hệ thống —
        // chặn ở đây sẽ để một khách đã trả đủ tiền không có vé, tệ hơn nhiều so với một khoản
        // hoàn thủ công.
        if (paidAmountVnd < amountVnd) {
            return Settlement.AMOUNT_MISMATCH;
        }

        this.status = PaymentStatus.CONFIRMED;
        this.provider = provider;
        this.providerTxnId = providerTxnId;
        this.paidAmountVnd = paidAmountVnd;
        this.confirmedAt = now;
        return Settlement.CONFIRMED;
    }

    /** @return false nếu không huỷ được vì đã nhận tiền — tiền đã vào thì không rút yêu cầu lại */
    public boolean cancel(Instant now) {
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        status = PaymentStatus.CANCELLED;
        cancelledAt = now;
        return true;
    }

    public boolean isConfirmed() {
        return status == PaymentStatus.CONFIRMED;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    /** Kết quả đối chiếu một lần báo có từ payOS. */
    public enum Settlement {
        CONFIRMED,
        /** Đúng giao dịch đó, đã ghi nhận rồi. Trả 2xx để cổng thôi retry. */
        DUPLICATE,
        /** Đã nhận tiền từ một giao dịch khác — khách có thể đã chuyển hai lần. */
        ALREADY_CONFIRMED,
        /** Intent đã huỷ hoặc hết hạn. */
        NOT_PENDING,
        /** Tiền vào ít hơn số phải trả. */
        AMOUNT_MISMATCH
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public PaymentReference reference() {
        return reference;
    }

    public long amountVnd() {
        return amountVnd;
    }

    public String vietQrPayload() {
        return vietQrPayload;
    }

    public String bankBin() {
        return bankBin;
    }

    public String bankAccountNumber() {
        return bankAccountNumber;
    }

    public String bankAccountName() {
        return bankAccountName;
    }

    /** Khoá đối soát với payOS: webhook trả đúng số này về. */
    public long payosOrderCode() {
        return payosOrderCode;
    }

    public String payosPaymentLinkId() {
        return payosPaymentLinkId;
    }

    /** Trang thanh toán payOS host. Frontend đưa khách tới đây khi họ không muốn tự quét QR. */
    public String checkoutUrl() {
        return checkoutUrl;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public PaymentStatus status() {
        return status;
    }

    public String provider() {
        return provider;
    }

    public String providerTxnId() {
        return providerTxnId;
    }

    public Long paidAmountVnd() {
        return paidAmountVnd;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }
}
