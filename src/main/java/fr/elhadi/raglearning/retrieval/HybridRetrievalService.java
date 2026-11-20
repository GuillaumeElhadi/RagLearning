package fr.elhadi.raglearning.retrieval;

import fr.elhadi.raglearning.retrieval.keyword.KeywordExtractor;
import fr.elhadi.raglearning.retrieval.keyword.KeywordSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final KeywordSearchService keywordSearchService;

    private final RetrievalService retrievalService;

    private final KeywordExtractor keywordExtractor;

    private final ReciprocalRankFusion reciprocalRankFusion;

}
