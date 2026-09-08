# Milestone 00 — Discovery

## Mục tiêu

Chốt MVP Việt Nam, persona, tenant, invariant và artifact đủ để Foundation bắt đầu trong tuần 2.

## Artifacts

| Artifact | Mục đích |
| --- | --- |
| [Architecture blueprint](architecture-blueprint.md) | Kiến trúc + phạm vi |
| [SRS](srs.md) | Functional / NFR |
| [Product decisions](product-decisions.md) | PD-01…PD-12 đã đóng |
| [RBAC matrix](rbac-permission-matrix.md) | Roles & permissions |
| [Success metrics](success-metrics.md) | KPI / SLO |
| [Legal VN](legal-constraints-vn.md) | VAT, privacy, settlement |
| [Security baseline](security/README.md) | Controls |
| ADR-0001…0002, 0008, 0009 | Architecture / stack / tenant / QR |
| [System context](diagrams/system-context.mmd) | C4 context |
| [UI & UX](../ui/README.md) | Sitemap, screens, UI flows, states |
| [Mobile](../ui/mobile/README.md) | Mobile web, PWA, React Native |
| [Mobile screens](../ui/mobile/screens/README.md) | Spec chi tiết từng màn mobile |

## Exit gate (tuần 1)

- [ ] Stakeholders phê duyệt SRS + product decisions.
- [ ] Review UI sitemap + customer purchase flow + [mobile docs](../ui/mobile/README.md) (không cần hi-fi Figma).
- [ ] Invariant: server authoritative, no oversell, tenant-scoped, QR no PII.
- [ ] Hold 5 phút / payment window 15 phút được xác nhận nghiệp vụ.
- [ ] Go/No-go vào Foundation.

## Tuần 1 checklist

1. Review SRS + RBAC với PM/stakeholder (0.5 ngày).
2. Xác nhận SePay sandbox account + bank test (song song).
3. Chốt Keycloak (hoặc managed OIDC) hosting.
4. Sign-off ghi vào PR merge `product-decisions.md`.
