# Identity & Access API (v1)

Base: `/v1`. Mọi route org-scoped mang `organizationId` trên đường dẫn; `TenantFilter` lấy tenant từ
đó và trả **404** nếu người gọi không phải thành viên.

## Đăng nhập và đăng xuất

**Không có endpoint login hay logout ở backend, và đó là thiết kế chứ không phải thiếu sót.**

Keycloak giữ mật khẩu và cấp token; backend là resource server thuần, xác thực JWT bằng JWKS. Dựng
một endpoint đăng nhập ở đây là tự rước lại nghĩa vụ mà cả hệ thống đã cố ý đẩy sang IdP.

| Việc | Chỗ làm |
| --- | --- |
| Đăng nhập | Keycloak `/protocol/openid-connect/auth` (Authorization Code + PKCE) |
| Đổi token | Keycloak `/protocol/openid-connect/token` |
| Đăng xuất | Keycloak `/protocol/openid-connect/logout` |
| Tạo bản ghi người dùng | tự động, ở request đầu tiên sau khi đăng nhập |

### Đăng xuất thu hồi được gì

Đã đo trên hệ thống đang chạy:

| Sau khi gọi logout | Kết quả |
| --- | --- |
| Refresh token cũ | **bị thu hồi ngay** (`400 invalid_grant`) |
| Access token cũ | **vẫn dùng được tới khi hết hạn** (15 phút) |

Đó là bản chất của JWT stateless, không phải lỗi: backend không tra database mỗi request nên không
có chỗ nào để đánh dấu "token này đã chết". Đánh đổi có ý thức — muốn thu hồi tức thì thì phải kiểm
tra tập trung ở mọi request, tức là bỏ đi lý do chính để dùng JWT.

Hệ quả cần biết khi vận hành: **đuổi một người khỏi tổ chức có hiệu lực ngay** (membership đọc từ
database mỗi request), nhưng **khoá tài khoản ở Keycloak thì trễ tối đa 15 phút**. Cần tức thì thì
hạ `access_token_lifespan` của realm.

### Tạo bản ghi người dùng ở lần chạm đầu

Không có màn hình đăng ký ở phía ta, nên không có bước nào để gắn việc tạo bản ghi vào. Người dùng
xuất hiện lần đầu ở request đầu tiên sau khi đăng nhập, và bản ghi được tạo ngay tại đó. Không làm
vậy thì họ nhận 401 vĩnh viễn: không endpoint nào chạm tới được, kể cả endpoint dùng để tạo bản ghi.

Khoá định danh là claim `sub`, **không phải email**. Email chỉ là thuộc tính. Nếu email đã thuộc về
một `sub` khác thì request trả 401 kèm log nêu rõ — hệ thống **không** tự chuyển bản ghi cũ sang
`sub` mới, vì nhận diện người dùng theo email chính là lỗ hổng chiếm tài khoản kinh điển.

## Hồ sơ của tôi

| Method | Path | Ghi chú |
| --- | --- | --- |
| GET | `/v1/me` | hồ sơ + danh sách tổ chức kèm vai trò |
| PATCH | `/v1/me` | `fullName`, `phone` |
| GET | `/v1/me/organizations` | chỉ danh sách tổ chức |

```json
{
  "id": "uuid", "email": "organizer@nexaticket.local",
  "fullName": "Ban Tổ Chức", "phone": "0987654321", "superAdmin": false,
  "organizations": [
    { "organizationId": "uuid", "slug": "nha-hat-lon", "name": "Nhà hát Lớn",
      "status": "ACTIVE", "role": "ORG_OWNER" }
  ]
}
```

Không có tham số id ở bất kỳ đâu — danh tính lấy từ token, nên endpoint này không thể dùng để đọc
hồ sơ người khác kể cả khi ai đó quên một bước kiểm tra.

**Email không sửa được.** Keycloak là nguồn chân lý, và email cũng là khoá khớp lời mời; sửa ở phía
ta sẽ tạo hai giá trị khác nhau cho cùng một người.

Với `PATCH`, chuỗi rỗng nghĩa là **giữ nguyên**, không phải xoá — form gửi chuỗi rỗng cho ô không
nhập, và không quy đổi thì người chỉ sửa số điện thoại sẽ vô tình xoá tên mình.

