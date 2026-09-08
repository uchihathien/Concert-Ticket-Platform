# RBAC & permission matrix

## Roles

| Role | Scope | Mô tả |
| --- | --- | --- |
| `CUSTOMER` | self | Mua vé, xem đơn/vé của mình |
| `CHECKIN_STAFF` | organization | Quét check-in cho event của org |
| `EVENT_MANAGER` | organization | Quản lý event/session/tier/promo của org |
| `ORG_ADMIN` | organization | Toàn quyền org trừ xóa org; quản lý members & bank accounts |
| `ORG_OWNER` | organization | Như ORG_ADMIN + chuyển ownership / xóa org (soft) |
| `PLATFORM_ADMIN` | platform | Cross-tenant ops, khóa tenant, hỗ trợ | 

Một user có thể có nhiều membership (nhiều org) với role khác nhau.

## Permission matrix

| Permission | CUSTOMER | CHECKIN_STAFF | EVENT_MANAGER | ORG_ADMIN | ORG_OWNER | PLATFORM_ADMIN |
| --- | --- | --- | --- | --- | --- | --- |
| View published catalog | Y | Y | Y | Y | Y | Y |
| Hold / order / own tickets | Y | — | — | — | — | —* |
| Check-in scan | — | Y | Y | Y | Y | Y |
| Venue CRUD | — | — | Y | Y | Y | Y† |
| Seat map author/publish | — | — | Y | Y | Y | Y† |
| Event/session/tier CRUD | — | — | Y | Y | Y | Y† |
| Publish/unpublish event | — | — | Y | Y | Y | Y† |
| Promotion manage | — | — | Y | Y | Y | Y† |
| Bank account manage | — | — | — | Y | Y | Y† |
| Org members invite/role | — | — | — | Y | Y | Y† |
| Org dashboard metrics | — | — | Y‡ | Y | Y | Y† |
| Refund / manual payment resolve | — | — | — | Y | Y | Y† |
| Platform tenant create/suspend | — | — | — | — | — | Y |
| Read audit cross-tenant | — | — | — | — | — | Y |

\* Platform admin không mua hộ trừ tooling support có audit riêng (post-MVP).  
† Platform admin luôn qua “impersonation/support mode” có audit; không silent write.  
‡ EVENT_MANAGER xem metrics event mình quản lý.

## Enforcement rules

1. Tenant context = membership đã xác thực của JWT/`sub`, không lấy từ body/query `organization_id` trừ khi kèm membership check.
2. Mọi query repository org-owned phải filter `organization_id = currentTenant`.
3. Public catalog endpoints không trả draft/unpublished của org khác.
4. Check-in staff chỉ check-in ticket thuộc event của org mình.
5. Integration tests bắt buộc cover IDOR: user A không đọc/sửa resource org B.

## Seed roles (Foundation)

Migration seed tạo roles hệ thống; org mới mặc định inviter là `ORG_OWNER`.
