// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Đặt ảnh bìa cho sự kiện mẫu rồi đẩy lên kho vật thể.
 *
 * <h2>Hai nguồn ảnh, ưu tiên ảnh chụp thật</h2>
 *
 * <p>Ảnh chụp thật ({@link DemoPosterPhotos}) đi trước, bìa tự vẽ ({@link PosterArtwork}) đỡ phía
 * sau. Thứ tự đó là vì demo tồn tại để cho người khác xem, và một dải màu vẽ bằng SVG không nói
 * được gì về việc hệ thống trông thế nào khi có ảnh thật — trong khi thứ bù lại cho ảnh thật thì
 * dễ mất: máy không có mạng, tường lửa chặn, host ngoài đổi đường dẫn.
 *
 * <p>Nên mỗi sự kiện thử ảnh chụp trước; tải không được thì <b>tụt xuống</b> bìa tự vẽ chứ không
 * bỏ trống, và lần khởi động sau lại thử ảnh chụp lần nữa (xem phần idempotent bên dưới).
 *
 * <h2>Vì sao tách khỏi {@link DemoCatalogSeeder}</h2>
 *
 * <p>Bộ dựng kia idempotent <b>theo slug</b>: sự kiện nào đã có thì bỏ qua nguyên cụm. Nếu phần
 * ảnh nằm trong đó thì lần chạy đầu không có MinIO sẽ để mọi sự kiện không ảnh <b>vĩnh viễn</b> —
 * bật MinIO lên rồi khởi động lại cũng không cứu được, vì sự kiện đã tồn tại nên cả cụm bị bỏ qua.
 *
 * <p>Tách ra thì điều kiện trở thành "sự kiện nào chưa có <b>đúng</b> ảnh của chính mình", và nó
 * <b>tự chữa</b> ở lần khởi động kế tiếp sau khi MinIO sẵn sàng.
 *
 * <h2>Tải ảnh không được chặn khởi động</h2>
 *
 * <p>36 tấm ảnh nhân vài trăm ms là hàng chục giây, và {@link ApplicationRunner} chạy khi cổng đã
 * mở nhưng trước khi ứng dụng báo "started" — nên chặn ở đây là chặn cả healthcheck của
 * {@code docker compose}, và một container bị đánh dấu unhealthy sẽ bị khởi động lại đúng lúc nó
 * đang làm việc dở. Vì vậy phần nặng chạy trên một luồng ảo.
 *
 * <h2>MinIO chưa chạy không được làm hỏng khởi động</h2>
 *
 * <p>Ảnh bìa là thứ tô điểm. Một máy phát triển chưa {@code docker compose up} phần hạ tầng vẫn
 * phải chạy được service, và giao diện đã có ảnh nền tạm cho sự kiện không ảnh. Nên mọi lỗi ở đây
 * chỉ ghi log rồi đi tiếp.
 */
