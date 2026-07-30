package io.casehub.eidos.runtime.preferences;

import io.casehub.platform.api.preferences.SingleValuePreference;

public record DecayFactorPreference(double value) implements SingleValuePreference {
    public DecayFactorPreference {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0)
            throw new IllegalArgumentException(
                    "disposition.decay-factor must be 0.0–1.0, got: " + value);
    }
    @Override public String toSerializedValue() { return String.valueOf(value); }
}
