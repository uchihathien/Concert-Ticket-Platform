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

## Đăng ký: `registrationAllowed` và callback riêng

`packages/auth` mở đường đăng ký bằng một provider Auth.js thứ hai, `keycloak-register`, trỏ vào
endpoint `/protocol/openid-connect/registrations` của Keycloak. Nó cần HAI thứ ở realm, thiếu một
trong hai là hỏng:

1. `registrationAllowed: true` — không bật thì Keycloak trả trang lỗi thay vì form đăng ký.
2. `http://localhost:3000/api/auth/callback/keycloak-register` trong `redirectUris` của
   `web-customer`. Auth.js đặt callback theo **id provider**, nên provider thứ hai có callback
   khác provider đăng nhập. Thiếu URI này thì Keycloak từ chối với
   `Invalid parameter: redirect_uri` — trước cả khi người dùng thấy form.

Chỉ mở cho `web-customer`. Tài khoản của ban tổ chức, nhân viên soát vé và superadmin đều do
người khác cấp; có nút tự đăng ký ở ba app kia là mời người lạ vào khu vực quản trị.

`registrationEmailAsUsername: true` để form chỉ hỏi email và mật khẩu. Với một trang bán vé,
bắt khách nghĩ ra username riêng là thêm một ô để họ bỏ dở.

## Đăng nhập bằng Google

Google là **identity provider của Keycloak**, không phải provider thứ ba của Auth.js. Frontend
vẫn dùng đúng client `web-customer`, đúng callback `/api/auth/callback/keycloak`, chỉ thêm một
tham số `kc_idp_hint=google` vào authorization request để Keycloak bỏ qua trang mật khẩu của nó
và chuyển thẳng sang Google.

Cách này đổi lấy ba thứ:

- **Một danh tính duy nhất.** Người đăng nhập bằng Google hôm nay và bằng mật khẩu ngày mai vẫn
  là cùng một user Keycloak, cùng một `sub` — mà `sub` chính là khoá backend dùng để tạo bản ghi
  người dùng. Nếu Google là provider riêng của Auth.js thì đó sẽ là hai `sub` khác nhau và một
  người thành hai tài khoản.
- **Thêm nhà cung cấp sau này là việc cấu hình.** Facebook hay Apple chỉ cần khai thêm trong
  Keycloak; code của bốn app không đổi một dòng.
- **Không thêm redirect URI.** Callback vẫn là callback cũ, nên realm file không phải sửa.

Khai bằng script, đừng nhét vào `nexaticket-realm.json`:

```bash
GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... ./scripts/setup-google-idp.sh
```

Realm file được commit, còn client secret của Google là bí mật thật. Để realm mặc định không có
Google nghĩa là mọi máy dev vẫn chạy được ngay khi chưa ai có tài khoản Google Cloud; ai cần thì
chạy thêm một lệnh.

Ba chỗ dễ sai:

1. **Redirect URI phía Google.** Trong Google Cloud Console phải khai
   `http://localhost:8081/realms/nexaticket/broker/google/endpoint` — địa chỉ của *Keycloak*,
   không phải của Next.js. Khai nhầm sang `localhost:3000` thì Google chặn với `redirect_uri_mismatch`.
2. **`kc_idp_hint` phải là đối số thứ BA của `signIn()`**, tức `authorizationParams`. Đặt nhầm
   vào đối số thứ hai thì Auth.js coi đó là tuỳ chọn của nó, bỏ qua lặng lẽ, và người dùng rơi
   vào trang đăng nhập mật khẩu của Keycloak — không có lỗi nào để lần ra.
3. **`trustEmail: true`.** Google đã xác minh email; không bật thì Keycloak gửi thêm một mail xác
   minh nữa, và ở môi trường dev không có SMTP thật thì người dùng kẹt luôn ở đó.

Một người có sẵn tài khoản mật khẩu rồi mới đăng nhập Google: realm bật
`registrationEmailAsUsername` nên Keycloak không cho hai user trùng email, và luồng
`first broker login` sẽ hỏi để **liên kết** hai đường vào cùng một tài khoản thay vì tạo tài
khoản mới. Đó là hành vi mong muốn — `sub` giữ nguyên nên backend không thấy gì thay đổi.

Nút ở frontend tắt mặc định, bật bằng `AUTH_GOOGLE_ENABLED=true` trong `.env.local` của
`web-customer`. Hiện nút khi chưa khai provider thì người dùng bấm vào và nhận trang lỗi của
Keycloak — tệ hơn hẳn so với không thấy nút.

## Đăng xuất: `post.logout.redirect.uris` và vì sao logout của Auth.js là chưa đủ

`signOut()` của Auth.js chỉ xoá cookie phiên của Next.js. Bên Keycloak không có gì thay đổi, và
điều đó gây ra một lỗi mà người dùng gặp ngay: **đăng xuất rồi không đăng nhập được bằng tài khoản
khác**.

Đo trên hệ thống đang chạy, cùng một tài khoản, ba cách kết thúc phiên:

| Cách | Refresh token | Phiên SSO | Đổi được tài khoản? |
| --- | --- | --- | --- |
| Không gọi gì (hành vi cũ) | còn dùng được | còn sống | **không** |
| `POST /logout` kèm `refresh_token` | đã thu hồi | đã kết thúc | có |
| `GET /logout?client_id=…` | còn dùng được | còn sống | **không** |

Dòng đầu là lỗi: `ssoSessionIdleTimeout` của realm là 30 ngày, nên cookie `KEYCLOAK_IDENTITY` sống
rất lâu. Lần đăng nhập sau Keycloak thấy phiên còn hiệu lực và **cấp code ngay mà không hỏi mật
khẩu** — bấm "Đăng nhập" là quay lại đúng tài khoản vừa thoát, không có cách nào chọn tài khoản
khác ngoài việc tự đi xoá cookie trình duyệt.

Dòng cuối gây bất ngờ hơn: Keycloak trả HTTP 200 nhưng **không đăng xuất gì cả**. Thiếu
`id_token_hint` thì nó chỉ hiện trang hỏi "bạn có chắc muốn thoát không" và chờ người dùng bấm.
Nhìn từ script thì như đã thành công.

Nên `packages/auth` gọi đường **backchannel** trong `events.signOut`: chạy hoàn toàn ở server, chỉ
cần refresh token vốn đã có sẵn ở store, làm đủ cả hai việc, và vẫn chạy khi người dùng đã đóng
tab trước lúc chuyển hướng kịp.

`post.logout.redirect.uris` vẫn được khai cho cả bốn client, để đường frontchannel — nếu sau này
cần, ví dụ muốn Keycloak đăng xuất luôn khỏi các app khác trong cùng phiên SSO — dùng được ngay.
Không khai thì Keycloak **từ chối** `post_logout_redirect_uri` và cả yêu cầu đăng xuất thất bại
trong im lặng.

`+` nghĩa là "mọi giá trị đã khai ở `redirectUris`", cộng thêm trang chủ và trang đăng nhập.
