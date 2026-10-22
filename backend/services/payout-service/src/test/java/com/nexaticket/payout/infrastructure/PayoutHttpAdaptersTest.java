// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.payout.domain.port.LedgerPort;
import com.nexaticket.payout.domain.port.OrganizationPort;
import com.nexaticket.payout.infrastructure.http.LedgerHttpAdapter;
import com.nexaticket.payout.infrastructure.http.OrganizationHttpAdapter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Hai adapter đọc của payout, và hai cách hỏng ngược nhau có chủ đích.
 *
 * <p>Sổ cái hỏng thì NÉM — trả 0 sẽ thành thông báo sai "không đủ số dư". Hồ sơ tổ chức hỏng thì trả
 * hồ sơ "không hoạt động, tên rỗng" — hai cổng chặn đóng lại, tức là mặc định không chi tiền.
 */
class PayoutHttpAdaptersTest {

    private static final String BASE = "http://dich-vu.test";
    private static final UUID ORG = UUID.fromString("c0000000-0000-4000-8000-000000000001");

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Nested
    @DisplayName("Sổ cái")
    class SoCai {

        @Test
        @DisplayName("200: đọc đủ ba số dư từ đúng đường dẫn nội bộ")
        void doc_so_du() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/balance"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(
                            "{\"availableVnd\":120000000,\"heldVnd\":30000000,\"receivableVnd\":0}",
                            MediaType.APPLICATION_JSON));

            LedgerPort.Balance balance = new LedgerHttpAdapter(client).balanceOf(ORG);

            assertThat(balance).isEqualTo(new LedgerPort.Balance(120_000_000L, 30_000_000L, 0L));
            server.verify();
        }

        @Test
        @DisplayName("500: ném LedgerUnavailableException, KHÔNG trả số dư 0")
        void loi_may_chu() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/balance"))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> new LedgerHttpAdapter(client).balanceOf(ORG))
                    .isInstanceOf(LedgerPort.LedgerUnavailableException.class)
                    .hasMessageContaining(ORG.toString());
        }

        @Test
        @DisplayName("404: cũng là không đọc được sổ cái, không phải số dư 0")
        void khong_tim_thay() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/balance"))
                    .andRespond(withResourceNotFound());

            assertThatThrownBy(() -> new LedgerHttpAdapter(client).balanceOf(ORG))
                    .isInstanceOf(LedgerPort.LedgerUnavailableException.class);
        }

        @Test
        @DisplayName("200 thân rỗng: ném, không đoán số dư")
        void than_rong() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/balance"))
                    .andRespond(withSuccess());

            assertThatThrownBy(() -> new LedgerHttpAdapter(client).balanceOf(ORG))
                    .isInstanceOf(LedgerPort.LedgerUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Hồ sơ tổ chức")
    class HoSo {

        @Test
        @DisplayName("ACTIVE: hoạt động, giữ tên pháp nhân")
        void hoat_dong() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/summary"))
                    .andRespond(withSuccess(
                            "{\"legalName\":\"Công ty TNHH ABC\",\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));

            OrganizationPort.Profile profile = new OrganizationHttpAdapter(client).profileOf(ORG);

            assertThat(profile).isEqualTo(new OrganizationPort.Profile(ORG, "Công ty TNHH ABC", true));
        }

        @Test
        @DisplayName("SUSPENDED: không hoạt động")
        void bi_dinh_chi() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/summary"))
                    .andRespond(withSuccess(
                            "{\"legalName\":\"Công ty TNHH ABC\",\"status\":\"SUSPENDED\"}",
                            MediaType.APPLICATION_JSON));

            assertThat(new OrganizationHttpAdapter(client).profileOf(ORG).active())
                    .isFalse();
        }

        @Test
        @DisplayName("Trạng thái viết thường: không coi là ACTIVE — so khớp chính xác")
        void trang_thai_viet_thuong() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/summary"))
                    .andRespond(withSuccess("{\"legalName\":\"X\",\"status\":\"active\"}", MediaType.APPLICATION_JSON));

            assertThat(new OrganizationHttpAdapter(client).profileOf(ORG).active())
                    .isFalse();
        }

        @Test
        @DisplayName("Identity lỗi 500: mặc định an toàn — không hoạt động, tên rỗng")
        void loi_may_chu() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/summary"))
                    .andRespond(withServerError());

            assertThat(new OrganizationHttpAdapter(client).profileOf(ORG))
                    .isEqualTo(new OrganizationPort.Profile(ORG, "", false));
        }

        @Test
        @DisplayName("Thân rỗng: mặc định an toàn")
        void than_rong() {
            server.expect(requestTo(BASE + "/internal/organizations/" + ORG + "/summary"))
                    .andRespond(withSuccess());

            assertThat(new OrganizationHttpAdapter(client).profileOf(ORG).active())
                    .isFalse();
        }
    }
}
