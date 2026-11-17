package fr.elhadi.raglearning.ingestion.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackChannel(
    String id,
    String name,
    Long created,
    String topic,
    String purpose,
    String creator,
    @JsonProperty("is_private") Boolean isPrivate
) {
}
