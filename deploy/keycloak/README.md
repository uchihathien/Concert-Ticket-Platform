# Keycloak realm cho môi trường dev

Import tự động khi container khởi động (`--import-realm`).

## Vì sao không có comment trong `nexaticket-realm.json`

JSON không có cú pháp comment. Đã từng có hai khoá `"_comment_*"` trong file để ghi chú,
và Keycloak **từ chối khởi động** vì nó parse realm bằng Jackson ở chế độ nghiêm ngặt:

```
ERROR: Failed to run import
ERROR: Unrecognized field "_comment_tokens" (class RealmRepresentation), not marked as ignorable
```

Container chết, không ai đăng nhập được, và thông báo lỗi không hề nhắc tới chữ "comment".
Ghi chú để ở đây thay vì trong file.

## Các ghi chú đó

- **tokens** — ADR-0016: access token ngắn hạn, refresh xoay vòng có phát hiện tái sử dụng.
- **roles** — Vai trò trong tổ chức (ORG_ADMIN, EVENT_MANAGER, CHECKIN_STAFF) KHÔNG nằm ở Keycloak. Chúng đến từ organization_members của identity-service, vì một người có thể có vai trò khác nhau ở các tổ chức khác nhau (ADR-0008).

## Tài khoản dev

`superadmin`, `organizer`, `staff`, `customer` — mật khẩu trùng tên đăng nhập. Chỉ dùng cho local.

## `directAccessGrantsEnabled` bật cho cả bốn client

Chỉ đúng với realm **dev** này. Luồng `grant_type=password` cho phép lấy token bằng một lệnh
`curl` duy nhất — thứ mà README gốc và mọi script thử luồng đều dựa vào. Trước đây cờ này tắt,
nên lệnh trong README trả `unauthorized_client: Client not allowed for direct access grants`
và không ai chạy được luồng G0.

**Không bật ở production.** Ở đó client phải đi authorization code + PKCE; direct access grants
nghĩa là ứng dụng cầm mật khẩu người dùng, và mọi lớp bảo vệ của Keycloak (MFA, brute force
detection, consent) bị đi vòng qua.

## `requiredActions` phải có `providerId`

Keycloak KHÔNG suy ra `providerId` từ `alias`. Thiếu nó thì realm vẫn import thành công, server
vẫn khởi động bình thường, rồi **mọi lần đăng nhập** đều hỏng với:

```
RuntimeException: Unable to find factory for Required Action 'null' configured in the realm
```

Thông báo lỗi trả về cho client chỉ là `unknown_error`, nên nhìn từ phía ứng dụng thì không có
manh mối nào — phải đọc log của Keycloak mới thấy.

`defaultAction: false` là cố ý: MFA có sẵn để bật, nhưng không ép mọi người dùng mới phải cấu
hình TOTP qua trình duyệt ngay lần đăng nhập đầu.
