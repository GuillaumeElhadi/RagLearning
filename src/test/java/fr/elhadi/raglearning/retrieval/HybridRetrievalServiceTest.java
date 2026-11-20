package fr.elhadi.raglearning.retrieval;

import fr.elhadi.raglearning.retrieval.keyword.KeywordExtractor;
import fr.elhadi.raglearning.retrieval.keyword.KeywordSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teste l'<em>orchestration</em> de {@link HybridRetrievalService} : quels collaborateurs sont appelés,
 * avec quoi, et comment leurs résultats sont fusionnés. Les trois collaborateurs sont simulés (leur
 * propre logique est testée ailleurs) ; la fusion, elle, est la vraie {@link ReciprocalRankFusion}.
 *
 * <p>Les résultats attendus ne dépendent pas de la constante de rang de la fusion : ils ne reposent que
 * sur l'ordre des listes et sur la règle de départage (à score égal, premier rencontré, la recherche
 * dense passant avant les mots-clés).
 */
class HybridRetrievalServiceTest {

    private static final String QUESTION = "Why does Kong return a 504?";

    private static final int DEPTH = 30;

    private RetrievalService retrievalService;

    private KeywordExtractor keywordExtractor;

    private KeywordSearchService keywordSearchService;

    private HybridRetrievalService hybridRetrievalService;

    @BeforeEach
    void createService() {
        retrievalService = mock(RetrievalService.class);
        keywordExtractor = mock(KeywordExtractor.class);
        keywordSearchService = mock(KeywordSearchService.class);
        hybridRetrievalService = new HybridRetrievalService(
            keywordSearchService, retrievalService, keywordExtractor, new ReciprocalRankFusion());
    }

    /** La recherche dense retourne ces fils, dans cet ordre. */
    private void givenDenseSearchFinds(final List<String> threadIds) {
        when(retrievalService.findRelatedThreadIds(QUESTION, DEPTH)).thenReturn(threadIds);
    }

    /** L'extracteur propose ces mots-clés, et la recherche par mots-clés retrouve ces fils. */
    private void givenKeywordSearchFinds(final List<String> keywords, final List<String> threadIds) {
        when(keywordExtractor.extract(QUESTION)).thenReturn(keywords);
        when(keywordSearchService.searchByKeywords(keywords, DEPTH)).thenReturn(threadIds);
    }

    @Nested
    class ZeroResults {

