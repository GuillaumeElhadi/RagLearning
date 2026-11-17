package fr.elhadi.raglearning.ingestion.slack;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SlackExportTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void shouldDeserializeSlackExportWithSnakeCaseAndNestedReplies() {
        String json = """
            {
              "channel": {
                "id": "C1",
                "name": "team",
                "created": 1545644829,
                "topic": "topic",
                "purpose": "purpose",
                "creator": "U1",
                "creator_email": "creator@example.com",
                "is_private": false
              },
              "members": [{"user_id": "U1", "email": "member@example.com"}],
              "messages": [{
                "user": "U1",
                "ts": "1545644829.000001",
                "reply_count": 1,
                "blocks_text": "message",
                "reactions_normalized": [],
                "files_normalized": [],
                "external_attachments": [],
                "replies": [{
                  "user": "U2",
                  "ts": "1545644830.000001",
                  "text": "reply",
                  "parent_user_id": "U1",
                  "language": "en"
                }]
              }],
              "user_directory": {"U1": "creator@example.com", "U2": null}
            }
            """;

        SlackExport export = jsonMapper.readValue(json, SlackExport.class);

        assertEquals("team", export.channel().name());
        assertFalse(export.channel().isPrivate());
        assertEquals("U1", export.members().getFirst().userId());
        assertEquals("message", export.messages().getFirst().blocksText());
        assertEquals("reply", export.messages().getFirst().replies().getFirst().text());
    }
}