## Thành viên

| Method | Path | Quyền |
| --- | --- | --- |
| GET | `/v1/organizations/{orgId}/members` | thành viên |
| PATCH | `/v1/organizations/{orgId}/members/{userId}` | ORG_ADMIN+ |
| DELETE | `/v1/organizations/{orgId}/members/{userId}` | ORG_ADMIN+ |

`GET` trả kèm `email` và `fullName`, không chỉ UUID: một bảng toàn UUID thì không ai biết đang gỡ
nhầm ai.

Ba luật, và lý do từng luật:

1. **Không hạ vai trò / không gỡ chủ sở hữu cuối cùng** → `LAST_OWNER` (409). Mất người sở hữu cuối
   là tổ chức không còn ai mời được ai vào, và cách cứu duy nhất là superadmin sửa tay database.
2. **Không tự đổi vai trò hay tự gỡ chính mình** → 403. Bấm nhầm là tự khoá mình ra ngoài — loại sự
   cố không tự sửa được.
3. **Chỉ `ORG_OWNER` phong được `ORG_OWNER`** → 403. Cho `ORG_ADMIN` làm việc đó là cho họ tự nâng
   quyền qua trung gian: phong một tài khoản mình kiểm soát lên OWNER rồi dùng tài khoản đó.

## Lời mời

| Method | Path | Quyền |
| --- | --- | --- |
| POST | `/v1/organizations/{orgId}/invitations` | ORG_ADMIN+ |
| GET | `/v1/organizations/{orgId}/invitations` | ORG_ADMIN+ — chỉ lời mời chưa dùng |
| DELETE | `/v1/organizations/{orgId}/invitations/{invitationId}` | ORG_ADMIN+ — 204 |
| POST | `/v1/invitations/{token}/accept` | người được mời |

**Token thô chỉ xuất hiện đúng một lần**, trong phản hồi lúc tạo, để notification-service gửi email.
Danh sách `GET` **không** chứa token dưới bất kỳ dạng nào, kể cả hash: lộ nó qua một endpoint đọc là
biến việc xem danh sách thành việc lấy được quyền vào tổ chức.

Lời mời đã dùng thì không thu hồi được (`INVITATION_ALREADY_USED`) — khi đó nó đã thành membership,
và cách gỡ là gỡ thành viên. Gộp hai thứ vào một nút sẽ khiến "thu hồi lời mời" âm thầm đuổi một
người đang làm việc.

## Tổ chức

| Method | Path | Quyền |
| --- | --- | --- |
| GET | `/v1/organizations/{orgId}` | thành viên |
| PATCH | `/v1/organizations/{orgId}` | ORG_ADMIN+ — đổi tên |
| POST | `/v1/platform/organizations` | SUPER_ADMIN — tạo |
| GET | `/v1/platform/organizations` | SUPER_ADMIN — liệt kê toàn hệ thống |
| POST | `/v1/platform/organizations/{orgId}/suspend` | SUPER_ADMIN |
| POST | `/v1/platform/organizations/{orgId}/activate` | SUPER_ADMIN |

Khoá / mở khoá là việc của **nền tảng**, không phải của tổ chức — tổ chức tự mở khoá cho mình thì
việc khoá chẳng có ý nghĩa gì.

Khoá **không xoá gì** và **không dừng việc bán vé đang diễn ra**. Nó chặn đúng những đường có kiểm
`isActive()` — hiện là mời thành viên. Dừng bán là thao tác của catalog (rút xuống hoặc huỷ); gộp
hai thứ vào một nút sẽ khiến một quyết định vận hành âm thầm kéo theo một quyết định thương mại.

## Trần mua vé của tổ chức

| Method | Path | Quyền |
| --- | --- | --- |
| GET | `/v1/organizations/{orgId}/purchase-limits` | ORG_ADMIN+ |
| PUT | `/v1/organizations/{orgId}/purchase-limits` | ORG_ADMIN+ |

```json
{ "maxSeatedPerHold": 4, "maxStandingPerHold": null,
  "maxUnitsPerHold": null, "maxTicketsPerCustomer": 6 }
```

`null` nghĩa là **kế thừa trần nền tảng**, không phải "không giới hạn" — và nó luôn có mặt trong
JSON, không bị bỏ đi. Vắng mặt thì frontend không phân biệt được "kế thừa mặc định" với "API thiếu
trường".

