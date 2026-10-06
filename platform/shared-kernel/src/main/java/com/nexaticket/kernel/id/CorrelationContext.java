// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.id;

/**
 * Nơi giữ mã tương quan của luồng đang xử lý.
 *
 * <p>Nằm ở shared-kernel chứ không ở starter-web vì <b>module không phải web cũng cần đọc nó</b> —
 * outbox gắn correlationId vào message, audit gắn vào bản ghi. Nếu để chỗ giữ này trong lớp filter
 * của servlet thì mọi module muốn đọc đều phải kéo theo Spring Web.
 *
 * <p>Ai điền vào: {@code CorrelationIdFilter} ở biên HTTP, và consumer message ở biên AMQP.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** Giá trị dùng khi chạy ngoài ngữ cảnh request — job nền, khởi động, test. */
    public static final String UNKNOWN = "unknown";

    private CorrelationContext() {}

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Không bao giờ trả null: log và bản ghi audit luôn có một giá trị để ghi. */
    public static String current() {
        String value = CURRENT.get();
        return value != null ? value : UNKNOWN;
    }

    /** Sinh mã mới nếu chưa có, dùng ở biên khi request đến không mang sẵn mã. */
    public static String currentOrGenerate() {
        String value = CURRENT.get();
        if (value == null) {
            value = CorrelationId.generate().value();
            CURRENT.set(value);
        }
        return value;
    }
}
