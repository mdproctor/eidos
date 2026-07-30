package io.casehub.eidos.runtime.preferences;

import io.casehub.platform.api.preferences.PreferenceKey;

public final class DispositionPreferenceKeys {

    private DispositionPreferenceKeys() {}

    /**
     * Per-activation weight increment applied to disposition function signals.
     * Effective weight = base weight + (activation count × delta).
     * Default: 0.06 (JPAF parameter).
     */
    public static final PreferenceKey<ReinforcementDeltaPreference> REINFORCEMENT_DELTA =
            new PreferenceKey<>("casehub.eidos", "disposition.reinforcement-delta",
                                new ReinforcementDeltaPreference(0.06),
                                s -> new ReinforcementDeltaPreference(Double.parseDouble(s)));

    /**
     * Effective weight ceiling for the dominant function. When the dominant's
     * effective weight reaches or exceeds this threshold, the probe returns
     * Drifted with high magnitude — signaling over-reinforcement.
     * Default: 0.50 (JPAF §2.6).
     */
    public static final PreferenceKey<OverReinforcementThresholdPreference> OVER_REINFORCEMENT_THRESHOLD =
            new PreferenceKey<>("casehub.eidos", "disposition.over-reinforcement-threshold",
                                new OverReinforcementThresholdPreference(0.50),
                                s -> new OverReinforcementThresholdPreference(Double.parseDouble(s)));

    /**
     * Multiplicative retention factor for activation count decay.
     * Semantics: 0.0 = instant reset (retain nothing), 1.0 = no decay (retain everything).
     * Default: 0.20 (JPAF §3.4 — aggressive dampening on reflection rejection).
     */
    public static final PreferenceKey<DecayFactorPreference> DECAY_FACTOR =
            new PreferenceKey<>("casehub.eidos", "disposition.decay-factor",
                                new DecayFactorPreference(0.20),
                                s -> new DecayFactorPreference(Double.parseDouble(s)));
}
