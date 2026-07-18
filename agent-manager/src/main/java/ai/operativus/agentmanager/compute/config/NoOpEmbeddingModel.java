package ai.operativus.agentmanager.compute.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain Responsibility: Default-fallback {@link EmbeddingModel} used in production
 *   when no real provider (OpenAI / Anthropic / Google / etc.) is configured. The
 *   2026-05-27 local dry-run surfaced an architectural blocker — the VectorStore
 *   bean has a hard {@link EmbeddingModel} dependency, and the Google AI embedding
 *   auto-config tries to fetch Application Default Credentials at boot, so without
 *   a real GCP setup the app cannot start at all even when the operator doesn't
 *   want Google.
 * State: Stateless. Every call returns a zero-vector of the configured dimension.
 *
 * <p><strong>Behavior:</strong> returns vectors of zeros so that downstream
 *   {@link org.springframework.ai.vectorstore.VectorStore} operations don't NPE on
 *   the response shape — but any cosine-similarity / dot-product search against
 *   zero-vectors returns essentially noise. <em>RAG retrieval against this model
 *   does not work meaningfully.</em>
 *
 * <p><strong>Operator opt-in to a real embedding provider:</strong> set
 *   {@code spring.ai.model.embedding=openai} (or {@code google}, {@code anthropic},
 *   etc.) in environment / properties, configure the matching provider API key,
 *   and Spring AI will register the real bean which wins the {@code @Primary}
 *   selection in {@code ChatConfig.primaryEmbeddingModel}.
 *
 * <p><strong>Dimension contract:</strong> 768 to match the production pgvector
 *   schema ({@code spring.ai.vectorstore.pgvector.dimension=768}). Real provider
 *   models that emit a different dimension will fail pgvector inserts — that
 *   constraint exists regardless of this fallback.
 */
public final class NoOpEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmbeddingModel.class);

    public static final int DIMENSIONS = 768;
    private static final float[] ZERO_VECTOR = new float[DIMENSIONS];

    private final boolean warnedOnce;

    public NoOpEmbeddingModel() {
        this.warnedOnce = warnOnce();
    }

    private static boolean warnOnce() {
        log.warn("NoOpEmbeddingModel is in use — no real embedding provider is configured. "
                + "Vector search will return noise. Set spring.ai.model.embedding=<provider> "
                + "(openai / google / anthropic) and configure the matching API key to enable "
                + "real RAG retrieval.");
        return true;
    }

    @Override
    public float[] embed(Document document) {
        return ZERO_VECTOR.clone();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int n = request.getInstructions().size();
        for (int i = 0; i < n; i++) {
            embeddings.add(new Embedding(ZERO_VECTOR.clone(), i));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
