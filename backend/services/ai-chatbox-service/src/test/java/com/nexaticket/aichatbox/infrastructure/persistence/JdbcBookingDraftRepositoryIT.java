// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.model.BookingDraft;
import com.nexaticket.aichatbox.domain.port.BookingDraftRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bản nháp đặt vé chạy trên Postgres thật (V0111): chip JSONB, ghi đè theo phiên, chỉ chủ phiên đọc được. */
class JdbcBookingDraftRepositoryIT extends AiChatboxTestBase {

    private static final Instant T1 = Instant.parse("2026-10-23T10:00:00Z");

    @Autowired
    BookingDraftRepository repository;

    @Test
    void luu_doc_lai_chip_va_ghi_de_theo_phien() {
        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID eventSession = UUID.randomUUID();
        repository.save(new BookingDraft(
                session,
                user,
                "dem-nhac-trinh",
                eventSession,
                null,
                2,
                "Bạn chọn hạng vé nào?",
                List.of(
                        new BookingDraft.Choice("VIP", "Hạng VIP", "1.800.000đ · còn 12 chỗ", null, null, "A", null),
                        new BookingDraft.Choice("Phổ thông", "Hạng Phổ thông", null, null, null, "GA", null)),
                BookingDraft.Step.ZONE,
                T1));

        BookingDraft read = repository.find(session, user).orElseThrow();
        assertThat(read.eventSlug()).isEqualTo("dem-nhac-trinh");
        assertThat(read.eventSessionId()).isEqualTo(eventSession);
        assertThat(read.quantity()).isEqualTo(2);
        assertThat(read.awaiting()).isEqualTo(BookingDraft.Step.ZONE);
        assertThat(read.choices()).extracting(BookingDraft.Choice::zoneCode).containsExactly("A", "GA");
        assertThat(read.choices().get(0).hint()).isEqualTo("1.800.000đ · còn 12 chỗ");
        assertThat(read.updatedAt()).isEqualTo(T1);

        repository.save(read.asking(BookingDraft.Step.QUANTITY, "Bạn lấy mấy vé?", List.of(), T1.plusSeconds(30)));
        assertThat(repository.find(session, user).orElseThrow().awaiting()).isEqualTo(BookingDraft.Step.QUANTITY);
    }

    @Test
    void phien_cua_nguoi_khac_khong_doc_khong_ghi_de_khong_xoa_duoc() {
        UUID session = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        repository.save(draft(session, owner));

        assertThat(repository.find(session, stranger)).isEmpty();
        repository.save(draft(session, stranger));
        repository.delete(session, stranger);

        assertThat(repository.find(session, owner)).isPresent();
        repository.delete(session, owner);
        assertThat(repository.find(session, owner)).isEmpty();
    }

    private static BookingDraft draft(UUID session, UUID user) {
        return new BookingDraft(session, user, null, null, null, null, null, List.of(), BookingDraft.Step.EVENT, T1);
    }
}
