package ai.operativus.agentmanager.control.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the audit-log changeset scrubber (T018). Asserts that:
 *   1. Top-level secret-keyed values are masked.
 *   2. Nested objects are walked.
 *   3. Arrays of objects are walked.
 *   4. Match is case-insensitive on key name.
 *   5. Malformed JSON is returned unchanged.
 *   6. Null / blank inputs are passed through.
 *   7. Non-secret values at any depth survive intact.
 *   8. Custom secret-key sets override the default.
 */
class ChangesetScrubberTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ChangesetScrubber scrubber;

    @BeforeEach
    void setUp() {
        scrubber = new ChangesetScrubber(mapper);
    }

    @Test
    @DisplayName("masks top-level apiKey and api_key (default set)")
    void masksTopLevelApiKeyVariants() throws Exception {
        String input = "{\"name\":\"GPT-4\",\"apiKey\":\"sk-real-secret\",\"api_key\":\"another\"}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        assertEquals("GPT-4", node.get("name").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("apiKey").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("api_key").asText());
    }

    @Test
    @DisplayName("masks nested secrets inside agent definition objects")
    void masksNestedSecrets() throws Exception {
        String input = "{\"agent\":{\"id\":\"a-1\",\"model\":{\"provider\":\"OPENAI\",\"apiKey\":\"sk-deep\"}}}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        assertEquals("a-1", node.at("/agent/id").asText());
        assertEquals("OPENAI", node.at("/agent/model/provider").asText());
        assertEquals(ChangesetScrubber.MASK, node.at("/agent/model/apiKey").asText());
    }

    @Test
    @DisplayName("masks secrets inside arrays of objects")
    void masksSecretsInsideArrays() throws Exception {
        String input = "{\"credentials\":[{\"name\":\"a\",\"token\":\"t1\"},{\"name\":\"b\",\"token\":\"t2\"}]}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        // Top-level "credentials" is itself a default-secret key — entire array gets flattened to MASK
        assertEquals(ChangesetScrubber.MASK, node.get("credentials").asText());
    }

    @Test
    @DisplayName("masks token inside array elements when array key is non-secret")
    void masksTokenInsideNonSecretArray() throws Exception {
        String input = "{\"items\":[{\"name\":\"a\",\"token\":\"t1\"},{\"name\":\"b\",\"token\":\"t2\"}]}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        assertEquals("a", node.at("/items/0/name").asText());
        assertEquals(ChangesetScrubber.MASK, node.at("/items/0/token").asText());
        assertEquals("b", node.at("/items/1/name").asText());
        assertEquals(ChangesetScrubber.MASK, node.at("/items/1/token").asText());
    }

    @Test
    @DisplayName("matches keys case-insensitively")
    void matchesCaseInsensitive() throws Exception {
        String input = "{\"APIKEY\":\"X\",\"Password\":\"Y\",\"Secret\":\"Z\",\"PrivateKey\":\"P\"}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        assertEquals(ChangesetScrubber.MASK, node.get("APIKEY").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("Password").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("Secret").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("PrivateKey").asText());
    }

    @Test
    @DisplayName("returns malformed JSON unchanged (defensive fallback)")
    void returnsMalformedJsonUnchanged() {
        String malformed = "{not real json,";
        assertEquals(malformed, scrubber.scrub(malformed));
    }

    @Test
    @DisplayName("passes null and blank inputs through")
    void passesNullAndBlankThrough() {
        assertEquals(null, scrubber.scrub(null));
        assertEquals("", scrubber.scrub(""));
        assertEquals("   ", scrubber.scrub("   "));
    }

    @Test
    @DisplayName("preserves non-secret primitives, arrays of primitives, and types")
    void preservesNonSecretValues() throws Exception {
        String input = "{\"name\":\"x\",\"count\":42,\"active\":true,\"tags\":[\"a\",\"b\"],\"meta\":null}";
        String out = scrubber.scrub(input);
        var node = mapper.readTree(out);
        assertEquals("x", node.get("name").asText());
        assertEquals(42, node.get("count").asInt());
        assertTrue(node.get("active").asBoolean());
        assertEquals(2, node.get("tags").size());
        assertEquals("a", node.get("tags").get(0).asText());
        assertNotNull(node.get("meta"));
        assertTrue(node.get("meta").isNull());
    }

    @Test
    @DisplayName("custom secret-key set overrides the default list")
    void customSecretKeySetOverridesDefault() throws Exception {
        ChangesetScrubber custom = new ChangesetScrubber(mapper, Set.of("customsecret"));
        String input = "{\"apiKey\":\"would-default-mask\",\"customSecret\":\"masked-by-custom\"}";
        String out = custom.scrub(input);
        var node = mapper.readTree(out);
        // apiKey survives because custom set doesn't include it
        assertEquals("would-default-mask", node.get("apiKey").asText());
        assertEquals(ChangesetScrubber.MASK, node.get("customSecret").asText());
    }
}
