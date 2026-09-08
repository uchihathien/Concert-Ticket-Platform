# Catalog & Admin API (v1)

Base: `/v1`. Mọi mutation: header `Idempotency-Key`. Org-scoped routes lấy tenant từ membership.

## Admin — Venues

| Method | Path | Role | Body / notes |
| --- | --- | --- | --- |
| POST | `/admin/venues` | EVENT_MANAGER+ | `{ name, city, address }` |
| GET | `/admin/venues` | EVENT_MANAGER+ | list tenant |
| GET | `/admin/venues/{id}` | EVENT_MANAGER+ | detail |
| PATCH | `/admin/venues/{id}` | EVENT_MANAGER+ | partial update |

## Admin — Seat maps

| Method | Path | Role | Notes |
| --- | --- | --- | --- |
| POST | `/admin/venues/{id}/seat-maps` | EVENT_MANAGER+ | create DRAFT version |
| POST | `/admin/seat-maps/{id}/seats:bulk` | EVENT_MANAGER+ | array seats |
| POST | `/admin/seat-maps/{id}/activate` | EVENT_MANAGER+ | archive previous ACTIVE |
| GET | `/admin/seat-maps/{id}` | EVENT_MANAGER+ | map + seats |

## Admin — Events

| Method | Path | Role | Notes |
| --- | --- | --- | --- |
| POST | `/admin/events` | EVENT_MANAGER+ | DRAFT |
| PATCH | `/admin/events/{id}` | EVENT_MANAGER+ | |
| POST | `/admin/events/{id}/sessions` | EVENT_MANAGER+ | |
| POST | `/admin/sessions/{id}/tiers` | EVENT_MANAGER+ | |
| PUT | `/admin/sessions/{id}/seat-tiers` | EVENT_MANAGER+ | map seats→tier |
| POST | `/admin/events/{id}/publish` | EVENT_MANAGER+ | materialize |
| POST | `/admin/events/{id}/unpublish` | EVENT_MANAGER+ | |

### Publish response errors

| Code | Meaning |
| --- | --- |
| `NO_ACTIVE_BANK_ACCOUNT` | thiếu bank |
| `SEATS_WITHOUT_TIER` | ghế bán thiếu tier |
| `INVALID_SALES_WINDOW` | sales window không hợp lệ |

## Admin — Promotions & banks

| Method | Path | Role |
| --- | --- | --- |
| POST/GET/PATCH | `/admin/promotions` | EVENT_MANAGER+ |
| POST/GET/PATCH | `/admin/bank-accounts` | ORG_ADMIN+ |

## Public catalog

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/events?query&city&from&to&page` | public |
| GET | `/events/{slug}` | public |

### `GET /events/{slug}` response (sketch)

```json
{
  "slug": "hoa-am-2026",
  "title": "Hòa Âm 2026",
  "city": "Hà Nội",
  "description": "...",
  "sessions": [
    {
      "id": "uuid",
      "startsAt": "2026-11-01T19:00:00+07:00",
      "tiers": [
        { "id": "uuid", "name": "VIP", "unitPriceCents": 150000000 }
      ]
    }
  ]
}
```

Giá VND lưu cents = đồng (integer); `150000000` = 1.500.000 VND nếu team chọn scale 100 — **chốt MVP: `unit_price_cents` = số đồng VND (không nhân 100)** để tránh nhầm. Đổi tên field API thành `unitPriceVnd` trong OpenAPI chính thức.

**Chốt:** API public dùng `unitPriceVnd` (integer VND). DB cột `unit_price_cents` hiểu là integer VND (legacy name); migration comment ghi rõ.
