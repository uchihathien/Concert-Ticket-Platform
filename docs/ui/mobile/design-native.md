# Design — native & mobile web

Cùng brand với [../design-direction.md](../design-direction.md). Map token sang RN.

## Tokens RN (`theme.ts`)

```ts
export const nt = {
  bg: '#0c1210',
  bgElevated: '#141c18',
  surface: '#1a2420',
  text: '#f2f5f0',
  textMuted: '#9aa89c',
  accent: '#e8f56d',
  accentInk: '#12180f',
  danger: '#ff6b5a',
  success: '#3dd68c',
  warn: '#ffb020',
  seatAvailable: '#2d6a4f',
  seatHeldMine: '#e8f56d',
  seatHeldOther: '#4a5560',
  seatReserved: '#ffb020',
  seatSold: '#2a3330',
  radius: 12,
  space: { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 },
  font: {
    regular: 'BeVietnamPro_400Regular',
    medium: 'BeVietnamPro_500Medium',
    bold: 'BeVietnamPro_700Bold',
  },
};
```

Load font lúc splash; fallback system UI nếu fail.

## Touch

| Element | Min size |
| --- | --- |
| CTA | height 52, full width inset 16 |
| Icon button | 44×44 |
| Seat (default zoom) | ≥ 44 pt touch |

## Safe area

- Mọi screen bọc `SafeAreaView` / insets.
- Bottom CTA: `paddingBottom = insets.bottom + 8`.
- Home indicator không che tray SeatMap.

## Motion (RN)

1. Seat select: `withTiming` 120–150ms.
2. Countdown &lt; 60s: color interpolate → warn.
3. PaymentSuccess: fade+scale nhẹ 250ms — không confetti.

## Platform polish

| iOS | Android |
| --- | --- |
| Large title optional trên List | Edge-to-edge status bar |
| Haptic light on seat select | Ripple on CTA |
| Swipe back (disabled Pay) | Predictive back disabled Pay |

## Dark only

Customer mobile: **dark** theo brand night-event. Không light theme toggle v1.
