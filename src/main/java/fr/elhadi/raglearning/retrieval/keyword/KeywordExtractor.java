package fr.elhadi.raglearning.retrieval.keyword;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transforme une question en mots-clés pour {@link KeywordSearchService}, avec un LLM guidé par
 * le prompt {@code prompts/keyword-extraction.st}.
 *
 * <p>Le travail se répartit en deux : le LLM propose des mots ({@link #extract(String)}), puis
 * {@link #normalize(List)} applique une politique <em>déterministe</em> à sa sortie. Le découpage des
 * mots composés (tokenisation) n'est pas ici : il appartient à {@link KeywordSearchService}, qui
 * doit découper comme la base, quel que soit l'appelant.
 */
public class KeywordExtractor {

    /**
     * Mots qui correspondent aux masques d'anonymisation de {@code SlackAnonymizer}
     * ({@code [HOST]}, {@code [IP]}, {@code [UUID]}, {@code [REDACTED]}) : les chercher
     * ramènerait n'importe quel fil contenant une valeur masquée.
     */
    private static final Set<String> PLACEHOLDER_WORDS = Set.of("host", "ip", "uuid", "redacted");
    /** Nombre maximal de mots-clés conservés (le prompt en demande 3 à 8). */
    private static final int MAX_KEYWORDS = 8;

    /**
     * Extrait les mots-clés d'une question.
     *
     * <p><strong>Pas encore implémentée</strong> : appellera le LLM avec le prompt d'extraction, lira
     * une {@code List<String>} en sortie structurée, puis la passera à {@link #normalize(List)}.
     *
     * @param question question de l'utilisateur, dans n'importe quelle langue
     * @return mots-clés prêts pour {@link KeywordSearchService}
     */
    public List<String> extract(String question) {
        return null;
    }

    /**
     * Applique la politique de nettoyage à la sortie brute du LLM : ignore les éléments
     * {@code null} ou vides, écarte les mots qui correspondent à un masque d'anonymisation
     * (sans tenir compte de la casse), puis garde au plus {@value #MAX_KEYWORDS} mots, dans l'ordre.
     * Un mot écarté ne compte pas dans le plafond. Les mots conservés ne sont pas modifiés (la casse
     * est laissée au service de recherche).
     *
     * @param rawKeywords mots proposés par le LLM ; {@code null} accepté
     * @return mots conservés, jamais {@code null}
     */
    static List<String> normalize(List<String> rawKeywords) {

        return Optional.ofNullable(rawKeywords).stream()
                       .flatMap(List::stream)
                       .filter(Objects::nonNull)
                       .map(String::strip)
                       .filter(keyword -> !keyword.isBlank())
                       .filter(keyword -> !PLACEHOLDER_WORDS.contains(keyword.toLowerCase()))
                       .limit(MAX_KEYWORDS)
                       .toList();
    }
}
