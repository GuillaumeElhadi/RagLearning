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

    private final VectorStore vectorStore;

    public List<RetrievedChunk> findRelatedChunksTo(final String userRequest) {
        log.info("Finding related chunks to the user request: {}", userRequest);
        final SearchRequest request = SearchRequest.builder().query(userRequest)
                                                   .topK(5)
                                                   .build();
        final List<Document> documents = vectorStore.similaritySearch(request);
        return documents.stream().map(document -> RetrievedChunk.builder()
                                                                .score(document.getScore())
                                                                .metadata(document.getMetadata())
                                                                .content(document.getText())
                                                                .build()).toList();
    }
}
