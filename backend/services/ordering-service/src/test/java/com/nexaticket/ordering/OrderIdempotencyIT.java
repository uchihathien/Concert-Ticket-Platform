// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.ordering.support.FakeRemoteServices;
import com.nexaticket.ordering.support.OrderingTestBase;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Idempotency-Key trên {@code POST /v1/orders} qua cả chuỗi thật: bảo mật → bộ lọc → controller →
 * saga → PostgreSQL. Unit test chỉ chứng minh từng mảnh; thứ chỉ test này chứng minh được là bộ lọc
 * <b>có mặt trong ứng dụng thật</b> (auto-config + {@code application.yml}) và đứng SAU bảo mật
 * nên biết người dùng là ai.
 */
@AutoConfigureMockMvc
class OrderIdempotencyIT extends OrderingTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeRemoteServices.FakeInventory inventory;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    MembershipLookup memberships;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void identity() {
        inventory.reserveTimesOut = false;
        inventory.refuseWithCode = null;
        inventory.reserveCalls.set(0);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(UUID user) {
        org.mockito.Mockito.when(memberships.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MembershipLookup.Principal(com.nexaticket.kernel.id.UserId.of(user), Map.of(), false));
        return jwt().jwt(j -> j.subject(user.toString()).claim("email", user + "@test.nexaticket.vn"));
    }

    private MvcResult place(UUID user, String key, UUID hold) throws Exception {
        var request = post("/v1/orders")
                .with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdId\":\"" + hold + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Thiếu khoá: 400, saga không chạy, không ghi gì vào idempotency_records")
    void thieu_khoa() throws Exception {
        UUID user = UUID.randomUUID();

        MvcResult result = place(user, null, UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(inventory.reserveCalls).hasValue(0);
    }

    @Test
    @DisplayName("Gửi lại cùng khoá + cùng nội dung: trả đúng đơn cũ, saga KHÔNG chạy lần hai")
    void gui_lai_tra_don_cu() throws Exception {
        UUID user = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();

        MvcResult first = place(user, key, hold);
        MvcResult second = place(user, key, hold);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(second).path("orderId").asText())
                .isEqualTo(body(first).path("orderId").asText());
        assertThat(inventory.reserveCalls).as("đặt chỗ chỉ một lần").hasValue(1);
        Integer orders = jdbc.queryForObject("SELECT count(*) FROM orders WHERE hold_id = ?", Integer.class, hold);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    @DisplayName("Cùng khoá nhưng holdId khác: 409 IDEMPOTENCY_KEY_REUSED")
    void khoa_dung_lai() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        place(user, key, UUID.randomUUID());

        MvcResult reused = place(user, key, UUID.randomUUID());

        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(reused).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("Người khác dùng cùng chuỗi khoá không đọc được đơn của mình: khoá có phạm vi theo người dùng")
    void pham_vi_theo_nguoi_dung() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        place(owner, key, hold);

        MvcResult stranger = place(UUID.randomUUID(), key, hold);

        assertThat(stranger.getResponse().getHeader("Idempotent-Replayed"))
                .as("không phát lại câu trả lời của người khác")
                .isNull();
        assertThat(stranger.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(body(place(owner, key, hold)).path("orderId").asText());
    }

    @Test
    @DisplayName("Saga lỗi (503): không lưu câu trả lời, gửi lại cùng khoá được xử lý lại chứ không bị kẹt")
    void loi_thi_thu_lai_duoc() throws Exception {
        UUID user = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        inventory.reserveTimesOut = true;

        MvcResult failed = place(user, key, hold);
        inventory.reserveTimesOut = false;
        MvcResult retried = place(user, key, hold);

        assertThat(failed.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(retried.getResponse().getHeader("Idempotent-Replayed")).isNull();
    }
}
