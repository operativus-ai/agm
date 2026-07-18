package ai.operativus.agentmanager.compute.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingHealthIndicatorTest {

    private static final int STORE_DIM = 768;

    private EmbeddingHealthIndicator probe(float[] embedResult) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(embedResult);
        return new EmbeddingHealthIndicator(model, new SimpleMeterRegistry(), STORE_DIM);
    }

    private static float[] nonZero(int dim) {
        float[] v = new float[dim];
        v[0] = 0.42f;
        return v;
    }

    @Test
    void operational_realDimMatchedModel() {
        EmbeddingHealthIndicator h = probe(nonZero(STORE_DIM));
        h.probeOnce();

        assertEquals(EmbeddingHealthIndicator.State.OPERATIONAL, h.currentState());
        assertEquals(STORE_DIM, h.currentModelDimensions());
        Health health = h.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals(Boolean.TRUE, health.getDetails().get("searchOperational"));
    }

    @Test
    void disabled_whenZeroVectorsNoOpElected() {
        EmbeddingHealthIndicator h = probe(new float[STORE_DIM]); // all zeros = NoOp
        h.probeOnce();

        assertEquals(EmbeddingHealthIndicator.State.DISABLED_NOOP, h.currentState());
        Health health = h.health();
        assertEquals(Status.UP, health.getStatus(), "RAG-optional: must stay UP so it can't break the container healthcheck");
        assertEquals(Boolean.FALSE, health.getDetails().get("searchOperational"));
        assertEquals("DISABLED_NOOP", health.getDetails().get("state"));
    }

    @Test
    void dimensionMismatch_realModelWrongDim() {
        EmbeddingHealthIndicator h = probe(nonZero(1536)); // real vector, wrong dimension
        h.probeOnce();

        assertEquals(EmbeddingHealthIndicator.State.DIMENSION_MISMATCH, h.currentState());
        assertEquals(1536, h.currentModelDimensions());
        Health health = h.health();
        assertEquals(Status.UP, health.getStatus());
        assertFalse((Boolean) health.getDetails().get("searchOperational"));
    }

    @Test
    void probeError_whenEmbedThrows() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("no api key"));
        EmbeddingHealthIndicator h = new EmbeddingHealthIndicator(model, new SimpleMeterRegistry(), STORE_DIM);
        h.probeOnce();

        assertEquals(EmbeddingHealthIndicator.State.PROBE_ERROR, h.currentState());
        assertEquals(Status.UP, h.health().getStatus());
    }

    @Test
    void beforeProbe_isUnknownButStillUp() {
        EmbeddingHealthIndicator h = probe(nonZero(STORE_DIM)); // not probed yet
        Health health = h.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("UNKNOWN", health.getDetails().get("state"));
        assertFalse((Boolean) health.getDetails().get("searchOperational"));
    }
}
