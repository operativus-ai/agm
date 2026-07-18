package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.entity.ModelType;
import com.operativus.agentmanager.core.model.ModelDTO;
import com.operativus.agentmanager.core.model.ModelRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pin the wire-format and validation contract for the §6 M-12
 * per-model rate-limit override field. Phase 2 wiring (actual enforcement at the LLM
 * call site) and Phase 3 wiring (UI control) both rely on this contract being stable;
 * silent drift in field name, type, or bounds breaks them.
 */
class ModelRateLimitFieldTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void modelEntity_rateLimitRpm_roundTripsThroughGettersAndSetters() {
        ModelEntity entity = new ModelEntity();
        assertThat(entity.getRateLimitRpm()).isNull();

        entity.setRateLimitRpm(60);
        assertThat(entity.getRateLimitRpm()).isEqualTo(60);

        entity.setRateLimitRpm(null);
        assertThat(entity.getRateLimitRpm()).isNull();
    }

    @Test
    void modelDTO_serializesRateLimitRpmField_usingExactWireName() throws Exception {
        ModelDTO dto = new ModelDTO(
                "id-1", "n", "OPENAI", null, "gpt-x",
                true, false, true,
                100, 100, null,
                ModelType.CHAT,
                LocalDateTime.now(), LocalDateTime.now(),
                0L, null, null, 0L,
                300, false);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"rateLimitRpm\":300");
    }

    @Test
    void modelDTO_nullRateLimitRpm_serializesAsJsonNull() throws Exception {
        ModelDTO dto = new ModelDTO(
                "id-1", "n", "OPENAI", null, "gpt-x",
                true, false, true,
                100, 100, null,
                ModelType.CHAT,
                LocalDateTime.now(), LocalDateTime.now(),
                0L, null, null, 0L,
                null, false);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
        String json = mapper.writeValueAsString(dto);

        assertThat(json).contains("\"rateLimitRpm\":null");
    }

    @Test
    void modelRequest_acceptsValidRateLimitRpm() {
        ModelRequest req = baseRequest(60);
        Set<ConstraintViolation<ModelRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void modelRequest_acceptsNullRateLimitRpm_meaningNoOverride() {
        ModelRequest req = baseRequest(null);
        Set<ConstraintViolation<ModelRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void modelRequest_rejectsNonPositiveRateLimitRpm() {
        Set<ConstraintViolation<ModelRequest>> zero = validator.validate(baseRequest(0));
        Set<ConstraintViolation<ModelRequest>> negative = validator.validate(baseRequest(-1));

        assertThat(zero).extracting(v -> v.getPropertyPath().toString())
                .contains("rateLimitRpm");
        assertThat(negative).extracting(v -> v.getPropertyPath().toString())
                .contains("rateLimitRpm");
    }

    @Test
    void modelRequest_rejectsRateLimitRpmAboveCap() {
        // Cap is 60_000 (1000 RPS) — anything higher is a misconfiguration, not a real ceiling.
        Set<ConstraintViolation<ModelRequest>> over = validator.validate(baseRequest(60_001));
        assertThat(over).extracting(v -> v.getPropertyPath().toString())
                .contains("rateLimitRpm");
    }

    @Test
    void modelRequest_acceptsRateLimitRpmAtCap() {
        Set<ConstraintViolation<ModelRequest>> atCap = validator.validate(baseRequest(60_000));
        assertThat(atCap).isEmpty();
    }

    private static ModelRequest baseRequest(Integer rpm) {
        return new ModelRequest(
                "n", "OPENAI", null, null, "gpt-x",
                100, 100, null,
                true, false, true,
                ModelType.CHAT, null,
                rpm);
    }
}
