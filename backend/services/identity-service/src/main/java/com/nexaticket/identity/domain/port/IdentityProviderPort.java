// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Những việc chỉ Keycloak làm được.
 *
 * <p>Cổng này cố ý <b>rất hẹp</b>. Keycloak Admin API cho phép đọc và sửa gần như mọi thứ về một
 * tài khoản — đổi mật khẩu thẳng, đọc thông tin đăng nhập, mạo danh người dùng. Mở một client
 * chung cho cả API đó rồi tự nhắc nhau "chỉ dùng đúng phần cần" là cách bảo vệ tệ nhất. Ở đây chỉ
 * có đúng một phương thức, và nó không đặt được mật khẩu — nó chỉ nhờ Keycloak gửi thư.
 *
 * <h2>Vì sao không tự làm luồng đặt lại mật khẩu</h2>
 *
 * <p>Hệ thống này <b>không lưu mật khẩu</b> (ADR-0016). Tự làm nghĩa là dựng bảng token đặt lại,
 * tự chống dò, tự hết hạn, tự chống dùng lại — rồi cuối cùng vẫn phải gọi Keycloak để ghi mật khẩu
 * mới. Toàn bộ phần đó Keycloak đã có, đã được kiểm chứng, và người dùng đặt mật khẩu trên đúng
 * tên miền mà họ vẫn đăng nhập — thứ duy nhất giúp họ phân biệt trang thật với trang giả.
 *
 * <p>Nên phần "quên mật khẩu" tự phục vụ đi thẳng qua Keycloak, không qua service này. Cổng này chỉ
 * lo trường hợp còn lại: <b>quản trị viên gửi hộ</b> khi một nhân viên không tự làm được.
 */
public interface IdentityProviderPort {

    /**
     * Nhờ Keycloak gửi thư đặt lại mật khẩu cho một tài khoản.
     *
     * <p>Thư chứa một liên kết dùng một lần dẫn tới màn hình đặt mật khẩu của Keycloak. Service này
     * không thấy mật khẩu mới, không thấy token, và không đặt được mật khẩu thay người dùng.
     *
     * @param idpSubject claim {@code sub} — id của tài khoản bên Keycloak
     * @param clientId client mà người dùng sẽ quay về sau khi đặt xong (quyết định giao diện và
     *     đường quay lại); {@code null} thì Keycloak dùng mặc định của realm
     * @param redirectUri nơi quay về sau khi đặt xong; phải khớp redirect URI đã khai của client,
     *     nếu không Keycloak từ chối
     * @throws IdentityProviderUnavailableException khi không cấu hình, hoặc Keycloak không trả lời
     */
    void sendPasswordResetEmail(String idpSubject, String clientId, String redirectUri);

    /*
     * --- Tài khoản nhân viên soát vé do ban tổ chức mời ---
     *
     * Bốn phương thức dưới đây nới cổng này ra, và nới có chủ đích: nhân viên soát vé KHÔNG tự đăng
     * ký, nên phải có ai đó tạo tài khoản cho họ. Chúng vẫn giữ nguyên tắc của cổng — không phương
     * thức nào đặt được mật khẩu hay đọc được thông tin đăng nhập. Tài khoản ra đời không có mật
     * khẩu, và người duy nhất đặt được nó là người mở thư.
     */

    /**
     * Tạo sẵn một tài khoản chưa có mật khẩu, buộc đặt mật khẩu và xác minh email ở lần đầu.
     *
     * @return id của tài khoản bên Keycloak — chính là claim {@code sub} khi người đó đăng nhập
     */
    String createInvitedAccount(String email, String fullName);

    /** Gửi thư chứa liên kết dùng một lần để đặt mật khẩu và xác minh email. */
    void sendInvitationEmail(String idpSubject, Duration linkLifetime);

    Optional<Account> findAccount(String idpSubject);

    Optional<Account> findAccountByEmail(String email);

    /**
     * Xoá một tài khoản <b>chưa kích hoạt</b> — cách duy nhất làm liên kết đã gửi hết dùng được.
     *
     * <p>Tự kiểm lại trạng thái ngay trước khi xoá chứ không tin người gọi: xoá nhầm một tài khoản
     * đang dùng là mất luôn người đó khỏi hệ thống, và không có lệnh nào hoàn tác.
     *
     * @throws AccountAlreadyActivatedException nếu tài khoản đã có người đặt mật khẩu
     */
    void deleteUnactivatedAccount(String idpSubject);

    /*
     * --- Tài khoản nhân viên do hệ thống cấp (tên đăng nhập + mã PIN) ---
     *
     * Đây là chỗ DUY NHẤT cổng này đặt mật khẩu thay người dùng, và nó đi ngược nguyên tắc ở trên có
     * chủ đích: cộng tác viên soát vé theo ca không có email để tự đặt mật khẩu, nên ban tổ chức phát
     * tài khoản kèm PIN tại chỗ. Bù lại, PIN do HỆ THỐNG sinh ngẫu nhiên (người bấm nút không chọn
     * được), chỉ trả về đúng một lần, không lưu ở đâu ngoài Keycloak, và mọi lần cấp đều vào nhật ký
     * kiểm toán. Tài khoản email của ban tổ chức vẫn không có đường nào bị đặt mật khẩu hộ.
     */

    /**
     * Tạo tài khoản đăng nhập được ngay bằng {@code pin}, không có hành động bắt buộc nào chờ.
     *
     * @param loginEmail email nội bộ dùng làm tên đăng nhập bên Keycloak (realm dùng email làm
     *     username); miền {@code .invalid} nên không bao giờ nhận thư
     * @return id của tài khoản bên Keycloak
     * @throws AccountAlreadyExistsException nếu tên đăng nhập đã có người dùng
     */
    String createStaffAccount(String loginEmail, String fullName, String pin);

    /** Đặt PIN mới (không tạm thời — nhân viên không phải đổi ở lần đăng nhập tới). */
    void setStaffPin(String idpSubject, String pin);

    /** Bật/tắt tài khoản bên Keycloak. Tắt rồi thì không đăng nhập và không làm mới token được. */
    void setEnabled(String idpSubject, boolean enabled);

    /** Đóng mọi phiên đăng nhập đang mở của tài khoản — refresh token cũ hết dùng ngay. */
    void signOutEverywhere(String idpSubject);

    /** Xoá hẳn tài khoản — chỉ dùng để dọn tài khoản vừa tạo khi phần còn lại của lệnh hỏng. */
    void deleteAccount(String idpSubject);

    /**
     * @param activated đã đặt mật khẩu xong — tức là không còn hành động bắt buộc nào chờ
     */
    record Account(String idpSubject, boolean activated) {}

    /** Từ chối xoá một tài khoản đã kích hoạt. */
    class AccountAlreadyActivatedException extends RuntimeException {

        public AccountAlreadyActivatedException(String idpSubject) {
            super("Tài khoản " + idpSubject + " đã kích hoạt, không được xoá");
        }
    }

    /** Tên đăng nhập đã có người dùng bên Keycloak. */
    class AccountAlreadyExistsException extends RuntimeException {

        public AccountAlreadyExistsException(String username) {
            super("Tên đăng nhập " + username + " đã có người dùng");
        }
    }

    /** Cổng chưa được cấu hình, hoặc Keycloak không trả lời. Tách khỏi lỗi nghiệp vụ. */
    class IdentityProviderUnavailableException extends RuntimeException {

        public IdentityProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
