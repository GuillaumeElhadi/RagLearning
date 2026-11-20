package fr.elhadi.raglearning.retrieval.keyword;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste {@link KeywordExtractor#normalize(List)}, la politique appliquée à la sortie du LLM.
 * L'appel au LLM lui-même n'est pas testé ici : il l'est par l'exploration
 * ({@code KeywordExtractionExplorationTest}).
 */
class KeywordExtractorTest {

    @Nested
    class ZeroKeywords {

        @Test
        void shouldReturnEmptyListWhenKeywordsAreNull() {
            assertThat(KeywordExtractor.normalize(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenKeywordsAreEmpty() {
            assertThat(KeywordExtractor.normalize(List.of())).isEmpty();
        }
    }

    @Nested
    class OneKeyword {

        @Test
        void shouldKeepAnOrdinaryKeywordUnchanged() {
            assertThat(KeywordExtractor.normalize(List.of("Kong"))).containsExactly("Kong");
        }

        @Test
        void shouldKeepErrorCodeAndAccentedKeywordUnchanged() {
            assertThat(KeywordExtractor.normalize(List.of("429", "paramètre"))).containsExactly("429", "paramètre");
        }

        @Test
        void shouldDropKeywordMatchingAnAnonymizationPlaceholder() {
            assertThat(KeywordExtractor.normalize(List.of("host"))).isEmpty();
        }

        @Test
        void shouldDropPlaceholderKeywordRegardlessOfCase() {
            assertThat(KeywordExtractor.normalize(List.of("IP", "Uuid", "REDACTED"))).isEmpty();
        }

        @Test
        void shouldDropBlankKeyword() {
            assertThat(KeywordExtractor.normalize(List.of("   ", ""))).isEmpty();
        }
    }

    @Nested
    class MultipleKeywords {

        @Test
        void shouldDropOnlyPlaceholderKeywordsAndKeepTheOthers() {
            assertThat(KeywordExtractor.normalize(List.of("kong", "host", "429", "uuid")))
                .containsExactly("kong", "429");
        }

        @Test
        void shouldKeepTheOrderOfKeywords() {
            assertThat(KeywordExtractor.normalize(List.of("prod", "kong", "429")))
                .containsExactly("prod", "kong", "429");
        }

        @Test
        void shouldDropNullElements() {
            assertThat(KeywordExtractor.normalize(Arrays.asList("kong", null, "429")))
                .containsExactly("kong", "429");
        }
    }

    @Nested
    class BoundaryConditions {

        @Test
        void shouldKeepAtMostEightKeywordsInTheirOriginalOrder() {
            final List<String> tenKeywords = List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10");

            assertThat(KeywordExtractor.normalize(tenKeywords))
                .containsExactly("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8");
        }

        @Test
        void shouldKeepEightKeywordsWhenExactlyEightAreGiven() {
            final List<String> eightKeywords = List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8");

            assertThat(KeywordExtractor.normalize(eightKeywords)).containsExactlyElementsOf(eightKeywords);
        }

        @Test
        void shouldNotCountDroppedKeywordsInTheLimit() {
            final List<String> keywords = List.of("host", "k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9");

            assertThat(KeywordExtractor.normalize(keywords))
                .containsExactly("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8");
        }
    }

}
