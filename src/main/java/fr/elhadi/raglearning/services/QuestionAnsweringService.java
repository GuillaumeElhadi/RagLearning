package fr.elhadi.raglearning.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionAnsweringService {

    public static final String USER_QUESTION_WITH_RAG_RESOURCES = """
        <documents>
        {context}
        </documents>
        
        Question : {question}
        """;

    private final RetrievalService retrievalService;

    private final ChatClient chatClient;

    public Set<String> askAbout(final String question) {
        log.info("Asking about: {}", question);
        final var relatedChunks = retrievalService.findRelatedChunksTo(question);

        final ChatResponse chatResponse = chatClient.prompt()
                                                    .user(u -> u.text(USER_QUESTION_WITH_RAG_RESOURCES)
                                                                .param("context", relatedChunks)
                                                                .param("question", question))
                                                    .advisors(SimpleLoggerAdvisor.builder().build())
                                                    .call()
                                                    .chatResponse();

        log.info("Answer to the question: {} is: {}", question, chatResponse);
        assert chatResponse != null;
        String answer = chatResponse.getResult().getOutput().getText();
        return Set.of(answer);
    }
}
