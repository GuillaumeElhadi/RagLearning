package fr.elhadi.raglearning.retrieval.keyword;

import fr.elhadi.raglearning.retrieval.ReciprocalRankFusion;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Recherche par mots-clés (plein texte PostgreSQL) sur les chunks de la base, avec un
 * classement par fil. C'est la seconde recherche de la recherche hybride : son résultat
 * est destiné à être fusionné avec celui de la recherche dense par
 * {@link ReciprocalRankFusion}, qui n'a besoin que de l'<em>ordre</em> des fils.
 *
 * <p>Principe, expliqué en détail dans le journal (E15) :
 * <ol>
 *   <li>un chunk est retenu s'il contient au moins un des mots-clés (OU) ;</li>
 *   <li>chaque mot vaut son IDF, {@code ln(nombre de chunks / nombre de chunks contenant
 *       le mot)} : un mot rare vaut plus qu'un mot fréquent ;</li>
 *   <li>le score d'un chunk est la somme des IDF des mots-clés qu'il contient ;</li>
 *   <li>le score d'un fil est celui de son <strong>meilleur</strong> chunk ;</li>
 *   <li>les fils sont triés par score décroissant, puis du plus récent au plus ancien,
 *       puis par identifiant, pour que l'ordre soit toujours le même.</li>
 * </ol>
 *
 * <p>Seuls les fils auxquels une personne a répondu ({@code responderReplyCount > 0}) sont
 * retournés, comme pour la recherche dense.
 */
@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    /**
     * Requête de recherche. Lue de haut en bas, une étape par bloc {@code WITH} :
     * <ul>
     *   <li>{@code keywords} : la chaîne de mots séparés par des espaces devient une ligne par mot ;</li>
     *   <li>{@code corpus} : nombre total de chunks (le « N » de l'IDF) ;</li>
     *   <li>{@code word_idf} : {@code ts_stat} compte, pour chaque mot de la base, le nombre de
     *       chunks qui le contiennent ; on garde les mots-clés et on calcule leur IDF ;</li>
     *   <li>{@code chunk_scores} : un chunk est joint à chaque mot-clé qu'il contient, et ses
     *       IDF sont additionnés ;</li>
     *   <li>la requête finale ne garde que le meilleur chunk de chaque fil, trie, puis limite.</li>
     * </ul>
     * L'IDF est calculé en {@code numeric} (décimal exact) : l'addition est alors exacte et
     * indépendante de l'ordre, donc deux fils au même score sont vraiment à égalité et
     * départagés par la date. Avec des {@code double}, l'ordre d'addition pourrait décaler le
     * dernier bit et casser l'égalité.
     */
    private static final String SEARCH_SQL = """
        WITH keywords AS (
            SELECT word
            FROM unnest(string_to_array(:keywords, ' ')) AS k(word)
        ),
        corpus AS (
            SELECT count(*) AS chunk_count
            FROM devportal_knowledge
        ),
        word_idf AS (
            SELECT s.word,
                   ln(CAST(corpus.chunk_count AS numeric) / s.ndoc) AS idf
            FROM ts_stat('SELECT to_tsvector(''simple'', content) FROM devportal_knowledge') AS s
            JOIN keywords ON keywords.word = s.word
            CROSS JOIN corpus
        ),
        chunk_scores AS (
            SELECT d.id,
                   d.metadata->>'threadId'                  AS thread_id,
                   CAST(d.metadata->>'date' AS timestamptz) AS thread_date,
                   sum(w.idf)                               AS score
            FROM devportal_knowledge d
            JOIN word_idf w
              ON to_tsvector('simple', d.content) @@ plainto_tsquery('simple', w.word)
            WHERE CAST(d.metadata->>'responderReplyCount' AS integer) > 0
            GROUP BY d.id, thread_id, thread_date
        )
        SELECT thread_id
        FROM chunk_scores
        GROUP BY thread_id
        ORDER BY max(score) DESC,
                 max(thread_date) DESC NULLS LAST,
                 thread_id
        LIMIT :limit
        """;

    private final JdbcClient jdbcClient;

    /**
     * Cherche les fils contenant au moins un des mots-clés et les classe par pertinence.
     *
     * <p>Les mots-clés sont découpés comme la base découpe le texte indexé : mis en minuscules,
     * puis coupés sur tout caractère qui n'est ni une lettre ni un chiffre (un mot-clé comme
     * {@code api-key} donne les mots {@code api} et {@code key}, de même que {@code "api key"}).
     * Les mots obtenus sont dédoublonnés <em>après</em> le découpage, pour qu'un mot présent
     * deux fois ne pèse pas deux fois dans le score ; les mots vides sont ignorés. Ils sont
     * transmis à la base comme <em>paramètre</em> et jamais concaténés au SQL, ce qui exclut
     * toute injection.
     *
     * @param keywords
     *     mots-clés simples, un mot par élément ; {@code null} ou vide donne une liste vide
     * @param limit
     *     nombre maximal de <em>fils</em> retournés (et non de chunks), strictement positif
     *
     * @return identifiants de fils (threadId) du plus pertinent au moins pertinent, sans doublon ;
     *     liste vide si rien ne correspond
     *
     * @throws IllegalArgumentException
     *     si {@code limit} est inférieur ou égal à zéro
     */
    public List<String> searchByKeywords(final List<String> keywords, final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be a positive integer");
        }

        if (CollectionUtils.isEmpty(keywords)) {
            return List.of();
        }

        final String cleanedKeywords = keywords.stream()
                                               .map(String::toLowerCase)
                                               .map(keyword -> keyword.replaceAll("[^\\p{L}\\p{N}]", " "))
                                               .flatMap(keyword -> Stream.of(keyword.split("\\s+")))
                                               .filter(keyword -> !keyword.isEmpty())
                                               .distinct()
                                               .collect(Collectors.joining(" "));
        if (!StringUtils.hasText(cleanedKeywords)) {
            return List.of();
        }

        return jdbcClient.sql(SEARCH_SQL)
                         .param("keywords", cleanedKeywords)
                         .param("limit", limit)
                         .query(String.class)
                         .list();
    }
}
