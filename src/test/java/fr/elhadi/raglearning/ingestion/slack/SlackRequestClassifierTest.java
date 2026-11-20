package fr.elhadi.raglearning.ingestion.slack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SlackRequestClassifierTest {

    private final SlackRequestClassifier classifier = new SlackRequestClassifier();

    @Test
    void shouldClassifyTimeoutRequestsAndNormalizeApiAndEnvironmentValues() {
        final SlackMessage first = message(
            "Timeout Change for api <https://example.test/api/one|API One>\nEnvironment * PROD"
        );
        final SlackMessage second = message(
            "TIMEOUT   Change for api <https://example.test/api/two|API Two>\n"
                + "Environment * SIT"
        );

        final SlackRequestClassifier.Classification firstResult = classifier.classify(first);
        final SlackRequestClassifier.Classification secondResult = classifier.classify(second);

        assertEquals(SlackRequestClassifier.Category.TIMEOUT_CHANGE, firstResult.category());
        assertEquals(firstResult.category(), secondResult.category());
        assertEquals(firstResult.templateId(), secondResult.templateId());
        assertEquals(
            "Timeout Change for api <https://example.test/api/one|API One>\nEnvironment * PROD",
            first.text()
        );
    }

    @Test
    void shouldNormalizeMentionsUuidsAndSubmitterValues() {
        final SlackMessage first = message(
            "Please validate <@U123|Alice> request 123e4567-e89b-12d3-a456-426614174000\n"
                + "Submitted by Alice Example"
        );
        final SlackMessage second = message(
            "Please validate <@U987|Bob> request 987e6543-e21b-34d3-a456-426614174111\n"
                + "Submitted by Bob Example"
        );

        assertEquals(
            classifier.classify(first).templateId(),
            classifier.classify(second).templateId()
        );
        assertEquals(
            SlackRequestClassifier.Category.APPROVAL_VALIDATION,
            classifier.classify(first).category()
        );
    }

    @Test
    void shouldCollapseUnicodeWhitespaceWhenGeneratingTemplateIds() {
        final SlackMessage regularWhitespace = message("Timeout Change request");
        final SlackMessage nonBreakingSpace = message("Timeout\u00A0Change request");

        assertEquals(
            classifier.classify(regularWhitespace).templateId(),
            classifier.classify(nonBreakingSpace).templateId()
        );
    }

    @Test
    void shouldUseBlocksTextWhenNonblankAndFallBackToTextOtherwise() {
        final SlackMessage blocksPreferred = new SlackMessage(
            null, null, null, null, null, null, null,
            "Timeout requested", "Access To Other Gateways",
            null, null, null
        );
        final SlackMessage textFallback = new SlackMessage(
            null, null, null, null, null, null, null,
            "Switch API Key to OAuth", "  ",
            null, null, null
        );

        assertEquals(
            SlackRequestClassifier.Category.GATEWAY_ACCESS,
            classifier.classify(blocksPreferred).category()
        );
        assertEquals(
            SlackRequestClassifier.Category.API_KEY_OAUTH,
            classifier.classify(textFallback).category()
        );
    }

    @Test
    void shouldClassifyCategoriesInPriorityOrderAndGiveCategoriesDistinctTemplateIds() {
        final SlackRequestClassifier.Classification timeout = classifier.classify(
            message("Timeout failed with an error")
        );
        final SlackRequestClassifier.Classification gateway = classifier.classify(
            message("Access To Other Gateways")
        );
        final SlackRequestClassifier.Classification apiKey = classifier.classify(
            message("Switch API Key to OAuth")
        );
        final SlackRequestClassifier.Classification runIssue = classifier.classify(
            message("Run failed with an error")
        );
        final SlackRequestClassifier.Classification approval = classifier.classify(
            message("Approval validation required")
        );
        final SlackRequestClassifier.Classification other = classifier.classify(
            message("Can someone review this?")
        );

        assertEquals(SlackRequestClassifier.Category.TIMEOUT_CHANGE, timeout.category());
        assertEquals(SlackRequestClassifier.Category.GATEWAY_ACCESS, gateway.category());
        assertEquals(SlackRequestClassifier.Category.API_KEY_OAUTH, apiKey.category());
        assertEquals(SlackRequestClassifier.Category.RUN_ISSUE, runIssue.category());
        assertEquals(SlackRequestClassifier.Category.APPROVAL_VALIDATION, approval.category());
        assertEquals(SlackRequestClassifier.Category.OTHER, other.category());

        final SlackRequestClassifier.Classification timeoutLink = classifier.classify(
            message("<https://example.test/request|Timeout>")
        );
        final SlackRequestClassifier.Classification gatewayLink = classifier.classify(
            message("<https://example.test/request|Other Gateways>")
        );
        assertEquals(timeoutLink.category(), SlackRequestClassifier.Category.TIMEOUT_CHANGE);
        assertEquals(gatewayLink.category(), SlackRequestClassifier.Category.GATEWAY_ACCESS);
        assertNotEquals(timeoutLink.templateId(), gatewayLink.templateId());
    }

    @Test
    void shouldIncludeCategoryWhenGeneratingTemplateIdForTheSameNormalizedTemplate() {
        final String normalizedTemplate = "same normalized template";

        final String timeoutTemplateId = SlackRequestClassifier.templateId(
            SlackRequestClassifier.Category.TIMEOUT_CHANGE,
            normalizedTemplate
        );
        final String gatewayTemplateId = SlackRequestClassifier.templateId(
            SlackRequestClassifier.Category.GATEWAY_ACCESS,
            normalizedTemplate
        );

        assertNotEquals(timeoutTemplateId, gatewayTemplateId);
    }

    private SlackMessage message(String text) {
        return new SlackMessage(
            null, null, null, null, null, null, null, text, null, null, null, null
        );
    }
}
