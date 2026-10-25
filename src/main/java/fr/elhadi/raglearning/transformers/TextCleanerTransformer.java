package fr.elhadi.raglearning.transformers;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TextCleanerTransformer implements DocumentTransformer {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\h{2,}");

    private static final Pattern LEADING_AND_TRAILING_SPACES = Pattern.compile("(?m)^\\h+|\\h+$");

    private static final Pattern MULTIPLE_LINE_BREAKS = Pattern.compile("\\R{2,}");

    private static final Pattern UNRECOGNIZE_CHARACTERS = Pattern.compile("\\p{Co}|\\uFFFD");

    @Override
    public @NonNull List<Document> apply(@NonNull List<Document> documents) {
        List<Document> result = new ArrayList<>(documents.size());

        for (Document document : documents) {
            String normalized = MULTIPLE_LINE_BREAKS.matcher(LEADING_AND_TRAILING_SPACES.matcher(
                                                        MULTIPLE_SPACES.matcher(
                                                                           UNRECOGNIZE_CHARACTERS.matcher(document.getText())
                                                                                                 .replaceAll(""))
                                                                       .replaceAll(" "))
                                                                                        .replaceAll(""))
                                               .replaceAll("\n\n");
            if (StringUtils.hasText(normalized)) {
                result.add(Document.builder()
                                   .id(document.getId())
                                   .metadata(document.getMetadata())
                                   .text(normalized)
                                   .build());
            }

        }

        return result;
    }
}
