// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.storage;

import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Kho vật thể tương thích S3 — MinIO khi chạy local, S3 thật ở production.
 *
 * <p>Cùng một giao thức, khác endpoint. Nhờ vậy không có đường mã riêng cho môi trường phát triển,
 * và thứ chạy trên máy lập trình viên đi đúng con đường mà production đi.
 */
@Component
public class S3ObjectStorageAdapter implements ObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageAdapter.class);

    /**
     * Hạn của URL tải lên.
     *
     * <p>Đủ để tải một ảnh 8 MB trên đường truyền chậm, đủ ngắn để một URL lọt ra ngoài không còn
     * dùng được vào hôm sau. URL này cho phép <b>ghi</b> vào kho, nên nó là thứ có giá trị.
     */
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(5);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final MediaProperties properties;

    public S3ObjectStorageAdapter(MediaProperties properties) {
        this.properties = properties;

        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        var region = Region.of(properties.region());
        // Đường dẫn kiểu path (`host/bucket/key`) chứ không phải kiểu virtual-host
        // (`bucket.host/key`): MinIO ở local chạy trên một địa chỉ IP hoặc tên container, và tên
        // bucket không gắn được vào trước một thứ không phải tên miền.
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(s3Config)
                .build();

        // Presigner ký bằng endpoint CÔNG KHAI, không phải endpoint nội bộ: chữ ký bao gồm cả tên
        // host, nên ký bằng `http://minio:9000` rồi đưa cho trình duyệt sẽ cho ra một URL vừa
        // không phân giải được vừa sai chữ ký.
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(properties.publicBaseUrl()))
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(s3Config)
                .build();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType) {
        // `contentType` đi vào chữ ký: trình duyệt gửi kiểu khác thì kho vật thể từ chối. Đây là
        // chốt chặn duy nhất chạy TRƯỚC khi byte được ghi.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(UPLOAD_TTL)
                .putObjectRequest(put)
                .build());

        // `URL.toURI()` khai ném URISyntaxException, nhưng URL này do SDK vừa dựng từ chính
        // endpoint ta cấu hình — nó không thể sai cú pháp. `URI.create` để ngoại lệ ấy không lan
        // ra khắp chữ ký của cổng chỉ vì một trường hợp không xảy ra.
        return new PresignedUpload(URI.create(presigned.url().toString()), key, presigned.expiration());
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // MinIO trả 404 cho HEAD trên khoá không tồn tại mà KHÔNG gói thành NoSuchKeyException
            // — vì phản hồi HEAD không có body để SDK đọc mã lỗi ra. Không bắt riêng ở đây thì
            // "chưa ai tải lên" nổi lên thành lỗi 500.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            // Nuốt có chủ đích: đường gọi duy nhất là dọn một vật thể vừa bị từ chối, và ở đó lỗi
            // thật đã được trả cho người dùng rồi. Ném thêm ở đây chỉ thay một thông báo rõ ràng
            // ("ảnh quá nặng") bằng một cái 500. Log lại để rác không tích tụ trong im lặng.
            log.warn("Không xoá được vật thể {}: {}", key, e.getMessage());
        }
    }

    @Override
    public URI publicUrl(String key) {
        return URI.create("%s/%s/%s".formatted(trimSlash(properties.publicBaseUrl()), properties.bucket(), key));
    }

    @Override
    public boolean isOwnUrl(String url) {
        return url != null
                && url.startsWith("%s/%s/".formatted(trimSlash(properties.publicBaseUrl()), properties.bucket()));
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
