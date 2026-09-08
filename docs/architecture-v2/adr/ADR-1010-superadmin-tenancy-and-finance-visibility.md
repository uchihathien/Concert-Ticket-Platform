# ADR-1010: Superadmin sở hữu tenancy và toàn bộ tài chính

**Status:** Accepted — **thay thế ADR-1007** (tự tạo tổ chức)

## Context

ADR-1007 cho bất kỳ user nào tự tạo tổ chức và đặt KYC ở cổng chi trả, nhằm giảm ma sát onboarding. Chủ dự án yêu cầu mô hình khác:

- **Superadmin tạo ra các tổ chức** — không tự phục vụ.
- **Superadmin quản lý toàn bộ tiền** — thu, giữ, đối soát, chi trả.
- **Tổ chức chỉ biết số tiền và số vé đã bán.**

Đây là mô hình vận hành đóng, hợp lý khi nền tảng giữ tiền và muốn kiểm soát chặt ai được lên sàn.

## Decision

### 1. Chỉ superadmin tạo tổ chức

```
SUPER_ADMIN ──POST /v1/platform/organizations {tên, thông tin pháp nhân, email chủ sở hữu}
          ──> Organization(status = ACTIVE) + Invitation(role = ORG_OWNER)
          ──> email mời người chủ sở hữu
```

Không có endpoint tự tạo tổ chức. `POST /v1/organizations` bị gỡ bỏ.

### 2. Bỏ máy trạng thái KYC trong phần mềm

Vì superadmin đã thẩm định trước khi tạo tổ chức, trạng thái `UNVERIFIED → PENDING_VERIFICATION → VERIFIED` không còn tác dụng gì trong luồng phần mềm. Trạng thái tổ chức quay về đúng như v1: **`ACTIVE` | `SUSPENDED`**.

Hồ sơ pháp nhân vẫn được lưu (`organization_profiles`) để phục vụ tuân thủ và đối soát, nhưng là **dữ liệu superadmin nhập**, không phải cổng kiểm soát tự động. Cổng kiểm soát chính giờ là con người: superadmin không tạo tổ chức mà họ chưa tin.

Đây là một sự đơn giản hoá thật: bỏ được một máy trạng thái, một luồng upload tài liệu, một hàng đợi duyệt và các mã lỗi liên quan.

### 3. Tổ chức không chạm vào tiền

Bị gỡ khỏi phạm vi tổ chức:

| Trước (ADR-1004/1007) | Sau |
| --- | --- |
| Tổ chức cấu hình tài khoản nhận chi trả | **Superadmin** nhập và quản lý |
| Tổ chức tự yêu cầu rút tiền | **Superadmin** quyết định và thực hiện |
| Tổ chức xem số dư khả dụng / đang giữ / dự phòng | Không thấy |
| Tổ chức xem sao kê sổ cái | Không thấy |
| Tổ chức thấy hoa hồng nền tảng | Không thấy |

`payout-service` trở thành **công cụ nội bộ của superadmin**. Sổ cái, kỳ giữ tiền, dự phòng hoàn tiền, đối soát vẫn giữ nguyên thiết kế — chúng vẫn cần thiết, chỉ là chỉ superadmin nhìn thấy.

### 4. Tổ chức thấy đúng hai con số

```
GET /v1/organizations/{id}/sales-summary?eventId&sessionId&from&to
{
  "ticketsSold": 1240,
  "grossSalesVnd": 1860000000,
  "breakdown": [ { "eventId": "…", "sessionId": "…", "ticketsSold": 320, "grossSalesVnd": 480000000 } ]
}
```

Chỉ tính vé đã phát hành từ đơn `PAID`. Vé đã hoàn tiền bị trừ khỏi cả hai con số.

**Ranh giới diễn giải:** yêu cầu "chỉ biết số tiền và số vé" được hiểu là giới hạn **thông tin tài chính**. Dữ liệu vận hành mà tổ chức bắt buộc phải có để chạy sự kiện vẫn giữ: số ghế còn trống theo trạng thái, số lượt check-in, danh sách sự kiện và suất diễn của mình. Không có những thứ đó thì tổ chức không bán vé và không soát vé được.

### 5. Hệ quả dây chuyền: bỏ điều kiện tài khoản ngân hàng khi publish

Preflight publish của v1 kiểm tra "tổ chức có ≥ 1 tài khoản ngân hàng active". Điều kiện đó tồn tại vì tiền chảy vào tài khoản tổ chức. Giờ tiền chảy vào tài khoản ký quỹ của nền tảng — luôn tồn tại — nên **mã lỗi `NO_ACTIVE_BANK_ACCOUNT` bị gỡ bỏ**.

Preflight còn 3 mục: seat map `ACTIVE`, mọi ghế bán được có tier, sales window hợp lệ. Màn A-PUBLISH và A-BANK của v1 thay đổi tương ứng: A-BANK chuyển sang khu vực platform.

## Consequences

Tích cực: kiểm soát chặt ai lên sàn; giảm hẳn rủi ro gian lận kiểu "tạo tổ chức ảo rồi rút tiền"; bỏ được máy trạng thái KYC, luồng payout tự phục vụ và một loạt màn hình phía tổ chức; ba phòng thủ chống gian lận của ADR-1004 giờ chỉ còn cần hai (kỳ giữ tiền và dự phòng), vì cổng vào đã do người gác.

Tiêu cực và phải chuẩn bị:

1. **Onboarding không tự phục vụ được** — mọi tổ chức mới cần thao tác thủ công của superadmin. Đây là nút thắt khi số tổ chức tăng. Chấp nhận được ở giai đoạn đầu, cần xem lại khi vượt ~50 tổ chức.
2. **Tổ chức sẽ hỏi "khi nào tôi nhận tiền, sao lại là con số này"** và phần mềm không trả lời được. Bắt buộc phải có **quy trình đối soát ngoài hệ thống**: superadmin xuất bảng kê thanh toán (sự kiện, số vé, doanh thu gộp, hoa hồng, số thực nhận) gửi cho tổ chức theo kỳ. Nếu bỏ qua việc này, đội vận hành sẽ chết chìm trong email hỏi tiền.
3. **Niềm tin dồn vào superadmin.** Tổ chức không tự kiểm chứng được số liệu, nên mọi thao tác của superadmin lên tiền và lên tổ chức **phải có audit log đầy đủ**, và bảng kê thanh toán phải sinh từ sổ cái chứ không gõ tay.

## Validation

- Test: user thường gọi `POST /v1/platform/organizations` → 403.
- Test: `ORG_ADMIN` gọi mọi endpoint payout/ledger → 403.
- Test: `sales-summary` không lộ hoa hồng, số dư, hay bất kỳ trường sổ cái nào; vé hoàn tiền bị trừ đúng.
- Test: publish thành công khi tổ chức không có tài khoản ngân hàng nào.
- Test: mọi hành động của superadmin lên tổ chức và lên tiền đều sinh `audit_logs` có actor, before/after.
