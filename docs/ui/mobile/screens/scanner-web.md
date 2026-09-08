# Mobile web — Scanner (`web-scanner`)

Staff check-in trên điện thoại. Portrait; minimal chrome; online-only. Spec web desktop-equivalent: [../../screens/scanner.md](../../screens/scanner.md).

---

## S-LOGIN — Đăng nhập scanner (mobile)

| | |
| --- | --- |
| **Route** | `/login` |
| **Platform** | Mobile web / PWA |
| **Auth** | Public |
| **Milestone** | 04 |

### Layout

```text
┌──────────────────────────┐
│                          │
│    NexaTicket Scan       │
│    Đăng nhập staff       │
│                          │
│    [    Đăng nhập    ]   │
│                          │
└──────────────────────────┘
```

Full viewport centered; không marketing footer.

### States

| No CHECKIN_STAFF role | “Tài khoản không có quyền quét vé” |
| MFA required | IdP handles |

### Navigation

Success → S-SESSION hoặc S-SCAN nếu session sticky trong `sessionStorage`.

### PWA

Add to Home Screen prompt Should sau first successful login.

### Acceptance

- [ ] CUSTOMER account rejected

---

## S-SESSION — Chọn sự kiện / suất

| | |
| --- | --- |
| **Route** | `/` (chưa có session) |
| **Platform** | Mobile web |
| **Auth** | CHECKIN_STAFF+ |
| **Milestone** | 04 |

### Mục đích

Chọn context trước khi bật camera.

### Layout

```text
┌──────────────────────────┐
│ NexaTicket Scan    ● Online│
│ user@org · Đăng xuất       │
├──────────────────────────┤
│ Chọn sự kiện               │
│ [ Dropdown / list      ▾ ] │
│ Chọn suất diễn             │
│ [ Dropdown             ▾ ] │
│                            │
│ [     Bắt đầu quét     ]   │
└──────────────────────────┘
```

### Thành phần

| Event select | Chỉ events org staff thuộc |
| Session select | Filter theo event |
| Online dot | Green/red |
| CTA | Disabled until both selected |

### Tương tác

- Bắt đầu quét → save `sessionId` sessionStorage → navigate `/scan` hoặc inline camera
- Logout → clear sticky

### States

| Empty events | “Không có sự kiện được gán quyền quét” |
| Offline | Banner; CTA disabled |

### API

List events/sessions scoped org + staff permission.

### Acceptance

- [ ] Sticky session survives PWA reopen

---

## S-SCAN — Camera quét QR

| | |
| --- | --- |
| **Route** | `/scan` hoặc `/` sau chọn session |
| **Platform** | Mobile web |
| **Auth** | CHECKIN_STAFF+ |
| **Milestone** | 04 |

### Layout

```text
┌──────────────────────────┐
│ ← Đổi suất  Hòa Âm 19:00 │
│                    ● Online│
├──────────────────────────┤
│ ┌──────────────────────┐ │
│ │                      │ │
│ │   Camera viewfinder  │ │
│ │   [  QR guide box ]  │ │
│ │                      │ │
│ └──────────────────────┘ │
│ [🔦] Torch (Should)      │
├──────────────────────────┤
│ Hoặc dán mã vé           │
│ [________________] [OK]  │
└──────────────────────────┘
```

### Thành phần

| Camera | `facingMode: environment`; permission prompt copy rõ |
| Guide box | 70% width square centered |
| Torch | Toggle nếu `track.applyConstraints` supported |
| Manual | Text input + Kiểm tra |
| Lock | Overlay mờ khi API pending |

### Tương tác

1. QR decoded → POST `/v1/check-ins`
2. Cooldown 500ms before next scan
3. Result → S-RESULT overlay
4. Offline → stop decode + banner

### States

| Camera denied | Hide viewfinder; manual only + hướng dẫn Settings |
| API error | S-RESULT danger variant “Lỗi mạng” |
| Wrong org ticket | S-RESULT WRONG_ORGANIZATION |

### Platform notes

| iOS Safari | Camera cần HTTPS; standalone PWA cần test permission persist |
| Android Chrome | Rear camera default; lock orientation portrait via CSS + meta |
| Notch | `env(safe-area-inset-*)` padding |

### Sound

Success/fail beep after first user tap unlocks AudioContext.

### Acceptance

- [ ] Scan → result &lt; 2s perceived on good network
- [ ] No double check-in from one decode burst
- [ ] Portrait only; viewfinder not clipped by notch

---

## S-RESULT — Overlay kết quả (mobile)

| | |
| --- | --- |
| **Surface** | Full-bleed overlay on S-SCAN |
| **Platform** | Mobile web |
| **Milestone** | 04 |

### Layout

Full screen color block + icon + text 32px+ + seat label + subtitle.

| CHECKED_IN | Green `--nt-success` | “HỢP LỆ” |
| ALREADY_CHECKED_IN | Amber `--nt-warn` | “ĐÃ CHECK-IN” + time |
| INVALID_* | Red `--nt-danger` | “KHÔNG HỢP LỆ” + reason |

Footer: “Chạm để quét tiếp”.

### Motion

300ms flash in; settle; auto dismiss 2.5s.

### Tương tác

- Tap anywhere → dismiss → camera active
- Không navigate away from S-SCAN

### Accessibility

Text + icon; không chỉ màu. Vibration Should Android.

### Acceptance

- [ ] Readable at 2m arm length outdoor
- [ ] Rescan same ticket → ALREADY not green success sound

---

## PWA scanner shell (optional)

| | |
| --- | --- |
| **Scope** | `web-scanner` manifest |
| **Milestone** | 11–13 Should |

- `display: standalone`
- `start_url: /scan` if session sticky else `/`
- Icon maskable 192/512
- SW shell only — no API cache

Hướng dẫn first-run: “Thêm vào Màn hình chính” (iOS Share).

Chi tiết: [pwa.md](../pwa.md).
