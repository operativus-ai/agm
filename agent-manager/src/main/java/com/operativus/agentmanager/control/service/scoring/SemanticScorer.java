package com.operativus.agentmanager.control.service.scoring;

import com.operativus.agentmanager.core.entity.EvaluationCase;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Domain Responsibility: Evaluates Agent outputs using Vector Embeddings to compute semantic cosine similarity against expected criteria.
 * State: Stateless
 * Dependencies: EmbeddingModel
 */
@Component
public class SemanticScorer implements Scorer {

    private final EmbeddingModel embeddingModel;
    private final double passingThreshold;

    public SemanticScorer(EmbeddingModel embeddingModel,
                          @org.springframework.beans.factory.annotation.Value("${agentmanager.scoring.semantic-passing-threshold:0.85}") double passingThreshold) {
        this.embeddingModel = embeddingModel;
        this.passingThreshold = passingThreshold;
    }

    /**
     * @summary Scores an evaluation case by comparing the semantic meaning of the actual output and the expected criteria.
     * @logic
     * - Validates that both expected and actual outputs are non-empty.
     * - Calls the Spring AI EmbeddingModel to generate dense vector representations of both strings.
     * - Computes the cosine similarity between the two vectors.
     * - Assigns a passing status if similarity exceeds the configured passingThreshold (0.85).
     */
    @Override
    public ScorerResult evaluate(EvaluationCase evaluationCase, String actualOutput) {
        if (evaluationCase.getExpectedOutput() == null || evaluationCase.getExpectedOutput().trim().isEmpty()) {
            return new ScorerResult(0.0, false, "Expected output is empty; cannot compute semantic similarity.");
        }
        if (actualOutput == null || actualOutput.trim().isEmpty()) {
            return new ScorerResult(0.0, false, "Actual output is empty.");
        }

        try {
            // Embed both the expected and actual strings
            EmbeddingResponse expectedEmbeddingResp = embeddingModel.embedForResponse(List.of(evaluationCase.getExpectedOutput()));
            EmbeddingResponse actualEmbeddingResp = embeddingModel.embedForResponse(List.of(actualOutput));

            float[] expectedVector = expectedEmbeddingResp.getResult().getOutput();
            float[] actualVector = actualEmbeddingResp.getResult().getOutput();

            // Calculate Cosine Similarity
            double similarity = cosineSimilarity(expectedVector, actualVector);
            boolean passed = similarity >= passingThreshold;
            
            String reasoning = String.format("Calculated semantic similarity: %.3f. Threshold: %.2f.", similarity, passingThreshold);

            return new ScorerResult(similarity, passed, reasoning);

        } catch (Exception e) {
            return new ScorerResult(0.0, false, "Failed to compute semantic similarity: " + e.getMessage());
        }
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
