# Ảnh dùng chung cho cả 11 service Spring Boot.
#
# MỘT Dockerfile, không phải mười một. Các service khác nhau ở đúng một thứ — module Maven nào được
# đóng gói — nên khác biệt đó là tham số `SERVICE`, không phải mười một file gần giống hệt nhau chờ
# lệch nhau ở lần sửa thứ ba.
#
#   docker build -f backend/Dockerfile --build-arg SERVICE=identity-service -t nexaticket/identity .
#
# Bối cảnh build là GỐC repo, không phải `backend/`: Maven multi-module cần cả cây pom để phân giải
# module cha và các module platform.

# ---------------------------------------------------------------------------
# Tầng 1 — build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY backend/ backend/
WORKDIR /src/backend

ARG SERVICE
RUN test -n "$SERVICE" || (echo "Thiếu --build-arg SERVICE=<tên module>" && exit 1)

# Cache mount cho ~/.m2 thay vì thủ thuật "chép pom trước rồi go-offline".
#
# Không khai `# syntax=docker/dockerfile:1.7`: chỉ thị đó bắt BuildKit tải một ảnh frontend từ
# Docker Hub trước khi làm bất cứ việc gì, nên một trục trặc mạng làm hỏng cả bản build vì lý do
# chẳng liên quan gì tới mã nguồn. Frontend dựng sẵn trong Docker 23+ đã hiểu `--mount=type=cache`.
#
# Thủ thuật kia không chạy được ở đây: `COPY backend/services/*/pom.xml dest/` làm PHẲNG tên, nên
# mười một file cùng tên `pom.xml` ghi đè lên nhau và chỉ còn lại một. Cache mount đạt đúng mục
# đích — không tải lại phụ thuộc giữa các lần build — mà không phải dựng lại cây thư mục bằng tay.
#
# `-am` dựng cả các module platform mà service phụ thuộc vào.
# Bỏ test: chúng đã chạy ở CI với Testcontainers, và chạy lại ở đây đòi Docker-trong-Docker.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl "services/$SERVICE" -am -DskipTests package

# Bung jar thành từng tầng.
#
# Một jar Spring Boot 60MB mà 55MB là thư viện: chép nguyên khối nghĩa là mỗi lần sửa code đẩy lại
# cả 60MB lên registry. Bung theo tầng thì lớp thư viện được dùng lại giữa các bản build, và chỉ
# vài trăm KB code thật sự đổi.
#
# Đổi tên jar thành `application.jar` ngay tại đây. `extract` giữ nguyên tên có số phiên bản
# (`identity-service-0.1.0-SNAPSHOT.jar`), nên một ENTRYPOINT cố định sẽ hỏng với
# `Unable to access jarfile` — và hỏng ở tầng chạy, sau khi mọi thứ khác đã build xong. Cách kia là
# dùng `sh -c 'java -jar *.jar'`, nhưng nó thêm một shell vào giữa init và JVM: tín hiệu TERM lúc
# đó dừng ở shell và pod chết bằng SIGKILL sau thời gian chờ thay vì tắt êm.
RUN cd "services/$SERVICE/target" && \
    jar_file=$(ls *.jar | grep -v sources | head -1) && \
    java -Djarmode=tools -jar "$jar_file" extract --layers --destination /extracted && \
    mv /extracted/application/*.jar /extracted/application/application.jar

# ---------------------------------------------------------------------------
# Tầng 2 — chạy
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Không chạy bằng root: một lỗ hổng thực thi mã trong ứng dụng khi đó bị giới hạn trong quyền của
# user này thay vì quyền của cả container.
RUN addgroup -S app && adduser -S -G app app && apk add --no-cache wget

# Thứ tự chép = thứ tự đổi, từ ít đổi nhất tới hay đổi nhất. Sai thứ tự thì việc bung theo tầng ở
# trên mất hết tác dụng.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER app

# Mọi service đọc cổng từ `${PORT:...}`, mỗi cái một mặc định khác nhau (8090, 8091, 8092…). Ghim
# một cổng duy nhất bên trong container: bên ngoài đã có service discovery, còn mười một cổng khác
# nhau chỉ tạo thêm mười một chỗ để nhớ sai.
ENV PORT=8080
# Cổng quản trị tách riêng: actuator không đi chung cổng ứng dụng, nên ingress chỉ mở 8080 và
# /actuator/prometheus không với tới được từ internet. Xem `management.server.port`.
ENV MANAGEMENT_PORT=9090
EXPOSE 8080 9090

# Container có giới hạn bộ nhớ riêng, và JVM mặc định đọc bộ nhớ của cả máy chủ.
# `MaxRAMPercentage` bảo nó dùng đúng phần được cấp — thiếu dòng này thì pod bị OOMKill trong khi
# log của JVM hoàn toàn sạch, và người ta đi tìm rò rỉ bộ nhớ không tồn tại.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Profile `prod` bật ProductionHardening: service TỪ CHỐI khởi động nếu còn cấu hình dev.
ENV SPRING_PROFILES_ACTIVE=prod

# Probe của ứng dụng, không phải "cổng có mở không": cổng mở trước khi Flyway chạy xong và trước
# khi kết nối database sẵn sàng.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:9090/actuator/health/readiness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "application.jar"]
