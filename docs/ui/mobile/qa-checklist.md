# Mobile QA checklist

## A. Go-live (mobile web) — bắt buộc tuần 13

### Devices

- [ ] iPhone gần nhất (Safari) 
- [ ] Android Chrome (Samsung/Xiaomi phổ biến)

### Customer purchase

- [ ] Home → list → detail responsive
- [ ] SeatMap: pan, chọn 1–N ghế, legend đúng màu
- [ ] Hold countdown 5p; hết hạn về chọn ghế
- [ ] Login return giữ flow
- [ ] VietQR: copy amount + reference; QR quét được bằng app NH test
- [ ] Poll → PAID → thấy vé + QR
- [ ] Resume order AWAITING từ Orders
- [ ] WS: ghế người khác đổi trạng thái (2 máy)
- [ ] Offline banner khi bật airplane

### Scanner

- [ ] Login staff
- [ ] Quét vé VALID → CHECKED_IN
- [ ] Quét lại → ALREADY_CHECKED_IN
- [ ] Token invalid → danger overlay
- [ ] Không mạng → không submit

### PWA (nếu làm)

- [ ] Add to Home Screen customer + scanner
- [ ] Không phục vụ seat data stale từ SW cache

## B. RN v1 — trước internal store

- [ ] Auth PKCE + SecureStore survive kill
- [ ] Parity purchase E2E staging
- [ ] Universal/App link từ email pay + ticket
- [ ] Background/foreground countdown đúng
- [ ] WS reconnect
- [ ] Sentry sourcemap
- [ ] ForceUpdate đường thử
- [ ] Không token trong log

## C. Performance budgets (đề xuất)

| Metric | Target |
| --- | --- |
| SeatMap interactive | &lt; 2s trên máy tầm trung sau navigate |
| Hold tap → confirmed UI | cảm giác &lt; 300ms + network |
| PaymentQr first paint | &lt; 1s sau có order payload |
