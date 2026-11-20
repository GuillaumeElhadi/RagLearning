package fr.elhadi.raglearning.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class BeansConfig {

    @Bean
    public TokenTextSplitter textSplitter() {
        return TokenTextSplitter.builder()
                                .withChunkSize(1500)
                                .build();
    }

    /**
     * Le prompt system vit dans un fichier versionné : plus lisible qu'une chaîne Java,
     * et modifiable sans toucher au code. Il ne doit pas contenir d'accolades, que le
     * moteur de modèles interpréterait comme des variables.
     */
    @Bean("questionAnsweringChatClient")
    ChatClient questionAnsweringChatClient(
        ChatClient.Builder builder,
        @Value("classpath:prompts/system-prompt.st") Resource systemPrompt
    ) {
        return builder.defaultSystem(systemPrompt).build();
    }

    /**
     * Le prompt system vit dans un fichier versionné : plus lisible qu'une chaîne Java,
     * et modifiable sans toucher au code. Il ne doit pas contenir d'accolades, que le moteur de modèles interpréterait comme des variables.
     */
    @Bean("keywordExtractorChatClient")
    ChatClient keywordExtractorChatClient(
        ChatClient.Builder builder,
        @Value("classpath:prompts/keyword-extraction.st") Resource systemPrompt
    ) {
        return builder.defaultSystem(systemPrompt).build();
    }
}
