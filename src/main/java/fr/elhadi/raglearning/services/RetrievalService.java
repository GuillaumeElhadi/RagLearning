package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.models.RetrievedChunk;
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
     * Écarte les fils restés sans réponse humaine : des demandes sans explication ni solution,
     * qui ressemblent beaucoup aux questions et encombrent le haut du classement.
     */
    static final String ANSWERED_THREADS_ONLY = "humanReplyCount > 0";

    private final VectorStore vectorStore;

    public List<RetrievedChunk> findRelatedChunksTo(final String userRequest) {
        log.info("Finding related chunks to the user request: {}", userRequest);
        final SearchRequest request = SearchRequest.builder().query(userRequest)
                                                   .topK(10)
                                                   .filterExpression(ANSWERED_THREADS_ONLY)
                                                   .build();
        final List<Document> documents = vectorStore.similaritySearch(request);
        return documents.stream().map(document -> RetrievedChunk.builder()
                                                                .score(document.getScore())
                                                                .metadata(document.getMetadata())
                                                                .content(document.getText())
                                                                .build()).toList();
    }
}
