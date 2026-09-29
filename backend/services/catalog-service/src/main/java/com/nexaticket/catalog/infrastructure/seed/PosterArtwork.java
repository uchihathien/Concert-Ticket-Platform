// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sinh ảnh bìa cho sự kiện mẫu.
 *
 * <h2>Vì sao tự vẽ thay vì lấy ảnh thật</h2>
 *
 * <p>Bản trước trỏ {@code poster_url} ra {@code picsum.photos} — ảnh ngẫu nhiên, <b>không liên
 * quan gì tới sự kiện</b>. Một hội thảo fintech hiện ra tấm ảnh bãi biển trông như dữ liệu bị
 * hỏng, chứ không như một catalog mẫu. Và nó còn hai vấn đề nặng hơn:
 *
 * <ul>
 *   <li>Phụ thuộc mạng ngoài. Máy không có internet thì mọi thẻ sự kiện thành ô trống — đúng cái
 *       ấn tượng "code hỏng" mà bộ dựng dữ liệu mẫu sinh ra để tránh.
 *   <li>{@code MEDIA_ALLOW_EXTERNAL_URLS=false} ở production sẽ <b>từ chối</b> chính những URL ấy.
 *       Dữ liệu mẫu đi được ở dev nhưng không đi được ở production là dữ liệu mẫu nói dối.
 * </ul>
 *
 * <p>Ảnh tự vẽ giải cả ba: đúng sự kiện, không cần mạng, và nằm trong kho vật thể của chính mình
 * nên đi qua được {@code PosterUrlPolicy}.
 *
 * <h2>Vì sao SVG</h2>
 *
 * <p>Không cần thư viện xử lý ảnh, không cần font cài sẵn trên máy chạy, và file nặng vài KB thay
 * vì vài trăm. Trình duyệt nào cũng hiển thị được SVG trong {@code <img>} — đây là ảnh <b>hiển
 * thị</b>, không phải ảnh để tải về, nên hạn chế của SVG ở chỗ khác không áp vào đây.
 */
final class PosterArtwork {

    /** 1200×675 — tỷ lệ 16:9, đúng khung {@code .media} của thẻ sự kiện. */
    private static final int WIDTH = 1200;

    private static final int HEIGHT = 675;

    private PosterArtwork() {}

    /**
     * Bảng màu và hoạ tiết theo thể loại.
     *
     * <p>Mỗi thể loại một bộ, và đó là điểm chính: khách lướt trang danh sách nhận ra "đây là đêm
     * nhạc" trước khi kịp đọc chữ. Màu lấy từ họ màu NexaTicket, không lấy của bên nào khác.
     *
     * @param motif hình nền: {@code waves} · {@code spotlight} · {@code arena} · {@code grid} ·
     *     {@code rings}
     */
    private record Palette(String from, String via, String to, String accent, String motif) {}

    private static Palette paletteOf(String category) {
        return switch (category == null ? "" : category.toLowerCase(Locale.ROOT)) {
                // Ca nhạc: đỏ ấm thương hiệu, sóng âm.
            case "nhac-song" -> new Palette("#5c0f0f", "#c02a2a", "#f2b705", "#ffd979", "waves");
                // Sân khấu: tím rượu và vàng kim, ánh đèn sân khấu.
            case "san-khau" -> new Palette("#2b1036", "#6d2352", "#d99a00", "#ffd979", "spotlight");
                // Thể thao: xanh lá trạng thái, đường chạy khán đài.
            case "the-thao" -> new Palette("#0d2b1e", "#1f7a4d", "#7ad19f", "#eafff3", "arena");
                // Hội thảo: xanh mực, lưới — nghiêm túc, không lễ hội.
            case "hoi-thao" -> new Palette("#0e1b2e", "#1d3f6b", "#4d84c4", "#cfe3ff", "grid");
            default -> new Palette("#1d1817", "#4a403b", "#b06a00", "#f2b705", "rings");
        };
    }

    /**
     * Ảnh bìa của một sự kiện.
     *
     * @param title tên sự kiện, xuống dòng theo từ
     * @param category quyết định màu và hoạ tiết
     * @param city dòng phụ dưới tên
     * @param lineup đội hình nghệ sĩ, một dòng ngắn đặt ngay dưới tên. Để trống thì dòng ấy biến
     *     mất hẳn chứ không để lại khoảng trắng — một chỗ trống chừa cho dữ liệu không có làm bố
     *     cục trông như lỗi căn lề.
     * @param seed để hoạ tiết của hai sự kiện cùng thể loại không giống hệt nhau
     */
    static String svg(String title, String category, String city, String seed) {
        return svg(title, category, city, null, seed);
    }

