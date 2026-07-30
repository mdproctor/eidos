package io.casehub.eidos.runtime.health;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionHealth.DispositionStatus;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.runtime.preferences.DispositionPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class DefaultDispositionEvolution implements DispositionEvolution {

    static final double DEFAULT_DOMINANT_WEIGHT = 0.35;
    static final double DEFAULT_AUXILIARY_WEIGHT = 0.20;
    private final Instance<PreferenceProvider> preferenceProviderInstance;

    @Inject
    public DefaultDispositionEvolution(final Instance<PreferenceProvider> preferenceProviderInstance) {
        this.preferenceProviderInstance = preferenceProviderInstance;
    }

    @Override
    public EvolutionResult evaluate(final AgentDescriptor descriptor,
                                    final DispositionStatus.EvolutionPending pending) {
        final var profile = descriptor.disposition().dispositionProfile();
        if (profile == null || profile.isEmpty()) {
            return new EvolutionResult.Dampened(decayFactor(descriptor.tenancyId()));
        }

        final var sorted = profile.stream()
                .sorted(Comparator.comparingDouble(DispositionValue::weight).reversed())
                .toList();
        final String previousLabel = typeLabel(sorted);

        final List<DispositionValue> newProfile = switch (pending.type().name()) {
            case "DOMINANT_AUXILIARY_SWAP" -> applySwap(sorted, pending.candidateFunction());
            case "DOMINANT_REPLACEMENT" -> applyReplacement(sorted, pending.candidateFunction(), 0);
            case "AUXILIARY_REPLACEMENT" -> applyReplacement(sorted, pending.candidateFunction(), 1);
            case "STRUCTURAL_REORGANIZATION" -> applyReorganization(sorted, pending.candidateFunction());
            default -> null;
        };

        if (newProfile == null) {
            return new EvolutionResult.Dampened(decayFactor(descriptor.tenancyId()));
        }

        final var normalizedProfile = normalize(newProfile);
        final var newSorted = normalizedProfile.stream()
                .sorted(Comparator.comparingDouble(DispositionValue::weight).reversed())
                .toList();
        final String newLabel = typeLabel(newSorted);

        return new EvolutionResult.Evolved(normalizedProfile, previousLabel, newLabel);
    }

    private double decayFactor(final String tenancyId) {
        if (preferenceProviderInstance.isUnsatisfied()) {
            return DispositionPreferenceKeys.DECAY_FACTOR.defaultValue().value();
        }
        return preferenceProviderInstance.get()
                .resolve(SettingsScope.root(tenancyId))
                .getOrDefault(DispositionPreferenceKeys.DECAY_FACTOR).value();
    }

    private List<DispositionValue> applySwap(final List<DispositionValue> sorted,
                                              final String candidateFunction) {
        if (sorted.size() < 2) {
            return null;
        }
        final var dominant = sorted.get(0);
        final var result = new ArrayList<DispositionValue>();
        for (final var dv : sorted) {
            if (dv.term().equals(candidateFunction)) {
                result.add(new DispositionValue(dv.term(), DEFAULT_DOMINANT_WEIGHT));
            } else if (dv.term().equals(dominant.term())) {
                result.add(new DispositionValue(dv.term(), DEFAULT_AUXILIARY_WEIGHT));
            } else {
                result.add(dv);
            }
        }
        return result;
    }

    private List<DispositionValue> applyReplacement(final List<DispositionValue> sorted,
                                                     final String candidateFunction,
                                                     final int targetIndex) {
        if (sorted.size() <= targetIndex) {
            return null;
        }
        final var    replaced     = sorted.get(targetIndex);
        final double targetWeight = targetIndex == 0 ? DEFAULT_DOMINANT_WEIGHT : DEFAULT_AUXILIARY_WEIGHT;
        double candidateOriginalWeight = sorted.stream()
                                               .filter(dv -> dv.term().equals(candidateFunction))
                                               .findFirst()
                                               .map(DispositionValue::weight)
                                               .orElse(0.05);
        final var result = new ArrayList<DispositionValue>();
        for (final var dv : sorted) {
            if (dv.term().equals(candidateFunction)) {
                result.add(new DispositionValue(dv.term(), targetWeight));
            } else if (dv.term().equals(replaced.term())) {
                result.add(new DispositionValue(dv.term(), candidateOriginalWeight));
            } else {
                result.add(dv);
            }
        }
        return result;
    }

    private List<DispositionValue> applyReorganization(final List<DispositionValue> sorted,
                                                        final String candidateFunction) {
        String newAuxiliary = null;
        if (sorted.size() > 1) {
            newAuxiliary = sorted.stream()
                                 .filter(dv -> !dv.term().equals(candidateFunction))
                                 .max(Comparator.comparingDouble(DispositionValue::weight))
                                 .map(DispositionValue::term)
                                 .orElse(null);
        }

        final var    result = new ArrayList<DispositionValue>();
        final String aux    = newAuxiliary;
        for (final var dv : sorted) {
            if (dv.term().equals(candidateFunction)) {
                result.add(new DispositionValue(dv.term(), DEFAULT_DOMINANT_WEIGHT));
            } else if (aux != null && dv.term().equals(aux)) {
                result.add(new DispositionValue(dv.term(), DEFAULT_AUXILIARY_WEIGHT));
            } else {
                result.add(dv);
            }
        }
        return result;
    }

    private List<DispositionValue> normalize(final List<DispositionValue> profile) {
        final double sum = profile.stream().mapToDouble(DispositionValue::weight).sum();
        if (sum <= 0.0 || Double.isNaN(sum)) {
            return profile;
        }
        final var result = new ArrayList<DispositionValue>();
        double runningSum = 0.0;
        for (int i = 0; i < profile.size(); i++) {
            final var dv = profile.get(i);
            if (i == profile.size() - 1) {
                double lastWeight = Math.max(0.0, 1.0 - runningSum);
                lastWeight = Math.round(lastWeight * 10000.0) / 10000.0;
                result.add(new DispositionValue(dv.term(), Math.min(1.0, lastWeight)));
            } else {
                double w = Math.round((dv.weight() / sum) * 10000.0) / 10000.0;
                runningSum += w;
                result.add(new DispositionValue(dv.term(), w));
            }
        }
        return List.copyOf(result);
    }

    private String typeLabel(final List<DispositionValue> sorted) {
        if (sorted.isEmpty()) {
            return "unknown";
        }
        final var sb = new StringBuilder();
        sb.append(sorted.get(0).term().toUpperCase());
        if (sorted.size() > 1) {
            sb.append('-').append(sorted.get(1).term().toUpperCase());
        }
        return sb.toString();
    }
}
