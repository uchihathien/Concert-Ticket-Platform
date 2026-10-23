// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chốt chặn HÌNH DẠNG câu trả lời: chặn câu mà khách nhìn là biết máy hỏng, trước khi nó tới khách.
 *
 * <h2>Ba lỗi đo được trên mô hình 7B chạy tại chỗ</h2>
 *
 * <ol>
 *   <li><b>Mẫu chưa điền</b> ({@link #TEMPLATE_PLACEHOLDER}). Khách hỏi một sự kiện, mô hình không gọi
 *       tool mà in ra một mẫu: "Diễn ra vào: [Ngày tháng năm] · Địa điểm: [Tên địa điểm]". Câu không có
 *       con số nào nên {@link AnswerGrounding} để lọt, và khách nhận một tờ phiếu trống.
 *   <li><b>Chữ Hán giữa câu</b> ({@link #FOREIGN_SCRIPT}). Gọi tool lỗi xong, mô hình (huấn luyện chủ yếu
 *       bằng tiếng Trung) chuyển ngôn ngữ giữa chừng và đọc to cả chỉ dẫn nội bộ: "保持在120字内…".
 *   <li><b>Nói về sự kiện chưa tra</b> ({@link #UNLOOKED_EVENT}). "Mình đã tìm thấy sự kiện "Sự kiện âm
 *       nhạc Trịnh Công Sơn"" — một cái tên danh mục không có, trong một lượt không gọi tool nào.
 * </ol>
 *
 * <h2>Vì sao trả về lời nhắc thay vì câu thay thế</h2>
 *
 * {@link AnswerGrounding} và {@link BookingClaimGuard} thay câu sai bằng một câu cố định. Ở đây làm
 * vậy thì phí: ba lỗi này mô hình <i>sửa được</i> nếu được nhắc — gọi findEvents rồi viết lại. Nên
 * kết quả là một {@link Rejected} mang lời nhắc cho mô hình; vòng ReAct cho nó một lượt sửa, và chỉ
 * khi lượt sửa vẫn hỏng mới dùng câu thay thế.
 *
 * <h2>Không chặn oan</h2>
 *
 * <ul>
 *   <li>Chữ trong ngoặc vuông chỉ tính khi có chữ cái và KHÔNG đứng ngay trước "(" — liên kết markdown
 *       {@code [Mua vé](/events/x)} không phải ô trống. Chữ trong ngoặc mà khách tự gõ cũng không tính.
 *   <li>Chữ Hán đi qua khi khách nhắn bằng chữ Hán (prompt cho phép trả lời theo ngôn ngữ khách), hoặc
 *       khi nó nằm nguyên văn trong kết quả tool (tên sự kiện có chữ Nhật).
 *   <li>Tên sự kiện có nguồn khi nó nằm trong kết quả tool của lượt, hoặc trong một lượt trợ lý TRƯỚC lượt
 *       này. Đã tra mà danh mục không ra thì được nhắc lại tên khách gõ ("mình chưa thấy sự kiện "X"").
 * </ul>
 */
public final class AnswerShapeGuard {

    private AnswerShapeGuard() {}

    public static final String TEMPLATE_PLACEHOLDER = "TEMPLATE_PLACEHOLDER";
    public static final String FOREIGN_SCRIPT = "FOREIGN_SCRIPT";
    public static final String UNLOOKED_EVENT = "UNLOOKED_EVENT";
    public static final String UNBACKED_PROPOSAL = "UNBACKED_PROPOSAL";

    public sealed interface Verdict permits Pass, Rejected {}

    public record Pass() implements Verdict {}

    /**
     * @param reason một trong ba hằng ở trên — cũng là tag của metric
     * @param correction lời nhắc gửi lại cho mô hình (khách không thấy)
     */
    public record Rejected(String reason, String correction) implements Verdict {}

    /** Ô trống kiểu {@code [Ngày tháng năm]}: có chữ cái, không phải nhãn liên kết markdown. */
    private static final Pattern BRACKET_SLOT = Pattern.compile("\\[([^\\[\\]\\n]{1,60})\\](?!\\()");

    private static final Pattern BRACE_SLOT = Pattern.compile("\\{\\{[^{}\\n]{1,60}\\}\\}");

    private static final Pattern LETTER = Pattern.compile("\\p{L}");

    /** Chữ Hán, kana Nhật, Hangul. Tiếng Việt viết bằng chữ Latinh nên không có ký tự nào trong đây. */
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+");

    /** {@code sự kiện "X"}, {@code sự kiện “X”}, {@code sự kiện «X»} — có hoặc không có dấu hai chấm. */
    private static final Pattern QUOTED_EVENT = Pattern.compile(
            "s[ựu]\\s*ki[ệe]n\\s*:?\\s*[\"“«„]\\s*([^\"”»\\n]{2,100}?)\\s*[\"”»]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Dòng liệt kê {@code · Sự kiện: X} — kiểu mô hình hay dùng khi tóm tắt một đề xuất tự nghĩ ra. */
    private static final Pattern LABELLED_EVENT = Pattern.compile(
            "s[ựu]\\s*ki[ệe]n\\s*:\\s*([^\"“«\\n][^\\n]{1,99})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Lời nói rằng đề xuất/thẻ ĐÃ có — trên chữ đã gập dấu. Câu hỏi "bạn có muốn mình lập đề xuất không"
     * không khớp (không có "da lap"); "chưa lập" cũng không.
     */
    private static final Pattern PROPOSAL_CLAIM =
            Pattern.compile("\\bda (lap|tao|gui) (de xuat|the)|\\bthe (ben )?duoi( day)?\\b|\\btren the\\b");

    private static final Set<String> EVENT_LOOKUP_TOOLS =
            Set.of(SupportAgentTools.FIND_EVENTS, SupportAgentTools.GET_EVENT_DETAILS);

    /**
     * @param answer câu trả lời đã gỡ dấu nội bộ
     * @param userQuery câu khách gõ ở lượt này (nguyên văn)
     * @param outcomes mọi kết quả tool của lượt này
     * @param priorTurns hội thoại TRƯỚC lượt này — không gồm câu trả lời đang xét hay bản bị từ chối
     * @param thisTurn các mục của lượt này trong transcript (để biết lời gọi nào là tool gì)
     */
    public static Verdict check(
            String answer,
            String userQuery,
            List<ToolOutcome> outcomes,
            List<Exchange> priorTurns,
            List<Exchange> thisTurn) {
        if (answer == null || answer.isBlank()) {
            return new Pass();
        }
        String query = userQuery == null ? "" : userQuery;

        String slot = firstPlaceholder(answer, query);
        if (slot != null) {
            return new Rejected(
                    TEMPLATE_PLACEHOLDER,
                    note("câu trả lời vừa rồi còn ô trống \"" + slot + "\" — đó là mẫu chưa điền, không gửi cho "
                            + "khách được. Gọi findEvents (rồi getEventDetails) để lấy thông tin thật và viết lại "
                            + "câu trả lời đầy đủ, không dùng ngoặc vuông. Tra không ra thì nói thẳng là chưa thấy."));
        }

        String payloads = payloadText(outcomes);
        if (hasStrayCjk(answer, query, payloads)) {
            return new Rejected(
                    FOREIGN_SCRIPT,
                    note("câu trả lời vừa rồi có chữ không phải tiếng Việt. Viết lại TOÀN BỘ bằng tiếng Việt, ngắn "
                            + "gọn, không nhắc tới hướng dẫn nội bộ."));
        }

        if (claimsProposal(answer) && !payloads.contains("\"proposed\":true")) {
            return new Rejected(
                    UNBACKED_PROPOSAL,
                    note("câu trả lời vừa rồi nói đã lập đề xuất hoặc có thẻ để bấm, nhưng lượt này CHƯA gọi "
                            + "proposeBooking nên khách không có thẻ nào. Gọi getEventDetails với slug của sự kiện để "
                            + "lấy sessionId và zoneCode của hạng vé khách chọn, rồi gọi proposeBooking với slug, "
                            + "sessionId, zoneCode và quantity. Chỉ nhắc tới thẻ sau khi tool trả proposed=true; "
                            + "tool từ chối thì nói lại lý do cho khách."));
        }

        String unsourced = firstUnsourcedEvent(answer, query, outcomes, payloads, priorTurns, thisTurn);
        if (unsourced != null) {
            return new Rejected(
                    UNLOOKED_EVENT,
                    note("câu trả lời vừa rồi nhắc tới sự kiện \"" + unsourced + "\" nhưng danh mục chưa trả về "
                            + "sự kiện nào mang tên đó. Gọi findEvents với tên khách nói trước khi mô tả sự kiện, "
                            + "và chỉ dùng đúng tên, ngày, giá mà tool trả về."));
        }
        return new Pass();
    }

    static boolean claimsProposal(String answer) {
        return PROPOSAL_CLAIM
                .matcher(TextFolding.fold(answer).replaceAll("[^\\p{L}\\p{N}]+", " "))
                .find();
    }

    private static String note(String body) {
        return "[Ghi chú hệ thống — khách không thấy dòng này] Không gửi được: " + body;
    }

    private static String firstPlaceholder(String answer, String query) {
        Matcher m = BRACKET_SLOT.matcher(answer);
        while (m.find()) {
            String inside = m.group(1).trim();
            if (LETTER.matcher(inside).find() && !query.contains(m.group())) {
                return m.group();
            }
        }
        Matcher braces = BRACE_SLOT.matcher(answer);
        if (braces.find() && !query.contains(braces.group())) {
            return braces.group();
        }
        return null;
    }

    private static boolean hasStrayCjk(String answer, String query, String payloads) {
        if (CJK_RUN.matcher(query).find()) {
            return false;
        }
        Matcher m = CJK_RUN.matcher(answer);
        while (m.find()) {
            if (!payloads.contains(m.group())) {
                return true;
            }
        }
        return false;
    }

    private static String firstUnsourcedEvent(
            String answer,
            String query,
            List<ToolOutcome> outcomes,
            String payloads,
            List<Exchange> priorTurns,
            List<Exchange> thisTurn) {
        List<String> names = mentionedEventNames(answer);
        if (names.isEmpty()) {
            return null;
        }
        String sources = comparable(payloads + "\n" + priorAssistantText(priorTurns));
        boolean lookedUpAndMissed = lookedUpEvents(outcomes, thisTurn) && !anyEventFound(outcomes, thisTurn);
        String asked = comparable(query);
        for (String name : names) {
            String probe = comparable(name);
            if (probe.isBlank() || sources.contains(probe)) {
                continue;
            }
            // Đã tra mà không ra: nhắc lại đúng tên khách gõ là câu trả lời đúng ("chưa thấy sự kiện X").
            if (lookedUpAndMissed && asked.contains(probe)) {
                continue;
            }
            return name;
        }
        return null;
    }

    static List<String> mentionedEventNames(String answer) {
        List<String> names = new ArrayList<>();
        for (Pattern p : List.of(QUOTED_EVENT, LABELLED_EVENT)) {
            Matcher m = p.matcher(answer);
            while (m.find()) {
                String name = m.group(1).replaceAll("[\\s.,;:!?)]+$", "").trim();
                if (name.length() >= 2) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Gập dấu, bỏ mọi thứ không phải chữ/số: "Đêm nhạc Trịnh · Ru Đời" và "dem nhac trinh ru doi" là một. */
    private static String comparable(String text) {
        return TextFolding.fold(text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String payloadText(List<ToolOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (ToolOutcome o : outcomes) {
            if (!o.failed() && o.payload() != null) {
                sb.append(o.payload()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String priorAssistantText(List<Exchange> priorTurns) {
        StringBuilder sb = new StringBuilder();
        for (Exchange e : priorTurns) {
            if (e instanceof Exchange.AssistantSaid said) {
                sb.append(said.text()).append('\n');
            }
        }
        return sb.toString();
    }

    private static Map<String, String> toolNamesByCallId(List<Exchange> thisTurn) {
        Map<String, String> names = new HashMap<>();
        for (Exchange e : thisTurn) {
            if (e instanceof Exchange.AssistantRequestedTools requested) {
                for (ToolInvocation call : requested.calls()) {
                    names.put(call.callId(), call.toolName());
                }
            }
        }
        return names;
    }

    private static boolean lookedUpEvents(List<ToolOutcome> outcomes, List<Exchange> thisTurn) {
        Map<String, String> names = toolNamesByCallId(thisTurn);
        return outcomes.stream().anyMatch(o -> EVENT_LOOKUP_TOOLS.contains(names.get(o.callId())));
    }

    private static boolean anyEventFound(List<ToolOutcome> outcomes, List<Exchange> thisTurn) {
        Map<String, String> names = toolNamesByCallId(thisTurn);
        return outcomes.stream()
                .anyMatch(o -> EVENT_LOOKUP_TOOLS.contains(names.get(o.callId()))
                        && !o.failed()
                        && o.payload() != null
                        && o.payload().contains("\"found\":true"));
    }
}