@Component
@ConditionalOnProperty(prefix = "nexaticket.catalog", name = "demo-data", havingValue = "true")
// Chạy SAU DemoCatalogSeeder: nó cần sự kiện đã tồn tại trong database để gắn ảnh vào.
@Order(20)
public class DemoPosterSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPosterSeeder.class);

    /**
     * Hạn cho một lượt tải ảnh.
     *
     * <p>Tách kết nối khỏi đọc: một host không trả lời thì phải biết trong 5 giây, còn một tấm ảnh
     * 300 KB trên đường mạng chậm thì được phép mất tới 20 giây. Gộp hai con số thành một thì hoặc
     * mạng chậm bị cắt oan, hoặc một host im lặng giữ luồng này 20 giây mỗi tấm.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final MediaProperties media;
    private final boolean usePhotos;
    private final HttpClient http;

    public DemoPosterSeeder(
            JdbcTemplate jdbc,
            ObjectStoragePort storage,
            MediaProperties media,
            @Value("${nexaticket.catalog.demo-poster-photos:true}") boolean usePhotos) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.media = media;
        this.usePhotos = usePhotos;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Không đi theo chuyển hướng sang host khác: đích đến đã là một URL cụ thể trên
                // một CDN cụ thể, nên một cú 302 ở đây là dấu hiệu có gì đó chen vào đường.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<PosterTarget> targets;
        try {
            targets = targets();
        } catch (RuntimeException e) {
            // Cả lớp này là thứ tô điểm, nên KHÔNG có đường nào trong nó được phép giết service —
            // kể cả câu truy vấn mở màn. Đã có lần nó giết: một lỗi bind tham số ở đây làm
            // catalog không khởi động nổi, và dấu vết duy nhất là một stack trace JDBC ở giữa log
            // khởi động, không ai nghĩ tới bộ dựng ảnh mẫu.
            log.warn("Không đọc được danh sách sự kiện mẫu để đặt ảnh bìa ({})", e.toString());
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("demo-poster-seeder").start(() -> seed(targets));
    }

    private void seed(List<PosterTarget> targets) {
        int photos = 0;
        int drawn = 0;
        boolean photosFailed = false;

        for (PosterTarget target : targets) {
            try {
                Optional<byte[]> photo = photosFailed ? Optional.empty() : photo(target);
                if (photo.isEmpty()
                        && usePhotos
                        && DemoPosterPhotos.forSlug(target.slug()).isPresent()) {
                    // Tấm đầu tải không được là đủ để biết máy này không ra được Internet; 35 tấm
                    // nữa chỉ làm khởi động chậm thêm mười phút để rồi cùng ra một kết quả.
                    photosFailed = true;
                }

                String key;
                if (photo.isPresent()) {
                    key = "posters/demo/%s.jpg".formatted(target.slug());
                    storage.put(key, DemoPosterPhotos.CONTENT_TYPE, photo.get());
                    photos++;
                } else {
                    key = svgKey(target.slug());
                    String svg = PosterArtwork.svg(
                            target.title(), target.category(), target.city(), target.summary(), target.slug());
                    storage.put(key, MediaProperties.GENERATED_CONTENT_TYPE, svg.getBytes(StandardCharsets.UTF_8));
                    drawn++;
                }

                jdbc.update(
                        "UPDATE events SET poster_url = ? WHERE id = ?",
                        storage.publicUrl(key).toString(),
                        target.id());
            } catch (RuntimeException e) {
                // Lần đầu hỏng là đủ để biết kho vật thể chưa sẵn sàng; chín lần nữa chỉ làm log ồn.
                log.warn("Chưa đặt được ảnh bìa mẫu ({}). Giao diện dùng ảnh nền tạm.", e.toString());
                return;
            }
        }

        if (photos > 0) {
            log.info("Đã tải {} ảnh bìa thật cho sự kiện mẫu", photos);
        }
        if (drawn > 0) {
            log.info("Đã vẽ {} ảnh bìa cho sự kiện mẫu", drawn);
        }
    }

    /**
     * Byte ảnh chụp của một sự kiện, nếu lấy được.
     *
     * <p>Rỗng không phân biệt "sự kiện này không chọn ảnh" với "tải không được": cả hai đều dẫn tới
     * cùng một việc tiếp theo là vẽ bìa thay thế. Người gọi phân biệt khi cần bằng cách hỏi lại
     * {@link DemoPosterPhotos}.
     */
    private Optional<byte[]> photo(PosterTarget target) {
        if (!usePhotos) {
            return Optional.empty();
        }
        Optional<DemoPosterPhotos.Photo> photo = DemoPosterPhotos.forSlug(target.slug());
        if (photo.isEmpty()) {
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(photo.get().url())
                .timeout(READ_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("Ảnh bìa {} trả về HTTP {}, dùng bìa tự vẽ", target.slug(), response.statusCode());
                return Optional.empty();
            }
            // Trần kích thước dùng chung với ảnh do ban tổ chức tải lên: một vật thể vượt trần sẽ
            // bị chính sách ảnh bìa xoá đi sau đó, nên đẩy nó lên là làm việc để bỏ.
            if (response.body().length > media.maxBytes()) {
                log.warn("Ảnh bìa {} nặng {} byte, vượt trần — dùng bìa tự vẽ", target.slug(), response.body().length);
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (IOException e) {
            log.warn("Không tải được ảnh bìa cho sự kiện mẫu ({}). Dùng bìa tự vẽ.", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            // Đang tắt máy. Trả lại cờ ngắt rồi đi tiếp bằng bìa tự vẽ — không ném, vì ngoại lệ ở
            // đây sẽ bị hiểu thành "kho vật thể hỏng" và bỏ dở cả danh sách.
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String svgKey(String slug) {
        return "posters/demo/%s.svg".formatted(slug);
    }

    /**
     * Sự kiện mẫu chưa có <b>đúng</b> ảnh mà bộ dựng này muốn đặt cho nó.
     *
     * <p>So bằng địa chỉ đầy đủ, không so tiền tố. Lý do là chuyện đổi nguồn ảnh: một máy đã chạy
     * qua thời chỉ có bìa tự vẽ thì mọi sự kiện đều <b>đang</b> có ảnh trong kho của mình, nên một
     * phép kiểm "đã có ảnh của mình chưa" sẽ bỏ qua tất cả và ảnh chụp thật không bao giờ tới được
     * — muốn thấy nó phải xoá database, một cái giá không đáng.
     *
     * <p>Đổi lại phải nói rõ <b>ảnh nào là của bộ dựng này</b>: chỉ hai khoá {@code .jpg} và
     * {@code .svg} theo slug. Một ảnh khác nằm trong kho của mình là ảnh ban tổ chức tự tải lên cho
     * sự kiện mẫu ấy, và nó được giữ nguyên. Còn URL trỏ ra ngoài thì thay — dữ liệu mẫu bản trước
     * trỏ tới {@code picsum.photos}, những URL vừa không liên quan gì tới sự kiện vừa bị
     * {@code PosterUrlPolicy} từ chối khi production tắt ảnh ngoài.
     *
     * <p>Chỉ đụng tới slug nằm trong {@link DemoData}: sự kiện thật do ban tổ chức tạo không được
     * một bộ dựng dữ liệu mẫu ghi đè.
     */
    private List<PosterTarget> targets() {
        List<String> slugs =
                DemoData.EVENTS.stream().map(DemoCatalogSeeder.EventSpec::slug).toList();

        List<PosterTarget> rows = jdbc.query(
                """
                SELECT e.id, e.slug, e.title, e.category, e.summary, e.poster_url, v.city
                  FROM events e
                  JOIN venues v ON v.id = e.venue_id
                 WHERE e.slug = ANY (?)
                """,
                (rs, i) -> new PosterTarget(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("category"),
                        rs.getString("summary"),
                        rs.getString("city"),
                        rs.getString("poster_url")),
                // Ép sang Object: `query(sql, mapper, Object... args)` với ĐÚNG MỘT đối số là
                // một mảng sẽ bị Java trải phẳng thành 36 tham số cho một dấu `?` duy nhất, và
                // Postgres báo "column index is out of range: 2, number of columns: 1" — một thông
                // báo không gợi gì tới varargs. Đoạn cũ không gặp lỗi này chỉ vì nó truyền thêm
                // một tham số thứ hai, nên trình biên dịch không trải phẳng.
                (Object) slugs.toArray(String[]::new));

        return rows.stream().filter(this::needsPoster).toList();
    }

    private boolean needsPoster(PosterTarget target) {
        String current = target.posterUrl();
        if (current == null || current.isBlank()) {
            return true;
        }
        // Ảnh ngoài: thay. Kể cả khi nó mở được, nó vẫn là địa chỉ mà mình không kiểm soát.
        if (!storage.isOwnUrl(current)) {
            return true;
        }

        String jpg = storage.publicUrl("posters/demo/%s.jpg".formatted(target.slug()))
                .toString();
        String svg = storage.publicUrl(svgKey(target.slug())).toString();
        boolean ours = current.equals(jpg) || current.equals(svg);
        if (!ours) {
            // Ảnh trong kho của mình nhưng không phải khoá của bộ dựng: ban tổ chức đã tự tải lên.
            return false;
        }

        boolean wantPhoto = usePhotos && DemoPosterPhotos.forSlug(target.slug()).isPresent();
        return !current.equals(wantPhoto ? jpg : svg);
    }

    private record PosterTarget(
            UUID id, String slug, String title, String category, String summary, String city, String posterUrl) {}
}
