package fr.elhadi.raglearning.evaluation;

import fr.elhadi.raglearning.retrieval.keyword.KeywordExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;

/**
 * Exploration : affiche les mots-clés que {@link KeywordExtractor} extrait de chaque question du
 * jeu d'évaluation (appel réel au LLM, avec le prompt {@code prompts/keyword-extraction.st}, puis
 * nettoyage). Sert à vérifier le prompt et la sortie structurée ; ce n'est pas un test de qualité
 * de la recherche.
 * <pre>RAG_EVALUATION=true ./mvnw test -Dtest=KeywordExtractionExplorationTest</pre>
 */
@SpringBootTest(properties = "rag.ingestion.enabled=false")
@EnabledIfEnvironmentVariable(named = "RAG_EVALUATION", matches = "true")
class KeywordExtractionExplorationTest {

    @Autowired
    private KeywordExtractor keywordExtractor;

    @Test
    void printExtractedKeywords() throws IOException {
        final List<RetrievalEvaluationTest.EvaluationCase> cases = RetrievalEvaluationTest.loadCases();

        System.out.println("\n=== Mots-clés extraits ===");
        for (int index = 0; index < cases.size(); index++) {
            final String question = cases.get(index).label();
            final List<String> keywords = keywordExtractor.extract(question);
            System.out.printf("%n#%d %s%n   -> %s%n", index + 1, question, keywords);
        }
    }
}
