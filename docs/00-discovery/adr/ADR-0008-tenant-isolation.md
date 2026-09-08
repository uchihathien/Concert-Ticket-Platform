# ADR-0008: Organizer là tenant độc lập

**Status:** Accepted

Mỗi organizer tương ứng một `organization` tenant. Tenant context xuất phát từ authenticated membership, không tin `organization_id` do client gửi. Mọi query/read/write nghiệp vụ phải scope bằng tenant và được bao phủ bởi authorization/IDOR integration tests.

Platform admin vận hành cross-tenant qua permission riêng có audit log. Tenant isolation dùng service/repository guard ở MVP; xem xét PostgreSQL RLS sau khi threat model và vận hành đã được xác thực.
