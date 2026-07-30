package io.casehub.eidos.runtime.health;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionEvolution.EvolutionResult;
import io.casehub.eidos.api.DispositionHealth.DispositionStatus;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.api.VocabularyTerm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@QuarkusTest
class DefaultDispositionEvolutionTest {

    @Inject
    DispositionEvolution evolution;

    @Inject
    DispositionSignalStore signalStore;

    @Inject
    VocabularyRegistry vocabRegistry;

    @VocabularyMetadata(uri = "urn:test:evo-functions", name = "Test Evo Functions", version = "1.0")
    enum TestFunction implements VocabularyTerm {
        F1("f1", "Function 1") {
            @Override public Optional<VocabularyTerm> opposite() { return Optional.of(F3); }
        },
        F2("f2", "Function 2") {
            @Override public Optional<VocabularyTerm> opposite() { return Optional.of(F4); }
        },
        F3("f3", "Function 3") {
            @Override public Optional<VocabularyTerm> opposite() { return Optional.of(F1); }
        },
        F4("f4", "Function 4") {
            @Override public Optional<VocabularyTerm> opposite() { return Optional.of(F2); }
        },
        F5("f5", "Function 5"),
        F6("f6", "Function 6");

        final String value, label;
        TestFunction(String v, String l) { value = v; label = l; }
        @Override public String value() { return value; }
        @Override public String label() { return label; }
    }

    static final String VOCAB_URI = "urn:test:evo-functions";

    static List<DispositionValue> testProfile() {
        return List.of(
                new DispositionValue("f1", 0.35),
                new DispositionValue("f2", 0.25),
                new DispositionValue("f3", 0.15),
                new DispositionValue("f4", 0.10),
                new DispositionValue("f5", 0.08),
                new DispositionValue("f6", 0.07));
    }

    AgentDescriptor agentWithProfile(List<DispositionValue> profile) {
        return AgentDescriptor.builder()
                              .agentId("a1").name("Test").slot("test").tenancyId("t1")
                              .dispositionVocabulary(VOCAB_URI)
                              .disposition(AgentDisposition.builder()
                                                           .dispositionProfile(profile)
                                                           .build())
                              .build();
    }

    @BeforeEach
    void setUp() {
        if (!vocabRegistry.isRegistered(VOCAB_URI)) {
            vocabRegistry.register(TestFunction.class);
        }
        signalStore.clear("a1", "t1");
    }

    @Test
    void dominant_auxiliary_swap_produces_evolved_with_swapped_weights() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_AUXILIARY_SWAP,
                "f2",
                Map.of("f1", 0.30, "f2", 0.35, "f3", 0.12, "f4", 0.09, "f5", 0.07, "f6", 0.07));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        assertThat(evolved.newProfile()).isNotEmpty();
        var newDominant = evolved.newProfile().stream()
                .max(java.util.Comparator.comparingDouble(DispositionValue::weight))
                .orElseThrow();
        assertThat(newDominant.term()).isEqualTo("f2");
    }

    @Test
    void dominant_replacement_produces_evolved_with_shadow_as_dominant() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_REPLACEMENT,
                "f3",
                Map.of("f1", 0.20, "f2", 0.18, "f3", 0.36, "f4", 0.10, "f5", 0.08, "f6", 0.08));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        var newDominant = evolved.newProfile().stream()
                .max(java.util.Comparator.comparingDouble(DispositionValue::weight))
                .orElseThrow();
        assertThat(newDominant.term()).isEqualTo("f3");
    }

    @Test
    void auxiliary_replacement_produces_evolved_with_shadow_as_auxiliary() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.AUXILIARY_REPLACEMENT,
                "f4",
                Map.of("f1", 0.33, "f2", 0.15, "f3", 0.14, "f4", 0.20, "f5", 0.10, "f6", 0.08));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        var sorted = evolved.newProfile().stream()
                .sorted(java.util.Comparator.comparingDouble(DispositionValue::weight).reversed())
                .toList();
        assertThat(sorted.get(0).term()).isEqualTo("f1");
        assertThat(sorted.get(1).term()).isEqualTo("f4");
    }

    @Test
    void structural_reorganization_promotes_candidate_to_dominant() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.STRUCTURAL_REORGANIZATION,
                "f5",
                Map.of("f1", 0.18, "f2", 0.16, "f3", 0.10, "f4", 0.08, "f5", 0.40, "f6", 0.08));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        var newDominant = evolved.newProfile().stream()
                .max(java.util.Comparator.comparingDouble(DispositionValue::weight))
                .orElseThrow();
        assertThat(newDominant.term()).isEqualTo("f5");
    }

    @Test
    void evolved_profile_weights_sum_to_one() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_AUXILIARY_SWAP,
                "f2",
                Map.of("f1", 0.30, "f2", 0.35, "f3", 0.12, "f4", 0.09, "f5", 0.07, "f6", 0.07));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        double sum = evolved.newProfile().stream()
                .mapToDouble(DispositionValue::weight).sum();
        assertThat(sum).isCloseTo(1.0, within(0.001));
    }

    @Test
    void evolved_profile_dominant_in_valid_weight_range() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_AUXILIARY_SWAP,
                "f2",
                Map.of("f1", 0.30, "f2", 0.35, "f3", 0.12, "f4", 0.09, "f5", 0.07, "f6", 0.07));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        var maxWeight = evolved.newProfile().stream()
                .mapToDouble(DispositionValue::weight).max().orElse(0);
        assertThat(maxWeight).isBetween(0.31, 1.0);
    }

    @Test
    void evolved_carries_type_labels() {
        var descriptor = agentWithProfile(testProfile());
        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_AUXILIARY_SWAP,
                "f2",
                Map.of("f1", 0.30, "f2", 0.35, "f3", 0.12, "f4", 0.09, "f5", 0.07, "f6", 0.07));

        var result = evolution.evaluate(descriptor, pending);

        assertThat(result).isInstanceOf(EvolutionResult.Evolved.class);
        var evolved = (EvolutionResult.Evolved) result;
        assertThat(evolved.previousTypeLabel()).isNotNull();
        assertThat(evolved.newTypeLabel()).isNotNull();
        assertThat(evolved.previousTypeLabel()).isNotEqualTo(evolved.newTypeLabel());
    }

    @Test
    void signals_unchanged_after_evolution() {
        var descriptor = agentWithProfile(testProfile());
        for (int i = 0; i < 5; i++) {signalStore.recordActivation("a1", "t1", "f2");}

        var pending = new DispositionStatus.EvolutionPending(
                DefaultDispositionHealth.DOMINANT_AUXILIARY_SWAP,
                "f2",
                Map.of("f1", 0.30, "f2", 0.35, "f3", 0.12, "f4", 0.09, "f5", 0.07, "f6", 0.07));

        evolution.evaluate(descriptor, pending);

        var counts  = signalStore.activationCounts("a1", "t1");
        int f2Count = counts.getOrDefault("f2", 0);
        assertThat(f2Count).isEqualTo(5);
    }
}
