// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.id.TenantId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc tổ chức từ đường dẫn — <b>một cài đặt duy nhất</b> cho cả bộ lọc lẫn bộ chặn quyền.
 *
 * <h2>Lỗ hổng mà lớp này sinh ra để bịt</h2>
 *
 * <p>Bản trước so khớp thẳng một mẫu UUID trên {@code getRequestURI()}. Tomcat trả về chuỗi
 * <b>thô</b> cho phương thức đó, còn Spring thì <b>giải mã</b> cùng đường dẫn ấy khi dựng
 * {@code @PathVariable}. Hai cách đọc khác nhau trên cùng một chuỗi, và hệ quả đo được:
 *
 * <pre>
 * GET /v1/organizations/%3131111111-1111-4111-a111-111111111111/members
 *      ↑ bộ lọc: mẫu UUID không khớp → "đường dẫn không mang tổ chức nào"
 *      ↑ controller: giải mã → UUID của tổ chức NGƯỜI KHÁC
 * </pre>
 *
 * <p>Bộ lọc khi đó rơi vào nhánh dự phòng "người này chỉ thuộc một tổ chức thì lấy tổ chức đó", cho
 * request đi tiếp, và {@code GET /members} — vốn không có lớp kiểm quyền thứ hai — trả về <b>200
 * kèm email và vai trò của toàn bộ thành viên tổ chức khác</b>.
 *
 * <h2>Hai thay đổi, và thay đổi thứ hai mới là thay đổi quan trọng</h2>
 *
 * <ol>
 *   <li>Bắt <b>cả đoạn</b> đường dẫn ({@code [^/]+}) rồi mới kiểm nó có đúng hình dạng UUID chuẩn
 *       hay không — thay vì chỉ bắt những đoạn đã đúng hình dạng.
 *   <li>Đoạn có mặt nhưng sai hình dạng ⇒ {@link Match#malformed()}, và người gọi phải <b>từ
 *       chối</b> request. Bản trước coi nó như "không có tổ chức nào trên đường dẫn" và rơi về
 *       nhánh dự phòng — chính bước đó biến một chuỗi lạ thành một lần vượt rào.
 * </ol>
 *
 * <p>Nhờ vậy mọi cách biểu diễn khác chuỗi UUID chuẩn — mã hoá phần trăm, mã hoá hai lần, ký tự
 * unicode tương đương — đều bị chặn ở cùng một chỗ, mà không phải đi đoán trước từng thủ thuật.
 */
public final class TenantPath {

    /**
     * Bắt cả đoạn, không bắt riêng UUID.
     *
     * <p>Đây là toàn bộ khác biệt so với bản trước: một mẫu chỉ khớp UUID hợp lệ sẽ <b>im lặng bỏ
     * qua</b> đoạn sai hình dạng, còn mẫu này thì nhìn thấy nó và báo lại.
     */
    private static final Pattern ORG_SEGMENT = Pattern.compile("/organizations/([^/?#]+)");

    /**
     * Hình dạng UUID chuẩn, không phải "36 ký tự hex hoặc gạch nối".
     *
     * <p>Mẫu lỏng khớp cả những chuỗi mà {@code UUID.fromString} từ chối — ví dụ
     * {@code ------------------------------------} — và khi đó {@link TenantId#parse} ném
     * {@code IllegalArgumentException} từ trong filter, ngoài tầm với của
     * {@code @RestControllerAdvice}: người gọi nhận 500 cho một đường dẫn đáng ra chỉ là 404.
     */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private TenantPath() {}

    /**
     * @param present đường dẫn có mang đoạn {@code /organizations/…} hay không
     * @param tenant tổ chức đọc được; {@code null} khi đoạn đó không phải một UUID chuẩn
     */
    public record Match(boolean present, TenantId tenant) {

        private static final Match ABSENT = new Match(false, null);

        /**
         * Có đoạn tổ chức nhưng đọc không ra.
         *
         * <p>Người gọi <b>phải</b> từ chối request. Coi nó như "không có tổ chức" là mở lại đúng
         * lỗ hổng mà lớp này bịt.
         */
        public boolean malformed() {
            return present && tenant == null;
        }
    }

    /**
     * @param uri {@code request.getRequestURI()} — chuỗi thô, chưa giải mã
     */
    public static Match organizationOf(String uri) {
        if (uri == null) {
            return Match.ABSENT;
        }
        Matcher matcher = ORG_SEGMENT.matcher(uri);
        if (!matcher.find()) {
            return Match.ABSENT;
        }

        String segment = matcher.group(1);
        if (!CANONICAL_UUID.matcher(segment).matches()) {
            return new Match(true, null);
        }
        try {
            return new Match(true, TenantId.parse(segment));
        } catch (IllegalArgumentException e) {
            // Mẫu ở trên đã chặn gần hết. Vẫn bắt ở đây vì filter không có lưới an toàn nào phía
            // sau: một ngoại lệ lọt ra thành 500 thay vì 404.
            return new Match(true, null);
        }
    }
}
