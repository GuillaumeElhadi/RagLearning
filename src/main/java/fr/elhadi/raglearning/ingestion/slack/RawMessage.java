package fr.elhadi.raglearning.ingestion.slack;

import lombok.Builder;

import java.util.List;

@Builder
public record RawMessage(String threadId, String author, String userRequest, List<String> replies) {
}
