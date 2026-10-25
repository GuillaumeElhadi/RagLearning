package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.transformers.TextCleanerTransformer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "rag.ingestion.enabled", havingValue = "true")
public class RagIngestorService {

    private final TokenTextSplitter textSplitter;

    private final TextCleanerTransformer textCleanerTransformer;

    private final VectorStore vectorStore;

    public void ingestPdf(final String pdfFilePath) {
        final List<Document> documents = getDocsFromPdfWithCatalog(pdfFilePath);
        final List<Document> cleanedDocuments = textCleanerTransformer.apply(documents);
        final List<Document> chunks    = textSplitter.transform(cleanedDocuments);
        vectorStore.add(chunks);
    }

    private List<Document> getDocsFromPdfWithCatalog(final String pdfFilePath) {

        ParagraphPdfDocumentReader pdfReader = new ParagraphPdfDocumentReader(new FileSystemResource(pdfFilePath));

        return pdfReader.read();
    }
}
