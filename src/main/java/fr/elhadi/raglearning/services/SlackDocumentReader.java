package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.models.RawMessage;
import fr.elhadi.raglearning.models.slack.SlackChannel;
import fr.elhadi.raglearning.models.slack.SlackExport;
import fr.elhadi.raglearning.models.slack.SlackMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SlackDocumentReader {

    private final JsonMapper jsonMapper;
    private final SlackAnonymizer slackAnonymizer;
    private final SlackRequestClassifier slackRequestClassifier;

    public List<Document> read(String slackJsonExtractionPath) throws IOException {
        final SlackExport slackExport;
        try (InputStream inputStream = new FileSystemResource(slackJsonExtractionPath).getInputStream()) {
            slackExport = jsonMapper.readValue(inputStream, SlackExport.class);
        }
        if (slackExport == null) {
            throw new IOException("Slack export is empty: " + slackJsonExtractionPath);
        }

        final SlackChannel channel = slackExport.channel();
        final String channelId = channel == null ? null : channel.id();
        final String channelName = channel == null ? null : channel.name();
        final String channelIdentity = firstNonBlank(channelId, channelName, "unknown-channel");
        final List<SlackMessage> messages =
            slackExport.messages() == null ? List.of() : slackExport.messages();
        final List<SlackThread> threads = groupThreads(messages);
        final List<Document> documents = new ArrayList<>(threads.size());
        final Map<String, Set<String>> seenBotAnswersByTemplate = new HashMap<>();

        for (SlackThread thread : threads) {
            final String threadId = thread.threadId();
            final SlackRequestClassifier.Classification classification =
                slackRequestClassifier.classify(thread.root());
            final Set<String> seenAnswers = classification.category()
                == SlackRequestClassifier.Category.OTHER
                ? new HashSet<>()
                : seenBotAnswersByTemplate.computeIfAbsent(
                    classification.templateId(),
                    ignored -> new HashSet<>()
                );
            final RawMessage rawMessage =
                slackAnonymizer.anonymize(thread.root(), threadId, seenAnswers);
            final String documentId = documentId(channelIdentity, threadId);
            final Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "slack");
            metadata.put("threadId", threadId);
            metadata.put("sourceDocumentId", documentId);
            metadata.put("requestCategory", classification.category().name());
            metadata.put("requestTemplateId", classification.templateId());
            if (channelId != null && !channelId.isBlank()) {
                metadata.put("channelId", channelId);
            }
            if (rawMessage.author() != null) {
                metadata.put("author", rawMessage.author());
            }
            if (channelName != null) {
                metadata.put("channel", channelName);
            }

            final List<String> renderedMessages = new ArrayList<>();
            if (!("RUNBOY".equals(rawMessage.author()) && rawMessage.userRequest().isBlank())) {
                renderedMessages.add(rawMessage.author() + ": " + rawMessage.userRequest());
            }
            renderedMessages.addAll(rawMessage.replies());
            final String text = String.join("\n", renderedMessages);
            documents.add(Document.builder()
                .id(documentId)
                .metadata(metadata)
                .text(text)
                .build());
        }
        return List.copyOf(documents);
    }

    private List<SlackThread> groupThreads(List<SlackMessage> messages) throws IOException {
        final Map<String, SlackMessage> threads = new LinkedHashMap<>();
        final List<SlackThread> topLevelReplies = new ArrayList<>();

        for (int index = 0; index < messages.size(); index++) {
            final SlackMessage message = messages.get(index);
            if (message == null) {
                throw new IOException("Slack export contains a null message at index " + index);
            }
            if (isTopLevelReply(message)) {
                topLevelReplies.add(new SlackThread(message.threadTs(), message));
                continue;
            }
            final String threadId = firstNonBlank(
                message.ts(),
                message.threadTs(),
                "missing-ts-" + index
            );
            threads.putIfAbsent(threadId, message);
        }

        for (SlackThread topLevelReply : topLevelReplies) {
            final SlackMessage parent = threads.get(topLevelReply.threadId());
            if (parent == null) {
                threads.putIfAbsent(topLevelReply.threadId(), topLevelReply.root());
                continue;
            }
            if (!containsReply(parent.replies(), topLevelReply.root())) {
                threads.put(topLevelReply.threadId(), withReply(parent, topLevelReply.root()));
            }
        }

        return threads.entrySet().stream()
            .map(entry -> new SlackThread(entry.getKey(), entry.getValue()))
            .toList();
    }

    private boolean isTopLevelReply(SlackMessage message) {
        return message.threadTs() != null
            && !message.threadTs().isBlank()
            && !Objects.equals(message.threadTs(), message.ts());
    }

    private boolean containsReply(List<SlackMessage> replies, SlackMessage candidate) {
        for (SlackMessage reply : replies == null ? List.<SlackMessage>of() : replies) {
            if (candidate.ts() != null && candidate.ts().equals(reply.ts())) {
                return true;
            }
            if (candidate.ts() == null && candidate.equals(reply)) {
                return true;
            }
        }
        return false;
    }

    private SlackMessage withReply(SlackMessage parent, SlackMessage reply) {
        final List<SlackMessage> replies = new ArrayList<>(
            parent.replies() == null ? List.of() : parent.replies()
        );
        replies.add(reply);
        return new SlackMessage(
            parent.type(),
            parent.subtype(),
            parent.ts(),
            parent.threadTs(),
            parent.user(),
            parent.userEmail(),
            parent.botId(),
            parent.botProfile(),
            parent.text(),
            parent.blocksText(),
            parent.attachments(),
            parent.reactions(),
            replies
        );
    }

    private String documentId(String channelIdentity, String threadId) {
        return UUID.nameUUIDFromBytes(
            (channelIdentity + ":" + threadId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private record SlackThread(String threadId, SlackMessage root) {}
}
