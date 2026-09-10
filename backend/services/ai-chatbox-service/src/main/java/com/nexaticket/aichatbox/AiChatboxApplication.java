// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox;

import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.infrastructure.llm.AnthropicProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {"com.nexaticket.aichatbox", "com.nexaticket.platform"})
@EnableConfigurationProperties({AgentProperties.class, AnthropicProperties.class})
public class AiChatboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatboxApplication.class, args);
    }

    /** Bean Clock để test tua được thời gian mà không phải mock static. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
