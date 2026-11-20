package fr.elhadi.raglearning.retrieval.keyword;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class KeywordSearchServiceTest {

    private static final Instant OLD_DATE = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant DEFAULT_DATE = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant RECENT_DATE = Instant.parse("2026-06-01T10:00:00Z");

    private KeywordSearchService keywordSearchService;

    private static JdbcClient jdbcClient;

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
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("CREATE EXTENSION IF NOT EXISTS vector").update();
        jdbcClient.sql("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"").update();
        jdbcClient.sql("""
                           create table devportal_knowledge
                           (
                               id        uuid default uuid_generate_v4() not null
                                   primary key,
                               content   text,
                               metadata  json,
                               embedding vector(1024)
                           );
                           """).update();
        jdbcClient.sql("""
                           create index devportal_knowledge_index
                               on devportal_knowledge using hnsw (embedding vector_cosine_ops);
                           """).update();
    }

    @BeforeEach
    void resetKeywordSearchService() {
        keywordSearchService = new KeywordSearchService(jdbcClient);
        jdbcClient.sql("TRUNCATE TABLE devportal_knowledge").update();
    }

    @Nested
    class ZeroKeywords {

        @Test
        void shouldReturnEmptyListWhenKeywordListIsNull() {
            assertThat(keywordSearchService.searchByKeywords(null, 1)).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenKeywordListIsEmpty() {
            List<String> emptyList = List.of();
            assertThat(keywordSearchService.searchByKeywords(emptyList, 1)).isEmpty();
        }
    }

    @Nested
    class OneKeyword {

        @Test
        void shouldReturnEmptyListWhenKeywordMatchesNoChunk() {
            insertChunk("thread-1", "This is a test chunk");
            List<String> unknownChunkList = List.of("unknown");
            assertThat(keywordSearchService.searchByKeywords(unknownChunkList, 60)).isEmpty();
        }

        @Test
        void shouldReturnThreadIdWhenKeywordMatchesOneChunk() {
            insertChunk("thread-1", "This is a test chunk");
            insertChunk("thread-2", "This is another chunk");
            List<String> chunkList = List.of("test");
            assertThat(keywordSearchService.searchByKeywords(chunkList, 60)).containsExactly("thread-1");
        }

        @Test
        void shouldReturnEveryMatchingThreadIdWhenKeywordMatchesChunksOfDifferentThreads() {
            insertChunk("thread-1", "This is a test chunk");
            insertChunk("thread-2", "This is another test chunk");
            List<String> chunkList = List.of("test");
            assertThat(keywordSearchService.searchByKeywords(chunkList, 60)).containsExactlyInAnyOrder("thread-1", "thread-2");
        }

        @Test
        void shouldMatchKeywordRegardlessOfCase() {
            insertChunk("thread-1", "Kong gateway");
            insertChunk("thread-2", "unrelated chunk");
            assertThat(keywordSearchService.searchByKeywords(List.of("KONG"), 60)).containsExactly("thread-1");
        }

        @Test
        void shouldMatchAccentedKeyword() {
            insertChunk("thread-1", "Le paramètre est obligatoire");
            insertChunk("thread-2", "unrelated chunk");
            assertThat(keywordSearchService.searchByKeywords(List.of("Paramètre"), 60)).containsExactly("thread-1");
        }

        @Test
        void shouldSplitHyphenatedKeywordIntoWords() {
            insertChunk("thread-1", "The key is invalid");
            insertChunk("thread-2", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("api-key"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldSplitKeywordContainingASpaceIntoWords() {
            insertChunk("thread-1", "The limit is reached");
            insertChunk("thread-2", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("rate limit"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldSplitKeywordOnAnyPunctuation() {
            insertChunk("thread-1", "The gateway answers 504");
            insertChunk("thread-2", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("error:504/timeout"), 60);

            assertThat(actual).containsExactly("thread-1");
        }
    }

    @Nested
    class MultipleKeywords {

        @Test
        void shouldReturnThreadIdOnlyOnceWhenSeveralKeywordsMatchTheSameChunk() {
            insertChunk("thread-1", "This is a test chunk");
            insertChunk("thread-2", "unknown chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("test", "THIS"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldReturnThreadIdOnlyOnceWhenSeveralChunksOfTheSameThreadMatch() {
            insertChunk("thread-1", "This is a test chunk");
            insertChunk("thread-1", "Another test chunk");
            insertChunk("thread-2", "unknown chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("test"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldReturnEveryMatchingThreadIdWhenKeywordsMatchChunksOfDifferentThreads() {
            insertChunk("thread-1", "kong gateway");
            insertChunk("thread-2", "ping identity");
            insertChunk("thread-3", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "ping"), 60);

            assertThat(actual).containsExactlyInAnyOrder("thread-1", "thread-2");
        }

        @Test
        void shouldReturnMatchingThreadIdsWhenOneOfTheKeywordsMatchesNoChunk() {
            insertChunk("thread-1", "kong gateway");
            insertChunk("thread-2", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "zorglub"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldReturnEmptyListWhenNoKeywordMatchesAnyChunk() {
            insertChunk("thread-1", "kong gateway");
            insertChunk("thread-2", "ping identity");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("zorglub", "blorp"), 60);

            assertThat(actual).isEmpty();
        }

        @Test
        void shouldNotCountTwiceAWordRepeatedAfterSplittingKeywords() {
            // "api-key" et "key" contiennent tous deux "key" : le mot ne doit peser qu'une fois.
            // Les deux fils ont le même score s'il est compté une fois ; thread-1 passe alors
            // devant par son identifiant. Compté deux fois, thread-2 le dépasserait.
            insertChunk("thread-1", "api gateway");
            insertChunk("thread-2", "key gateway");
            insertChunk("thread-3", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("api-key", "key"), 60);

            assertThat(actual).containsExactly("thread-1", "thread-2");
        }

        @Test
        void shouldReturnThreadIdOnlyOnceWhenSameKeywordIsGivenTwice() {
            insertChunk("thread-1", "kong gateway");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "KONG"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldRankThreadMatchingMoreKeywordsFirst() {
            // thread-1 est plus récent : si l'ordre vient de la date, le test échoue
            insertChunk("thread-1", "kong gateway", RECENT_DATE, 1);
            insertChunk("thread-2", "kong ping gateway", OLD_DATE, 1);
            insertChunk("thread-3", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "ping"), 60);

            assertThat(actual).containsExactly("thread-2", "thread-1");
        }

        @Test
        void shouldRankThreadMatchingRareKeywordBeforeThreadMatchingTwoCommonKeywords() {
            // kong et ping sont présents dans 10 chunks sur 20 ; zorglub dans un seul
            for (int i = 1; i <= 9; i++) {
                insertChunk("filler-kong-" + i, "kong filler");
                insertChunk("filler-ping-" + i, "ping filler");
            }
            insertChunk("thread-common", "kong ping");
            insertChunk("thread-rare", "zorglub");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "ping", "zorglub"), 60);

            assertThat(actual).startsWith("thread-rare", "thread-common");
        }

    }

    @Nested
    class BoundaryConditions {

        @Test
        void shouldRankMostRecentThreadFirstWhenThreadsHaveTheSameScore() {
            insertChunk("thread-1", "kong gateway", OLD_DATE, 1);
            insertChunk("thread-2", "kong gateway", RECENT_DATE, 1);
            insertChunk("thread-3", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong"), 60);

            assertThat(actual).containsExactly("thread-2", "thread-1");
        }

        @Test
        void shouldRankByThreadIdWhenScoreAndDateAreEqual() {
            insertChunk("thread-b", "kong gateway");
            insertChunk("thread-a", "kong gateway");
            insertChunk("thread-c", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong"), 60);

            assertThat(actual).containsExactly("thread-a", "thread-b");
        }

        @Test
        void shouldNotReturnThreadWithoutAnyResponderReply() {
            insertChunk("thread-1", "kong gateway", DEFAULT_DATE, 0);
            insertChunk("thread-2", "kong gateway", DEFAULT_DATE, 1);

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong"), 60);

            assertThat(actual).containsExactly("thread-2");
        }

        @Test
        void shouldCountThreadsAndNotChunksWhenApplyingTheLimit() {
            insertChunk("thread-1", "kong gateway one");
            insertChunk("thread-1", "kong gateway two");
            insertChunk("thread-1", "kong gateway three");
            insertChunk("thread-2", "kong gateway");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong"), 2);

            assertThat(actual).containsExactlyInAnyOrder("thread-1", "thread-2");
        }

        @Test
        void shouldKeepBestRankedThreadWhenLimitIsReached() {
            insertChunk("thread-1", "kong gateway", RECENT_DATE, 1);
            insertChunk("thread-2", "kong ping gateway", OLD_DATE, 1);
            insertChunk("thread-3", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong", "ping"), 1);

            assertThat(actual).containsExactly("thread-2");
        }

    }

    @Nested
    class ErrorConditions {

        @Test
        void shouldReturnEmptyListWhenKeywordsContainOnlySpecialCharacters() {
            insertChunk("thread-1", "This is a test chunk");
            insertChunk("thread-2", "This is another test chunk");
            List<String> specialCharacterList = List.of("!@#$%^&*()");
            assertThat(keywordSearchService.searchByKeywords(specialCharacterList, 60)).isEmpty();
        }

        @Test
        void shouldStillMatchValidKeywordsWhenOthersContainSpecialCharacters() {
            insertChunk("thread-1", "kong gateway");
            insertChunk("thread-2", "unrelated chunk");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("kong!", "|&:!"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldIgnoreBlankKeywords() {
            insertChunk("thread-1", "kong gateway");

            final List<String> actual = keywordSearchService.searchByKeywords(List.of("   ", "kong"), 60);

            assertThat(actual).containsExactly("thread-1");
        }

        @Test
        void shouldNotExecuteSqlContainedInKeywords() {
            insertChunk("thread-1", "kong gateway");

            assertThatCode(() -> keywordSearchService.searchByKeywords(List.of("kong'; DROP TABLE devportal_knowledge; --"), 60))
                .doesNotThrowAnyException();

            final Integer remainingChunks = jdbcClient.sql("SELECT count(*) FROM devportal_knowledge").query(Integer.class).single();
            assertThat(remainingChunks).isEqualTo(1);
        }

        @Test
        void shouldThrowExceptionWhenLimitIsNegative() {
            assertThatThrownBy(() -> keywordSearchService.searchByKeywords(List.of("kong"), -1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldThrowExceptionWhenLimitIsZero() {
            assertThatThrownBy(() -> keywordSearchService.searchByKeywords(List.of("kong"), 0)).isInstanceOf(IllegalArgumentException.class);
        }

    }

    void insertChunk(final String threadId, final String content) {
        insertChunk(threadId, content, DEFAULT_DATE, 1);
    }

    void insertChunk(final String threadId, final String content, final Instant date, final int responderReplyCount) {
        jdbcClient.sql("""
                               INSERT INTO devportal_knowledge (content, metadata, embedding)
                               VALUES (:content,
                                       json_build_object('threadId', :threadId,
                                                         'date', :date,
                                                         'responderReplyCount', :responderReplyCount),
                                       null)
                           """)
                  .param("content", content)
                  .param("threadId", threadId)
                  .param("date", date.toString())
                  .param("responderReplyCount", responderReplyCount)
                  .update();
    }

}
