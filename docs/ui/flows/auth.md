# UI flow — Auth & account

```mermaid
flowchart TD
  A[Landing / deep link] --> B{Authenticated?}
  B -->|No| C[C-LOGIN OIDC]
  C --> D[Keycloak + MFA if admin]
  D --> E[Callback upsert user]
  E --> F{returnUrl?}
  F -->|Yes| G[Target screen]
  F -->|No customer| H[C-HOME]
  F -->|No admin| I[A-DASH or org picker]
  B -->|Yes| G
```

## Màn hình

### C-LOGIN / A-LOGIN / S-LOGIN

- Primary: nút “Đăng nhập với NexaTicket” → IdP.
- Không form password local trên app.
- Admin/scanner: copy nhắc MFA nếu role yêu cầu.

### Invite member (admin)

1. A-MEMBERS: nhập email + role → gửi invite.
2. User login lần đầu → màn “Chấp nhận lời mời” (có thể là route `/invites/[token]` trên admin hoặc customer).
3. Thành công → A-DASH org đó.

### C-ACCOUNT

- Họ tên, email (read-only từ IdP nếu chưa hỗ trợ edit), SĐT optional.
- Link đăng xuất.

## Edge

| Case | UI |
| --- | --- |
| Refresh reuse detected | Force re-login |
| User không membership vào admin URL | “Chưa thuộc tổ chức” + contact |
| Platform admin | Org switcher + badge “Platform” |
