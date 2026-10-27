package fr.elhadi.raglearning.models;

import lombok.Builder;

import java.util.Map;

@Builder
public record RetrievedChunk(Double score, Map<String, Object> metadata, String content) {
}
