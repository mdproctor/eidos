package io.casehub.eidos.api;

import java.util.Map;

/**
 * Persistent store for cognitive function activation signals used by JPAF personality adaptation.
 *
 * <p>Activation counts drive effective weight computation:
 * {@code effectiveWeight(f) = baseWeight(f) + activationCount(f) × Δw}.
 */
public interface DispositionSignalStore {

    void recordActivation(String agentId, String tenancyId, String functionTerm);

    Map<String, Integer> activationCounts(String agentId, String tenancyId);

    /**
     * Multiplicative decay of all activation counts for an agent.
     *
     * <p>{@code decayFactor} is the <em>retention fraction</em>: each count is multiplied
     * by this value. Semantics: 0.0 = instant reset (retain nothing),
     * 1.0 = no decay (retain everything). JPAF default is 0.2 (retain 20%).
     */
    void decay(String agentId, String tenancyId, double decayFactor);

    void clear(String agentId, String tenancyId);
}
