// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.MediaProperties;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.ObjectStoragePort;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Cấp lượt tải ảnh lên, đặt ảnh sơ đồ ghế, và những đường biên của chính sách URL ảnh mà
 * {@code PosterUrlPolicyTest} chưa chạm tới.
 *
 * <p>Kho vật thể là mock: không MinIO, không mạng. Điều cần chứng minh là <b>kiểu nội dung đi vào
 * chữ ký</b> chỉ có thể là một trong ba kiểu ảnh raster — SVG, HTML hay bất cứ thứ gì chạy được
 * trong trình duyệt đều không bao giờ tới được {@code presignUpload}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaUseCasesTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final String OWN_PREFIX = "https://cdn.nexaticket.vn/nexaticket-media/";
    private static final Instant EXPIRES = Instant.parse("2026-10-22T03:05:00Z");

    @Mock
    ObjectStoragePort storage;

    @Mock
    CatalogAccess access;

    @Mock
    VenueRepository venues;

    @Mock
    EventRepository events;

    private final MediaProperties properties = new MediaProperties(
            "http://minio:9000",
            "https://cdn.nexaticket.vn",
            "nexaticket-media",
            "k",
            "s",
            "us-east-1",
            8L * 1024 * 1024,
            List.of("image/jpeg", "image/png", "image/webp"),
            false);

    @BeforeEach
    void khoGia() {
        when(storage.presignUpload(anyString(), anyString()))
                .thenAnswer(inv -> new ObjectStoragePort.PresignedUpload(
                        URI.create("https://cdn.nexaticket.vn/upload/" + inv.getArgument(0)),
                        inv.getArgument(0),
                        EXPIRES));
        when(storage.publicUrl(anyString())).thenAnswer(inv -> URI.create(OWN_PREFIX + inv.getArgument(0)));
        when(storage.isOwnUrl(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).startsWith(OWN_PREFIX));
    }

    @Nested
    @DisplayName("Cấp lượt tải ảnh bìa")
    class CapLuotTai {

        private PosterUploadUseCase useCase() {
            return new PosterUploadUseCase(storage, properties, access);
        }

        @Test
        @DisplayName("ảnh PNG: khoá mang id tổ chức, tên ngẫu nhiên và đuôi đúng kiểu; trả kèm trần kích thước")
        void anh_png() {
            PosterUploadUseCase.Ticket ticket = useCase().issue(ORG, "image/png");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(storage).presignUpload(key.capture(), eq("image/png"));
            assertThat(key.getValue()).matches("posters/" + ORG + "/[0-9a-f-]{36}\\.png");
            assertThat(ticket.publicUrl()).isEqualTo(OWN_PREFIX + key.getValue());
            assertThat(ticket.contentType()).isEqualTo("image/png");
            assertThat(ticket.maxBytes()).isEqualTo(8L * 1024 * 1024);
            assertThat(ticket.expiresAt()).isEqualTo(EXPIRES);
        }

        @Test
        @DisplayName("kiểu viết hoa và có khoảng trắng được chuẩn hoá trước khi ký")
        void chuan_hoa_kieu() {
            useCase().issue(ORG, "  IMAGE/JPEG ");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(storage).presignUpload(key.capture(), eq("image/jpeg"));
            assertThat(key.getValue()).endsWith(".jpg");
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "image/svg+xml",
                    "text/html",
                    "application/xhtml+xml",
                    "application/javascript",
                    "application/x-msdownload",
                    "image/gif",
                    "image/png; charset=utf-8",
                    "image/png\r\nX-Amz-Acl: public-read-write",
                    "image/*",
                    ""
                })
        @DisplayName("kiểu không nằm trong danh sách trắng: 422 POSTER_URL_INVALID và không ký gì")
        void kieu_khong_cho_phep(String contentType) {
            assertThatThrownBy(() -> useCase().issue(ORG, contentType))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.POSTER_URL_INVALID);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(422);
                    });
            verify(storage, never()).presignUpload(anyString(), anyString());
        }

        @Test
        @DisplayName("kiểu null: 422, không NullPointerException")
        void kieu_null() {
            assertThatThrownBy(() -> useCase().issue(ORG, null)).isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("không đủ quyền: không ký URL nào")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertThatThrownBy(() -> useCase().issue(ORG, "image/png"))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.Common.FORBIDDEN));
            verify(storage, never()).presignUpload(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Chính sách URL ảnh — đường biên")
    class DuongBien {

        private PosterUrlPolicy policy() {
            return new PosterUrlPolicy(storage, properties);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "javascript:alert(1)",
                    "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
                    "file:///etc/passwd",
                    "ftp://cdn.example/a.jpg",
                    "//cdn.example/a.jpg",
                    "relative/path.jpg"
                })
        @DisplayName("scheme không phải https: 422, không chạm kho")
        void scheme_nguy_hiem(String url) {
            assertThatThrownBy(() -> policy().validate(url))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.POSTER_URL_INVALID));
            verify(storage, never()).stat(anyString());
        }

        @Test
        @DisplayName("URL sai cú pháp: 422 chứ không URISyntaxException")
        void sai_cu_phap() {
            assertThatThrownBy(() -> policy().validate("https://cdn.example/a b<>.jpg"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.POSTER_URL_INVALID));
        }

        @Test
        @DisplayName("vật thể của mình nhưng kiểu thật là text/html: từ chối VÀ xoá")
        void html_gia_anh() {
            String key = "posters/" + ORG + "/x.png";
            when(storage.stat(key)).thenReturn(Optional.of(new ObjectStoragePort.StoredObject(100, "text/html")));

            assertThatThrownBy(() -> policy().validate(OWN_PREFIX + key)).isInstanceOf(ApiException.class);
            verify(storage).delete(key);
        }

        @Test
        @DisplayName("ảnh đúng bằng trần kích thước vẫn được nhận; vượt một byte thì 413")
        void bien_kich_thuoc() {
            String ok = "posters/" + ORG + "/ok.jpg";
            String big = "posters/" + ORG + "/big.jpg";
            when(storage.stat(ok))
                    .thenReturn(Optional.of(new ObjectStoragePort.StoredObject(8L * 1024 * 1024, "image/jpeg")));
            when(storage.stat(big))
                    .thenReturn(Optional.of(new ObjectStoragePort.StoredObject(8L * 1024 * 1024 + 1, "image/jpeg")));

            assertThat(policy().validate(OWN_PREFIX + ok)).isEqualTo(OWN_PREFIX + ok);
            assertThatThrownBy(() -> policy().validate(OWN_PREFIX + big))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.POSTER_TOO_LARGE);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(413);
                    });
        }

        @Test
        @DisplayName("khoảng trắng hai đầu bị cắt trước khi kiểm và trước khi lưu")
        void cat_khoang_trang() {
            String key = "posters/" + ORG + "/ok.webp";
            when(storage.stat(key)).thenReturn(Optional.of(new ObjectStoragePort.StoredObject(10, "image/webp")));

            assertThat(policy().validate("  " + OWN_PREFIX + key + "\n")).isEqualTo(OWN_PREFIX + key);
        }
    }

    @Nested
    @DisplayName("Ảnh sơ đồ ghế")
    class AnhSoDo {

        private SeatMapImageUseCase useCase() {
            return new SeatMapImageUseCase(venues, events, new PosterUrlPolicy(storage, properties), access);
        }

        @Test
        @DisplayName("null nghĩa là gỡ ảnh: lưu chuỗi rỗng, không phải giữ nguyên")
        void null_la_go_anh() {
            UUID venueId = UUID.randomUUID();
            when(venues.updateSeatMapImage(ORG, venueId, "")).thenReturn(1);

            useCase().setForVenue(ORG, venueId, null);

            verify(venues).updateSeatMapImage(ORG, venueId, "");
        }

        @Test
        @DisplayName("địa điểm không thuộc tổ chức (0 dòng bị sửa): 404 VENUE_NOT_FOUND")
        void dia_diem_khong_thuoc() {
            UUID venueId = UUID.randomUUID();
            when(venues.updateSeatMapImage(any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> useCase().setForVenue(ORG, venueId, ""))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.VENUE_NOT_FOUND));
        }

        @Test
        @DisplayName("sự kiện không thuộc tổ chức: 404 EVENT_NOT_FOUND")
        void su_kien_khong_thuoc() {
            when(events.updateSeatMapImage(any(), any(), any())).thenReturn(0);

            assertThatThrownBy(() -> useCase().setForEvent(ORG, UUID.randomUUID(), " "))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(CatalogErrorCode.EVENT_NOT_FOUND));
        }

        @Test
        @DisplayName("đường dẫn ngoài khi cấu hình cấm: 422 và không ghi gì")
        void duong_dan_ngoai() {
            assertThatThrownBy(() -> useCase().setForEvent(ORG, UUID.randomUUID(), "https://evil.example/map.png"))
                    .isInstanceOf(ApiException.class);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("không đủ quyền: không ghi gì")
        void khong_du_quyen() {
            doThrow(ApiException.forbidden("no")).when(access).requireCatalogManager(ORG);

            assertThatThrownBy(() -> useCase().setForVenue(ORG, UUID.randomUUID(), ""))
                    .isInstanceOf(ApiException.class);
            verifyNoInteractions(venues);
        }
    }
}
