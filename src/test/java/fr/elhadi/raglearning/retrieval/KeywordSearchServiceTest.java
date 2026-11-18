package fr.elhadi.raglearning.retrieval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class KeywordSearchServiceTest {

    private KeywordSearchService keywordSearchService;

    private static JdbcTemplate jdbcTemplate;

    @Container
    static PostgreSQLContainer postres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18-trixie").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("rag-learning")
        .withUsername("rag-learning")
        .withPassword("rag-learning");

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(postres.getDriverClassName());
        dataSource.setUrl(postres.getJdbcUrl());
        dataSource.setUsername(postres.getUsername());
        dataSource.setPassword(postres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
        jdbcTemplate.execute("""
                                 create table devportal_knowledge
                                 (
                                     id        uuid default uuid_generate_v4() not null
                                         primary key,
                                     content   text,
                                     metadata  json,
                                     embedding vector(1024)
                                 );
                                 
                                 create index devportal_knowledge_index
                                     on devportal_knowledge using hnsw (embedding vector_cosine_ops);
                                 """);
    }

    @BeforeEach
    void resetKeywordSearchService() {
        keywordSearchService = new KeywordSearchService(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE devportal_knowledge");
    }

    @Nested
    class ZeroKeywords {

        @Test
        void shouldReturnEmptyListWhenKeywordListIsNull() {
        }

        @Test
        void shouldReturnEmptyListWhenKeywordListIsEmpty() {
        }
    }

    @Nested
    class OneKeyword {

        @Test
        void shouldReturnEmptyListWhenKeywordMatchesNoChunk() {
        }

        @Test
        void shouldReturnThreadIdWhenKeywordMatchesOneChunk() {
        }

        @Test
        void shouldReturnEveryMatchingThreadIdWhenKeywordMatchesChunksOfDifferentThreads() {
        }
    }

    @Nested
    class MultipleKeywords {

        @Test
        void shouldReturnThreadIdOnlyOnceWhenSeveralKeywordsMatchTheSameChunk() {

        }

        @Test
        void shouldRankThreadMatchingMoreKeywordsFirst() {
        }

        @Test
        void shouldReturnThreadIdOnlyOnceWhenSeveralChunksOfTheSameThreadMatch() {
        }

        @Test
        void shouldReturnEveryMatchingThreadIdWhenKeywordsMatchChunksOfDifferentThreads() {
        }

        @Test
        void shouldReturnMatchingThreadIdsWhenOneOfTheKeywordsMatchesNoChunk() {
        }

        @Test
        void shouldReturnEmptyListWhenNoKeywordMatchesAnyChunk() {
        }

    }

    @Nested
    class BoundaryConditions {

        @Test
        void shouldRankMostRecentThreadFirstWhenThreadsHaveTheSameScore() {
        }

    }

    @Nested
    class ErrorConditions {

        @Test
        void shouldNotThrowWhenKeywordsContainSpecialCharacters() {
        }
    }

}