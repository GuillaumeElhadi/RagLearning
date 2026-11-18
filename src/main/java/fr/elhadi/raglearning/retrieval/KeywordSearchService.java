package fr.elhadi.raglearning.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final JdbcTemplate jdbcTemplate;

    public List<String> searchByKeywords(final List<String> keywords, final int limit) {
        return null;
    }
}
