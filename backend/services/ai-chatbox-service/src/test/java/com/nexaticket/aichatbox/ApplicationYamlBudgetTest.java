// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

/**
 * Hạn async phải lớn hơn ngân sách xấu nhất của một lượt chat, với CẢ HAI nhà cung cấp.
 *
 * <p>Tính từ chính các khoá trong {@code application.yml} (giá trị mặc định sau dấu {@code :}). Đổi
 * một hạn bên trong mà quên hạn async thì test này đỏ — trước đây hạn async 600s nhỏ hơn ngân sách
 * thật, và nó nổ trong khi lượt vẫn chạy (R-22).
 */
class ApplicationYamlBudgetTest {

    /** Một vòng đặt vé gọi tới 5 service × hạn 3s — xem chú thích ở spring.mvc.async. */
    private static final Duration TOOL_BUDGET_PER_ROUND = Duration.ofSeconds(20);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]+:([^}]*)}");

    private static Properties yaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }

    private static String value(Properties props, String key) {
        String raw = props.getProperty(key);
        assertThat(raw).as(key).isNotNull();
        Matcher m = PLACEHOLDER.matcher(raw);
        return m.matches() ? m.group(1) : raw;
    }

    private static Duration duration(Properties props, String key) {
        return DurationStyle.detectAndParse(value(props, key));
    }

    @Test
    void han_async_lon_hon_ngan_sach_cua_ca_hai_nha_cung_cap() {
        Properties p = yaml();
        Duration asyncTimeout = duration(p, "spring.mvc.async.request-timeout");
        Duration queueWait = duration(p, "nexaticket.aichatbox.agent.max-queue-wait");
        int rounds = Integer.parseInt(value(p, "nexaticket.aichatbox.agent.max-tool-iterations"));

        Duration ollama = queueWait
                .plus(duration(p, "nexaticket.aichatbox.ollama.embedding-timeout"))
                .plus(duration(p, "nexaticket.aichatbox.ollama.timeout")
                        .plus(TOOL_BUDGET_PER_ROUND)
                        .multipliedBy(rounds));

        int retries = Integer.parseInt(value(p, "nexaticket.aichatbox.anthropic.max-retries"));
        Duration anthropic = queueWait
                .plus(duration(p, "nexaticket.aichatbox.embedding.timeout"))
                .plus(duration(p, "nexaticket.aichatbox.anthropic.timeout")
                        .multipliedBy(1L + retries)
                        .plus(TOOL_BUDGET_PER_ROUND)
                        .multipliedBy(rounds));

        assertThat(ollama).isEqualTo(Duration.ofSeconds(680));
        assertThat(anthropic).isEqualTo(Duration.ofSeconds(630));
        assertThat(asyncTimeout).isGreaterThan(ollama).isGreaterThan(anthropic);
    }
}
