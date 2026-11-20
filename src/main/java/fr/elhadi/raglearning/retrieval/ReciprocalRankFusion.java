package fr.elhadi.raglearning.retrieval;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
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
        final List<Integer> equalWeights =
            rankings == null ? List.of() : Collections.nCopies(rankings.size(), 1);
        return fuse(rankings, equalWeights, rankConstant);
    }

    /**
     * Fusionne des classements comme {@link #fuse(List, int)}, en donnant à chacun un <strong>poids</strong> :
     * l'élément à la position {@code p} d'un classement de poids {@code w} marque
     * {@code w / (p + 1 + rankConstant)}. Un poids {@code 2} revient à compter le classement deux fois ;
     * un poids {@code 0} l'écarte (ses éléments n'apparaissent pas dans le résultat, sauf s'ils sont
     * dans un autre classement).
     *
     * <p>Les poids sont des entiers : les scores restent exacts et la fusion reste déterministe.
     * Les règles de {@link #fuse(List, int)} (doublons, départage, arrondi) s'appliquent à l'identique.
     *
     * @param rankings classements triés; une liste externe ou interne nulle est ignorée
     * @param weights un poids non négatif par classement, dans le même ordre que {@code rankings}
     * @param rankConstant constante de rang non négative, fournie par l'appelant (par exemple 60)
     * @return éléments uniques ordonnés par score décroissant
     * @throws IllegalArgumentException si {@code rankConstant} est négatif, si {@code weights} est
     *     {@code null}, n'a pas autant d'éléments que {@code rankings}, ou contient une valeur négative
     */
    public List<String> fuse(final List<List<String>> rankings, final List<Integer> weights, final int rankConstant) {
        if (rankConstant < 0) {
            throw new IllegalArgumentException("rankConstant must be non-negative");
        }

        if (rankings == null || rankings.isEmpty()) {
            return Collections.emptyList();
        }
        validateWeights(rankings, weights);

        final Map<String, BigDecimal> scores = calculateScores(rankings, weights, rankConstant);
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

    private void validateWeights(final List<List<String>> rankings, final List<Integer> weights) {
        if (weights == null || weights.size() != rankings.size()) {
            throw new IllegalArgumentException("There must be exactly one weight per ranking");
        }
        for (final Integer weight : weights) {
            if (weight == null || weight < 0) {
                throw new IllegalArgumentException("Weights must be non-negative");
            }
        }
    }

    private Map<String, BigDecimal> calculateScores(
        final List<List<String>> rankings, final List<Integer> weights, final int rankConstant) {
        final Map<String, BigDecimal> scores = new LinkedHashMap<>();
        for (int index = 0; index < rankings.size(); index++) {
            final List<String> ranking = rankings.get(index);
            final int          weight  = weights.get(index);
            if (ranking == null || ranking.isEmpty() || weight == 0) {
                continue;
            }

            addRankingScores(scores, ranking, weight, rankConstant);
        }
        return scores;
    }

    private void addRankingScores(
        final Map<String, BigDecimal> scores,
        final List<String> ranking,
        final int weight,
        final int rankConstant
    ) {
        final Set<String> seenInRanking = new HashSet<>();
        for (int position = 0; position < ranking.size(); position++) {
            final String element = ranking.get(position);
            if (!seenInRanking.add(element)) {
                continue;
            }

            final BigDecimal contribution = BigDecimal.valueOf(weight).divide(
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