    static String svg(String title, String category, String city, String lineup, String seed) {
        Palette palette = paletteOf(category);
        int variant = Math.floorMod(seed == null ? 0 : seed.hashCode(), 3);

        List<String> lines = wrap(title, 18);
        StringBuilder svg = new StringBuilder(2048);

        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(WIDTH)
                .append(' ')
                .append(HEIGHT)
                .append("\" width=\"")
                .append(WIDTH)
                .append("\" height=\"")
                .append(HEIGHT)
                .append("\" role=\"img\">");

        // Dải nền chéo: cùng hướng với gradient của ảnh bìa tạm ở frontend, nên sự kiện có ảnh và
        // sự kiện chưa có ảnh nằm cạnh nhau vẫn thuộc cùng một ngôn ngữ hình ảnh.
        svg.append("<defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
                .append("<stop offset=\"0%\" stop-color=\"")
                .append(palette.from())
                .append("\"/><stop offset=\"55%\" stop-color=\"")
                .append(palette.via())
                .append("\"/><stop offset=\"100%\" stop-color=\"")
                .append(palette.to())
                .append("\"/></linearGradient></defs>");

        svg.append("<rect width=\"")
                .append(WIDTH)
                .append("\" height=\"")
                .append(HEIGHT)
                .append("\" fill=\"url(#bg)\"/>");
        svg.append(motif(palette, variant));

        // Lớp phủ tối ở đáy: chữ trắng phải đọc được kể cả khi hoạ tiết sáng chạy qua chỗ đó.
        svg.append("<defs><linearGradient id=\"ink\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
                .append("<stop offset=\"40%\" stop-color=\"#000\" stop-opacity=\"0\"/>")
                .append("<stop offset=\"100%\" stop-color=\"#000\" stop-opacity=\"0.72\"/></linearGradient></defs>")
                .append("<rect width=\"")
                .append(WIDTH)
                .append("\" height=\"")
                .append(HEIGHT)
                .append("\" fill=\"url(#ink)\"/>");

        svg.append(text(72, 92, 30, 800, palette.accent(), 6, "NEXATICKET"));

        // Dựng từ ĐÁY lên, và MỌI khối đều neo dòng CUỐI của nó vào con trỏ.
        //
        // Bản trước neo tiêu đề bằng dòng ĐẦU rồi vẽ xuống dưới, nên khi chèn thêm khối nghệ sĩ ở
        // giữa thì tiêu đề tràn đè lên nó — vẫn là SVG hợp lệ, vẫn không có lỗi nào, chỉ là hai
        // dòng chữ chồng khít. Neo thống nhất theo dòng cuối làm chuyện ấy không xảy ra được nữa.
        int cursor = HEIGHT - 72;

        if (city != null && !city.isBlank()) {
            svg.append(text(72, HEIGHT - 56, 30, 500, "#f0e7e3", 1, escape(city)));
            cursor -= 56;
        }

        List<String> credits = lineup == null || lineup.isBlank() ? List.of() : wrap(headline(lineup), 46);
        for (int i = 0; i < credits.size(); i++) {
            svg.append(text(
                    72, cursor - (credits.size() - 1 - i) * 34, 26, 600, palette.accent(), 1, escape(credits.get(i))));
        }
        if (!credits.isEmpty()) {
            cursor -= (credits.size() - 1) * 34 + 48;
        }

        for (int i = 0; i < lines.size(); i++) {
            svg.append(text(72, cursor - (lines.size() - 1 - i) * 66, 58, 800, "#ffffff", 0, escape(lines.get(i))));
        }

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Hoạ tiết nền.
     *
     * <p>{@code variant} làm hai sự kiện cùng thể loại không chồng khít lên nhau. Nó suy từ slug
     * nên cố định: chụp màn hình trong tài liệu không đổi sau mỗi lần dựng lại database.
     */
    private static String motif(Palette palette, int variant) {
        String stroke = palette.accent();
        StringBuilder out = new StringBuilder(512);

        switch (palette.motif()) {
            case "waves" -> {
                // Sóng âm: ba đường sin chồng nhau, biên độ lệch dần.
                for (int i = 0; i < 3; i++) {
                    int y = 300 + i * 70 + variant * 18;
                    int amp = 70 - i * 16;
                    out.append("<path d=\"M0 ")
                            .append(y)
                            .append(" Q 150 ")
                            .append(y - amp)
                            .append(" 300 ")
                            .append(y)
                            .append(" T 600 ")
                            .append(y)
                            .append(" T 900 ")
                            .append(y)
                            .append(" T 1200 ")
                            .append(y)
                            .append("\" fill=\"none\" stroke=\"")
                            .append(stroke)
                            .append("\" stroke-opacity=\"0.3\" stroke-width=\"3\"/>");
                }
            }
            case "spotlight" -> {
                // Hai chùm đèn từ mép trên chiếu xuống.
                out.append("<polygon points=\"")
                        .append(320 + variant * 40)
                        .append(",0 ")
                        .append(120 + variant * 40)
                        .append(",675 ")
                        .append(500 + variant * 40)
                        .append(",675\" fill=\"")
                        .append(stroke)
                        .append("\" fill-opacity=\"0.12\"/>");
                out.append("<polygon points=\"")
                        .append(820 - variant * 30)
                        .append(",0 ")
                        .append(700 - variant * 30)
                        .append(",675 ")
                        .append(1080 - variant * 30)
                        .append(",675\" fill=\"")
                        .append(stroke)
                        .append("\" fill-opacity=\"0.1\"/>");
            }
            case "arena" -> {
                // Khán đài: các cung đồng tâm quanh một tâm lệch, gợi đúng hình học của ARC.
                for (int i = 0; i < 5; i++) {
                    out.append("<circle cx=\"980\" cy=\"")
                            .append(140 + variant * 30)
                            .append("\" r=\"")
                            .append(150 + i * 95)
                            .append("\" fill=\"none\" stroke=\"")
                            .append(stroke)
                            .append("\" stroke-opacity=\"0.18\" stroke-width=\"2\"/>");
                }
            }
            case "grid" -> {
                for (int x = 80; x < WIDTH; x += 80) {
                    out.append("<line x1=\"")
                            .append(x)
                            .append("\" y1=\"0\" x2=\"")
                            .append(x)
                            .append("\" y2=\"675\" stroke=\"")
                            .append(stroke)
                            .append("\" stroke-opacity=\"0.12\" stroke-width=\"1\"/>");
                }
                for (int y = 80; y < HEIGHT; y += 80) {
                    out.append("<line x1=\"0\" y1=\"")
                            .append(y)
                            .append("\" x2=\"1200\" y2=\"")
                            .append(y)
                            .append("\" stroke=\"")
                            .append(stroke)
                            .append("\" stroke-opacity=\"0.12\" stroke-width=\"1\"/>");
                }
            }
            default -> {
                for (int i = 0; i < 4; i++) {
                    out.append("<circle cx=\"")
                            .append(1000 - i * 60)
                            .append("\" cy=\"")
                            .append(180 + variant * 40)
                            .append("\" r=\"")
                            .append(90 + i * 70)
                            .append("\" fill=\"none\" stroke=\"")
                            .append(stroke)
                            .append("\" stroke-opacity=\"0.16\" stroke-width=\"2\"/>");
                }
            }
        }
        return out.toString();
    }

    /**
     * Kiểu chữ đặt bằng THUỘC TÍNH, không bằng CSS.
     *
     * <p>Ảnh này được nhúng qua {@code <img src>}, nên nó đi một mình — không stylesheet nào theo
     * cùng. Mọi kiểu đặt bằng class sẽ biến mất, để lại chữ đen 16px trên nền tối. Cùng lý do với
     * {@code TicketPoster} ở frontend.
     *
     * <p>Font phải có phương án dự phòng thật: máy chạy không chắc có "Be Vietnam Pro", và một
     * font thiếu làm dấu tiếng Việt vỡ.
     */
    private static String text(int x, int y, int size, int weight, String fill, int spacing, String content) {
        return "<text x=\"%d\" y=\"%d\" font-family=\"'Be Vietnam Pro','Segoe UI',system-ui,sans-serif\" "
                        .formatted(x, y)
                + "font-size=\"%d\" font-weight=\"%d\" fill=\"%s\" letter-spacing=\"%d\">%s</text>"
                        .formatted(size, weight, fill, spacing, content);
    }

    /** Ngắt theo TỪ. Cắt giữa từ trên một tấm poster đọc như lỗi hiển thị. */
    /**
     * Câu đầu của phần tóm tắt — chỗ tên nghệ sĩ nằm.
     *
     * <p>Cắt ở dấu chấm đầu tiên chứ không lấy cả đoạn: phần tóm tắt viết cho trang chi tiết, còn
     * poster chỉ có chỗ cho một dòng. Không có dấu chấm nào thì lấy nguyên chuỗi và để {@code wrap}
     * lo phần còn lại.
     */
    private static String headline(String summary) {
        String trimmed = summary.strip();
        int stop = trimmed.indexOf('.');
        return stop > 0 ? trimmed.substring(0, stop) : trimmed;
    }

    private static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.trim().split("\\s+")) {
            if (current.length() + word.length() + 1 > maxChars && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        // Ba dòng là trần: tên dài hơn thì cắt, vì dòng thứ tư đè lên phần thông tin phía dưới.
        return lines.size() <= 3 ? lines : new ArrayList<>(lines.subList(0, 3));
    }

    /**
     * Thoát ký tự cho nội dung XML.
     *
     * <p>Tên sự kiện ở đây do chính ta khai, nhưng hàm này vẫn phải có: một dấu {@code &} trong
     * tên ("Rock & Roll") là đủ để cả file SVG hỏng, và trình duyệt bỏ hẳn ảnh mà không báo gì.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
