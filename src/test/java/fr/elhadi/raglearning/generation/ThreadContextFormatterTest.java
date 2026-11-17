package fr.elhadi.raglearning.generation;

import fr.elhadi.raglearning.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadContextFormatterTest {

    private final ThreadContextFormatter formatter = new ThreadContextFormatter();

    @Test
    void shouldWrapEachThreadWithItsIdAndDateAndDropTechnicalMetadata() {
        final List<RetrievedChunk> chunks = List.of(
            chunk(Map.of("threadId", "1.1", "date", "2024-03-19", "requestCategory", "OTHER"), "USER A: first\n"),
            chunk(Map.of("threadId", "2.2", "date", "2019-01-31"), "USER B: second")
        );

        assertEquals(
            "<thread id=\"1.1\" date=\"2024-03-19\">\nUSER A: first\n</thread>\n\n"
                + "<thread id=\"2.2\" date=\"2019-01-31\">\nUSER B: second\n</thread>",
            formatter.format(chunks)
        );
    }

    @Test
    void shouldMarkMissingIdOrDateAsUnknown() {
        assertEquals(
            "<thread id=\"unknown\" date=\"unknown\">\nUSER A: text\n</thread>",
            formatter.format(List.of(chunk(Map.of(), "USER A: text")))
        );
    }

    @Test
    void shouldTellTheModelExplicitlyWhenNoThreadWasFound() {
        assertEquals(ThreadContextFormatter.NO_THREAD_FOUND, formatter.format(List.of()));
    }

    private RetrievedChunk chunk(Map<String, Object> metadata, String content) {
        return RetrievedChunk.builder().score(0.7).metadata(metadata).content(content).build();
    }
}
