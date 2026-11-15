package fr.elhadi.raglearning.services;

import fr.elhadi.raglearning.models.RawMessage;
import fr.elhadi.raglearning.models.slack.SlackMessage;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SlackAnonymizerTest {

    private final SlackAnonymizer anonymizer = new SlackAnonymizer();

    @Test
    void shouldRedactAuthorizationApiKeysAndJwtTokens() {
        final SlackMessage message = message(
            "U1",
            "Authorization: Bearer bearer-secret, apikey=api-secret, "
                + "x-api-key: header-secret, "
                + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123",
            null,
            List.of()
        );

        final RawMessage anonymized = anonymizer.anonymize(message, "1.1");

        assertEquals(
            "USER A",
            anonymized.author()
        );
        assertEquals(
            "Authorization: Bearer [REDACTED], apikey=[REDACTED], "
                + "x-api-key: [REDACTED], [REDACTED_JWT]",
            anonymized.userRequest()
        );
        assertFalse(anonymized.userRequest().contains("bearer-secret"));
        assertFalse(anonymized.userRequest().contains("api-secret"));
        assertFalse(anonymized.userRequest().contains("header-secret"));
        assertFalse(anonymized.userRequest().contains("eyJ"));
    }

    @Test
    void shouldNotAssignAliasesToBotAuthorsAndShouldRestartForEachThread() {
        final SlackMessage humanReply = message("U2", "human reply", null, List.of());
        final SlackMessage botReply = message(
            "BOTUSER",
            "bot reply",
            "B1",
            List.of(humanReply)
        );
        final SlackMessage firstThread = message("U1", "root", null, List.of(botReply));
        final SlackMessage secondThread = message("U9", "another root", null, List.of());

        final RawMessage first = anonymizer.anonymize(firstThread, "1.1");
        final RawMessage second = anonymizer.anonymize(secondThread, "2.1");

        assertEquals(
            List.of("USER A: root", "RUNBOY: bot reply", "USER B: human reply"),
            List.of(first.author() + ": " + first.userRequest(), first.replies().get(0), first.replies().get(1))
        );
        assertEquals("USER A", second.author());
    }

    @Test
    void shouldDeduplicateOnlyExactBotTextAndKeepNestedAndHumanReplies() {
        final Set<String> seenAnswers = new HashSet<>();
        final SlackMessage nestedHuman = message("U2", "Approved", null, List.of());
        final SlackMessage firstBotReply = message(
            "BOTUSER",
            "Approved",
            "B1",
            List.of(nestedHuman)
        );
        final SlackMessage firstThread = message("U1", "first request", null, List.of(firstBotReply));
        final SlackMessage duplicateBotReply = message(
            "BOTUSER",
            "Approved",
            "B1",
            List.of(message("U3", "nested follow-up", null, List.of()))
        );
        final SlackMessage secondThread = message(
            "U4",
            "second request",
            null,
            List.of(duplicateBotReply)
        );

        final RawMessage first = anonymizer.anonymize(firstThread, "1.1", seenAnswers);
        final RawMessage second = anonymizer.anonymize(secondThread, "2.1", seenAnswers);

        assertEquals(
            List.of("RUNBOY: Approved", "USER B: Approved"),
            first.replies()
        );
        assertEquals(List.of("USER B: nested follow-up"), second.replies());
        assertEquals(Set.of("Approved"), seenAnswers);
    }

    @Test
    void shouldKeepRepliesWhenDuplicateBotIsTheThreadRoot() {
        final Set<String> seenAnswers = new HashSet<>();
        final SlackMessage firstBotRoot = message("BOTUSER", "Approved", "B1", List.of());
        final SlackMessage duplicateBotRoot = message(
            "BOTUSER",
            " Approved ",
            "B1",
            List.of(message("U1", "human follow-up", null, List.of()))
        );

        anonymizer.anonymize(firstBotRoot, "1.1", seenAnswers);
        final RawMessage duplicate = anonymizer.anonymize(duplicateBotRoot, "2.1", seenAnswers);

        assertEquals("", duplicate.userRequest());
        assertEquals(List.of("USER A: human follow-up"), duplicate.replies());
        assertEquals(Set.of("Approved"), seenAnswers);
    }

    @Test
    void shouldSkipBlankBotRepliesAndKeepNestedHumanReplies() {
        final SlackMessage blankBotReply = message(
            "BOTUSER",
            " \n\t ",
            "B1",
            List.of(message("U2", "nested human follow-up", null, List.of()))
        );
        final SlackMessage thread = message("U1", "request", null, List.of(blankBotReply));

        final RawMessage anonymized = anonymizer.anonymize(thread, "1.1");

        assertEquals(List.of("USER B: nested human follow-up"), anonymized.replies());
    }

    @Test
    void shouldReplaceSlackLinksByTheirLabelOrTheirPathWithoutHostNorQuery() {
        final SlackMessage message = message(
            "U1",
            "See <https://developer.example.com/manager/api/0a1b2c3d-1111-2222-3333-444455556666/"
                + "access-restriction?token=abc|magnews V1> and "
                + "<https://developer.example.com/manager/api/0a1b2c3d-1111-2222-3333-444455556666/"
                + "access-restriction?token=abc>",
            null,
            List.of()
        );

        final RawMessage anonymized = anonymizer.anonymize(message, "1.1");

        assertEquals(
            "See magnews V1 and /manager/api/[UUID]/access-restriction",
            anonymized.userRequest()
        );
        assertFalse(anonymized.userRequest().contains("developer.example.com"));
        assertFalse(anonymized.userRequest().contains("token"));
    }

    @Test
    void shouldAnonymizeBareUrlsStandaloneUuidsAndMailtoLinks() {
        final SlackMessage message = message(
            "U1",
            "api 0A1B2C3D-1111-2222-3333-444455556666 at https://host.example.com/path?x=1, "
                + "home <https://host.example.com/>, contact <mailto:john@example.com|john@example.com>",
            null,
            List.of()
        );

        final RawMessage anonymized = anonymizer.anonymize(message, "1.1");

        assertEquals(
            "api [UUID] at /path, home [URL], contact [REDACTED_EMAIL]",
            anonymized.userRequest()
        );
    }

    @Test
    void shouldKeepIdentifierTypePrefixesAndOnlyRedactTheUuidPart() {
        final SlackMessage message = message(
            "U1",
            "Open <https://host.example.com/manager/api/api-0a1b2c3d-1111-2222-3333-444455556666/"
                + "access-restriction> for application-0a1b2c3d-1111-2222-3333-444455556666 "
                + "and api_0a1b2c3d-1111-2222-3333-444455556666",
            null,
            List.of()
        );

        final RawMessage anonymized = anonymizer.anonymize(message, "1.1");

        assertEquals(
            "Open /manager/api/api-[UUID]/access-restriction for application-[UUID] and api_[UUID]",
            anonymized.userRequest()
        );
    }

    @Test
    void shouldAliasPlainTextSubmitterNamesInBotFormsEverywhereInTheThread() {
        final SlackMessage reply = message("U1", "FYI Jane DOE, ping jane doe again", null, List.of());
        final SlackMessage botForm = message(
            "BOTUSER",
            "*Submitted by* Jane DOE\n*Description* help",
            "B1",
            List.of(reply)
        );

        final RawMessage anonymized = anonymizer.anonymize(botForm, "1.1");

        assertEquals("*Submitted by* USER A\n*Description* help", anonymized.userRequest());
        assertEquals(List.of("USER B: FYI USER A, ping USER A again"), anonymized.replies());
    }

    @Test
    void shouldAliasSubmitterNamesWrittenAfterAnEmojiOrWithASpacedLabel() {
        final SlackMessage questionForm = message(
            "BOTUSER",
            ":question:Submitted by John SMITH\nYour question: hello",
            "B1",
            List.of()
        );
        final SlackMessage spacedForm = message("BOTUSER", "*Submitted by * John SMITH", "B1", List.of());

        assertEquals(
            ":question:Submitted by USER A\nYour question: hello",
            anonymizer.anonymize(questionForm, "1.1").userRequest()
        );
        assertEquals("*Submitted by * USER A", anonymizer.anonymize(spacedForm, "2.1").userRequest());
    }

    @Test
    void shouldKeepAliasingSubmittersGivenAsSlackMentions() {
        final SlackMessage botForm = message("BOTUSER", "*Submitted by* <@U1>", "B1", List.of());

        assertEquals("*Submitted by* USER A", anonymizer.anonymize(botForm, "1.1").userRequest());
    }

    @Test
    void shouldLeaveSlackChannelReferencesAndPlainTextUntouched() {
        final SlackMessage message = message(
            "U1",
            "Ask in <#C123|general> at 10:30, ratio 3:4",
            null,
            List.of()
        );

        final RawMessage anonymized = anonymizer.anonymize(message, "1.1");

        assertEquals("Ask in <#C123|general> at 10:30, ratio 3:4", anonymized.userRequest());
    }

    private SlackMessage message(
        String user,
        String text,
        String botId,
        List<SlackMessage> replies
    ) {
        return new SlackMessage(
            null,
            null,
            "1.1",
            null,
            user,
            botId,
            null,
            text,
            null,
            null,
            null,
            replies
        );
    }
}
