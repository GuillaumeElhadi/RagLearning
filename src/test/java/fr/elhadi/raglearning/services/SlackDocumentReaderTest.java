package fr.elhadi.raglearning.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SlackDocumentReaderTest {

    @TempDir
    Path tempDir;

    private final SlackDocumentReader reader = new SlackDocumentReader(
        JsonMapper.builder().build(),
        new SlackAnonymizer()
    );

    @Test
    void shouldBuildDocumentIdsFromChannelAndThreadRatherThanFilePath() throws Exception {
        final String export = """
            {
              "channel": {"id": "C1", "name": "support"},
              "messages": [{"ts": "123.4", "user": "U1", "text": "hello"}]
            }
            """;
        final Path firstPath = tempDir.resolve("first.json");
        final Path movedPath = tempDir.resolve("moved/second.json");
        Files.createDirectories(movedPath.getParent());
        Files.writeString(firstPath, export);
        Files.writeString(movedPath, export);

        final Document first = reader.read(firstPath.toString()).getFirst();
        final Document moved = reader.read(movedPath.toString()).getFirst();

        assertEquals(first.getId(), moved.getId());
        assertEquals("C1", first.getMetadata().get("channelId"));
        assertEquals("123.4", first.getMetadata().get("threadId"));
        assertEquals(first.getId(), first.getMetadata().get("sourceDocumentId"));
    }

    @Test
    void shouldUseThreadTimestampAndGiveMissingTimestampsDistinctFallbackIds() throws Exception {
        final Path exportPath = tempDir.resolve("missing-timestamps.json");
        Files.writeString(exportPath, """
            {
              "channel": {"id": "C1"},
              "messages": [
                {"thread_ts": "parent-ts", "user": "U1", "text": "first"},
                {"user": "U2", "text": "second"}
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(
            List.of("missing-ts-1", "parent-ts"),
            documents.stream()
                .map(document -> (String) document.getMetadata().get("threadId"))
                .sorted()
                .toList()
        );
        assertNotEquals(documents.get(0).getId(), documents.get(1).getId());
    }

    @Test
    void shouldMergeTopLevelRepliesAndAvoidRepliesAlreadyNestedUnderTheirThread() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-thread.json");
        Files.writeString(exportPath, """
            {
              "channel": {"id": "C1"},
              "messages": [
                {
                  "ts": "1.0",
                  "thread_ts": "1.0",
                  "user": "U1",
                  "text": "root",
                  "replies": [{
                    "ts": "1.1",
                    "thread_ts": "1.0",
                    "user": "U2",
                    "text": "reply"
                  }]
                },
                {
                  "ts": "1.1",
                  "thread_ts": "1.0",
                  "user": "U2",
                  "text": "reply"
                }
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(1, documents.size());
        assertEquals("1.0", documents.getFirst().getMetadata().get("threadId"));
        assertEquals("USER A: root\nUSER B: reply", documents.getFirst().getText());
    }
}
