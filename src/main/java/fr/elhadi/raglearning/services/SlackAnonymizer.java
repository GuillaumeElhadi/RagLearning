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

    /**
     * Lien Slack : {@code <url>} ou {@code <url|libellé>}. Les références de canal
     * ({@code <#C123|nom>}), les mentions ({@code <@U123>}) et les commandes
     * ({@code <!here>}) ne commencent pas par un schéma et ne sont donc pas concernées.
     */
    private static final Pattern SLACK_LINK =
        Pattern.compile("<([a-zA-Z][a-zA-Z0-9+.-]*:[^|>\\s]*)(?:\\|([^>]*))?>");

    /** URL écrite en clair dans le texte, hors syntaxe de lien Slack. */
    private static final Pattern BARE_URL =
        Pattern.compile("(?i)\\bhttps?://[^\\s<>|]*[^\\s<>|.,;:!?)]");

    /** Partie chemin d'une URL HTTP, sans hôte, requête ni fragment. */
    private static final Pattern HTTP_URL_PATH =
        Pattern.compile("(?i)^https?://[^/?#]+([^?#]*)");

    /**
     * UUID, y compris préfixé par un type ({@code api-…}, {@code application_…}) : seule la
     * partie UUID est remplacée, le préfixe est conservé car il porte du sens sans identifier.
     * Les bornes excluent un caractère hexadécimal plutôt qu'utiliser {@code \b}, qui ne voit
     * pas de frontière entre {@code _} et un chiffre.
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])"
    );

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
        final String withoutSlackLinks = SLACK_LINK.matcher(withAliases)
            .replaceAll(match -> Matcher.quoteReplacement(describeLink(match.group(1), match.group(2))));
        final String withoutBareUrls = BARE_URL.matcher(withoutSlackLinks)
            .replaceAll(match -> Matcher.quoteReplacement(describeUrl(match.group())));
        final String withoutUuids = UUID_PATTERN.matcher(withoutBareUrls).replaceAll("[UUID]");
        final String withAuthorizationRedacted = AUTHORIZATION_HEADER.matcher(withoutUuids)
            .replaceAll("$1[REDACTED]");
        final String withApiKeysRedacted = API_KEY_ASSIGNMENT.matcher(withAuthorizationRedacted)
            .replaceAll("$1[REDACTED]");
        final String withJwtsRedacted = JWT.matcher(withApiKeysRedacted)
            .replaceAll("[REDACTED_JWT]");
        final String withoutCredentials = CREDENTIAL_ASSIGNMENT.matcher(withJwtsRedacted)
            .replaceAll("$1=[REDACTED]");
        return EMAIL_ADDRESS.matcher(withoutCredentials).replaceAll("[REDACTED_EMAIL]");
    }

    /**
     * Le libellé d'un lien porte son sens ("magnews V1") : on le garde quand il existe.
     * Sinon, l'URL est réduite à ce qui est utile sans être identifiant.
     */
    private String describeLink(String url, String label) {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return describeUrl(url);
    }

    /**
     * Ne garde que le chemin d'une URL HTTP : l'hôte révèle l'infrastructure interne,
     * et la requête peut contenir des jetons. Les autres schémas (mailto…) sont masqués.
     */
    private String describeUrl(String url) {
        if (url.regionMatches(true, 0, "mailto:", 0, "mailto:".length())) {
            return "[REDACTED_EMAIL]";
        }
        final Matcher httpUrl = HTTP_URL_PATH.matcher(url);
        if (httpUrl.find()) {
            final String path = httpUrl.group(1);
            return path.isEmpty() || "/".equals(path) ? "[URL]" : path;
        }
        return "[URL]";
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
