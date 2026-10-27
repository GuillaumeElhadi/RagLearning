package fr.elhadi.raglearning.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeansConfig {

    @Bean
    public TokenTextSplitter textSplitter() {
        return TokenTextSplitter.builder()
                                .build();
    }

    @Bean
    ChatClient customChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(
            "You are a helpful assistant. but when you don't find answer in my rag resources, don't imagine answer, tell to user that you don't know").build();
    }
}
