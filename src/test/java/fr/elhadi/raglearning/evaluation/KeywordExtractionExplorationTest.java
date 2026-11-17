package fr.elhadi.raglearning.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Exploration : affiche les mots-clés que le LLM extrait de chaque question du jeu d'évaluation,
 * avec le prompt {@code prompts/keyword-extraction.st}. Sert à mettre au point ce prompt avant
 * de l'utiliser dans la recherche par mots-clés.
 * <pre>RAG_EVALUATION=true ./mvnw test -Dtest=KeywordExtractionExplorationTest</pre>
 */
@SpringBootTest(properties = "rag.ingestion.enabled=false")
@EnabledIfEnvironmentVariable(named = "RAG_EVALUATION", matches = "true")
class KeywordExtractionExplorationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Value("classpath:prompts/keyword-extraction.st")
    private Resource keywordExtractionPrompt;

    @Test
    void printExtractedKeywords() throws IOException {
        final ChatClient chatClient = chatClientBuilder.defaultSystem(keywordExtractionPrompt).build();
        final List<RetrievalEvaluationTest.EvaluationCase> cases = RetrievalEvaluationTest.loadCases();

        System.out.println("\n=== Mots-clés extraits ===");
        for (int index = 0; index < cases.size(); index++) {
            final String question = cases.get(index).label();
            final ChatResponse response = chatClient.prompt()
                .user(question)
                .call()
                .chatResponse();
            System.out.printf("%n#%d %s%n   -> %s%n", index + 1, question, text(response));
        }
    }

    /** Assemble les blocs de texte de la réponse, en ignorant un éventuel bloc de réflexion vide. */
    private String text(ChatResponse response) {
        return response == null ? "" : response.getResults().stream()
            .map(result -> result.getOutput().getText())
            .filter(Objects::nonNull)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining(" "));
    }
}