`PUT` chứ không `PATCH`: đây là thay cả bộ. Với `PATCH` thì "không gửi" và "đặt về kế thừa" không
phân biệt được, và một ô đã điền sẽ không bao giờ xoá được.

Chuỗi kế thừa đầy đủ: **suất diễn → tổ chức → nền tảng**, giải một lần lúc catalog publish
(`PurchaseLimits.resolve`) rồi gửi giá trị đã chốt sang Inventory. Không kẹp bằng trần nền tảng lúc
ghi vào đây: làm vậy sẽ đóng băng giá trị của ngày hôm ghi, và hạ trần nền tảng sau đó không còn tác
dụng với tổ chức đã khai.

## Open Host Service (`/internal/**`)

| Method | Path | Dùng bởi |
| --- | --- | --- |
| GET | `/internal/memberships` | mọi service, qua `HttpMembershipLookup` |

Một endpoint, không phải hai. `/internal/users/provision` đã bị bỏ: nó UPSERT vô điều kiện rồi mới
tra, nên đường nóng của mười service thành một lệnh ghi mỗi 60 giây mỗi người dùng — và lệnh ghi đó
đè `full_name` bằng claim của Keycloak, làm tên vừa sửa ở `PATCH /v1/me` quay về bản cũ trong vòng
một phút. `/internal/memberships` vốn đã **tra-hoặc-tạo**: tạo ở lần chạm đầu tiên, và không đụng
vào bản ghi đã có.

Gateway **cố ý không route** `/internal/**`: chỉ gọi được trong mạng nội bộ. Không service nào đọc
thẳng bảng `organization_members` của identity (ADR-1002).

Mạng nội bộ là hàng rào thứ nhất, không phải hàng rào duy nhất. Khai
`nexaticket.internal.shared-secret` (biến `NEXATICKET_INTERNAL_SHAREDSECRET`, **cùng một giá trị cho
mọi service**) thì `InternalApiFilter` đòi header `X-Internal-Token` và trả 404 nếu thiếu. Bắt buộc ở
production: `/internal/memberships` tạo được người dùng với `email` do người gọi tự đặt, và
`SuperAdminBootstrap` cấp `SUPER_ADMIN` theo email.

## Mã lỗi

| Code | HTTP |
| --- | --- |
| `ORGANIZATION_NOT_FOUND` | 404 |
| `NOT_A_MEMBER` | 404 |
| `SLUG_ALREADY_TAKEN` | 409 |
| `ALREADY_A_MEMBER` | 409 |
| `LAST_OWNER` | 409 |
| `ORGANIZATION_SUSPENDED` | 409 |
| `INVITATION_INVALID` | 400 |
| `INVITATION_EXPIRED` | 410 |
| `INVITATION_ALREADY_USED` | 409 |
| `EMAIL_MISMATCH` | 403 |

`SCANNER_CODE_INVALID` và `SCANNER_CODE_EXHAUSTED` đã khai trong enum nhưng **chưa có endpoint nào
phát ra**: mã truy cập soát vé (ADR-1008) có bảng `scanner_access_codes` từ migration đầu tiên mà
chưa được dựng. Đó là phần còn thiếu duy nhất của identity-service.

## Còn thiếu

- **Mã truy cập soát vé (ADR-1008).** Bảng đã có, API chưa có. Cần thêm một quyết định thiết kế
  chưa được chốt ở đâu: token phạm vi hẹp cấp cho từng thiết bị sẽ do identity tự ký (ticketing phải
  chấp nhận hai issuer), hay là một bản ghi phiên mà ticketing hỏi lại qua `/internal/**`.
- **Xác thực `aud`.** `auth-oidc.md` yêu cầu kiểm `iss`, `aud`, `exp` — hiện chỉ kiểm `iss` và
  `exp`, vì Keycloak không phát claim `aud` cho bốn client này. Hệ quả: token của app khách dùng
  được ở API quản trị. Không phải leo thang quyền (uỷ quyền vẫn theo membership và vai trò), nhưng
  là một lớp phòng vệ đang thiếu. Cách sửa: thêm audience mapper `nexaticket-api` cho cả bốn client
  rồi bật kiểm audience ở resource server.
