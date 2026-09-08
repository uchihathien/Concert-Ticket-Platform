# CI/CD & environments

## Environments

| Env | Mục đích |
| --- | --- |
| local | Compose: PG, Redis, RabbitMQ, Keycloak, Mailhog |
| staging | Giống prod thu nhỏ; SePay sandbox; load test |
| production | Soft launch tuần 13 |

## Pipeline (GitHub Actions gợi ý)

1. Lint / format (Java Spotless, ESLint, Prettier).
2. Unit tests.
3. Integration tests Testcontainers (PG + Redis).
4. Build Docker images; push on `main`.
5. Deploy staging (manual approve tuần đầu, rồi auto).
6. Smoke: `/actuator/health`, login OIDC, migration version.

## Observability baseline (tuần 3)

- OpenTelemetry → collector → Tempo/Jaeger + Prometheus metrics.
- Structured JSON logs + `correlation_id`.
- Alerts stub: hold error rate, webhook 5xx, consumer lag.

## Secrets

- SePay secret, DB, Keycloak admin, QR signing key: secret manager / env — không commit.
- `.env.example` chỉ placeholder.

## Exit gate checklist

- [ ] `main` CI xanh trên PR mẫu.
- [ ] Staging URL ổn định; migration up/down smoke.
- [ ] OTel trace một request HTTP → DB span.
- [ ] Backup/restore PG drill ghi biên bản (RPO).
