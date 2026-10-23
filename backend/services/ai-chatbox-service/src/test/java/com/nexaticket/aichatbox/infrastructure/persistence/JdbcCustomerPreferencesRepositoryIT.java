// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.CustomerPreferences;
import com.nexaticket.aichatbox.domain.port.CustomerPreferencesRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Mảng text[], upsert và xoá cứng chạy trên Postgres thật (V0110). */
class JdbcCustomerPreferencesRepositoryIT extends AiChatboxTestBase {

    private static final Instant T1 = Instant.parse("2026-10-23T10:00:00Z");

    @Autowired
    CustomerPreferencesRepository repository;

    @Test
    void luu_doc_lai_mang_va_ngan_sach_null() {
        UUID user = UUID.randomUUID();
        repository.save(
                new CustomerPreferences(user, List.of("nhac-song", "the-thao"), List.of("Đà Nẵng"), null, null, T1));

        CustomerPreferences read = repository.find(user).orElseThrow();
        assertThat(read.categories()).containsExactly("nhac-song", "the-thao");
        assertThat(read.cities()).containsExactly("Đà Nẵng");
        assertThat(read.maxBudgetVnd()).isNull();
        assertThat(read.note()).isNull();
        assertThat(read.updatedAt()).isEqualTo(T1);
    }

    @Test
    void luu_lan_hai_ghi_de_dong_cu() {
        UUID user = UUID.randomUUID();
        repository.save(new CustomerPreferences(user, List.of("nhac-song"), List.of(), 300_000L, "a", T1));
        repository.save(new CustomerPreferences(
                user, List.of("san-khau"), List.of("Huế", "Hà Nội"), 900_000L, "b", T1.plusSeconds(60)));

        CustomerPreferences read = repository.find(user).orElseThrow();
        assertThat(read.categories()).containsExactly("san-khau");
        assertThat(read.cities()).containsExactly("Huế", "Hà Nội");
        assertThat(read.maxBudgetVnd()).isEqualTo(900_000L);
        assertThat(read.note()).isEqualTo("b");
    }

    @Test
    void xoa_cung_va_khong_dung_dong_khach_khac() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        repository.save(new CustomerPreferences(user, List.of("hoi-thao"), List.of(), null, null, T1));
        repository.save(new CustomerPreferences(other, List.of("the-thao"), List.of(), null, null, T1));

        assertThat(repository.delete(user)).isTrue();
        assertThat(repository.delete(user)).isFalse();
        assertThat(repository.find(user)).isEmpty();
        assertThat(repository.find(other)).isPresent();
    }

    @Test
    void rang_buoc_bang_chan_ghi_chu_qua_dai_va_qua_nhieu_thanh_pho() {
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() ->
                        repository.save(new CustomerPreferences(user, List.of(), List.of(), null, "x".repeat(201), T1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.save(
                        new CustomerPreferences(user, List.of(), List.of("A", "B", "C", "D"), null, null, T1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
