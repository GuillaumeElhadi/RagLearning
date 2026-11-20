package fr.elhadi.raglearning.ingestion;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    value = "rag.ingestion.enabled",
    havingValue = "true"
)
public class CommandLineIngestor implements CommandLineRunner {

    private final RagIngestorService ragIngestorService;

    @Value("${rag.ingestion.source-dir}")
    private String ragIngestionSourceDir;

    @Override
    public void run(String @NonNull ... args) throws Exception {
        final List<Path> filesToIngest;
        try (var files = Files.list(Path.of(ragIngestionSourceDir))) {
            filesToIngest = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (IOException exception) {
            throw new IOException(
                "Unable to list Slack JSON files from the " + ragIngestionSourceDir + " directory",
                exception
            );
        }

        for (Path file : filesToIngest) {
            ragIngestorService.ingestSlackHistory(file.toString());
        }
    }
}
