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
        new SlackAnonymizer(),
        new SlackRequestClassifier()
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

    @Test
    void shouldDeduplicateBotAnswersOnlyWithinMatchingRequestTemplates() throws Exception {
        final Path exportPath = tempDir.resolve("matching-timeout-requests.json");
        Files.writeString(exportPath, """
            {
              "channel": {"id": "C1"},
              "messages": [
                {
                  "ts": "1",
                  "user": "U1",
                  "text": "Please change timeout\\nAPI: <https://api-a.example|API A>\\nEnvironment prod",
                  "replies": [{"ts": "1.1", "bot_id": "B1", "text": "Approved"}]
                },
                {
                  "ts": "2",
                  "user": "U2",
                  "text": "Please change timeout\\nAPI: <https://api-b.example|API B>\\nEnvironment staging",
                  "replies": [{"ts": "2.1", "bot_id": "B1", "text": "Approved"}]
                }
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(2, documents.size());
        assertEquals(
            "TIMEOUT_CHANGE",
            documents.get(0).getMetadata().get("requestCategory")
        );
        assertEquals(
            documents.get(0).getMetadata().get("requestTemplateId"),
            documents.get(1).getMetadata().get("requestTemplateId")
        );
        assertEquals(
            "USER A: Please change timeout\nAPI: API A\n"
                + "Environment prod\nRUNBOY: Approved",
            documents.get(0).getText()
        );
        assertEquals(
            "USER A: Please change timeout\nAPI: API B\n"
                + "Environment staging",
            documents.get(1).getText()
        );
    }

    @Test
    void shouldKeepIdenticalBotAnswersAcrossDifferentRequestCategories() throws Exception {
        final Path exportPath = tempDir.resolve("different-request-categories.json");
        Files.writeString(exportPath, """
            {
              "messages": [
                {
                  "ts": "1",
                  "user": "U1",
                  "text": "Please change timeout",
                  "replies": [{"ts": "1.1", "bot_id": "B1", "text": "Approved"}]
                },
                {
                  "ts": "2",
                  "user": "U2",
                  "text": "Please add access to other gateways",
                  "replies": [{"ts": "2.1", "bot_id": "B1", "text": "Approved"}]
                }
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(2, documents.size());
        assertEquals("TIMEOUT_CHANGE", documents.get(0).getMetadata().get("requestCategory"));
        assertEquals("GATEWAY_ACCESS", documents.get(1).getMetadata().get("requestCategory"));
        assertNotEquals(
            documents.get(0).getMetadata().get("requestTemplateId"),
            documents.get(1).getMetadata().get("requestTemplateId")
        );
        assertEquals(1, countOccurrences(documents.get(0).getText(), "RUNBOY: Approved"));
        assertEquals(1, countOccurrences(documents.get(1).getText(), "RUNBOY: Approved"));
    }

    @Test
    void shouldNotDeduplicateAnswersAcrossUnknownRequestThreads() throws Exception {
        final Path exportPath = tempDir.resolve("unknown-request-threads.json");
        Files.writeString(exportPath, """
            {
              "messages": [
                {
                  "ts": "1",
                  "user": "U1",
                  "text": "first unrelated request",
                  "replies": [{"ts": "1.1", "bot_id": "B1", "text": "Approved"}]
                },
                {
                  "ts": "2",
                  "user": "U2",
                  "text": "second unrelated request",
                  "replies": [{"ts": "2.1", "bot_id": "B1", "text": "Approved"}]
                }
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(2, documents.size());
        assertEquals("OTHER", documents.get(0).getMetadata().get("requestCategory"));
        assertEquals("OTHER", documents.get(1).getMetadata().get("requestCategory"));
        assertEquals(1, countOccurrences(documents.get(0).getText(), "RUNBOY: Approved"));
        assertEquals(1, countOccurrences(documents.get(1).getText(), "RUNBOY: Approved"));
    }

    @Test
    void shouldOmitDuplicateBotRootPrefixAndKeepItsReplies() throws Exception {
        final Path exportPath = tempDir.resolve("duplicate-bot-roots.json");
        Files.writeString(exportPath, """
            {
              "messages": [
                {"ts": "1", "bot_id": "B1", "text": "timeout approved"},
                {
                  "ts": "2",
                  "bot_id": "B1",
                  "text": "timeout approved",
                  "replies": [{"ts": "2.1", "user": "U1", "text": "human follow-up"}]
                }
              ]
            }
            """);

        final List<Document> documents = reader.read(exportPath.toString());

        assertEquals(2, documents.size());
        assertEquals("USER A: human follow-up", documents.get(1).getText());
    }

    private int countOccurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }
}
