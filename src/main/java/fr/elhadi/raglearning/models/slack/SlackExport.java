package fr.elhadi.raglearning.models.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackExport(
    SlackChannel channel,
    List<SlackMember> members,
    List<SlackMessage> messages,
    @JsonProperty("user_directory") Map<String, String> userDirectory
) {
}
