package fr.elhadi.raglearning.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
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
        final RetrievalEvaluationTest.EvaluationCase[] cases = loadCases();

        System.out.println("\n=== Mots-clés extraits ===");
        for (int index = 0; index < cases.length; index++) {
            final ChatResponse response = chatClient.prompt()
                .user(cases[index].question())
                .call()
                .chatResponse();
            System.out.printf("%n#%d %s%n   -> %s%n", index + 1, cases[index].question(), text(response));
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

    private RetrievalEvaluationTest.EvaluationCase[] loadCases() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/evaluation/retrieval-questions.json")) {
            if (input == null) {
                throw new IOException("Jeu d'évaluation introuvable : /evaluation/retrieval-questions.json");
            }
            return JsonMapper.builder().build().readValue(input, RetrievalEvaluationTest.EvaluationCase[].class);
        }
    }
}
