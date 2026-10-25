package fr.elhadi.raglearning;

import fr.elhadi.raglearning.services.RagIngestorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

@SpringBootApplication
public class RagLearningApplication {

    @Value("${rag.ingestion.source-dir}")
    private String ragIngestionSourceDir;

    static void main(String[] args) {
        SpringApplication.run(RagLearningApplication.class, args);
    }

    @Bean
    CommandLineRunner ingestSources(final RagIngestorService ragIngestorService) {
        return _ -> {
            try (var files = Files.list(Path.of(ragIngestionSourceDir))) {
                files.filter(Files::isRegularFile)
                     .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                     .sorted(Comparator.naturalOrder())
                     .forEach(path -> ragIngestorService.ingestPdf(path.toString()));
            } catch (IOException exception) {
                throw new IOException("Unable to read PDF files from the " + ragIngestionSourceDir + " directory", exception);
            }
        };
    }
}
