package fr.elhadi.raglearning.transformers;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextCleanerTransformerTest {

    @Test
    void shouldKeepParagraphMeaning() {
        Document document = Document.builder()
                            .id("1")
                            .text("Titre\n        \n        \nParagraphe")
                            .build();

        TextCleanerTransformer transformer = new TextCleanerTransformer();

        final List<Document> normalizedDocument = transformer.apply(List.of(document));

        assertEquals("Titre\n\nParagraphe", normalizedDocument.getFirst().getText());

    }

    @Test
    void shouldCleanMultipleSpaceInSameLine() {
        Document document = Document.builder()
                                    .id("1")
                                    .text("Titre     Paragraphe      With     multiple    space.")
                                    .build();

        TextCleanerTransformer transformer = new TextCleanerTransformer();

        final List<Document> normalizedDocument = transformer.apply(List.of(document));

        assertEquals("Titre Paragraphe With multiple space.", normalizedDocument.getFirst().getText());
    }

    @Test
    void shouldCleanUnrecognizeCharacters() {
        Document document = Document.builder()
                                    .id("1")
                                    .text("a \uE000 b")
                                    .build();

        TextCleanerTransformer transformer = new TextCleanerTransformer();

        final List<Document> normalizedDocument = transformer.apply(List.of(document));

        assertEquals("a b", normalizedDocument.getFirst().getText());
    }

    @Test
    void shouldNotKeepDocumentWithOnlyUnrecognizeCharacters() {
        Document document = Document.builder()
                                    .id("1")
                                    .text("\uE000\uE000\uE000\uE000\uE000\uE000\uE000\uE000")
                                    .build();

        TextCleanerTransformer transformer = new TextCleanerTransformer();

        final List<Document> normalizedDocument = transformer.apply(List.of(document));

        assertTrue(normalizedDocument.isEmpty());
    }

    @Test
    void shouldNotKeepDocumentWithOnlySpecialCharacters() {
        Document document = Document.builder()
                                    .id("1")
                                    .text("Summary \b�������������������������������������������������������������������������������������������������������������������������� 95\n")
                                    .build();

        TextCleanerTransformer transformer = new TextCleanerTransformer();

        final List<Document> normalizedDocument = transformer.apply(List.of(document));

        assertEquals("Summary \b 95\n", normalizedDocument.getFirst().getText());
    }
}