// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.SupportAgentPrompts;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Mọi chỗ nhắc hotline đều viết đúng {@code HOTLINE_NUMBER} và {@code HOTLINE_HOURS}.
 *
 * <p>{@code AnswerGrounding} bỏ đúng hai chuỗi này trước khi dò con số bịa. Đổi giờ hotline ở một
 * câu mà quên hằng (hoặc ngược lại) thì câu đó lại bị chốt chặn coi là giờ diễn bịa — test này đỏ
 * trước khi khách thấy điều đó.
 *
 * <p>Quét bằng reflection như {@code SupportAgentPromptsTest}: câu mới thêm vào cũng được kiểm.
 */
class SupportAgentPromptsHotlineTest {

    @Test
    void moi_cau_nhac_hotline_deu_dung_hang() throws Exception {
        int checked = 0;
        for (Method m : SupportAgentPrompts.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())
                    || !Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() != String.class
                    || !(m.getName().endsWith("Message") || m.getName().equals("systemPrompt"))) {
                continue;
            }
            String text = (String) m.invoke(null, sampleArgs(m));
            if (!text.contains("1900")) {
                continue;
            }
            checked++;
            assertThat(text)
                    .as(m.getName())
                    .contains(SupportAgentPrompts.HOTLINE_NUMBER)
                    .contains(SupportAgentPrompts.HOTLINE_HOURS);
        }
        // Prompt hệ thống + bốn câu báo hiện có nhắc hotline. Con số này giảm nghĩa là bộ quét hỏng.
        assertThat(checked).isGreaterThanOrEqualTo(5);
    }

    private static Object[] sampleArgs(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                args[i] = "NT-240001";
            } else if (types[i].isEnum()) {
                args[i] = types[i].getEnumConstants()[0];
            } else {
                throw new IllegalStateException("Thêm giá trị mẫu cho kiểu " + types[i] + " ở " + m.getName());
            }
        }
        return args;
    }
}
