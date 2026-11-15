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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class SlackAnonymizer {

    /**
     * Adresse IPv4, avec un éventuel masque CIDR ({@code 10.204.184.79}, {@code 172.16.0.0/12}).
     * Elle révèle la topologie du réseau interne, comme un nom d'hôte. Un numéro de version à
     * trois segments ({@code 1.2.3}) n'est pas concerné.
     */
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
        "(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:/\\d{1,2})?(?![\\w.])"
    );

    private static final Pattern SLACK_MENTION =
        Pattern.compile("<@([A-Z0-9]+)(?:\\|[^>]+)?>");

    /**
     * Demandeur écrit en texte brut dans le formulaire du bot, par exemple
     * {@code *Submitted by* Prénom NOM} ou {@code :question:Submitted by Prénom NOM}.
     * Une mention ({@code <@U123>}) est exclue : elle est déjà traitée par {@link #SLACK_MENTION}.
     */
    private static final Pattern PLAIN_TEXT_SUBMITTER = Pattern.compile(
        "(?im)\\bSubmitted by\\b[\\h*:]*([^\\s<*:][^\\n<*]*?)\\h*$"
    );

    /** Préfixe des clés d'alias qui désignent un nom en texte brut plutôt qu'un identifiant Slack. */
    private static final String PLAIN_NAME_KEY = "name:";

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

    /** Extensions de domaine publiques. */
    private static final List<String> PUBLIC_TLDS = List.of(
        // Génériques
        "com", "net", "org", "edu", "gov", "info", "pro", "dev", "app", "cloud", "tech",
        "blog", "cat", "design", "digital", "meet", "post", "solutions", "world", "microsoft", "gle",
        // Codes pays utilisés comme génériques
        "io", "ai", "co",
        // Codes pays
        "fr", "eu", "be", "es", "it", "pt", "pl", "br", "za", "ag", "ch", "cn", "cy", "dj",
        "gl", "gr", "hu", "ie", "im", "kz", "mp", "ne", "qa", "ro", "ru", "se", "ua", "vn"
    );

    /** Suffixes de noms d'hôte propres à l'environnement de l'entreprise. */
    private static final List<String> INTERNAL_DOMAIN_SUFFIXES = List.of(
        // Réseau interne, Kubernetes et cloud
        "local", "internal", "corp", "svc", "cluster", "gcp", "api",
        // Domaines de l'entreprise et de ses enseignes
        "adeo", "adeocom", "leroymerlin", "obram", "obramax", "weldom",
        // Noms de services internes relevés dans les messages
        "frlm-enki-backend-dev", "scdp-delizone-hmbu-sit",
        // Fautes de frappe ou noms tronqués relevés dans les messages
        "comy", "ri", "som"
    );

    /**
     * Nom d'hôte écrit sans schéma, par exemple {@code prep-developer.example.fr/docs}.
     * Seuls les noms se terminant par un suffixe de {@link #PUBLIC_TLDS} ou de
     * {@link #INTERNAL_DOMAIN_SUFFIXES} sont visés, pour épargner les noms de fichiers
     * ({@code config.json}). Un paquet Java dont le dernier segment est aussi un suffixe
     * ({@code org.springframework.ai}) est masqué : masquer à tort coûte moins cher que laisser fuiter.
     * Le chemin qui suit l'hôte est conservé. Une adresse e-mail n'est pas concernée : son domaine
     * est précédé d'un {@code @}, et la règle des e-mails la masque ensuite.
     */
    private static final Pattern BARE_HOSTNAME = Pattern.compile(
        "(?i)(?<![\\w.@/-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:"
            + Stream.concat(PUBLIC_TLDS.stream(), INTERNAL_DOMAIN_SUFFIXES.stream())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"))
            + ")(?![\\w.-])"
    );

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

    private final SlackMarkupCleaner markupCleaner;

    public SlackAnonymizer(SlackMarkupCleaner markupCleaner) {
        this.markupCleaner = Objects.requireNonNull(markupCleaner, "markupCleaner");
    }

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
        if (isBot(message)) {
            collectSubmitterNames(messageText(message), userIds);
        }
        collectMentionIds(messageText(message), userIds);
        safeMessages(message.replies()).forEach(reply -> collectUserIds(reply, userIds));
    }

    private void collectSubmitterNames(String text, LinkedHashSet<String> userIds) {
        final Matcher submitters = PLAIN_TEXT_SUBMITTER.matcher(text);
        while (submitters.find()) {
            userIds.add(PLAIN_NAME_KEY + submitters.group(1).strip());
        }
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

    /**
     * Nettoie la mise en forme, <b>puis</b> anonymise. L'ordre est essentiel : une mise en forme
     * peut couper une donnée sensible ({@code *ccdp*.priv.example.cloud}) au point qu'aucune règle
     * ne la reconnaisse ; si le nettoyage passait après, il la recomposerait en clair.
     * L'anonymisation doit donc être la dernière transformation du texte.
     */
    private String sanitizeText(String text, Map<String, String> userAliases) {
        final String withoutMarkup = markupCleaner.clean(text);
        final String withMentionAliases = SLACK_MENTION.matcher(withoutMarkup)
            .replaceAll(match -> userAliases.getOrDefault(match.group(1), "USER UNKNOWN"));
        final String withAliases = replacePlainNames(withMentionAliases, userAliases);
        final String withoutSlackLinks = SLACK_LINK.matcher(withAliases)
            .replaceAll(match -> Matcher.quoteReplacement(describeLink(match.group(1), match.group(2))));
        final String withoutBareUrls = BARE_URL.matcher(withoutSlackLinks)
            .replaceAll(match -> Matcher.quoteReplacement(describeUrl(match.group())));
        final String withoutHostnames = BARE_HOSTNAME.matcher(withoutBareUrls).replaceAll("[HOST]");
        final String withoutIpAddresses = IPV4_ADDRESS.matcher(withoutHostnames).replaceAll("[IP]");
        final String withoutUuids = UUID_PATTERN.matcher(withoutIpAddresses).replaceAll("[UUID]");
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
     * Remplace chaque nom repéré dans le formulaire du bot, partout dans le texte du fil,
     * par l'alias de la personne. Les noms les plus longs sont traités d'abord, pour qu'un
     * prénom seul ne remplace pas le début d'un nom complet.
     */
    private String replacePlainNames(String text, Map<String, String> userAliases) {
        String result = text;
        final List<Map.Entry<String, String>> plainNames = userAliases.entrySet().stream()
            .filter(alias -> alias.getKey().startsWith(PLAIN_NAME_KEY))
            .sorted((first, second) -> Integer.compare(second.getKey().length(), first.getKey().length()))
            .toList();
        for (Map.Entry<String, String> plainName : plainNames) {
            final String name = plainName.getKey().substring(PLAIN_NAME_KEY.length());
            result = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])")
                .matcher(result)
                .replaceAll(Matcher.quoteReplacement(plainName.getValue()));
        }
        return result;
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
