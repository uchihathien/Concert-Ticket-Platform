// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.application.media.PosterUrlPolicy;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import com.nexaticket.catalog.infrastructure.storage.S3ObjectStorageAdapter;
import com.nexaticket.platform.web.error.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Vòng đời một tấm ảnh bìa, chạy trên kho S3 thật.
 *
 * <h2>Vì sao cần kho thật chứ không phải cổng giả</h2>
 *
 * <p>{@code PosterUrlPolicyTest} đã kiểm luật bằng cổng giả, và nó đúng việc của nó: luật là logic
 * Java. Nhưng ba thứ ở đây <b>không</b> nằm trong Java:
 *
 * <ul>
 *   <li>kho có trả lại đúng {@code content-type} đã ghi lúc tải lên không — cả chính sách ảnh bìa
 *       dựa vào câu trả lời ấy;
 *   <li>{@code HEAD} trên một khoá không tồn tại có được nhận ra là "không có" không, hay ném ra
 *       một ngoại lệ khác mà adapter không bắt (MinIO từng trả HEAD không kèm body, và SDK không
 *       phân tích được — đúng chỗ {@code S3ObjectStorageAdapter.stat} phải bắt hai loại ngoại lệ);
 *   <li>ảnh có <b>đọc được mà không cần khoá</b> không — khách xem trang không có khoá nào.
 * </ul>
 *
 * <h2>Vì sao SeaweedFS chứ không phải MinIO</h2>
 *
 * <p>Ảnh container của MinIO đã bị gỡ khỏi mọi registry công khai. Xem ghi chú trong
 * {@code deploy/compose/infra.yml}.
 *
 * <h2>Vòng đời container</h2>
 *
 * <p>{@code @BeforeAll} / {@code @AfterAll} tự tay, không dùng {@code @Testcontainers} +
 * {@code @Container}: lớp này không dựng Spring context nên không dính vấn đề context cache mà
 * {@code PostgresSingleton} mô tả, và dừng hẳn container ở cuối thì không để lại gì cho lần chạy
 * sau.
 */
class PosterLifecycleIT {

    private static final String IMAGE = "chrislusf/seaweedfs:4.47";
    private static final String BUCKET = "nexaticket-media";
    private static final String KEY = "nexaticket";
    private static final String SECRET = "nexaticket-test-secret";

    @SuppressWarnings("resource") // Đóng trong @AfterAll.
    private static final GenericContainer<?> S3 = new GenericContainer<>(IMAGE)
            .withExposedPorts(9000)
            .withCommand(
                    "server",
                    "-dir=/data",
                    "-s3",
                    "-s3.port=9000",
                    "-s3.config=/etc/seaweed/s3.json",
                    "-s3.allowedOrigins=*",
                    "-s3.autoCreateBucket")
            .withCopyToContainer(
                    org.testcontainers.images.builder.Transferable.of(
                            """
                            {"identities":[
                              {"name":"anonymous","actions":["Read"]},
                              {"name":"app","credentials":[{"accessKey":"%s","secretKey":"%s"}],
                               "actions":["Admin","Read","Write","List","Tagging"]}
                            ]}
                            """
                                    .formatted(KEY, SECRET)),
                    "/etc/seaweed/s3.json")
            .waitingFor(Wait.forHttp("/status").forPort(9000).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    private static ObjectStoragePort storage;
    private static MediaProperties properties;

    @BeforeAll
    static void start() {
        try {
            S3.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Không start được kho S3 cho test. Cần Docker đang chạy — kiểm tra `docker info`.", e);
        }
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9000);
        properties = new MediaProperties(
                endpoint,
                endpoint,
                BUCKET,
                KEY,
                SECRET,
                "us-east-1",
                8L * 1024 * 1024,
                List.of("image/jpeg", "image/png", "image/webp"),
                false);
        storage = new S3ObjectStorageAdapter(properties);
    }

    @AfterAll
    static void stop() {
        S3.stop();
    }

