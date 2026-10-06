# Khuôn service

Không build trực tiếp thư mục này — nó không nằm trong `services/pom.xml`.

Tạo service mới bằng:

```bash
./scripts/new-service.sh catalog
```

Script sinh cấu trúc hexagonal, POM, `application.yml`, lớp Application, `ArchitectureTest`, và
thêm module vào `services/pom.xml`.

Cấu trúc mỗi service (tactical-ddd.md §1):

```
domain/          aggregate, entity, value object, domain event, port  ← KHÔNG biết framework
application/     command handler, query, saga; @Transactional ở đây
infrastructure/  adapter persistence, messaging, HTTP client, ACL
interfaces/      controller, event listener, scheduler
```
