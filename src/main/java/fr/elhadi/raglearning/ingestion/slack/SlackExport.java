package fr.elhadi.raglearning.ingestion.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackExport(
    SlackChannel channel,
    List<SlackMember> members,
    List<SlackMessage> messages
) {
}