    @Test
    @DisplayName("vẽ → đẩy lên → kiểm → đọc được không cần khoá")
    void vong_doi_day_du() throws Exception {
        String svg = PosterArtwork.svg("Đêm nhạc Trịnh & bạn bè", "nhac-song", "Huế", "dem-nhac-trinh");
        String key = "posters/demo/%s.svg".formatted(UUID.randomUUID());

        storage.put(key, MediaProperties.GENERATED_CONTENT_TYPE, svg.getBytes(StandardCharsets.UTF_8));

        // 1. Kho phải nhớ đúng kiểu đã ghi. Chính sách ảnh bìa hỏi đúng câu này, và trả lời sai thì
        //    nó XOÁ tấm ảnh rồi báo lỗi.
        ObjectStoragePort.StoredObject stored =
                storage.stat(key).orElseThrow(() -> new AssertionError("Kho không thấy vật thể vừa ghi"));
        assertThat(stored.contentType()).isEqualTo(MediaProperties.GENERATED_CONTENT_TYPE);
        assertThat(stored.sizeBytes()).isEqualTo(svg.getBytes(StandardCharsets.UTF_8).length);

        // 2. Chính sách chấp nhận ảnh hệ thống tự vẽ, và KHÔNG xoá nó.
        String url = storage.publicUrl(key).toString();
        PosterUrlPolicy policy = new PosterUrlPolicy(storage, properties);
        assertThat(policy.validate(url)).isEqualTo(url);
        assertThat(storage.stat(key)).isPresent();

        // 3. Khách xem trang không có khoá nào — ảnh phải tải được bằng một GET trần.
        HttpResponse<String> response = get(url);
        assertThat(response.statusCode()).isEqualTo(200);
        // Khẳng định theo MẢNH chứ không theo cả tiêu đề: tên dài được ngắt thành nhiều dòng
        // <text>, nên so cả chuỗi là so một thứ SVG cố ý không tạo ra. Chuyện xuống dòng đã có
        // PosterArtworkTest lo. Ở đây chỉ cần biết đúng nội dung ấy đi qua kho và quay về —
        // `&amp;` là bằng chứng nó không bị hỏng dọc đường.
        assertThat(response.body())
                .contains("<svg")
                .contains("Đêm nhạc Trịnh &amp;")
                .contains("bạn bè");
        assertThat(response.headers().firstValue("content-type")).hasValue(MediaProperties.GENERATED_CONTENT_TYPE);
    }

    @Test
    @DisplayName("khoá chưa có gì thì bị từ chối, không phải ném ngoại lệ lạ")
    void khoa_trong_bi_tu_choi() {
        // Đường này không cần ác ý: xin được URL tải lên rồi mạng rớt giữa chừng là đủ. `stat` phải
        // nhận ra "không có" — một HEAD 404 mà SDK không phân tích được sẽ nổ thành lỗi 500.
        String url = storage.publicUrl("posters/demo/khong-ton-tai.svg").toString();
        PosterUrlPolicy policy = new PosterUrlPolicy(storage, properties);

        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Chưa có ảnh");
    }

    @Test
    @DisplayName("ảnh quá cỡ bị từ chối VÀ bị dọn khỏi kho")
    void anh_qua_co_bi_don() throws Exception {
        String key = "posters/demo/%s.png".formatted(UUID.randomUUID());
        storage.put(key, "image/png", new byte[9 * 1024 * 1024]);

        PosterUrlPolicy policy = new PosterUrlPolicy(storage, properties);
        String url = storage.publicUrl(key).toString();

        assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
        // Phần quan trọng: vật thể không còn gì trỏ tới, để lại là để rác tích tụ mà không ai biết
        // đường dọn. Đây là thứ cổng giả không chứng minh được.
        assertThat(storage.stat(key)).isEmpty();
    }

    @Test
    @DisplayName("xoá rồi thì đọc ẩn danh cũng không còn thấy")
    void xoa_that_su_xoa() throws Exception {
        String key = "posters/demo/%s.svg".formatted(UUID.randomUUID());
        storage.put(
                key,
                MediaProperties.GENERATED_CONTENT_TYPE,
                "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8));
        String url = storage.publicUrl(key).toString();
        assertThat(get(url).statusCode()).isEqualTo(200);

        storage.delete(key);

        assertThat(storage.stat(key)).isEmpty();
        assertThat(get(url).statusCode()).isNotEqualTo(200);
    }

    /** GET trần, không khoá, không chữ ký — đúng như trình duyệt của khách. */
    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
