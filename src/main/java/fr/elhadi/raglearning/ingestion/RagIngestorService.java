package fr.elhadi.raglearning.ingestion;

import fr.elhadi.raglearning.ingestion.slack.SlackDocumentReader;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    value = "rag.ingestion.enabled",
    havingValue = "true"
)
public class RagIngestorService {

    private static final int INGESTION_BATCH_SIZE = 100;

    private final TokenTextSplitter textSplitter;

    private final VectorStore vectorStore;

    private final SlackDocumentReader slackDocumentReader;

    public void ingestSlackHistory(final String slackJsonExtractionPath) throws IOException {
        // Le texte Slack est déjà nettoyé, puis anonymisé, par le lecteur. Toute transformation
        // après l'anonymisation risquerait de recomposer une donnée sensible que les règles
        // n'avaient pas reconnue.
        final List<Document> documents = slackDocumentReader.read(slackJsonExtractionPath);
        final List<Document> chunks = stableSlackChunkIds(textSplitter.transform(documents));
        addChunksWithProgress(chunks);
    }

    private void addChunksWithProgress(final List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        final int total = chunks.size();
        int completed = 0;
        for (int start = 0; start < total; start += INGESTION_BATCH_SIZE) {
            final int end = Math.min(start + INGESTION_BATCH_SIZE, total);
            vectorStore.add(chunks.subList(start, end));
            completed = end;
            printProgress(completed, total);
        }
        System.out.println();
    }

    private void printProgress(final int completed, final int total) {
        final int barWidth = 30;
        final int filledWidth = (int) ((long) completed * barWidth / total);
        final String bar = "#".repeat(filledWidth) + "-".repeat(barWidth - filledWidth);
        System.out.printf("\r[%s] %d/%d chunks", bar, completed, total);
        System.out.flush();
    }

    private List<Document> stableSlackChunkIds(List<Document> chunks) {
        final Map<String, Integer> chunkIndexes = new HashMap<>();
        return chunks.stream()
            .map(chunk -> {
                final Object sourceDocumentId = chunk.getMetadata().get("sourceDocumentId");
                if (!(sourceDocumentId instanceof String stableDocumentId)) {
                    throw new IllegalStateException(
                        "Slack chunk is missing its stable source document ID"
                    );
                }
                final int chunkIndex = chunkIndexes.getOrDefault(stableDocumentId, 0);
                chunkIndexes.put(stableDocumentId, chunkIndex + 1);
                final String chunkId = UUID.nameUUIDFromBytes(
                    (stableDocumentId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)
                ).toString();
                return Document.builder()
                    .id(chunkId)
                    .metadata(chunk.getMetadata())
                    .text(chunk.getText())
                    .build();
            })
            .toList();
    }
}
