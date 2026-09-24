// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Factory HTTP <b>có pool kết nối</b> cho mọi lời gọi ra ngoài của service này.
 *
 * <h2>Vì sao không dùng {@code SimpleClientHttpRequestFactory}</h2>
 *
 * <p>Nó dựa trên {@code HttpURLConnection} và <b>không pool gì cả</b>: mỗi request mở một kết nối
 * TCP mới rồi đóng. Với phần lớn service của hệ thống thì đó là đánh đổi không đáng bàn — vài lời
 * gọi nội bộ mỗi phút trong cùng một mạng docker.
 *
 * <p>Ở đây thì khác, và khác về bậc: <b>mỗi lượt chat</b> gọi ít nhất một lần nhúng cộng một tới
 * bốn lần gọi mô hình, cộng lời gọi tool sang ordering. Với nhà cung cấp trả phí thì mỗi lời gọi
 * ấy là một lần bắt tay TLS đầy đủ tới một host ở xa — thường tốn nhiều hơn chính thời gian xử lý
 * của lời gọi. Pool lại thì bắt tay xảy ra một lần cho hàng trăm lượt.
 *
 * <p>{@code JdkClientHttpRequestFactory} bọc {@link HttpClient} của JDK: pool sẵn, giữ kết nối
 * sống, và nói được HTTP/2 với nhà cung cấp nào hỗ trợ.
 *
 * <h2>Hai hạn, hai ý nghĩa khác nhau</h2>
 *
 * <p>Hạn <b>kết nối</b> đo việc bắt được tay hay không — hỏng ở đây là hạ tầng, và nó phải ngắn.
 * Hạn <b>đọc</b> đo việc bên kia trả lời nhanh hay chậm — với một mô hình chạy trên CPU thì chậm
 * là bình thường, nên nó phải rộng. Gộp hai thứ vào một con số là ép chọn giữa "báo hỏng quá muộn"
 * và "cắt ngang một câu trả lời đang được sinh ra".
 */
public final class PooledHttpFactory {

    private PooledHttpFactory() {}

    public static ClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Mặc định của JDK là ALWAYS redirect. Không theo chuyển hướng ở đây: một endpoint
                // mô hình trả 30x là dấu hiệu cấu hình sai địa chỉ, và đi theo nó âm thầm nghĩa là
                // token của khách cùng nội dung hội thoại đi tới một host không ai khai.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
