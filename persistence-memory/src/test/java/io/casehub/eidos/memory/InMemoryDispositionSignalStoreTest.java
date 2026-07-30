package io.casehub.eidos.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDispositionSignalStoreTest {

    InMemoryDispositionSignalStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDispositionSignalStore();
    }

    @Test
    void recordActivation_increments_count() {
        store.recordActivation("agent-1", "tenant-1", "ti");
        store.recordActivation("agent-1", "tenant-1", "ti");
        var counts = store.activationCounts("agent-1", "tenant-1");
        assertThat(counts).containsEntry("ti", 2);
    }

    @Test
    void recordActivation_multiple_functions() {
        store.recordActivation("a", "t", "ti");
        store.recordActivation("a", "t", "ne");
        store.recordActivation("a", "t", "ne");
        var counts = store.activationCounts("a", "t");
        assertThat(counts).containsEntry("ti", 1).containsEntry("ne", 2);
    }

    @Test
    void activationCounts_empty_when_no_signals() {
        assertThat(store.activationCounts("a", "t")).isEmpty();
    }

    @Test
    void decay_retains_fraction_of_counts() {
        for (int i = 0; i < 10; i++) {store.recordActivation("a", "t", "ti");}
        store.decay("a", "t", 0.20);
        // 0.20 retention: 10 * 0.2 = 2
        assertThat(store.activationCounts("a", "t")).containsEntry("ti", 2);
    }

    @Test
    void decay_single_activation_drops_to_zero() {
        store.recordActivation("a", "t", "ne");
        store.decay("a", "t", 0.20);
        // 1 * 0.2 = 0.2 → truncated to 0
        assertThat(store.activationCounts("a", "t").getOrDefault("ne", 0)).isEqualTo(0);
    }

    @Test
    void decay_retains_fraction_of_higher_counts() {
        for (int i = 0; i < 100; i++) {store.recordActivation("a", "t", "fi");}
        store.decay("a", "t", 0.20);
        // 0.20 retention: 100 * 0.2 = 20
        assertThat(store.activationCounts("a", "t")).containsEntry("fi", 20);
    }

    @Test
    void decay_truncates_to_integer() {
        for (int i = 0; i < 3; i++) {store.recordActivation("a", "t", "se");}
        store.decay("a", "t", 0.70);
        // 3 * 0.7 = 2.1 → truncated to 2
        assertThat(store.activationCounts("a", "t")).containsEntry("se", 2);
    }

    @Test
    void decay_zero_factor_clears_all() {
        for (int i = 0; i < 5; i++) {store.recordActivation("a", "t", "ti");}
        store.decay("a", "t", 0.0);
        // 0.0 retention = instant reset
        assertThat(store.activationCounts("a", "t").getOrDefault("ti", 0)).isEqualTo(0);
    }

    @Test
    void decay_full_factor_retains_all() {
        for (int i = 0; i < 5; i++) {store.recordActivation("a", "t", "ti");}
        store.decay("a", "t", 1.0);
        // 1.0 retention = no decay
        assertThat(store.activationCounts("a", "t")).containsEntry("ti", 5);
    }

    @Test
    void decay_removes_zero_count_entries() {
        store.recordActivation("a", "t", "ne");
        store.decay("a", "t", 0.20);
        assertThat(store.activationCounts("a", "t")).doesNotContainKey("ne");
    }

    @Test
    void clear_removes_all() {
        store.recordActivation("a", "t", "ti");
        store.recordActivation("a", "t", "ne");
        store.clear("a", "t");
        assertThat(store.activationCounts("a", "t")).isEmpty();
    }

    @Test
    void tenancy_isolation() {
        store.recordActivation("a", "t1", "ti");
        store.recordActivation("a", "t2", "fe");
        assertThat(store.activationCounts("a", "t1")).containsOnlyKeys("ti");
        assertThat(store.activationCounts("a", "t2")).containsOnlyKeys("fe");
    }

    @Test
    void agent_isolation() {
        store.recordActivation("a1", "t", "ti");
        store.recordActivation("a2", "t", "fe");
        assertThat(store.activationCounts("a1", "t")).containsOnlyKeys("ti");
        assertThat(store.activationCounts("a2", "t")).containsOnlyKeys("fe");
    }

    @Test
    void clear_does_not_affect_other_agents() {
        store.recordActivation("a1", "t", "ti");
        store.recordActivation("a2", "t", "fe");
        store.clear("a1", "t");
        assertThat(store.activationCounts("a1", "t")).isEmpty();
        assertThat(store.activationCounts("a2", "t")).containsEntry("fe", 1);
    }

    @Test
    void decay_does_not_affect_other_agents() {
        for (int i = 0; i < 10; i++) {store.recordActivation("a1", "t", "ti");}
        for (int i = 0; i < 10; i++) {store.recordActivation("a2", "t", "ti");}
        store.decay("a1", "t", 0.50);
        // 0.50 retention: 10 * 0.5 = 5
        assertThat(store.activationCounts("a1", "t")).containsEntry("ti", 5);
        assertThat(store.activationCounts("a2", "t")).containsEntry("ti", 10);
    }

    @Test
    void concurrent_recordActivation_is_thread_safe() throws Exception {
        final int threads = 20;
        final Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> store.recordActivation("a", "t", "ti"));
        }
        for (final Thread t : ts) t.start();
        for (final Thread t : ts) t.join();
        assertThat(store.activationCounts("a", "t")).containsEntry("ti", threads);
    }

    @Test
    void activationCounts_returns_immutable_map() {
        store.recordActivation("a", "t", "ti");
        var counts = store.activationCounts("a", "t");
        assertThat(counts).isUnmodifiable();
    }
}
