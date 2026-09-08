# ADR-0009: QR ticket opaque và có chữ ký

**Status:** Accepted

QR ticket chứa token opaque, signed và `jti` duy nhất; không chứa PII hay ticket ID đoán được. Backend xác thực chữ ký, expiry và trạng thái ticket trước check-in. `jti` có unique constraint.
