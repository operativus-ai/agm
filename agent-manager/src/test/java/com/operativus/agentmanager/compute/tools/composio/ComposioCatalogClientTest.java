package com.operativus.agentmanager.compute.tools.composio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.ComposioCatalogAction;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-unit coverage of {@link ComposioCatalogClient#parse(String)} — every
 * upstream envelope shape AGM is tolerant of, plus the defensive null / blank /
 * malformed / no-array-found / partial-row paths.
 *
 * <p>The live ChatClient call path is exercised in the integration tests with
 * WireMock-stubbed responses; here we only validate the JSON adapter.
 */
class ComposioCatalogClientTest {

    private ComposioCatalogClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // The client only needs a real ObjectMapper for parse(). WebClient + breaker
        // are unused in these tests; pass minimal stubs to satisfy the ctor.
        client = new ComposioCatalogClient(
                WebClient.builder().baseUrl("http://localhost").build(),
                mapper,
                CircuitBreaker.ofDefaults("test-cb"),
                "fake-key",
                "/api/v2/actions/list",
                100);
    }

    @Test
    void parse_bareArrayEnvelope_extractsActions() {
        String body = "[{\"name\":\"SLACK_SEND_MESSAGE\",\"app\":\"slack\",\"displayName\":\"Send Message\"}," +
                "{\"name\":\"GITHUB_CREATE_ISSUE\",\"app\":\"github\"}]";
        List<ComposioCatalogAction> out = client.parse(body);
        assertEquals(2, out.size());
        assertEquals("SLACK_SEND_MESSAGE", out.get(0).name());
        assertEquals("slack", out.get(0).app());
        assertEquals("Send Message", out.get(0).displayName());
        assertEquals("GITHUB_CREATE_ISSUE", out.get(1).name());
    }

    @Test
    void parse_v2EnvelopeWithItems_extractsActions() {
        String body = "{\"items\":[{\"name\":\"NOTION_CREATE_PAGE\",\"app\":\"notion\"}]}";
        List<ComposioCatalogAction> out = client.parse(body);
        assertEquals(1, out.size());
        assertEquals("NOTION_CREATE_PAGE", out.get(0).name());
    }

    @Test
    void parse_v3EnvelopeWithTotalAndCursor_extractsItemsArray() {
        String body = "{\"items\":[{\"name\":\"A\",\"app\":\"x\"},{\"name\":\"B\",\"app\":\"y\"}]," +
                "\"totalItems\":2,\"nextCursor\":\"ABC\"}";
        List<ComposioCatalogAction> out = client.parse(body);
        assertEquals(2, out.size());
        assertEquals("A", out.get(0).name());
        assertEquals("B", out.get(1).name());
    }

    @Test
    void parse_actionsKeyAlternative_extractsAsArray() {
        // Composio docs occasionally surface 'actions' instead of 'items'.
        String body = "{\"actions\":[{\"name\":\"X\"}]}";
        assertEquals(1, client.parse(body).size());
    }

    @Test
    void parse_dataKeyAlternative_extractsAsArray() {
        String body = "{\"data\":[{\"name\":\"X\"}]}";
        assertEquals(1, client.parse(body).size());
    }

    @Test
    void parse_jsonAliases_actionFieldName() {
        // Some Composio surfaces have used 'action' rather than 'name'.
        String body = "{\"items\":[{\"action\":\"FOO\",\"appKey\":\"slack\"}]}";
        List<ComposioCatalogAction> out = client.parse(body);
        assertEquals(1, out.size());
        assertEquals("FOO", out.get(0).name());
        assertEquals("slack", out.get(0).app());
    }

    @Test
    void parse_snakeCaseDisplayName_isAccepted() {
        String body = "[{\"name\":\"X\",\"display_name\":\"Big X\"}]";
        assertEquals("Big X", client.parse(body).get(0).displayName());
    }

    @Test
    void parse_partialRow_missingName_isFiltered() {
        // Defensive: upstream sometimes returns rows mid-deprecation with no name.
        String body = "[{\"name\":\"OK\"},{\"description\":\"orphan\"}]";
        List<ComposioCatalogAction> out = client.parse(body);
        assertEquals(1, out.size());
        assertEquals("OK", out.get(0).name());
    }

    @Test
    void parse_blankName_isFiltered() {
        String body = "[{\"name\":\"   \"},{\"name\":\"REAL\"}]";
        assertEquals(1, client.parse(body).size());
    }

    @Test
    void parse_nullBody_returnsEmpty() {
        assertNotNull(client.parse(null));
        assertEquals(0, client.parse(null).size());
    }

    @Test
    void parse_blankBody_returnsEmpty() {
        assertEquals(0, client.parse("").size());
        assertEquals(0, client.parse("   ").size());
    }

    @Test
    void parse_noArrayInResponse_returnsEmpty() {
        // {"error": "..."} or {"status":"ok"} shape — no actions to extract.
        assertEquals(0, client.parse("{\"error\":\"unauthorized\"}").size());
        assertEquals(0, client.parse("{\"status\":\"ok\"}").size());
    }

    @Test
    void parse_malformedJson_returnsEmpty_doesNotThrow() {
        assertEquals(0, client.parse("not json at all").size());
        assertEquals(0, client.parse("{\"items\":[broken").size());
    }

    @Test
    void parse_deprecatedFlag_isCarriedThroughBothAliases() {
        String body = "[{\"name\":\"A\",\"deprecated\":true},{\"name\":\"B\",\"isDeprecated\":true}]";
        List<ComposioCatalogAction> out = client.parse(body);
        assertTrue(out.get(0).isDeprecated());
        assertTrue(out.get(1).isDeprecated());
    }
}
