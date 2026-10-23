// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.preference;

import com.nexaticket.aichatbox.domain.model.CustomerPreferences;
import com.nexaticket.aichatbox.domain.port.CustomerPreferencesRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Sở thích mua vé của khách: ghi nhớ khi khách tự nói, đọc lại để gợi ý, xoá khi khách yêu cầu.
 *
 * <h2>Vì sao kiểm ở đây chứ không tin mô hình</h2>
 *
 * Tham số do mô hình điền. Một mô hình 7B sẽ có lúc điền "nhạc sống" thay cho {@code nhac-song},
 * "500k" thay cho 500000, hoặc chép nguyên câu khách có số điện thoại vào ghi chú. Lớp này là chỗ
 * quyết định cái gì được lưu: giá trị lạ bị bỏ và được báo lại trong {@link Remembered#ignored()} để
 * mô hình hỏi lại, thay vì ghi một dòng rác mà lượt sau đọc như sự thật.
 *
 * <h2>Gộp, không ghi đè</h2>
 *
 * Khách nói "mình thích nhạc sống" hôm nay và "cả thể thao nữa" lượt sau — dòng phải có cả hai. Thành
 * phố thì mới nhất đứng đầu (người ta chuyển nhà, đi công tác); ngân sách và ghi chú thì lần nói sau
 * thay lần trước.
 */
@Service
public class CustomerPreferenceUseCase {

    /** Khớp tham số {@code category} của findEvents — một danh sách, hai chỗ dùng. */
    public static final List<String> CATEGORIES = List.of("nhac-song", "san-khau", "the-thao", "hoi-thao");

    static final int MAX_CATEGORIES = 4;
    static final int MAX_CITIES = 3;
    static final int MAX_NOTE = 200;
    static final long MIN_BUDGET = 10_000L;
    static final long MAX_BUDGET = 200_000_000L;

    /** Số điện thoại (8 chữ số trở lên, có thể cách nhau) hoặc email: không lưu vào ghi chú. */
    private static final Pattern CONTACT = Pattern.compile("(\\d[\\s.\\-]?){8,}|[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    private final CustomerPreferencesRepository repository;
    private final Clock clock;

    public CustomerPreferenceUseCase(CustomerPreferencesRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Thứ mô hình gửi lên; mọi trường có thể null. */
    public record Update(List<String> categories, String city, Long maxBudgetVnd, String note) {}

    /** Bản đọc được của sở thích — dùng cho tool và cho REST, không lộ kiểu domain ra ngoài. */
    public record View(List<String> categories, List<String> cities, Long maxBudgetVnd, String note) {

        static View of(CustomerPreferences p) {
            return new View(p.categories(), p.cities(), p.maxBudgetVnd(), p.note());
        }
    }

    /**
     * @param saved sở thích sau khi gộp; rỗng khi không có gì hợp lệ để lưu
     * @param ignored giá trị bị bỏ kèm lý do, để mô hình hỏi lại khách
     */
    public record Remembered(Optional<View> saved, List<String> ignored) {}

    public Optional<View> view(UUID userId) {
        return repository.find(userId).map(View::of);
    }

    public Remembered remember(UUID userId, Update update) {
        List<String> ignored = new ArrayList<>();

        List<String> newCategories = new ArrayList<>();
        for (String raw : update.categories() == null ? List.<String>of() : update.categories()) {
            String c = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (c.isEmpty()) {
                continue;
            }
            if (CATEGORIES.contains(c)) {
                newCategories.add(c);
            } else {
                ignored.add("category \"" + raw.trim() + "\": chỉ nhận " + String.join(", ", CATEGORIES));
            }
        }

        String city = clean(update.city());
        if (city != null && city.length() > 60) {
            ignored.add("city: quá dài");
            city = null;
        }

        Long budget = update.maxBudgetVnd();
        if (budget != null && (budget < MIN_BUDGET || budget > MAX_BUDGET)) {
            ignored.add("maxBudgetVnd " + budget + ": phải là số đồng của MỘT vé, từ 10000 tới 200000000");
            budget = null;
        }

        String note = update.note() == null
                ? null
                : update.note().replaceAll("\\s+", " ").trim();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (note != null && CONTACT.matcher(note).find()) {
            ignored.add("note: có số điện thoại hoặc email — không lưu thông tin liên lạc");
            note = null;
        }
        if (note != null && note.length() > MAX_NOTE) {
            note = note.substring(0, MAX_NOTE).trim();
        }

        if (newCategories.isEmpty() && city == null && budget == null && note == null) {
            return new Remembered(Optional.empty(), List.copyOf(ignored));
        }

        Optional<CustomerPreferences> current = repository.find(userId);

        Set<String> categories =
                new LinkedHashSet<>(current.map(CustomerPreferences::categories).orElse(List.of()));
        categories.addAll(newCategories);
        List<String> mergedCategories =
                categories.stream().limit(MAX_CATEGORIES).toList();

        List<String> cities = new ArrayList<>();
        if (city != null) {
            cities.add(city);
        }
        for (String old : current.map(CustomerPreferences::cities).orElse(List.of())) {
            if (cities.stream().noneMatch(old::equalsIgnoreCase)) {
                cities.add(old);
            }
        }
        List<String> mergedCities = cities.stream().limit(MAX_CITIES).toList();

        CustomerPreferences merged = new CustomerPreferences(
                userId,
                mergedCategories,
                mergedCities,
                budget != null
                        ? budget
                        : current.map(CustomerPreferences::maxBudgetVnd).orElse(null),
                note != null ? note : current.map(CustomerPreferences::note).orElse(null),
                clock.instant());
        repository.save(merged);
        return new Remembered(Optional.of(View.of(merged)), List.copyOf(ignored));
    }

    /** @return {@code true} nếu có gì để xoá */
    public boolean forget(UUID userId) {
        return repository.delete(userId);
    }

    /** Tên thành phố: gọn khoảng trắng, bỏ dấu phẩy ("Đà Nẵng, Việt Nam" thừa phần sau), rỗng thành null. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        int comma = raw.indexOf(',');
        String s = (comma >= 0 ? raw.substring(0, comma) : raw)
                .replaceAll("\\s+", " ")
                .trim();
        return s.isEmpty() ? null : s;
    }
}
