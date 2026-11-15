package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.models.slack.SlackMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SlackRequestClassifier {

    private static final Pattern TIMEOUT =
        Pattern.compile("(?i)\\btimeout\\b|\\btime[\\s_-]+out\\b");
    private static final Pattern GATEWAY =
        Pattern.compile("(?i)\\bother\\s+gateways?\\b");
    private static final Pattern API_KEY_OR_OAUTH =
        Pattern.compile("(?i)\\bapi[\\s_-]*key\\b|\\boauth\\b");
    private static final Pattern RUN_ISSUE =
        Pattern.compile("(?i)\\b(run|error|failed|failure|incident)\\b");
    private static final Pattern APPROVAL =
        Pattern.compile("(?i)\\b(approval|approve|validation|submitted\\s+by)\\b");

    private static final Pattern SLACK_LINK =
        Pattern.compile("<https?://[^>|]+(?:\\|[^>]+)?>");
    private static final Pattern SLACK_MENTION =
        Pattern.compile("<@([A-Z0-9]+)(?:\\|[^>]+)?>");
    private static final Pattern UUID_PATTERN =
        Pattern.compile("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b");
    private static final Pattern ENVIRONMENT_VALUE =
        Pattern.compile("(?i)(environment\\s*\\*?\\s*)[a-z0-9_-]+");
    private static final Pattern SUBMITTER_VALUE =
        Pattern.compile("(?im)(\\bsubmitted\\s+by\\s+\\*?).*?(\\*?\\s*(?:\\R|$))");
    private static final Pattern WHITESPACE =
        Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public Classification classify(SlackMessage message) {
        final String body = message.blocksText() != null && !message.blocksText().isBlank()
            ? message.blocksText()
            : message.text();
        final String requestText = body == null ? "" : body;
        final Category category = categoryFor(requestText);
        final String normalizedTemplate = normalize(requestText);
        final String templateId = templateId(category, normalizedTemplate);
        return new Classification(category, templateId);
    }

    private Category categoryFor(String text) {
        if (TIMEOUT.matcher(text).find()) {
            return Category.TIMEOUT_CHANGE;
        }
        if (GATEWAY.matcher(text).find()) {
            return Category.GATEWAY_ACCESS;
        }
        if (API_KEY_OR_OAUTH.matcher(text).find()) {
            return Category.API_KEY_OAUTH;
        }
        if (RUN_ISSUE.matcher(text).find()) {
            return Category.RUN_ISSUE;
        }
        if (APPROVAL.matcher(text).find()) {
            return Category.APPROVAL_VALIDATION;
        }
        return Category.OTHER;
    }

    static String templateId(Category category, String normalizedTemplate) {
        return UUID.nameUUIDFromBytes(
            (category.name() + ":" + normalizedTemplate).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String normalize(String text) {
        String normalized = SLACK_LINK.matcher(text).replaceAll("<link>");
        normalized = SLACK_MENTION.matcher(normalized).replaceAll("<user>");
        normalized = UUID_PATTERN.matcher(normalized).replaceAll("<uuid>");
        normalized = ENVIRONMENT_VALUE.matcher(normalized).replaceAll("$1<environment>");
        normalized = SUBMITTER_VALUE.matcher(normalized).replaceAll("$1<person>$2");
        return WHITESPACE.matcher(normalized.toLowerCase(Locale.ROOT))
            .replaceAll(" ")
            .trim();
    }

    public enum Category {
        TIMEOUT_CHANGE,
        GATEWAY_ACCESS,
        API_KEY_OAUTH,
        RUN_ISSUE,
        APPROVAL_VALIDATION,
        OTHER
    }

    public record Classification(Category category, String templateId) {}
}
