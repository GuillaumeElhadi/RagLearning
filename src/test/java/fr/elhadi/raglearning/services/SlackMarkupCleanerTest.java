package fr.elhadi.raglearning.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlackMarkupCleanerTest {

    private final SlackMarkupCleaner cleaner = new SlackMarkupCleaner();

    @Test
    void shouldRemoveWorkspaceTagsAndEmojiShortcodes() {
        assertEquals(
            "hello   world",
            cleaner.clean("_tag:devportal_ hello :smile: :+1::tada: world")
        );
    }

    @Test
    void shouldKeepTimesRatiosAndColonSeparatedValues() {
        final String text = "Meeting at 10:30:45, ratio 3:4, path /a:b:c";

        assertEquals(text, cleaner.clean(text));
    }

    @Test
    void shouldTurnBotFieldsIntoPlainLabelsAndDropEmptyFields() {
        assertEquals(
            "Environment: UAT1\n\nDescription: help",
            cleaner.clean("*Environment * UAT1\n*Javascript error*\n*Description* help")
        );
    }

    @Test
    void shouldNotDuplicateColonWhenLabelAlreadyEndsWithOne() {
        assertEquals("Submitted by: X", cleaner.clean("*Submitted by:* X"));
    }

    @Test
    void shouldRemoveInlineBoldMarkersButKeepMultiplications() {
        assertEquals(
            "Use public exposure, not 2 * 3",
            cleaner.clean("Use *public* exposure, not 2 * 3")
        );
    }

    @Test
    void shouldReturnEmptyTextForNullInput() {
        assertEquals("", cleaner.clean(null));
    }
}
