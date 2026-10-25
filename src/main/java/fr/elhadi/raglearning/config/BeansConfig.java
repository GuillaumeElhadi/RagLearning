package fr.elhadi.raglearning.config;

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
}
