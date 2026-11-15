package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.models.RawMessage;
import fr.elhadi.raglearning.models.slack.SlackMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SlackAnonymizer {

    private static final Pattern SLACK_MENTION =
        Pattern.compile("<@([A-Z0-9]+)(?:\\|[^>]+)?>");

    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(client[\\s_-]*id|client[\\s_-]*secret|login|password|passwd)\\b[\"']?\\s*"
            + "(?:[:=]\\s*|\\bis\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    );

    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
        "(?i)(\\bauthorization\\b[\"']?\\s*[:=]\\s*[\"']?(?:bearer\\s+|basic\\s+)?)[^\"'\\s,;]+"
    );

    private static final Pattern API_KEY_ASSIGNMENT = Pattern.compile(
        "(?i)(\\b(?:x[\\s_-]*api[\\s_-]*key|api[\\s_-]*key)\\b[\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,;]+"
    );

    private static final Pattern JWT =
        Pattern.compile("\\beyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){2}\\b");

    private static final Pattern EMAIL_ADDRESS =
        Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    public RawMessage anonymize(SlackMessage thread, String threadId) {
        return anonymize(thread, threadId, new HashSet<>());
    }

    public RawMessage anonymize(SlackMessage thread, String threadId, Set<String> seenAnswers) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(seenAnswers, "seenAnswers");
        final Map<String, String> userAliases = userAliases(thread);

        return RawMessage.builder()
            .threadId(threadId)
            .author(speaker(thread, userAliases))
            .userRequest(sanitizeText(
                shouldOmitBotText(thread, seenAnswers) ? "" : messageText(thread),
                userAliases
            ))
            .replies(replyTexts(thread.replies(), userAliases, seenAnswers))
            .build();
    }

    private Map<String, String> userAliases(SlackMessage thread) {
        final LinkedHashSet<String> userIds = new LinkedHashSet<>();
        collectUserIds(thread, userIds);
        final Map<String, String> aliases = new HashMap<>();
        int index = 0;
        for (String userId : userIds) {
            aliases.put(userId, "USER " + alphabeticLabel(index++));
        }
        return aliases;
    }

    private void collectUserIds(SlackMessage message, LinkedHashSet<String> userIds) {
        if (!isBot(message) && message.user() != null) {
            userIds.add(message.user());
        }
        collectMentionIds(messageText(message), userIds);
        safeMessages(message.replies()).forEach(reply -> collectUserIds(reply, userIds));
    }

    private void collectMentionIds(String text, LinkedHashSet<String> userIds) {
        final Matcher mentions = SLACK_MENTION.matcher(text);
        while (mentions.find()) {
            userIds.add(mentions.group(1));
        }
    }

    private List<String> replyTexts(
        List<SlackMessage> replies,
        Map<String, String> userAliases,
        Set<String> seenAnswers
    ) {
        final List<String> replyTexts = new ArrayList<>();
        for (SlackMessage reply : safeMessages(replies)) {
            if (!shouldOmitBotText(reply, seenAnswers)) {
                replyTexts.add(speaker(reply, userAliases) + ": "
                    + sanitizeText(messageText(reply), userAliases));
            }
            replyTexts.addAll(replyTexts(reply.replies(), userAliases, seenAnswers));
        }
        return replyTexts;
    }

    private boolean shouldOmitBotText(SlackMessage message, Set<String> seenAnswers) {
        if (!isBot(message)) {
            return false;
        }
        final String answer = messageText(message).strip();
        return answer.isEmpty() || !seenAnswers.add(answer);
    }

    private String speaker(SlackMessage message, Map<String, String> userAliases) {
        if (isBot(message)) {
            return "RUNBOY";
        }
        return userAliases.getOrDefault(message.user(), "USER UNKNOWN");
    }

    private boolean isBot(SlackMessage message) {
        return message.botProfile() != null || message.botId() != null;
    }

    private String messageText(SlackMessage message) {
        if (message.blocksText() != null && !message.blocksText().isBlank()) {
            return message.blocksText();
        }
        return message.text() == null ? "" : message.text();
    }

    private String sanitizeText(String text, Map<String, String> userAliases) {
        final String withAliases = SLACK_MENTION.matcher(text)
            .replaceAll(match -> userAliases.getOrDefault(match.group(1), "USER UNKNOWN"));
        final String withAuthorizationRedacted = AUTHORIZATION_HEADER.matcher(withAliases)
            .replaceAll("$1[REDACTED]");
        final String withApiKeysRedacted = API_KEY_ASSIGNMENT.matcher(withAuthorizationRedacted)
            .replaceAll("$1[REDACTED]");
        final String withJwtsRedacted = JWT.matcher(withApiKeysRedacted)
            .replaceAll("[REDACTED_JWT]");
        final String withoutCredentials = CREDENTIAL_ASSIGNMENT.matcher(withJwtsRedacted)
            .replaceAll("$1=[REDACTED]");
        return EMAIL_ADDRESS.matcher(withoutCredentials).replaceAll("[REDACTED_EMAIL]");
    }

    private String alphabeticLabel(int index) {
        final StringBuilder label = new StringBuilder();
        int value = index;
        do {
            label.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return label.toString();
    }

    private List<SlackMessage> safeMessages(List<SlackMessage> messages) {
        return messages == null ? List.of() : messages;
    }
}
