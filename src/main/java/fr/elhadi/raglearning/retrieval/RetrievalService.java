package fr.elhadi.raglearning.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    /**
     * Écarte les fils restés sans réponse d'une autre personne que le demandeur : des demandes
     * sans explication ni solution, qui ressemblent beaucoup aux questions et encombrent le haut
     * du classement. Une simple relance ou un « Thanks » du demandeur ne suffit pas.
     */
    static final String ANSWERED_THREADS_ONLY = "responderReplyCount > 0";

    private final VectorStore vectorStore;

    /** Nombre de fils transmis au modèle pour répondre à une question. */
    static final int DEFAULT_TOP_K = 10;

    public List<RetrievedChunk> findRelatedChunksTo(final String userRequest) {
        return findRelatedChunksTo(userRequest, DEFAULT_TOP_K);
    }

    /**
     * Variante avec une profondeur de recherche explicite, utile pour l'évaluation : elle
     * permet de voir à quel rang se trouve un fil, même au-delà de ce qui est transmis au modèle.
     */
    public List<RetrievedChunk> findRelatedChunksTo(final String userRequest, final int topK) {
        log.info("Finding the {} chunks most related to the user request: {}", topK, userRequest);
        final SearchRequest request = SearchRequest.builder().query(userRequest)
                                                   .topK(topK)
                                                   .filterExpression(ANSWERED_THREADS_ONLY)
                                                   .build();
        final List<Document> documents = vectorStore.similaritySearch(request);
        return documents.stream().map(document -> RetrievedChunk.builder()
                                                                .score(document.getScore())
                                                                .metadata(document.getMetadata())
                                                                .content(document.getText())
                                                                .build()).toList();
    }

    public List<String> findRelatedThreadIds(final String userRequest, final int topK) {

        return findRelatedChunksTo(userRequest, topK)
            .stream()
            .map(RetrievedChunk::metadata)
            .map(metadata -> metadata.get("threadId"))
            .map(Object::toString)
            .distinct()
            .toList();
    }
}
