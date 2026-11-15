package fr.elhadi.raglearning.evaluation;

import fr.elhadi.raglearning.models.RetrievedChunk;
import fr.elhadi.raglearning.services.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Mesure la qualité de la recherche sur un jeu de questions réelles, chacune associée aux fils
 * qui y répondent le mieux ({@code src/test/resources/evaluation/retrieval-questions.json}).
 * <p>
 * Ce n'est pas un test unitaire : il interroge la vraie base et le vrai modèle d'embedding.
 * Il ne s'exécute donc que sur demande :
 * <pre>RAG_EVALUATION=true ./mvnw test -Dtest=RetrievalEvaluationTest</pre>
 * Indicateurs :
 * <ul>
 *   <li><b>Hit@5 / Hit@10</b> : part des questions dont un fil de référence est dans les 5 ou 10 premiers
 *   (10 = ce que reçoit le modèle) ;</li>
 *   <li><b>MRR</b> (<i>Mean Reciprocal Rank</i>) : moyenne de 1/rang du premier fil de référence,
 *   0 s'il est absent. 1 = toujours premier ; 0,5 = en moyenne deuxième.</li>
 * </ul>
 */
@SpringBootTest(properties = "rag.ingestion.enabled=false")
@EnabledIfEnvironmentVariable(named = "RAG_EVALUATION", matches = "true")
class RetrievalEvaluationTest {

    /** Profondeur de recherche : au-delà du topK de production, pour voir les fils « presque trouvés ». */
    private static final int SEARCH_DEPTH = 30;

    @Autowired
    private RetrievalService retrievalService;

    record EvaluationCase(String question, List<String> expectedThreadIds) {}

    record CaseResult(EvaluationCase evaluationCase, OptionalInt rank) {}

    @Test
    void reportRetrievalQuality() throws IOException {
        final List<CaseResult> results = loadCases().stream().map(this::evaluate).toList();

        System.out.println(report(results));

        assertFalse(results.isEmpty(), "Le jeu d'évaluation est vide");
    }

    private CaseResult evaluate(EvaluationCase evaluationCase) {
        final List<String> rankedThreadIds = retrievalService
            .findRelatedChunksTo(evaluationCase.question(), SEARCH_DEPTH)
            .stream()
            .map(RetrievedChunk::metadata)
            .map(metadata -> String.valueOf(metadata.get("threadId")))
            .distinct()
            .toList();
        for (int index = 0; index < rankedThreadIds.size(); index++) {
            if (evaluationCase.expectedThreadIds().contains(rankedThreadIds.get(index))) {
                return new CaseResult(evaluationCase, OptionalInt.of(index + 1));
            }
        }
        return new CaseResult(evaluationCase, OptionalInt.empty());
    }

    private String report(List<CaseResult> results) {
        final StringBuilder report = new StringBuilder("\n=== Évaluation de la recherche ===\n");
        report.append(String.format(Locale.ROOT, "%-4s %-6s %s%n", "#", "Rang", "Question"));
        for (int index = 0; index < results.size(); index++) {
            final CaseResult result = results.get(index);
            final String rank = result.rank().isPresent()
                ? String.valueOf(result.rank().getAsInt())
                : ">" + SEARCH_DEPTH;
            report.append(String.format(Locale.ROOT, "%-4d %-6s %s%n",
                index + 1, rank, abbreviate(result.evaluationCase().question())));
        }
        report.append(String.format(Locale.ROOT, "%nHit@5  : %.0f %%%n", 100 * hitRate(results, 5)));
        report.append(String.format(Locale.ROOT, "Hit@10 : %.0f %%%n", 100 * hitRate(results, 10)));
        report.append(String.format(Locale.ROOT, "MRR    : %.3f%n", meanReciprocalRank(results)));
        return report.toString();
    }

    private double hitRate(List<CaseResult> results, int cutoff) {
        return results.stream()
            .filter(result -> result.rank().isPresent() && result.rank().getAsInt() <= cutoff)
            .count() / (double) results.size();
    }

    private double meanReciprocalRank(List<CaseResult> results) {
        return results.stream()
            .mapToDouble(result -> result.rank().isPresent() ? 1.0 / result.rank().getAsInt() : 0.0)
            .average()
            .orElse(0.0);
    }

    private String abbreviate(String question) {
        return question.length() <= 90 ? question : question.substring(0, 87) + "...";
    }

    private List<EvaluationCase> loadCases() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/evaluation/retrieval-questions.json")) {
            if (input == null) {
                throw new IOException("Jeu d'évaluation introuvable : /evaluation/retrieval-questions.json");
            }
            return Arrays.asList(JsonMapper.builder().build().readValue(input, EvaluationCase[].class));
        }
    }
}
