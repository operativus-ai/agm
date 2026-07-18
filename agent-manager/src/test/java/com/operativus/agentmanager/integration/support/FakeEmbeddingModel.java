package com.operativus.agentmanager.integration.support;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: In-process replacement for Spring AI {@link EmbeddingModel}.
 *   Returns deterministic {@value #DIMENSIONS}-dim float vectors derived from a
 *   SHA-256 hash of each input string — identical text always produces the identical
 *   vector, different text produces a different vector. That is the property the
 *   knowledge-base and agentic-memory tests rely on (semantic retrieval is stubbed,
 *   but uniqueness-by-text is preserved).
 * State: Mutable — records every {@link EmbeddingRequest} so tests can assert on
 *   what production code shipped to the embedding boundary.
 *
 * Thread-safety: the {@code received} log is a {@link CopyOnWriteArrayList} so
 *   background-queue workers (virtual threads) recording embeddings concurrently
 *   with the test thread never race. Vector generation is pure and thread-safe.
 *
 * Dimension contract: {@link #DIMENSIONS} = 768 to match the production pgvector
 *   schema ({@code spring.ai.vectorstore.pgvector.dimension=768}). Changing this
 *   will break {@code PgVectorStore} inserts.
 */
public final class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 768;

    private final List<EmbeddingRequest> received = new CopyOnWriteArrayList<>();

    /**
     * One-shot failure script: when non-null, {@link #call(EmbeddingRequest)} consumes it on
     * the next invocation by throwing the staged exception INSTEAD of recording/returning a
     * vector. Used to simulate embedding-API failures mid-ingest. The reference is cleared
     * atomically on consumption so a single-shot stub only fires once.
     */
    private final AtomicReference<RuntimeException> failNextCall = new AtomicReference<>();

    public List<EmbeddingRequest> receivedRequests() {
        return List.copyOf(received);
    }

    public void reset() {
        received.clear();
        failNextCall.set(null);
    }

    /**
     * Stage a one-shot failure: the next call to {@link #call(EmbeddingRequest)} throws the
     * given exception and the script clears itself. Subsequent calls succeed normally.
     */
    public void failNextCallWith(RuntimeException exception) {
        failNextCall.set(exception);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        RuntimeException staged = failNextCall.getAndSet(null);
        if (staged != null) {
            throw staged;
        }
        received.add(request);
        List<String> inputs = request.getInstructions();
        List<Embedding> out = new ArrayList<>(inputs.size());
        int totalTokens = 0;
        for (int i = 0; i < inputs.size(); i++) {
            String text = inputs.get(i);
            out.add(new Embedding(deterministicVector(text), i));
            totalTokens += Math.max(1, text.length() / 4);
        }
        EmbeddingResponseMetadata meta = new EmbeddingResponseMetadata(
                "fake-embedding-model",
                new DefaultUsage(totalTokens, 0, totalTokens));
        return new EmbeddingResponse(out, meta);
    }

    @Override
    public float[] embed(Document document) {
        return deterministicVector(document.getText() == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] deterministicVector(String text) {
        float[] v = new float[DIMENSIONS];
        byte[] digest = sha256(text == null ? "" : text);
        for (int i = 0; i < DIMENSIONS; i++) {
            byte b = digest[i % digest.length];
            v[i] = ((b & 0xFF) / 255.0f) * 2.0f - 1.0f;
        }
        double norm = 0.0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            float inv = (float) (1.0 / norm);
            for (int i = 0; i < v.length; i++) v[i] *= inv;
        }
        return v;
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
