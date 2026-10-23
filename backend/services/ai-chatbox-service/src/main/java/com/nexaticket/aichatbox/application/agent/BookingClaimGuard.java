// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chốt chặn thứ hai: trợ lý không được NÓI là đã đặt vé khi hệ thống chưa tạo đơn nào (NFR-001).
 *
 * <p>Đo được thật (22/10/2026, qwen2.5 7B): khách giục "đặt luôn", trợ lý trả "Đã đặt 2 vé, số đơn
 * 3fa85f64-…, quét VietQR trên màn hình" với {@code toolsUsed} rỗng — UUID chép từ ví dụ trong mô tả
 * tool rồi sửa vài ký tự. {@link AnswerGrounding} để lọt vì lượt có đoạn RAG. ADR-2001 chặn mô hình
 * <i>tạo</i> đơn; lớp này chặn mô hình <i>nói</i> là đã tạo — khách tin mình có vé thì lời bịa gây hại
 * y như đơn sai.
 *
 * <p>Hai quy tắc, không LLM, không gọi service:
 *
 * <ol>
 *   <li><b>Số đơn phải có nguồn.</b> Mọi số đơn ({@code NT-xxxxxx-xxxxxx}) hay UUID trong câu trả lời
 *       phải xuất hiện nguyên văn trong payload một tool không lỗi của lượt này, hoặc trong một lượt
 *       trợ lý/nhân viên đã lưu. Guard chạy TRƯỚC khi lưu câu trả lời, nên số bịa không bao giờ vào
 *       lịch sử để lượt sau được coi là "có nguồn".
 *   <li><b>Khẳng định đã đặt phải có đơn thật.</b> "đã đặt", "đã tạo đơn", "quét mã VietQR"… chỉ đi
 *       qua khi lượt có {@code "booked":true} hoặc câu có ít nhất một số đơn đã qua quy tắc 1. Câu
 *       phủ định ("chưa đặt", "không đặt được") không tính.
 * </ol>
 */
public final class BookingClaimGuard {

    /** Định dạng số đơn của ordering, đo trên dữ liệu dev ({@code NT-260925-FSQM90}). */
    static final Pattern ORDER_NUMBER = Pattern.compile("NT-\\d{6}-[A-Z0-9]{6}", Pattern.CASE_INSENSITIVE);

    static final Pattern UUID_REF =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

    public static final String UNSOURCED_ORDER_REF = "UNSOURCED_ORDER_REF";
    public static final String UNBACKED_BOOKING_CLAIM = "UNBACKED_BOOKING_CLAIM";

    /** Cụm khẳng định, ở dạng đã gập dấu (xem {@link TextFolding}). */
    private static final List<String> CLAIMS = List.of(
            "da dat",
            "dat thanh cong",
            "da giu cho",
            "da tao don",
            "don cua ban la",
            "ma don cua ban",
            "quet ma vietqr",
            "vietqr hien tren man hinh",
            "thanh toan truoc");

    /** Từ phủ định đứng trong 3 từ ngay trước cụm khẳng định thì cụm ấy không tính. */
    private static final List<String> NEGATIONS = List.of("chua", "khong");

    private static final int NEGATION_WINDOW_WORDS = 3;

    private BookingClaimGuard() {}

    public sealed interface Verdict permits Pass, Blocked {}

    public record Pass() implements Verdict {}

    /** @param reason {@link #UNSOURCED_ORDER_REF} hoặc {@link #UNBACKED_BOOKING_CLAIM} */
    public record Blocked(String reason) implements Verdict {}

    /**
     * @param outcomes mọi kết quả tool của lượt này (gộp các vòng)
     * @param transcript hội thoại đã nạp — chỉ lượt trợ lý/nhân viên ({@code AssistantSaid}) được coi là nguồn
     */
    public static Verdict check(String answer, List<ToolOutcome> outcomes, List<Exchange> transcript) {
        if (answer == null || answer.isBlank()) {
            return new Pass();
        }
        String sources = sourcesOf(outcomes, transcript);
        List<String> refs = orderRefs(answer);
        for (String ref : refs) {
            if (!sources.contains(ref.toLowerCase(Locale.ROOT))) {
                return new Blocked(UNSOURCED_ORDER_REF);
            }
        }
        if (claims(answer)) {
            boolean booked = outcomes.stream()
                    .filter(o -> !o.failed() && o.payload() != null)
                    .anyMatch(o -> o.payload().contains("\"booked\":true"));
            if (!booked && refs.isEmpty()) {
                return new Blocked(UNBACKED_BOOKING_CLAIM);
            }
        }
        return new Pass();
    }

    /** Số đơn và UUID trong câu trả lời, theo thứ tự xuất hiện. */
    static List<String> orderRefs(String answer) {
        List<String> refs = new ArrayList<>();
        for (Pattern p : List.of(ORDER_NUMBER, UUID_REF)) {
            Matcher m = p.matcher(answer);
            while (m.find()) {
                refs.add(m.group());
            }
        }
        return refs;
    }

    private static String sourcesOf(List<ToolOutcome> outcomes, List<Exchange> transcript) {
        StringBuilder sb = new StringBuilder();
        for (ToolOutcome o : outcomes) {
            if (!o.failed() && o.payload() != null) {
                sb.append(o.payload()).append('\n');
            }
        }
        for (Exchange e : transcript) {
            if (e instanceof Exchange.AssistantSaid said && said.text() != null) {
                sb.append(said.text()).append('\n');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Có ít nhất một cụm khẳng định KHÔNG bị phủ định ngay trước. */
    static boolean claims(String answer) {
        String folded = TextFolding.fold(answer);
        for (String claim : CLAIMS) {
            int from = 0;
            while (true) {
                int at = folded.indexOf(claim, from);
                if (at < 0) {
                    break;
                }
                if (!negatedBefore(folded, at)) {
                    return true;
                }
                from = at + claim.length();
            }
        }
        return false;
    }

    private static boolean negatedBefore(String folded, int at) {
        String before = folded.substring(0, at).trim();
        String[] words = before.split("\\s+");
        int start = Math.max(0, words.length - NEGATION_WINDOW_WORDS);
        for (int i = start; i < words.length; i++) {
            String w = words[i].replaceAll("[^a-z0-9]", "");
            if (NEGATIONS.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
