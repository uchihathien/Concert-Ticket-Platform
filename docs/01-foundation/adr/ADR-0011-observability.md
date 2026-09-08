# ADR-0011: OpenTelemetry và SLO vận hành

**Status:** Accepted

Trace HTTP, database, Redis, outbox và consumer bằng OpenTelemetry; tập trung log structured có correlation ID. SLO MVP: API availability 99.9%, p95 seat-hold dưới 300 ms và RPO không quá 5 phút. Alert cho hold error, payment webhook failure, queue lag, oversell invariant và check-in rejection spike.
