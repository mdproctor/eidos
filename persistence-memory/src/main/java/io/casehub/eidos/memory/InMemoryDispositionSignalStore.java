package io.casehub.eidos.memory;

import io.casehub.eidos.api.DispositionSignalStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryDispositionSignalStore implements DispositionSignalStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>> store =
            new ConcurrentHashMap<>();

    @Override
    public void recordActivation(final String agentId, final String tenancyId,
                                  final String functionTerm) {
        store.computeIfAbsent(key(agentId, tenancyId), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(functionTerm, k -> new AtomicInteger())
                .incrementAndGet();
    }

    @Override
    public Map<String, Integer> activationCounts(final String agentId,
                                                  final String tenancyId) {
        final var functions = store.get(key(agentId, tenancyId));
        if (functions == null) return Map.of();
        final var result = new HashMap<String, Integer>();
        functions.forEach((term, count) -> {
            final int v = count.get();
            if (v > 0) result.put(term, v);
        });
        return Map.copyOf(result);
    }

    @Override
    public void decay(final String agentId, final String tenancyId,
                      final double decayFactor) {
        final var functions = store.get(key(agentId, tenancyId));
        if (functions == null) {return;}
        functions.forEach((term, count) -> {
            final int newCount = (int) (count.get() * decayFactor);
            count.set(newCount);
        });
        functions.entrySet().removeIf(e -> e.getValue().get() <= 0);}

    @Override
    public void clear(final String agentId, final String tenancyId) {
        store.remove(key(agentId, tenancyId));
    }

    private static String key(final String agentId, final String tenancyId) {
        return agentId + ":" + tenancyId;
    }
}
