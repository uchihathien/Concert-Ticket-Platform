# Mobile screen specs

Mô tả chi tiết từng màn trên điện thoại. Template giống [web screens](../../screens/README.md).

| Kênh | Spec |
| --- | --- |
| Customer mobile web | [customer-web.md](customer-web.md) |
| Customer React Native | [customer-rn.md](customer-rn.md) |
| Scanner mobile web | [scanner-web.md](scanner-web.md) |

Inventory ngắn: [../screen-inventory.md](../screen-inventory.md).

## Chung cho mọi màn mobile

- **Breakpoint:** mobile web = viewport &lt; 768px; RN = phone portrait mặc định.
- **Safe area:** respect notch / home indicator; CTA cách đáy ≥ 8pt + inset.
- **Touch:** hit target ≥ 44×44 pt.
- **Tokens:** [design-native.md](../design-native.md) (RN) / [design-direction.md](../../design-direction.md) (web).
- **States:** [states.md](../states.md) + [../../states.md](../../states.md).
- **Countdown:** server `expiresAt` / `paymentExpiresAt`; resync on `visibilitychange` / AppState active.

## Template mỗi màn

1. Meta (ID, route/screen, platform, auth, milestone)
2. Mục đích
3. Layout ASCII (portrait)
4. Thành phần UI
5. Navigation / gestures
6. States riêng
7. API / data
8. Copy vi-VN
9. Platform notes (iOS / Android / Safari / Chrome)
10. Acceptance criteria
