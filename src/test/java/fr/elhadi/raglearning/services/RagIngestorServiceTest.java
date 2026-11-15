package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.transformers.TextCleanerTransformer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;

class RagIngestorServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnonymizeSlackThreadsRedactCredentialsAndIndexThem() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export.json");
        Files.writeString(exportPath, """
            {
              "channel": {"name": "engineering"},
              "members": [],
              "messages": [
                {
                  "ts": "1",
                  "user": "U1",
                  "blocks_text": "Hi <@U2>, clientID=client-123, contact alice@example.com",
                  "replies": [{
                    "ts": "1.1",
                    "bot_id": "B1",
                    "bot_profile": {"name": "runboy"},
                    "text": "client secret: secret-123 login=alice password is pass-123"
                  }]
                },
                {"ts": "2", "user": "U2", "text": "second message"}
              ],
              "user_directory": {}
            }
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        final VectorStore vectorStore = mock(VectorStore.class);
        final RagIngestorService service = service(textSplitter, vectorStore);

        service.ingestSlackHistory(exportPath.toString());

        verify(vectorStore).add(argThat((List<Document> documents) ->
            documents.size() == 2
                && documents.get(0).getText().equals(
                "USER A: Hi USER B, clientID=[REDACTED], contact [REDACTED_EMAIL]\n"
                        + "RUNBOY: client secret=[REDACTED] login=[REDACTED] password=[REDACTED]"
                )
                && documents.get(1).getText().equals("USER A: second message")
                && !documents.get(0).getText().contains("client-123")
                && !documents.get(0).getText().contains("secret-123")
                && !documents.get(0).getText().contains("alice")
                && !documents.get(0).getText().contains("pass-123")
                && documents.get(0).getMetadata().get("channel").equals("engineering")
        ));
    }

    @Test
    void shouldAssignAliasesByFirstAppearanceAcrossTextBlocksAndReplies() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-order.json");
        Files.writeString(exportPath, """
            {
              "messages": [
                {
                  "ts": "1",
                  "user": "U9",
                  "text": "text mentions <@U1>",
                  "blocks_text": "blocks mention <@U7>",
                  "replies": [{
                    "ts": "1.1",
                    "user": "U8",
                    "text": "reply mentions <@U9>"
                  }]
                },
                {"ts": "2", "user": "U1", "text": "second"}
              ]
            }
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        final VectorStore vectorStore = mock(VectorStore.class);
        final RagIngestorService service = service(textSplitter, vectorStore);

        service.ingestSlackHistory(exportPath.toString());

        verify(vectorStore).add(argThat((List<Document> documents) ->
            documents.size() == 2
                && documents.get(0).getText().equals(
                    "USER A: blocks mention USER B\nUSER C: reply mentions USER A"
                )
                && documents.get(1).getText().equals("USER A: second")
                && documents.stream().noneMatch(document ->
                    document.getText().matches("(?s).*\\bU[0-9]+\\b.*"))
        ));
    }

    @Test
    void shouldNotAssignAnAliasToBotUsers() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-bot.json");
        Files.writeString(exportPath, """
            {
              "messages": [{
                "ts": "1",
                "user": "U1",
                "text": "root",
                "replies": [{
                  "ts": "1.1",
                  "user": "BOTUSER",
                  "bot_id": "B1",
                  "text": "bot reply",
                  "replies": [{
                    "ts": "1.2",
                    "user": "U2",
                    "text": "human reply"
                  }]
                }]
              }]
            }
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        final VectorStore vectorStore = mock(VectorStore.class);
        final RagIngestorService service = service(textSplitter, vectorStore);

        service.ingestSlackHistory(exportPath.toString());

        verify(vectorStore).add(argThat((List<Document> documents) ->
            documents.size() == 1
                && documents.get(0).getText().equals(
                    "USER A: root\nRUNBOY: bot reply\nUSER B: human reply"
                )
        ));
    }

    @Test
    void shouldAssignStableChunkIdsWhenSplitterCreatesNewIds() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-chunks.json");
        Files.writeString(exportPath, """
            {
              "channel": {"id": "C1"},
              "messages": [{"ts": "1.1", "user": "U1", "text": "thread"}]
            }
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> {
            final List<Document> documents = invocation.getArgument(0);
            return documents.stream()
                .flatMap(document -> List.of(0, 1).stream()
                    .map(index -> Document.builder()
                        .id(UUID.randomUUID().toString())
                        .metadata(document.getMetadata())
                        .text(document.getText() + " chunk " + index)
                        .build()))
                .toList();
        });
        final List<List<String>> storedChunkIds = new ArrayList<>();
        final VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            final List<Document> documents = invocation.getArgument(0);
            storedChunkIds.add(documents.stream().map(Document::getId).toList());
            return null;
        }).when(vectorStore).add(anyList());
        final RagIngestorService service = service(textSplitter, vectorStore);

        service.ingestSlackHistory(exportPath.toString());
        service.ingestSlackHistory(exportPath.toString());

        assertEquals(2, storedChunkIds.size());
        assertEquals(storedChunkIds.get(0), storedChunkIds.get(1));
        assertEquals(2, storedChunkIds.get(0).stream().distinct().count());
    }

    @Test
    void shouldPreserveStableSourceMetadataThroughRealTokenSplitter() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-real-splitter.json");
        Files.writeString(exportPath, """
            {
              "channel": {"id": "C1"},
              "messages": [{"ts": "1.1", "user": "U1", "text": "thread"}]
            }
            """);

        final List<Document> storedChunks = new ArrayList<>();
        final VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            storedChunks.addAll(invocation.getArgument(0));
            return null;
        }).when(vectorStore).add(anyList());
        final RagIngestorService service = service(TokenTextSplitter.builder().build(), vectorStore);

        service.ingestSlackHistory(exportPath.toString());

        assertEquals(1, storedChunks.size());
        final String sourceDocumentId = (String) storedChunks.getFirst()
            .getMetadata()
            .get("sourceDocumentId");
        assertEquals(
            UUID.nameUUIDFromBytes((sourceDocumentId + ":0").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString(),
            storedChunks.getFirst().getId()
        );
    }

    @Test
    void shouldWriteChunksInBatchesAndReportProgress() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-progress.json");
        Files.writeString(exportPath, """
            {"messages": [{"ts": "1", "user": "U1", "text": "thread"}]}
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> {
            final List<Document> sourceDocuments = invocation.getArgument(0);
            final Document source = sourceDocuments.getFirst();
            return java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> Document.builder()
                    .id("split-" + index)
                    .metadata(source.getMetadata())
                    .text("chunk " + index)
                    .build())
                .toList();
        });

        final List<List<Document>> batches = new ArrayList<>();
        final VectorStore vectorStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            batches.add(List.copyOf(invocation.getArgument(0)));
            return null;
        }).when(vectorStore).add(anyList());

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        final RagIngestorService service = service(textSplitter, vectorStore);
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            service.ingestSlackHistory(exportPath.toString());
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(List.of(100, 100, 1), batches.stream().map(List::size).toList());
        assertEquals(
            java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> "chunk " + index)
                .toList(),
            batches.stream().flatMap(List::stream).map(Document::getText).toList()
        );
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("201/201"));
    }

    @Test
    void shouldNotReportFailedBatchAsComplete() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-progress-failure.json");
        Files.writeString(exportPath, """
            {"messages": [{"ts": "1", "user": "U1", "text": "thread"}]}
            """);

        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenAnswer(invocation -> {
            final List<Document> sourceDocuments = invocation.getArgument(0);
            final Document source = sourceDocuments.getFirst();
            return java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> Document.builder()
                    .id("split-" + index)
                    .metadata(source.getMetadata())
                    .text("chunk " + index)
                    .build())
                .toList();
        });
        final VectorStore vectorStore = mock(VectorStore.class);
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("write failed"))
            .when(vectorStore).add(anyList());

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        final RagIngestorService service = service(textSplitter, vectorStore);
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.ingestSlackHistory(exportPath.toString())
            );
        } finally {
            System.setOut(originalOut);
        }

        verify(vectorStore, times(2)).add(anyList());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("100/101"));
        org.junit.jupiter.api.Assertions.assertFalse(output.toString(StandardCharsets.UTF_8).contains("101/101"));
    }

    @Test
    void shouldSkipVectorStoreAndProgressForEmptyChunks() throws Exception {
        final Path exportPath = tempDir.resolve("slack-export-empty-chunks.json");
        Files.writeString(exportPath, """
            {"messages": [{"ts": "1", "user": "U1", "text": "thread"}]}
            """);
        final TokenTextSplitter textSplitter = mock(TokenTextSplitter.class);
        when(textSplitter.transform(anyList())).thenReturn(List.of());
        final VectorStore vectorStore = mock(VectorStore.class);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        final RagIngestorService service = service(textSplitter, vectorStore);

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            service.ingestSlackHistory(exportPath.toString());
        } finally {
            System.setOut(originalOut);
        }

        verify(vectorStore, never()).add(anyList());
        assertTrue(output.toString(StandardCharsets.UTF_8).isEmpty());
    }

    private RagIngestorService service(TokenTextSplitter textSplitter, VectorStore vectorStore) {
        final SlackDocumentReader slackDocumentReader = new SlackDocumentReader(
            JsonMapper.builder().build(),
            new SlackAnonymizer(),
            new SlackRequestClassifier()
        );
        return new RagIngestorService(
            textSplitter,
            new TextCleanerTransformer(),
            vectorStore,
            slackDocumentReader
        );
    }
}
