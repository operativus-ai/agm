package ai.operativus.agentmanager.control.approval;

import ai.operativus.agentmanager.core.model.enums.RoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Domain Responsibility: Enforces the 3-Tier Threshold Logic for Bounded Autonomy.
 * Applies heuristic evaluation based on Whitelist, FinOps limits, and Cryptographic MFA requirement.
 *
 * 2026+ Update (Gap 3): CRYPTO_MFA tier now verifies that the MFA assertion is cryptographically
 * pinned to the specific ToolDecisionId being evaluated — not just a generic session-level claim.
 * This ensures non-repudiation for hazardous agent actions per Verifiable Credential requirements.
 *
 * State: Stateless
 */
@Service
public class DecisionTierLogic {

    private static final Logger log = LoggerFactory.getLogger(DecisionTierLogic.class);

    /** JWT claim key carrying the specific ToolDecisionId the MFA assertion is bound to. */
    private static final String CLAIM_TOOL_DECISION_ID = "tool_decision_id";

    /** JWT claim key for verifiable credential type (DID / FIDO2 / X.509 assertion class). */
    private static final String CLAIM_VC_TYPE = "vc_type";

    /** Accepted verifiable credential types that satisfy non-repudiation requirements. */
    private static final java.util.Set<String> ACCEPTED_VC_TYPES = java.util.Set.of(
        "FIDO2_ASSERTION", "X509_HARDWARE_TOKEN", "DID_VERIFIABLE_CREDENTIAL"
    );

    /**
     * Evaluates a DecisionPackage to verify whether the agent is cleared to proceed autonomously
     * or if a HITL (Human-In-The-Loop) lock must be engaged.
     */
    public boolean evaluateThreshold(DecisionPackage decisionPackage) {
        log.info("Evaluating Decision Package for ToolAction: {} under Tier: {}",
            decisionPackage.toolDecisionId(), decisionPackage.targetTier());

        return switch (decisionPackage.targetTier()) {
            case WHITELIST    -> processWhitelistConstraints(decisionPackage);
            case FINOPS_CHECK -> processFinOpsConstraints(decisionPackage);
            case CRYPTO_MFA   -> processCryptoMfaConstraints(decisionPackage);
        };
    }

    private boolean processWhitelistConstraints(DecisionPackage decisionPackage) {
        var scopes = Optional.ofNullable(decisionPackage.validationCriteria().allowedScopes())
                .orElse(java.util.List.of());

        if (scopes.isEmpty()) return true; // Open access if no strict scope bounds are defined

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isWhitelisted = false;
        if (auth != null && auth.isAuthenticated()) {
            isWhitelisted = auth.getAuthorities().stream()
                .anyMatch(a -> scopes.contains(a.getAuthority().replace("SCOPE_", ""))
                            || scopes.contains(a.getAuthority()));
        }
        log.debug("Whitelist Evaluation against scopes {}: {}", scopes, isWhitelisted);
        return isWhitelisted;
    }

    private boolean processFinOpsConstraints(DecisionPackage decisionPackage) {
        Double threshold = decisionPackage.validationCriteria().costThresholdUsd();
        if (threshold == null) return true; // No cost constraint configured

        Double remainingBudget = 0.0;
        try {
            var ctx = ai.operativus.agentmanager.control.security.AgentContextHolder.getContext();
            if (ctx.remainingBudget() != null) remainingBudget = ctx.remainingBudget();
        } catch (Exception e) {
            log.trace("FinOps Eval skipped — contextual budget lookup unavailable: {}", e.getMessage());
        }

        boolean underBudget = remainingBudget >= threshold;
        log.debug("FinOps Evaluation: Threshold {} vs Remaining {} -> Authorized: {}", threshold, remainingBudget, underBudget);
        return underBudget;
    }

    /**
     * @summary Verifies a cryptographic MFA assertion pinned to the specific ToolDecisionId.
     * @logic
     * 2026+ requirement (Gap 3): Moves beyond generic session-level ROLE_MFA_AUTHENTICATED checks.
     * A hazardous agent action requires a verifiable credential (DID / X.509 / FIDO2) whose
     * JWT claim {@code tool_decision_id} matches the exact ToolDecisionId in the DecisionPackage.
     * This ensures absolute non-repudiation — the human authorized THIS specific action,
     * not just any action during the session.
     *
     * Evaluation sequence:
     * 1. If mfaRequired is false, permit unconditionally.
     * 2. Require ROLE_MFA_AUTHENTICATED or SCOPE_mfa on the authentication principal.
     * 3. If the principal carries a JWT (JwtAuthenticationToken), additionally verify:
     *    a. The JWT's {@code tool_decision_id} claim matches decisionPackage.toolDecisionId().
     *    b. The JWT's {@code vc_type} claim is one of the accepted verifiable credential types.
     * 4. If no JWT is present, fall back to the role check only (legacy compatibility).
     */
    private boolean processCryptoMfaConstraints(DecisionPackage decisionPackage) {
        if (!decisionPackage.validationCriteria().mfaRequired()) return true;

        Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.debug("Crypto MFA Evaluation: No authenticated principal — DENIED.");
            return false;
        }

        boolean hasMfaRole = auth.getAuthorities().stream()
            .anyMatch(a -> RoleType.ROLE_MFA_AUTHENTICATED.getValue().equals(a.getAuthority())
                       || "SCOPE_mfa".equals(a.getAuthority()));

        if (!hasMfaRole) {
            log.debug("Crypto MFA Evaluation: Missing MFA role/scope — DENIED.");
            return false;
        }

        // 2026+ extension: verify the MFA assertion is pinned to this specific ToolDecisionId
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            boolean authorized = verifyToolDecisionClaim(jwt, decisionPackage.toolDecisionId());
            log.debug("Crypto MFA Evaluation (JWT claim pinning to ToolDecisionId={}): {}",
                decisionPackage.toolDecisionId(), authorized);
            return authorized;
        }

        // Legacy fallback: role check satisfied without JWT claim pinning (non-JWT auth providers)
        log.debug("Crypto MFA Evaluation (legacy role check, no JWT claim pinning): AUTHORIZED");
        return true;
    }

    /**
     * @summary Verifies the JWT carries a {@code tool_decision_id} claim matching the target decision
     *          and a {@code vc_type} claim indicating an acceptable verifiable credential assertion.
     * @logic This satisfies the non-repudiation requirement: the credential must be explicitly scoped
     *        to the ToolDecisionId being evaluated, not a generic session-wide MFA cookie.
     */
    private boolean verifyToolDecisionClaim(Jwt jwt, String expectedToolDecisionId) {
        String claimedDecisionId = jwt.getClaimAsString(CLAIM_TOOL_DECISION_ID);
        if (claimedDecisionId == null || !claimedDecisionId.equals(expectedToolDecisionId)) {
            log.warn("JWT tool_decision_id claim [{}] does not match expected [{}] — non-repudiation FAILED.",
                claimedDecisionId, expectedToolDecisionId);
            return false;
        }

        String vcType = jwt.getClaimAsString(CLAIM_VC_TYPE);
        if (vcType == null || !ACCEPTED_VC_TYPES.contains(vcType)) {
            log.warn("JWT vc_type claim [{}] is not an accepted verifiable credential type — DENIED.", vcType);
            return false;
        }

        log.debug("JWT verifiable credential pinned to ToolDecisionId [{}] with vc_type [{}] — AUTHORIZED.",
            expectedToolDecisionId, vcType);
        return true;
    }
}
