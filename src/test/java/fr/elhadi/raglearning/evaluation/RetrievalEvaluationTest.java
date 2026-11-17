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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedSet;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Mesure la qualité de la recherche sur un jeu de questions réelles, chacune associée aux fils
 * qui y répondent le mieux ({@code src/test/resources/evaluation/retrieval-questions.json}).
 * <p>
 * Chaque question existe en plusieurs <b>variantes par langue</b> ({@code fr}, {@code en}…). Les indicateurs
 * sont calculés <b>par langue</b>, jamais en mélangeant les langues : on compare ainsi la même question posée
 * en français et en anglais, sur un corpus surtout anglophone.
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

    /** Langue de référence : celle des utilisateurs, affichée en premier et utilisée pour le libellé. */
    static final String REFERENCE_LANGUAGE = "fr";

    /** Légende affichée sous le rapport, pour le lire sans ouvrir le code. */
    private static final String LEGEND = """

        Légende
          FR, EN  rang, pour la question posée dans cette langue, du premier fil de référence
                  parmi les fils retrouvés (1 = en tête) ; >%d s'il n'est pas dans les %d premiers ;
                  pire rang si la langue a plusieurs formulations
          Hit@5   part des questions dont un fil de référence est dans les 5 premiers
          Hit@10  part des questions dont un fil de référence est dans les 10 premiers,
                  c'est-à-dire dans le contexte réellement envoyé au LLM
          MRR     Mean Reciprocal Rank : moyenne de 1/rang (0 si absent) ;
                  1 = toujours premier, 0,5 = en moyenne deuxième, 0,1 = en moyenne dixième.
                  Contrairement aux Hit@k, il distingue un 1er rang d'un 4e
        """.formatted(SEARCH_DEPTH, SEARCH_DEPTH);

    @Autowired
    private RetrievalService retrievalService;

    /**
     * Une question du jeu : ses formulations par langue et les fils qui y répondent.
     * Jackson produit une {@link LinkedHashMap} : l'ordre des langues est celui du fichier.
     */
    record EvaluationCase(Map<String, List<String>> variants, List<String> expectedThreadIds) {

        List<String> variantsIn(String language) {
            return variants.getOrDefault(language, List.of());
        }

        /** Libellé court de la question, dans la langue de référence. */
        String label() {
            return variantsIn(REFERENCE_LANGUAGE).stream().findFirst().orElse("(sans variante fr)");
        }
    }

    /** Le rang retenu pour chaque langue (vide = aucun fil de référence dans les {@value SEARCH_DEPTH} premiers). */
    record CaseResult(EvaluationCase evaluationCase, Map<String, OptionalInt> rankByLanguage) {

        OptionalInt rankIn(String language) {
            return rankByLanguage.get(language);
        }
    }

    @Test
    void reportRetrievalQuality() throws IOException {
        final List<EvaluationCase> cases = loadCases();
        assertFalse(cases.isEmpty(), "Le jeu d'évaluation est vide");

        final SequencedSet<String> languages = languagesOf(cases);
        assertEveryCaseHasEveryLanguage(cases, languages);

        final List<CaseResult> results = cases.stream()
            .map(evaluationCase -> evaluate(evaluationCase, languages))
            .toList();

        System.out.println(report(results, languages));
    }

    private CaseResult evaluate(EvaluationCase evaluationCase, SequencedSet<String> languages) {
        final Map<String, OptionalInt> rankByLanguage = new LinkedHashMap<>();
        for (String language : languages) {
            final List<OptionalInt> variantRanks = evaluationCase.variantsIn(language).stream()
                .map(question -> rankOf(question, evaluationCase.expectedThreadIds()))
                .toList();
            rankByLanguage.put(language, worstRank(variantRanks));
        }
        return new CaseResult(evaluationCase, rankByLanguage);
    }

    /** Rang du premier fil de référence parmi les fils distincts retrouvés pour cette formulation. */
    private OptionalInt rankOf(String question, List<String> expectedThreadIds) {
        final List<String> rankedThreadIds = retrievalService
            .findRelatedChunksTo(question, SEARCH_DEPTH)
            .stream()
            .map(RetrievedChunk::metadata)
            .map(metadata -> String.valueOf(metadata.get("threadId")))
            .distinct()
            .toList();
        for (int index = 0; index < rankedThreadIds.size(); index++) {
            if (expectedThreadIds.contains(rankedThreadIds.get(index))) {
                return OptionalInt.of(index + 1);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Quand une langue a plusieurs formulations, on retient le <b>pire rang</b> : c'est le choix le plus
     * exigeant, un système robuste doit réussir quelle que soit la formulation. Une formulation sans fil de
     * référence trouvé rend la question ratée pour cette langue.
     */
    private OptionalInt worstRank(List<OptionalInt> variantRanks) {
        if (variantRanks.stream().anyMatch(OptionalInt::isEmpty)) {
            return OptionalInt.empty();
        }
        return variantRanks.stream().mapToInt(OptionalInt::getAsInt).max();
    }

    private String report(List<CaseResult> results, SequencedSet<String> languages) {
        final StringBuilder report = new StringBuilder("\n=== Évaluation de la recherche ===\n");

        report.append(String.format(Locale.ROOT, "%-4s", "#"));
        languages.forEach(language -> report.append(String.format(Locale.ROOT, "%-6s", language.toUpperCase(Locale.ROOT))));
        report.append("Question\n");

        for (int index = 0; index < results.size(); index++) {
            final CaseResult result = results.get(index);
            report.append(String.format(Locale.ROOT, "%-4d", index + 1));
            for (String language : languages) {
                report.append(String.format(Locale.ROOT, "%-6s", format(result.rankIn(language))));
            }
            report.append(abbreviate(result.evaluationCase().label())).append('\n');
        }

        report.append(String.format(Locale.ROOT, "%n%-6s %8s %8s %8s%n", "Langue", "Hit@5", "Hit@10", "MRR"));
        for (String language : languages) {
            report.append(String.format(Locale.ROOT, "%-6s %7.0f%% %7.0f%% %8.3f%n",
                language.toUpperCase(Locale.ROOT),
                100 * hitRate(results, language, 5),
                100 * hitRate(results, language, 10),
                meanReciprocalRank(results, language)));
        }
        report.append(LEGEND);
        return report.toString();
    }

    private String format(OptionalInt rank) {
        return rank.isPresent() ? String.valueOf(rank.getAsInt()) : ">" + SEARCH_DEPTH;
    }

    private double hitRate(List<CaseResult> results, String language, int cutoff) {
        return results.stream()
            .map(result -> result.rankIn(language))
            .filter(rank -> rank.isPresent() && rank.getAsInt() <= cutoff)
            .count() / (double) results.size();
    }

    private double meanReciprocalRank(List<CaseResult> results, String language) {
        return results.stream()
            .map(result -> result.rankIn(language))
            .mapToDouble(rank -> rank.isPresent() ? 1.0 / rank.getAsInt() : 0.0)
            .average()
            .orElse(0.0);
    }

    /** Toutes les langues présentes dans le jeu, dans l'ordre où elles apparaissent. */
    private SequencedSet<String> languagesOf(List<EvaluationCase> cases) {
        final SequencedSet<String> languages = new LinkedHashSet<>();
        cases.forEach(evaluationCase -> languages.addAll(evaluationCase.variants().keySet()));
        return languages;
    }

    /**
     * Les indicateurs de deux langues ne sont comparables que s'ils portent sur les mêmes questions :
     * une question sans variante dans une langue fausserait la comparaison.
     */
    private void assertEveryCaseHasEveryLanguage(List<EvaluationCase> cases, SequencedSet<String> languages) {
        for (int index = 0; index < cases.size(); index++) {
            for (String language : languages) {
                assertFalse(cases.get(index).variantsIn(language).isEmpty(),
                    "La question " + (index + 1) + " n'a pas de variante '" + language + "'");
            }
        }
    }

    private String abbreviate(String question) {
        return question.length() <= 90 ? question : question.substring(0, 87) + "...";
    }

    static List<EvaluationCase> loadCases() throws IOException {
        try (InputStream input = RetrievalEvaluationTest.class.getResourceAsStream("/evaluation/retrieval-questions.json")) {
            if (input == null) {
                throw new IOException("Jeu d'évaluation introuvable : /evaluation/retrieval-questions.json");
            }
            return Arrays.asList(JsonMapper.builder().build().readValue(input, EvaluationCase[].class));
        }
    }
}
