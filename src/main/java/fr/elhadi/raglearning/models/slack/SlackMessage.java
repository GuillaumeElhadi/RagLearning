package fr.elhadi.raglearning.models.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackMessage(
    String type,
    String subtype,
    String ts,
    @JsonProperty("thread_ts") String threadTs,
    String user,
    @JsonProperty("user_email") String userEmail,
    @JsonProperty("bot_id") String botId,
    @JsonProperty("bot_profile") BotProfile botProfile,
    String text,
    @JsonProperty("blocks_text") String blocksText,
    List<Attachment> attachments,
    List<Reaction> reactions,
    List<SlackMessage> replies) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BotProfile(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(String text, String fallback) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reaction(String name, int count) {}
}