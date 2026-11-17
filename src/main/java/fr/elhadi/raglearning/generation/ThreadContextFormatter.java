package fr.elhadi.raglearning.generation;

import fr.elhadi.raglearning.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Met en forme les fils retrouvés pour le prompt : un bloc {@code <thread>} par fil, avec son
 * identifiant et sa date en attributs. Claude voit ainsi d'un coup d'œil ce qui est récent, et
 * peut citer ses sources. Le score et les métadonnées techniques ne sont pas transmis : ce sont
 * des tokens sans utilité pour rédiger la réponse.
 */
@Component
public class ThreadContextFormatter {

    static final String NO_THREAD_FOUND = "No thread matched this question.";

    private static final String UNKNOWN = "unknown";

    public String format(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return NO_THREAD_FOUND;
        }
        return chunks.stream()
            .map(this::formatThread)
            .collect(Collectors.joining("\n\n"));
    }

    private String formatThread(RetrievedChunk chunk) {
        final Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
        return "<thread id=\"" + metadata.getOrDefault("threadId", UNKNOWN)
            + "\" date=\"" + metadata.getOrDefault("date", UNKNOWN) + "\">\n"
            + (chunk.content() == null ? "" : chunk.content().strip())
            + "\n</thread>";
    }
}
