// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.command.AddSessionHandler;
import com.nexaticket.catalog.application.command.AddTicketTypeHandler;
import com.nexaticket.catalog.application.command.AddZoneHandler;
import com.nexaticket.catalog.application.command.CancelEventHandler;
import com.nexaticket.catalog.application.command.CreateEventHandler;
import com.nexaticket.catalog.application.command.CreateVenueHandler;
import com.nexaticket.catalog.application.command.EditSessionHandler;
import com.nexaticket.catalog.application.command.EditTicketTypeHandler;
import com.nexaticket.catalog.application.command.PublishEventHandler;
import com.nexaticket.catalog.application.command.UnpublishEventHandler;
import com.nexaticket.catalog.application.command.UpdateEventHandler;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.catalog.interfaces.rest.AdminCatalogController;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Cửa vai trò của khu vực quản trị với {@link CatalogAccess} và truy vấn THẬT, chỉ kho dữ liệu là
 * mock. Nằm ngoài gói {@code interfaces} vì cần chạm tới cổng của domain (kho) — ArchitectureTest
 * cấm tầng interfaces làm vậy.
 */
@WebMvcTest(controllers = AdminCatalogController.class)
@Import({CatalogAccess.class, AdminCatalogQuery.class, CreateVenueHandler.class})
class AdminRolesTest extends WebSliceBase {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-a333-333333333333");
    private static final String BASE = "/v1/organizations/" + ORG;

    @MockitoBean
    VenueRepository venues;

    @MockitoBean
    EventRepository events;

    @MockitoBean
    AddZoneHandler addZone;

    @MockitoBean
    CreateEventHandler createEvent;

    @MockitoBean
    UpdateEventHandler updateEvent;

    @MockitoBean
    AddSessionHandler addSession;

    @MockitoBean
    AddTicketTypeHandler addTicketType;

    @MockitoBean
    PublishEventHandler publishEvent;

    @MockitoBean
    UnpublishEventHandler unpublishEvent;

    @MockitoBean
    EditSessionHandler editSession;

    @MockitoBean
    EditTicketTypeHandler editTicketType;

    @MockitoBean
    CancelEventHandler cancelEvent;

    @MockitoBean
    CatalogQueries queries;

    private String as(Role role) {
        return bearer(tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), role), false));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Role.class,
            names = {"CHECKIN_STAFF", "CUSTOMER"})
    @DisplayName("vai trò không có CATALOG_MANAGE: 403 ở đường đọc lẫn ghi, không chạm kho")
    void thieu_quyen(Role role) throws Exception {
        String token = as(role);

        mvc.perform(get(BASE + "/venues").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post(BASE + "/venues")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"city\":\"B\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/events/" + EVENT).header("Authorization", token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(venues, events);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = Role.class,
            names = {"EVENT_MANAGER", "ORG_ADMIN", "ORG_OWNER"})
    @DisplayName("vai trò có CATALOG_MANAGE: đọc 200, tạo địa điểm 201 và lưu vào kho")
    void du_quyen(Role role) throws Exception {
        java.util.concurrent.atomic.AtomicReference<com.nexaticket.catalog.domain.model.Venue> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
                    saved.set(inv.getArgument(0));
                    return null;
                })
                .when(venues)
                .save(any());
        when(venues.findAllByOrganization(ORG))
                .thenAnswer(inv -> saved.get() == null ? List.of() : List.of(saved.get()));

        mvc.perform(get(BASE + "/venues").header("Authorization", as(role))).andExpect(status().isOk());
        mvc.perform(post(BASE + "/venues")
                        .header("Authorization", as(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"city\":\"B\"}"))
                .andExpect(status().isCreated());
        verify(venues).save(any());
    }

    @Test
    @DisplayName("khách thuộc tổ chức khác không đọc được sự kiện của tổ chức này: 404, kho không bị hỏi")
    void tenant_khac() throws Exception {
        String stranger = bearer(tokenFor(
                UUID.randomUUID(),
                Map.of(TenantId.of(UUID.fromString("22222222-2222-4222-a222-222222222222")), Role.ORG_OWNER),
                false));

        mvc.perform(get(BASE + "/events/" + EVENT).header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/events/" + EVENT).header("Authorization", stranger))
                .andExpect(status().isNotFound());
        verify(events, never()).findByIdForOrganization(any(), any());
    }
}
