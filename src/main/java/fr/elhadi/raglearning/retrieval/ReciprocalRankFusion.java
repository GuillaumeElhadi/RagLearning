package fr.elhadi.raglearning.retrieval;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReciprocalRankFusion {
    /**
     * Fusionne des classements déjà triés du plus pertinent au moins pertinent avec
     * le score réciproque {@code 1 / (position + 1 + rankConstant)}.
     *
     * <p>Un élément répété dans un même classement ne contribue qu'à sa première
     * position et n'apparaît qu'une fois dans le résultat. Les éléments sont ordonnés
     * par score décroissant; à score égal, l'ordre de première rencontre est conservé
     * (ordre des classements, puis ordre des éléments).
     *
     * <p>Chaque division est arrondie selon {@link MathContext#DECIMAL128}, puis les
     * contributions sont accumulées exactement et indépendamment de leur ordre. La
     * précision finie des divisions peut toutefois créer ou masquer certaines égalités
     * mathématiques ou distinctions entre scores.
     *
     * @param rankings classements triés; une liste externe ou interne nulle est ignorée
     * @param rankConstant constante de rang non négative, fournie par l'appelant (par exemple 60)
     * @return éléments uniques ordonnés par score décroissant
     * @throws IllegalArgumentException si {@code rankConstant} est négatif
     */
    public List<String> fuse(final List<List<String>> rankings, final int rankConstant) {
        if (rankConstant < 0) {
            throw new IllegalArgumentException("rankConstant must be non-negative");
        }

        final Map<String, BigDecimal> scores = calculateScores(rankings, rankConstant);
        if (scores.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Map.Entry<String, BigDecimal>> rankedElements = sortByScore(scores);
        final List<String>                    fusedList      = new ArrayList<>(rankedElements.size());
        for (Map.Entry<String, BigDecimal> entry : rankedElements) {
            fusedList.add(entry.getKey());
        }
        return fusedList;
    }

    private Map<String, BigDecimal> calculateScores(
        final List<List<String>> rankings, final int rankConstant) {
        final Map<String, BigDecimal> scores = new LinkedHashMap<>();
        if (rankings == null || rankings.isEmpty()) {
            return scores;
        }

        for (final List<String> ranking : rankings) {
            if (ranking == null || ranking.isEmpty()) {
                continue;
            }

            addRankingScores(scores, ranking, rankConstant);
        }
        return scores;
    }

    private void addRankingScores(
        final Map<String, BigDecimal> scores,
        final List<String> ranking,
        final int rankConstant
    ) {
        final Set<String> seenInRanking = new HashSet<>();
        for (int position = 0; position < ranking.size(); position++) {
            final String element = ranking.get(position);
            if (!seenInRanking.add(element)) {
                continue;
            }

            final BigDecimal contribution = BigDecimal.ONE.divide(
                BigDecimal.valueOf((long) position + rankConstant + 1L),
                MathContext.DECIMAL128
            );
            scores.merge(element, contribution, BigDecimal::add);
        }
    }

    private List<Map.Entry<String, BigDecimal>> sortByScore(final Map<String, BigDecimal> scores) {
        final List<Map.Entry<String, BigDecimal>> orderedEntries =
            new ArrayList<>(scores.entrySet());
        orderedEntries.sort(Map.Entry.<String, BigDecimal>comparingByValue(BigDecimal::compareTo).reversed());
        return orderedEntries;
    }
}
