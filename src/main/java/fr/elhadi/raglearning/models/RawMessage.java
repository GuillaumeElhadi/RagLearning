package fr.elhadi.raglearning.models;

import lombok.Builder;

import java.util.List;

@Builder
public record RawMessage(String threadId, String author, String userRequest, List<String> replies) {
}
