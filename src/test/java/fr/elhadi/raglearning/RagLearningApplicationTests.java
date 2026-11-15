package fr.elhadi.raglearning;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Vérifie que le contexte Spring démarre. L'ingestion est désactivée explicitement :
 * sinon le {@code CommandLineIngestor} relancerait une ingestion complète des sources
 * réelles à chaque exécution des tests.
 */
@SpringBootTest(properties = "rag.ingestion.enabled=false")
class RagLearningApplicationTests {

    @Test
    void contextLoads() {
    }

}
