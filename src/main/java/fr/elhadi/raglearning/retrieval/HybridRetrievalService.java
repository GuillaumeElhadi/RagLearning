package fr.elhadi.raglearning.retrieval;

import fr.elhadi.raglearning.retrieval.keyword.KeywordExtractor;
import fr.elhadi.raglearning.retrieval.keyword.KeywordSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Recherche hybride : combine la recherche dense (sens) et la recherche par mots-clés (termes exacts)
 * en fusionnant leurs deux classements de <strong>fils</strong> avec {@link ReciprocalRankFusion}.
 *
 * <p>Ce service ne contient aucune logique de recherche : il orchestre ses quatre collaborateurs.
 * Il rend des identifiants de fils, pas du texte ; charger le texte d'un fil est une étape à part.
 */
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    /**
     * Constante de la fusion : plus elle est grande, plus l'écart entre deux rangs voisins compte peu.
     * 60 est la valeur usuelle de la littérature. Ne pas la confondre avec la profondeur de recherche.
     */
    static final int RRF_RANK_CONSTANT = 60;

    /** Poids de la recherche dense dans la fusion par défaut : les deux recherches comptent autant. */
    static final int DEFAULT_DENSE_WEIGHT = 1;

    /** Poids de la recherche par mots-clés dans la fusion par défaut. */
    static final int DEFAULT_KEYWORD_WEIGHT = 1;

    private final KeywordSearchService keywordSearchService;

    private final RetrievalService retrievalService;

    private final KeywordExtractor keywordExtractor;

    private final ReciprocalRankFusion reciprocalRankFusion;

    /**
     * Retrouve les fils les plus proches d'une question, en combinant les deux recherches.
     *
     * <p>La recherche dense et la recherche par mots-clés (mots extraits de la question par un LLM)
     * sont lancées toutes les deux à la profondeur demandée, puis fusionnées. Un fil trouvé par les deux
     * passe avant un fil trouvé par une seule ; à score égal, la recherche dense passe avant les mots-clés.
     * Si l'une des recherches ne trouve rien, le résultat est le classement de l'autre.
     *
     * <p>Si l'extraction des mots-clés échoue, l'exception remonte : un repli silencieux sur la recherche
     * dense ferait passer l'hybride pour du dense dans les mesures.
     *
     * @param question question de l'utilisateur, dans n'importe quelle langue
     * @param depth profondeur de chaque recherche (en chunks pour la recherche dense, en fils pour les
     *     mots-clés) et nombre maximal de fils retournés ; strictement positif
     *
     * @return identifiants de fils, du plus pertinent au moins pertinent, sans doublon ; liste vide si
     *     la question est vide ou si rien n'est trouvé
     *
     * @throws IllegalArgumentException si {@code depth} est inférieur ou égal à zéro
     */
    public List<String> findRelatedThreadIds(final String question, final int depth) {
        return findRelatedThreadIds(question, depth, DEFAULT_DENSE_WEIGHT, DEFAULT_KEYWORD_WEIGHT);
    }

    /**
     * Comme {@link #findRelatedThreadIds(String, int)}, avec un poids pour chaque recherche dans la fusion.
     * Un poids de {@code 2} pour la recherche dense et {@code 1} pour les mots-clés donne à la première deux
     * fois plus d'influence sur l'ordre final : utile quand les mots-clés, souvent génériques, font
     * descendre un bon fil que la recherche dense plaçait en tête.
     *
     * @param question question de l'utilisateur, dans n'importe quelle langue
     * @param depth profondeur de chaque recherche et nombre maximal de fils retournés ; strictement positif
     * @param denseWeight poids de la recherche dense ; non négatif ({@code 0} l'écarte)
     * @param keywordWeight poids de la recherche par mots-clés ; non négatif ({@code 0} l'écarte)
     *
     * @return identifiants de fils, du plus pertinent au moins pertinent, sans doublon
     *
     * @throws IllegalArgumentException si {@code depth} est inférieur ou égal à zéro, ou si un poids est négatif
     */
    public List<String> findRelatedThreadIds(
        final String question, final int depth, final int denseWeight, final int keywordWeight) {
        if (depth <= 0) {
            throw new IllegalArgumentException("Depth must be greater than 0");
        }

        if (denseWeight < 0 || keywordWeight < 0) {
            throw new IllegalArgumentException("Weights must be non-negative");
        }

        if (!StringUtils.hasText(question)) {
            return List.of();
        }

        final List<String> denseThreadIds = retrievalService.findRelatedThreadIds(question, depth);
        final List<String> keywordThreadIds = keywordSearchService.searchByKeywords(keywordExtractor.extract(question), depth);

        return reciprocalRankFusion.fuse(
                                       List.of(denseThreadIds, keywordThreadIds),
                                       List.of(denseWeight, keywordWeight),
                                       RRF_RANK_CONSTANT)
                                   .stream()
                                   .limit(depth)
                                   .toList();
    }
}
