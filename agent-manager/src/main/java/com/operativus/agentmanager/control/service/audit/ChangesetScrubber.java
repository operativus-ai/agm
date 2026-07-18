package com.operativus.agentmanager.control.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Domain Responsibility: Recursively masks values for known secret-key names in a
 *   JSON document. Used by the audit-log CSV export (T018) so encrypted-at-rest
 *   provider credentials and similar fields stamped into {@code agent_audits.changeset}
 *   never reach an exported file or external system.
 *
 *   <p>Matching is case-insensitive on key name; scrubbing applies anywhere in the
 *   tree (top-level fields, nested objects, and arrays of objects). The value is
 *   replaced with {@link #MASK}, regardless of the original type — primitives,
 *   strings, and even nested objects under a secret-named key are flattened to
 *   the mask. The structure of the document is preserved so consumers can still
 *   diff scrubbed exports.</p>
 *
 *   <p>Malformed JSON is returned unchanged: the caller has already paid for a
 *   row that doesn't parse cleanly elsewhere; this utility doesn't add a second
 *   failure mode. Callers that care can pre-validate.</p>
 *
 * State: Stateless. Thread-safe iff the supplied {@link ObjectMapper} is.
 */
public final class ChangesetScrubber {

    /** Default secret-key names (lowercase). Operators inheriting this default get
     *  protection for the standard {@code AgentEntity} api_key + the common token /
     *  password / private-key / OAuth-style fields. Keep this list narrow — over-
     *  scrubbing makes diffs unreadable. Custom deployments can pass their own set. */
    public static final Set<String> DEFAULT_SECRET_KEYS = Set.of(
            "apikey",            "api_key",
            "password",
            "secret",            "clientsecret",       "client_secret",
            "encryptedsecret",   "encrypted_secret",
            "token",             "accesstoken",        "access_token",
            "refreshtoken",      "refresh_token",
            "privatekey",        "private_key",
            "credentials"
    );

    public static final String MASK = "***";

    private final ObjectMapper mapper;
    private final Set<String> secretKeys;

    public ChangesetScrubber(ObjectMapper mapper) {
        this(mapper, DEFAULT_SECRET_KEYS);
    }

    public ChangesetScrubber(ObjectMapper mapper, Set<String> secretKeys) {
        this.mapper = mapper;
        this.secretKeys = secretKeys;
    }

    /**
     * @summary Returns a JSON string identical to {@code raw} except for values whose
     *     key matches the configured secret-key set, which are replaced with {@link #MASK}.
     * @logic null / blank in → returned as-is. Malformed JSON → returned as-is. Otherwise
     *     parsed → walked → re-serialized via the supplied ObjectMapper.
     */
    public String scrub(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            JsonNode root = mapper.readTree(raw);
            walk(root);
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return raw;
        }
    }

    private void walk(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String key : fieldNames) {
                if (secretKeys.contains(key.toLowerCase(Locale.ROOT))) {
                    obj.put(key, MASK);
                } else {
                    walk(obj.get(key));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child);
            }
        }
    }
}
