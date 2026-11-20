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
        if (depth <= 0) {
            throw new IllegalArgumentException("Depth must be greater than 0");
        }

        if (!StringUtils.hasText(question)) {
            return List.of();
        }

        final List<String> denseThreadIds = retrievalService.findRelatedThreadIds(question, depth);
        final List<String> keywordThreadIds = keywordSearchService.searchByKeywords(keywordExtractor.extract(question), depth);

        return reciprocalRankFusion.fuse(List.of(denseThreadIds, keywordThreadIds), RRF_RANK_CONSTANT)
                                   .stream()
                                   .limit(depth)
                                   .toList();
    }
}
