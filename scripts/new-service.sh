#!/usr/bin/env bash
# Tạo một service mới từ khuôn.
#
#   ./scripts/new-service.sh catalog 8091
#
# Sinh cấu trúc hexagonal, POM, application.yml, lớp Application, ArchitectureTest,
# và thêm module vào backend/services/pom.xml.
set -euo pipefail

NAME="${1:?Cần tên context, ví dụ: catalog}"
PORT="${2:-8099}"
ROOT="$(cd "$(dirname "$0")/../backend" && pwd)"
DIR="$ROOT/services/$NAME-service"
PKG="$ROOT/services/$NAME-service/src/main/java/com/nexaticket/$NAME"
CLASS="$(printf '%s' "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"

if [[ -d "$DIR" ]]; then
  echo "Đã tồn tại: $DIR" >&2
  exit 1
fi

echo "==> Tạo $NAME-service (cổng $PORT)"

mkdir -p "$PKG"/{domain/{model,port},application/command,infrastructure/persistence,interfaces/rest}
mkdir -p "$DIR/src/main/resources/db/migration/$NAME"
mkdir -p "$DIR/src/test/java/com/nexaticket/$NAME"

sed "s/__NAME__/$NAME/g" "$ROOT/services/_template/pom.xml.template" > "$DIR/pom.xml"

cat > "$PKG/${CLASS}Application.java" <<JAVA
// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.$NAME;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.$NAME", "com.nexaticket.platform"})
public class ${CLASS}Application {

    public static void main(String[] args) {
        SpringApplication.run(${CLASS}Application.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
JAVA

cat > "$DIR/src/main/resources/application.yml" <<YAML
spring:
  application:
    name: $NAME-service
  datasource:
    url: \${DB_URL:jdbc:postgresql://localhost:5432/${NAME}_db}
    username: \${DB_USER:$NAME}
    password: \${DB_PASSWORD:$NAME}
    hikari:
      maximum-pool-size: \${DB_POOL_SIZE:30}
      pool-name: $NAME-pool
  flyway:
    enabled: true
    locations: classpath:db/migration/platform,classpath:db/migration/$NAME
  rabbitmq:
    host: \${RABBITMQ_HOST:localhost}
    port: \${RABBITMQ_PORT:5672}
    username: \${RABBITMQ_USER:nexaticket}
    password: \${RABBITMQ_PASSWORD:nexaticket}
    publisher-confirm-type: correlated
    publisher-returns: true
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: \${OIDC_ISSUER:http://localhost:8081/realms/nexaticket}

server:
  port: \${PORT:$PORT}
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%X{correlationId:-}] %logger{36} - %msg%n"
YAML

cat > "$DIR/src/test/java/com/nexaticket/$NAME/ArchitectureTest.java" <<JAVA
// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.$NAME;

import com.nexaticket.platform.test.ArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.nexaticket.$NAME")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_thuan_nghiep_vu = ArchitectureRules.domainIsFrameworkFree();

    @ArchTest
    static final ArchRule chieu_phu_thuoc_hexagonal = ArchitectureRules.hexagonalLayers("com.nexaticket.$NAME");

    @ArchTest
    static final ArchRule khong_import_cheo_context = ArchitectureRules.noCrossContextImports("$NAME");
}
JAVA

# Đặt tên theo context ngay từ đầu, KHÔNG phải V0100__init.sql.
#
# Maven không xoá resource đã bị xoá khỏi target/classes. Nếu khuôn sinh ra
# V0100__init.sql rồi sau đó ta xoá đi để viết V0100__<name>.sql, bản cũ vẫn nằm
# trong target và Flyway sẽ chết với "Found more than one migration with version 0100"
# — một lỗi trỏ vào Flyway trong khi nguyên nhân là build cache. Dùng đúng tên cuối
# cùng ngay từ đầu thì không bao giờ có hai file.
cat > "$DIR/src/main/resources/db/migration/$NAME/V0100__$NAME.sql" <<SQL
-- Migration đầu tiên của $NAME-service.
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
SQL

# Thêm module vào backend/services/pom.xml ngay trước </modules>.
#
# Dùng sed chứ không dùng Python: trên Windows, PATH có sẵn một stub python3.exe của
# Microsoft Store — `command -v python3` báo là có nhưng chạy thì in ra "Python was not
# found" rồi thoát lỗi, nên việc dò interpreter cũng không cứu được. Ở đâu chạy được
# bash thì ở đó có sed.
POM="$ROOT/services/pom.xml"
if grep -q "<module>$NAME-service</module>" "$POM"; then
  echo "    (module đã có sẵn trong services/pom.xml)"
else
  sed -i "s|^  </modules>|    <module>$NAME-service</module>\n  </modules>|" "$POM"
  grep -q "<module>$NAME-service</module>" "$POM" \
    || { echo "Không chèn được module vào $POM — thêm tay giúp" >&2; exit 1; }
fi

echo "==> Xong: $DIR"
echo
echo "Việc còn lại:"
echo "  1. Thêm database + user cho $NAME vào deploy/compose/initdb/01-databases.sql (nếu chưa có)"
echo "  2. Thêm exchange/queue vào deploy/rabbitmq/topology.yaml"
echo "  3. Thêm route vào backend/services/api-gateway/src/main/resources/application.yml"
echo "  4. Thêm path filter cho $NAME vào .github/workflows/backend.yml"
