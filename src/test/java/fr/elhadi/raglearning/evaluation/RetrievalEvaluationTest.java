package fr.elhadi.raglearning.evaluation;

import fr.elhadi.raglearning.retrieval.HybridRetrievalService;
import fr.elhadi.raglearning.retrieval.RetrievalService;
import fr.elhadi.raglearning.retrieval.keyword.KeywordExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SequencedSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Mesure la qualité de la recherche sur un jeu de questions réelles, chacune associée aux fils
 * qui y répondent le mieux ({@code src/test/resources/evaluation/retrieval-questions.json}).
 * <p>
 * Chaque question existe en plusieurs <b>variantes par langue</b> ({@code fr}, {@code en}…). Les indicateurs
 * sont calculés <b>par langue</b>, jamais en mélangeant les langues : on compare ainsi la même question posée
 * en français et en anglais, sur un corpus surtout anglophone.
 * <p>
 * Chaque question est aussi posée à plusieurs <b>stratégies de recherche</b>, à la même profondeur : la recherche
 * <b>dense</b> seule, et la recherche <b>hybride</b> (dense + mots-clés, fusion RRF) avec trois pondérations
 * (dense:mots-clés = 1:1, 2:1 et 4:1). Le rapport les met côte à côte, puis affiche les mots-clés réellement
 * extraits par le LLM : ils changent d'une exécution à l'autre, c'est ce qui permet d'expliquer un écart de rang.
 * <p>
 * Au sein d'une même exécution, les stratégies hybrides <b>partagent la même extraction</b> de mots-clés pour
 * une question donnée : la comparaison des pondérations est <i>appariée</i>, le bruit du LLM n'y entre pas.
 * L'extraction est tirée de nouveau à chaque exécution.
 * <p>
 * L'extraction des mots-clés par le LLM n'est pas déterministe : une seule exécution ne permet pas de dire si
 * un écart de rang est un effet réel ou du bruit. Le banc répète donc toute la mesure
 * ({@value #DEFAULT_RUNS} fois par défaut, réglable avec {@code RAG_EVALUATION_RUNS}) et rapporte, par question,
 * le <b>min, la moyenne et le max</b> du rang. Un rang qui ne bouge pas d'une exécution à l'autre est fiable ;
 * un rang qui varie beaucoup ne doit pas servir à conclure. La recherche dense, déterministe, sert de témoin :
 * son min, sa moyenne et son max doivent être égaux.
 * <p>
 * Ce n'est pas un test unitaire : il interroge la vraie base, le vrai modèle d'embedding et le vrai LLM.
 * Il ne s'exécute donc que sur demande :
 * <pre>RAG_EVALUATION=true RAG_EVALUATION_RUNS=5 ./mvnw test -Dtest=RetrievalEvaluationTest</pre>
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

    /** Variable d'environnement qui fixe le nombre d'exécutions de toute la mesure. */
    private static final String RUNS_ENV = "RAG_EVALUATION_RUNS";

    /** Nombre d'exécutions quand {@value #RUNS_ENV} n'est pas définie. */
    static final int DEFAULT_RUNS = 5;

    /** Langue de référence : celle des utilisateurs, affichée en premier et utilisée pour le libellé. */
    static final String REFERENCE_LANGUAGE = "fr";

    /** Légende affichée sous le rapport, pour le lire sans ouvrir le code. */
    private static final String LEGEND = """

        Légende
          FR/dense, FR/hybrid, EN/…  min/moyenne/max, sur toutes les exécutions, du rang du premier fil de
                  référence parmi les fils retrouvés (1 = en tête). Pas de rang dans les %d premiers :
                  affiché >%d, et compté %d dans la moyenne ; pire rang si la langue a plusieurs formulations.
                  Un min et un max éloignés = rang instable : ne pas conclure sur une seule exécution
          dense   recherche vectorielle seule (déterministe : min = moyenne = max, sinon il y a un problème)
          hybrid d:m  recherche dense + recherche par mots-clés (extraits par un LLM), fusion RRF ; d:m est le
                  rapport des poids dense:mots-clés (1:1 = à égalité, 4:1 = la dense compte quatre fois plus).
                  Les lignes hybrides d'une même exécution partagent les mêmes mots-clés
          Hit@5   part des questions dont un fil de référence est dans les 5 premiers (moyenne des exécutions)
          Hit@10  idem pour les 10 premiers, c'est-à-dire le contexte réellement envoyé au LLM
          MRR     Mean Reciprocal Rank : moyenne de 1/rang (0 si absent) ; min, moyenne et max de l'exécution.
                  1 = toujours premier, 0,5 = en moyenne deuxième, 0,1 = en moyenne dixième.
                  L'écart max - min est le bruit de la mesure : une différence entre deux recherches
                  plus petite que cet écart ne prouve rien
          Échecs  appels où le LLM n'a pas rendu un JSON lisible : la question est comptée ratée (rang absent),
                  comme une erreur pour l'utilisateur ; la section dit à quelles questions cela est arrivé
          Mots-clés  chaque liste est précédée du nombre d'exécutions où le LLM l'a produite
        """.formatted(SEARCH_DEPTH, SEARCH_DEPTH, SEARCH_DEPTH + 1);

    /** Une façon de chercher, comparée aux autres à la même profondeur. */
    private record Strategy(String name, BiFunction<String, Integer, List<String>> search) {
    }

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    /** Espion : l'extracteur fait son vrai travail, et on note les mots qu'il a produits pour chaque question. */
    @MockitoSpyBean
    private KeywordExtractor keywordExtractor;

    /** Pour chaque question, les mots-clés produits à chaque exécution (une liste par appel du LLM). */
    private final Map<String, List<List<String>>> keywordsByQuestion = new LinkedHashMap<>();

    /** Pour chaque question, le message de chaque échec de l'extraction (le LLM n'a pas rendu un JSON lisible). */
    private final Map<String, List<String>> extractionFailuresByQuestion = new LinkedHashMap<>();

    /** Mots-clés déjà tirés pour chaque question dans l'exécution en cours, réutilisés par toutes les pondérations. */
    private final Map<String, List<String>> extractionsInCurrentRun = new HashMap<>();

    /** Échecs déjà subis dans l'exécution en cours : un second appel ne doit pas réussir là où le premier a échoué. */
    private final Map<String, ExtractionFailed> failuresInCurrentRun = new HashMap<>();

    /**
     * Échec de l'extraction des mots-clés, signalé par l'espion. Le banc l'attrape pour le compter au lieu de
     * s'arrêter : il ne rattrape que celui-là, une panne de la base ou des embeddings doit toujours le faire échouer.
     */
    private static final class ExtractionFailed extends RuntimeException {

        ExtractionFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void recordExtractedKeywords() {
        keywordsByQuestion.clear();
        extractionFailuresByQuestion.clear();
        startRun();
        doAnswer(invocation -> {
            final String question = invocation.getArgument(0);
            final ExtractionFailed knownFailure = failuresInCurrentRun.get(question);
            if (knownFailure != null) {
                throw knownFailure;
            }
            final List<String> knownKeywords = extractionsInCurrentRun.get(question);
            if (knownKeywords != null) {
                return knownKeywords;
            }
            try {
                final List<String> keywords = (List<String>) invocation.callRealMethod();
                extractionsInCurrentRun.put(question, keywords);
                keywordsByQuestion.computeIfAbsent(question, ignored -> new ArrayList<>()).add(keywords);
                return keywords;
            } catch (RuntimeException failure) {
                final String message = String.valueOf(failure.getMessage()).lines().findFirst().orElse("").strip();
                extractionFailuresByQuestion.computeIfAbsent(question, ignored -> new ArrayList<>()).add(message);
                final ExtractionFailed failed = new ExtractionFailed(message, failure);
                failuresInCurrentRun.put(question, failed);
                throw failed;
            }
        }).when(keywordExtractor).extract(anyString());
    }

    /** Oublie les mots-clés de l'exécution précédente : chaque exécution tire sa propre extraction. */
    private void startRun() {
        extractionsInCurrentRun.clear();
        failuresInCurrentRun.clear();
    }

    private List<Strategy> strategies() {
        return List.of(
            new Strategy("dense", retrievalService::findRelatedThreadIds),
            hybrid(1, 1),
            hybrid(2, 1),
            hybrid(4, 1));
    }

    /** Recherche hybride avec ce rapport de poids dense:mots-clés. */
    private Strategy hybrid(int denseWeight, int keywordWeight) {
        return new Strategy("hybrid " + denseWeight + ":" + keywordWeight,
            (question, depth) -> hybridRetrievalService.findRelatedThreadIds(question, depth, denseWeight, keywordWeight));
    }

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

    /**
     * Le rang retenu pour chaque recherche puis chaque langue (vide = aucun fil de référence dans les
     * {@value SEARCH_DEPTH} premiers).
     */
    record CaseResult(EvaluationCase evaluationCase, Map<String, Map<String, OptionalInt>> rankByStrategyAndLanguage) {

        OptionalInt rankIn(String strategy, String language) {
            return rankByStrategyAndLanguage.get(strategy).get(language);
        }
    }

    @Test
    void reportRetrievalQuality() throws IOException {
        final List<EvaluationCase> cases = loadCases();
        assertFalse(cases.isEmpty(), "Le jeu d'évaluation est vide");

        final SequencedSet<String> languages = languagesOf(cases);
        assertEveryCaseHasEveryLanguage(cases, languages);

        final int runCount = runCount();
        final List<List<CaseResult>> runs = IntStream.rangeClosed(1, runCount)
            .mapToObj(run -> {
                System.out.printf("Exécution %d/%d…%n", run, runCount);
                startRun();
                return cases.stream().map(evaluationCase -> evaluate(evaluationCase, languages)).toList();
            })
            .toList();

        System.out.println(report(runs, languages));
    }

    /** Nombre d'exécutions demandé par {@value #RUNS_ENV}, {@value #DEFAULT_RUNS} par défaut. */
    private int runCount() {
        final String value = System.getenv(RUNS_ENV);
        if (value == null || value.isBlank()) {
            return DEFAULT_RUNS;
        }
        final int runs = Integer.parseInt(value.strip());
        assertTrue(runs >= 1, RUNS_ENV + " doit valoir au moins 1, reçu : " + runs);
        return runs;
    }

    private CaseResult evaluate(EvaluationCase evaluationCase, SequencedSet<String> languages) {
        final Map<String, Map<String, OptionalInt>> rankByStrategyAndLanguage = new LinkedHashMap<>();
        for (Strategy strategy : strategies()) {
            final Map<String, OptionalInt> rankByLanguage = new LinkedHashMap<>();
            for (String language : languages) {
                final List<OptionalInt> variantRanks = evaluationCase.variantsIn(language).stream()
                    .map(question -> rankOf(strategy, question, evaluationCase.expectedThreadIds()))
                    .toList();
                rankByLanguage.put(language, worstRank(variantRanks));
            }
            rankByStrategyAndLanguage.put(strategy.name(), rankByLanguage);
        }
        return new CaseResult(evaluationCase, rankByStrategyAndLanguage);
    }

    /** Rang du premier fil de référence parmi les fils distincts retrouvés pour cette formulation ; vide si aucun,
     * ou si l'extraction des mots-clés a échoué. */
    private OptionalInt rankOf(Strategy strategy, String question, List<String> expectedThreadIds) {
        final List<String> rankedThreadIds;
        try {
            rankedThreadIds = strategy.search().apply(question, SEARCH_DEPTH);
        } catch (ExtractionFailed failure) {
            // Comme en production, une extraction qui échoue ne donne aucun résultat : la question est ratée.
            // Elle est comptée à part (voir la section « Échecs de l'extraction »).
            return OptionalInt.empty();
        }
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

    private String report(List<List<CaseResult>> runs, SequencedSet<String> languages) {
        final StringBuilder report = new StringBuilder(String.format(Locale.ROOT,
            "%n=== Évaluation de la recherche : %d exécution(s) ===%n", runs.size()));
        final List<String> strategyNames = strategies().stream().map(Strategy::name).toList();
        final List<CaseResult> firstRun = runs.getFirst();

        report.append("Rang du premier fil de référence : min/moyenne/max\n");
        report.append(String.format(Locale.ROOT, "%-4s", "#"));
        for (String language : languages) {
            for (String strategy : strategyNames) {
                report.append(String.format(Locale.ROOT, "%-17s", language.toUpperCase(Locale.ROOT) + "/" + strategy));
            }
        }
        report.append("Question\n");

        for (int index = 0; index < firstRun.size(); index++) {
            report.append(String.format(Locale.ROOT, "%-4d", index + 1));
            for (String language : languages) {
                for (String strategy : strategyNames) {
                    report.append(String.format(Locale.ROOT, "%-17s", rankSummary(runs, index, strategy, language)));
                }
            }
            report.append(abbreviate(firstRun.get(index).evaluationCase().label())).append('\n');
        }

        report.append(String.format(Locale.ROOT, "%n%-6s %-11s %9s %10s %9s %9s %9s%n",
            "Langue", "Recherche", "Hit@5 moy", "Hit@10 moy", "MRR min", "MRR moy", "MRR max"));
        for (String language : languages) {
            for (String strategy : strategyNames) {
                final DoubleSummaryStatistics hit5 = runs.stream()
                    .mapToDouble(run -> hitRate(run, strategy, language, 5)).summaryStatistics();
                final DoubleSummaryStatistics hit10 = runs.stream()
                    .mapToDouble(run -> hitRate(run, strategy, language, 10)).summaryStatistics();
                final DoubleSummaryStatistics mrr = runs.stream()
                    .mapToDouble(run -> meanReciprocalRank(run, strategy, language)).summaryStatistics();
                report.append(String.format(Locale.ROOT, "%-6s %-11s %8.0f%% %9.0f%% %9.3f %9.3f %9.3f%n",
                    language.toUpperCase(Locale.ROOT),
                    strategy,
                    100 * hit5.getAverage(),
                    100 * hit10.getAverage(),
                    mrr.getMin(),
                    mrr.getAverage(),
                    mrr.getMax()));
            }
        }

        report.append(extractionFailures(firstRun, languages));
        report.append(extractedKeywords(firstRun, languages));
        report.append(LEGEND);
        return report.toString();
    }

    /** Min/moyenne/max, sur les exécutions, du rang d'une question ; l'absence de rang compte pour la profondeur + 1. */
    private String rankSummary(List<List<CaseResult>> runs, int caseIndex, String strategy, String language) {
        final IntSummaryStatistics ranks = runs.stream()
            .map(run -> run.get(caseIndex).rankIn(strategy, language))
            .mapToInt(rank -> rank.orElse(SEARCH_DEPTH + 1))
            .summaryStatistics();
        return String.format(Locale.ROOT, "%s/%.1f/%s",
            formatRank(ranks.getMin()), ranks.getAverage(), formatRank(ranks.getMax()));
    }

    /**
     * Les mots-clés que le LLM a produits pour chaque formulation, pour expliquer les rangs de l'hybride.
     * Les listes identiques sont regroupées, avec le nombre d'exécutions où elles sont sorties.
     */
    private String extractedKeywords(List<CaseResult> firstRun, SequencedSet<String> languages) {
        final StringBuilder section = new StringBuilder("\nMots-clés extraits (recherche hybride)\n");
        for (int index = 0; index < firstRun.size(); index++) {
            for (String language : languages) {
                for (String question : firstRun.get(index).evaluationCase().variantsIn(language)) {
                    final String prefix = String.format(Locale.ROOT, "#%-3d %-3s ", index + 1, language);
                    final Map<List<String>, Long> occurrences = keywordsByQuestion.getOrDefault(question, List.of()).stream()
                        .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
                    String linePrefix = prefix;
                    for (Map.Entry<List<String>, Long> entry : occurrences.entrySet().stream()
                        .sorted(Map.Entry.<List<String>, Long>comparingByValue().reversed())
                        .toList()) {
                        section.append(linePrefix)
                            .append(String.format(Locale.ROOT, "(%d×) %s%n", entry.getValue(), entry.getKey()));
                        linePrefix = " ".repeat(prefix.length());
                    }
                }
            }
        }
        return section.toString();
    }

    /**
     * Les appels où le LLM n'a pas rendu un JSON lisible. Ces questions comptent comme ratées (rang absent) dans
     * les tableaux : cette section dit si un « >30 » vient d'une vraie recherche ratée ou d'un échec d'extraction.
     */
    private String extractionFailures(List<CaseResult> firstRun, SequencedSet<String> languages) {
        final long failures = extractionFailuresByQuestion.values().stream().mapToLong(List::size).sum();
        final long successes = keywordsByQuestion.values().stream().mapToLong(List::size).sum();
        final long calls = failures + successes;
        final StringBuilder section = new StringBuilder(String.format(Locale.ROOT,
            "%nÉchecs de l'extraction des mots-clés : %d sur %d appels (%.1f %%)%n",
            failures, calls, 100.0 * failures / Math.max(1, calls)));
        for (int index = 0; index < firstRun.size(); index++) {
            for (String language : languages) {
                for (String question : firstRun.get(index).evaluationCase().variantsIn(language)) {
                    final List<String> messages = extractionFailuresByQuestion.getOrDefault(question, List.of());
                    if (!messages.isEmpty()) {
                        final int questionCalls = messages.size() + keywordsByQuestion.getOrDefault(question, List.of()).size();
                        section.append(String.format(Locale.ROOT, "#%-3d %-3s %d échec(s) sur %d : %s%n",
                            index + 1, language, messages.size(), questionCalls, abbreviate(messages.getFirst())));
                    }
                }
            }
        }
        return section.toString();
    }

    private String formatRank(int rank) {
        return rank > SEARCH_DEPTH ? ">" + SEARCH_DEPTH : String.valueOf(rank);
    }

    private double hitRate(List<CaseResult> results, String strategy, String language, int cutoff) {
        return results.stream()
            .map(result -> result.rankIn(strategy, language))
            .filter(rank -> rank.isPresent() && rank.getAsInt() <= cutoff)
            .count() / (double) results.size();
    }

    private double meanReciprocalRank(List<CaseResult> results, String strategy, String language) {
        return results.stream()
            .map(result -> result.rankIn(strategy, language))
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