        @Test
        void shouldReturnEmptyListWhenNeitherSearchFindsAnything() {
            givenDenseSearchFinds(List.of());
            givenKeywordSearchFinds(List.of("kong"), List.of());

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH)).isEmpty();
        }
    }

    @Nested
    class OneSearchWithResults {

        @Test
        void shouldReturnDenseOrderWhenKeywordSearchFindsNothing() {
            givenDenseSearchFinds(List.of("thread-a", "thread-b", "thread-c"));
            givenKeywordSearchFinds(List.of("kong"), List.of());

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .containsExactly("thread-a", "thread-b", "thread-c");
        }

        @Test
        void shouldReturnKeywordOrderWhenDenseSearchFindsNothing() {
            givenDenseSearchFinds(List.of());
            givenKeywordSearchFinds(List.of("kong"), List.of("thread-x", "thread-y"));

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .containsExactly("thread-x", "thread-y");
        }

        @Test
        void shouldReturnDenseOrderWhenNoKeywordIsExtracted() {
            givenDenseSearchFinds(List.of("thread-a", "thread-b"));
            givenKeywordSearchFinds(List.of(), List.of());

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .containsExactly("thread-a", "thread-b");
        }
    }

    @Nested
    class BothSearchesWithResults {

        @Test
        void shouldRankThreadFoundByBothSearchesBeforeThreadsFoundByOne() {
            // thread-c est 3e en dense et 1er en mots-clés : l'accord des deux le place en tête.
            // thread-b et thread-d ont le même score (2e de leur liste) : premier rencontré, donc le dense.
            givenDenseSearchFinds(List.of("thread-a", "thread-b", "thread-c"));
            givenKeywordSearchFinds(List.of("kong"), List.of("thread-c", "thread-d"));

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .containsExactly("thread-c", "thread-a", "thread-b", "thread-d");
        }

        @Test
        void shouldReturnTheUnionOfBothSearchesWhenTheyShareNothing() {
            // mêmes rangs deux à deux : à égalité, la recherche dense passe avant les mots-clés
            givenDenseSearchFinds(List.of("thread-a", "thread-b"));
            givenKeywordSearchFinds(List.of("kong"), List.of("thread-c", "thread-d"));

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .containsExactly("thread-a", "thread-c", "thread-b", "thread-d");
        }

        @Test
        void shouldReturnEachThreadOnlyOnceWhenBothSearchesFindTheSameThreads() {
            givenDenseSearchFinds(List.of("thread-a", "thread-b"));
            givenKeywordSearchFinds(List.of("kong"), List.of("thread-b", "thread-a"));

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .hasSize(2)
                .containsExactlyInAnyOrder("thread-a", "thread-b");
        }
    }

    @Nested
    class BoundaryConditions {

        @Test
        void shouldKeepAtMostDepthThreadsWhenTheFusionFindsMore() {
            final int depth = 4;
            when(retrievalService.findRelatedThreadIds(QUESTION, depth))
                .thenReturn(List.of("thread-a", "thread-b", "thread-c"));
            when(keywordExtractor.extract(QUESTION)).thenReturn(List.of("kong"));
            when(keywordSearchService.searchByKeywords(List.of("kong"), depth))
                .thenReturn(List.of("thread-d", "thread-e", "thread-f"));

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, depth))
                .containsExactly("thread-a", "thread-d", "thread-b", "thread-e");
        }

        @Test
        void shouldReturnFewerThreadsThanDepthWhenTheSearchesFindFew() {
            givenDenseSearchFinds(List.of("thread-a"));
            givenKeywordSearchFinds(List.of("kong"), List.of());

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH)).containsExactly("thread-a");
        }
    }

    @Nested
    class Interactions {

        @Test
        void shouldSearchDenseWithTheQuestionAndTheDepth() {
            hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH);

            verify(retrievalService).findRelatedThreadIds(QUESTION, DEPTH);
        }

        @Test
        void shouldExtractKeywordsFromTheQuestion() {
            hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH);

            verify(keywordExtractor).extract(QUESTION);
        }

        @Test
        void shouldSearchByKeywordsWithTheExtractedKeywordsAndTheDepth() {
            givenKeywordSearchFinds(List.of("kong", "504"), List.of());

            hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH);

            verify(keywordSearchService).searchByKeywords(List.of("kong", "504"), DEPTH);
        }
    }

    @Nested
    class RankConstant {

        @Test
        void shouldRankThreadFoundLowByBothSearchesBeforeThreadFoundFirstByOnlyOne() {
            // Seul test sensible à la constante de rang de la fusion : thread-both est 50e dans chaque
            // liste, thread-first est 1er dans une seule. Avec 60 : 2/(60+50) > 1/(60+1), thread-both gagne.
            // Avec 30 (la profondeur, par erreur) : 2/(30+50) < 1/(30+1), thread-first gagnerait.
            final int depth = 50;
            final List<String> denseThreadIds = new ArrayList<>();
            denseThreadIds.add("thread-first");
            denseThreadIds.addAll(ranking("dense", 48));
            denseThreadIds.add("thread-both");
            final List<String> keywordThreadIds = new ArrayList<>(ranking("keyword", 49));
            keywordThreadIds.add("thread-both");
            when(retrievalService.findRelatedThreadIds(QUESTION, depth)).thenReturn(denseThreadIds);
            when(keywordExtractor.extract(QUESTION)).thenReturn(List.of("kong"));
            when(keywordSearchService.searchByKeywords(List.of("kong"), depth)).thenReturn(keywordThreadIds);

            assertThat(hybridRetrievalService.findRelatedThreadIds(QUESTION, depth)).first().isEqualTo("thread-both");
        }

        private List<String> ranking(final String prefix, final int size) {
            return IntStream.rangeClosed(1, size).mapToObj(index -> prefix + "-" + index).toList();
        }
    }

    @Nested
    class EmptyQuestion {

        @Test
        void shouldReturnEmptyListWhenQuestionIsNull() {
            assertThat(hybridRetrievalService.findRelatedThreadIds(null, DEPTH)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWithoutSearchingWhenQuestionIsBlank() {
            assertThat(hybridRetrievalService.findRelatedThreadIds("   ", DEPTH)).isEmpty();

            verifyNoInteractions(retrievalService, keywordExtractor, keywordSearchService);
        }
    }

    @Nested
    class ErrorConditions {

        @Test
        void shouldPropagateTheFailureWhenKeywordExtractionFails() {
            // pas de repli silencieux sur le dense : il fausserait la mesure (l'hybride aurait l'air du dense)
            givenDenseSearchFinds(List.of("thread-a"));
            when(keywordExtractor.extract(QUESTION)).thenThrow(new IllegalStateException("LLM unavailable"));

            assertThatThrownBy(() -> hybridRetrievalService.findRelatedThreadIds(QUESTION, DEPTH))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldThrowExceptionWhenDepthIsZero() {
            assertThatThrownBy(() -> hybridRetrievalService.findRelatedThreadIds(QUESTION, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldThrowExceptionWhenDepthIsNegative() {
            assertThatThrownBy(() -> hybridRetrievalService.findRelatedThreadIds(QUESTION, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
