package fr.elhadi.raglearning.retrieval;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReciprocalRankFusionTest {

    private static final int RANK_CONSTANT = 60;

    private final ReciprocalRankFusion reciprocalRankFusion = new ReciprocalRankFusion();

    @Nested
    class Zero {
        @Test
        void whenAllThreadListAreNullEmptyListIsReturned() {
            final List<String> result = reciprocalRankFusion.fuse(null, RANK_CONSTANT);

            assertEquals(List.of(), result);
        }

        @Test
        void whenAllThreadListAreEmptyEmptyListIsReturned() {
            List<List<String>> rankings = List.of(List.of(), List.of());
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of(), result);
        }

        @Test
        void whenOneThreadListIsNullThenNonNullListIsReturnedIsSameOrder() {
            List<List<String>> rankings = new ArrayList<>();
            rankings.add(null);
            rankings.add(List.of("test"));
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("test"), result);
        }

        @Test
        void whenOneThreadListIsEmptyThenNonEmptyListIsReturnedIsSameOrder() {
            List<List<String>> rankings = new ArrayList<>();
            rankings.add(List.of("test"));
            rankings.add(List.of());
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("test"), result);
        }
    }

    @Nested
    class One {
        @Test
        void whenSameThreadAppearsInTwoRankingsThenItIsReturnedOnce() {
            List<String> listA = List.of("test");
            List<String> listB = List.of("test");
            final List<String> result = reciprocalRankFusion.fuse(List.of(listA, listB), RANK_CONSTANT);

            assertEquals(List.of("test"), result);
        }

        @Test
        void whenOnlyOneRankingIsProvidedThenItsOrderIsPreservedWithoutMutation() {
            List<String> ranking = new ArrayList<>(List.of("C", "B", "A"));
            List<String> originalRanking = new ArrayList<>(ranking);
            List<List<String>> rankings = List.of(ranking);

            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("C", "B", "A"), result);
            assertEquals(originalRanking, ranking);
        }
    }

    @Nested
    class Multiple {
        @Test
        void whenElementsAppearAcrossRankingsThenReciprocalScoresDetermineOrder() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B"),
                    List.of("B", "C")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("B", "A", "C"), result);
        }

        @Test
        void whenElementRepeatsWithinRankingThenOnlyItsFirstPositionContributes() {
            List<List<String>> rankings = List.of(
                    List.of("A", "A", "B"),
                    List.of("B")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("B", "A"), result);
        }

        @Test
        void whenRankingsAgreeOnLowerRanksThenTheConsensusElementRanksFirst() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B", "C", "D"),
                    List.of("D", "C", "E")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("D", "C", "A", "B", "E"), result);
        }

        @Test
        void whenThreeRankingsFavorTheSameElementThenItRanksFirst() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B"),
                    List.of("B", "C"),
                    List.of("C", "B")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("B", "C", "A"), result);
        }

        @Test
        void whenRankingsAreDisjointThenEqualScoresKeepFirstEncounterOrder() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B"),
                    List.of("C", "D")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("A", "C", "B", "D"), result);
        }
    }

    @Nested
    class InvalidInput {
        @Test
        void whenRankConstantIsNegativeThenAnExceptionIsThrown() {
            assertThrows(IllegalArgumentException.class,
                    () -> reciprocalRankFusion.fuse(List.of(List.of("A")), -1));
        }
    }

    @Nested
    class Behavior {
        @Test
        void whenRankingsGiveEqualScoresThenFirstEncounteredElementRanksFirst() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B"),
                    List.of("B", "A")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("A", "B"), result);
        }

        @Test
        void whenEqualMathematicalScoresAreAccumulatedInDifferentOrdersThenFirstEncounterWins() {
            List<List<String>> rankings = List.of(
                    List.of("A", "B", "C", "D", "E", "F", "G"),
                    List.of("B", "C", "D", "E", "F", "G", "A"),
                    List.of("C", "D", "E", "F", "G", "A", "B"),
                    List.of("D", "E", "F", "G", "A", "B", "C"),
                    List.of("E", "F", "G", "A", "B", "C", "D"),
                    List.of("F", "G", "A", "B", "C", "D", "E"),
                    List.of("G", "A", "B", "C", "D", "E", "F")
            );
            final List<String> result = reciprocalRankFusion.fuse(rankings, RANK_CONSTANT);

            assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"), result);
        }
    }

    @Nested
    class Weighted {

        @Test
        void whenAllWeightsAreOneThenTheResultMatchesTheUnweightedFusion() {
            final List<List<String>> rankings = List.of(List.of("A", "B", "C"), List.of("C", "B", "D"));

            assertEquals(
                reciprocalRankFusion.fuse(rankings, RANK_CONSTANT),
                reciprocalRankFusion.fuse(rankings, List.of(1, 1), RANK_CONSTANT));
        }

        @Test
        void whenTwoRankingsDisagreeThenTheHeavierRankingDecidesTheOrder() {
            final List<List<String>> rankings = List.of(List.of("A", "B"), List.of("B", "A"));

            assertEquals(List.of("A", "B"), reciprocalRankFusion.fuse(rankings, List.of(2, 1), RANK_CONSTANT));
            assertEquals(List.of("B", "A"), reciprocalRankFusion.fuse(rankings, List.of(1, 2), RANK_CONSTANT));
        }

        @Test
        void whenRankingsAreWeightedEqualAndDisagreeThenFirstEncounteredElementRanksFirst() {
            final List<List<String>> rankings = List.of(List.of("A", "B"), List.of("B", "A"));

            assertEquals(List.of("A", "B"), reciprocalRankFusion.fuse(rankings, List.of(1, 1), RANK_CONSTANT));
        }

        @Test
        void whenAWeightIsNThenTheRankingCountsAsIfItWasProvidedNTimes() {
            final List<String> first = List.of("A", "B", "C");
            final List<String> second = List.of("C", "B", "D");

            assertEquals(
                reciprocalRankFusion.fuse(List.of(first, second, second, second), RANK_CONSTANT),
                reciprocalRankFusion.fuse(List.of(first, second), List.of(1, 3), RANK_CONSTANT));
        }

        @Test
        void whenAWeightIsZeroThenItsRankingIsIgnoredAndItsOwnElementsAreAbsent() {
            final List<List<String>> rankings = List.of(List.of("A", "B"), List.of("C", "B"));

            assertEquals(List.of("A", "B"), reciprocalRankFusion.fuse(rankings, List.of(1, 0), RANK_CONSTANT));
        }

        @Test
        void whenAllWeightsAreZeroThenEmptyListIsReturned() {
            final List<List<String>> rankings = List.of(List.of("A"), List.of("B"));

            assertEquals(List.of(), reciprocalRankFusion.fuse(rankings, List.of(0, 0), RANK_CONSTANT));
        }

        @Test
        void whenRankingsAreNullThenEmptyListIsReturned() {
            assertEquals(List.of(), reciprocalRankFusion.fuse(null, List.of(), RANK_CONSTANT));
        }

        @Test
        void whenAWeightedRankingIsEmptyThenOnlyTheOtherRankingCounts() {
            final List<List<String>> rankings = List.of(List.of(), List.of("A", "B"));

            assertEquals(List.of("A", "B"), reciprocalRankFusion.fuse(rankings, List.of(5, 1), RANK_CONSTANT));
        }

        @Test
        void whenWeightsAreNullThenAnExceptionIsThrown() {
            final List<List<String>> rankings = List.of(List.of("A"));

            assertThrows(IllegalArgumentException.class, () -> reciprocalRankFusion.fuse(rankings, null, RANK_CONSTANT));
        }

        @Test
        void whenThereIsNotExactlyOneWeightPerRankingThenAnExceptionIsThrown() {
            final List<List<String>> rankings = List.of(List.of("A"), List.of("B"));

            assertThrows(IllegalArgumentException.class, () -> reciprocalRankFusion.fuse(rankings, List.of(1), RANK_CONSTANT));
            assertThrows(IllegalArgumentException.class, () -> reciprocalRankFusion.fuse(rankings, List.of(1, 1, 1), RANK_CONSTANT));
        }

        @Test
        void whenAWeightIsNegativeThenAnExceptionIsThrown() {
            final List<List<String>> rankings = List.of(List.of("A"), List.of("B"));

            assertThrows(IllegalArgumentException.class, () -> reciprocalRankFusion.fuse(rankings, List.of(1, -1), RANK_CONSTANT));
        }

        @Test
        void whenRankConstantIsNegativeThenAnExceptionIsThrown() {
            final List<List<String>> rankings = List.of(List.of("A"));

            assertThrows(IllegalArgumentException.class, () -> reciprocalRankFusion.fuse(rankings, List.of(1), -1));
        }
    }
}
