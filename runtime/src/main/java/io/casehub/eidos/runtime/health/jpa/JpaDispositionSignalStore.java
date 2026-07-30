package io.casehub.eidos.runtime.health.jpa;

import io.casehub.eidos.api.DispositionSignalStore;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.HashMap;
import java.util.Map;

@IfBuildProperty(name = "casehub.eidos.reactive.enabled", stringValue = "false", enableIfMissing = true)
@ApplicationScoped
public class JpaDispositionSignalStore implements DispositionSignalStore {

    @Inject EntityManager em;

    @Override
    @Transactional
    public void recordActivation(final String agentId, final String tenancyId,
                                  final String functionTerm) {
        final var id = new DispositionSignalId(agentId, tenancyId, functionTerm);
        final var existing = em.find(DispositionSignalEntity.class, id);
        if (existing != null) {
            existing.count++;
        } else {
            em.persist(new DispositionSignalEntity(agentId, tenancyId, functionTerm, 1));
        }
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public Map<String, Integer> activationCounts(final String agentId,
                                                  final String tenancyId) {
        final var results = em.createQuery(
                "SELECT e FROM DispositionSignalEntity e"
                    + " WHERE e.id.agentId = :agentId"
                    + " AND e.id.tenancyId = :tenancyId"
                    + " AND e.count > 0",
                DispositionSignalEntity.class)
            .setParameter("agentId", agentId)
            .setParameter("tenancyId", tenancyId)
            .getResultList();

        final var map = new HashMap<String, Integer>();
        for (final var e : results) {
            map.put(e.id.functionTerm, e.count);
        }
        return Map.copyOf(map);
    }

    @Override
    @Transactional
    public void decay(final String agentId, final String tenancyId,
                      final double decayFactor) {
        final var results = em.createQuery(
                                      "SELECT e FROM DispositionSignalEntity e"
                                      + " WHERE e.id.agentId = :agentId"
                                      + " AND e.id.tenancyId = :tenancyId",
                                      DispositionSignalEntity.class)
                              .setParameter("agentId", agentId)
                              .setParameter("tenancyId", tenancyId)
                              .getResultList();

        for (final var e : results) {
            e.count = (int) (e.count * decayFactor);
        }

        em.createQuery(
                  "DELETE FROM DispositionSignalEntity e"
                  + " WHERE e.id.agentId = :agentId"
                  + " AND e.id.tenancyId = :tenancyId"
                  + " AND e.count <= 0")
          .setParameter("agentId", agentId)
          .setParameter("tenancyId", tenancyId)
          .executeUpdate();}

    @Override
    @Transactional
    public void clear(final String agentId, final String tenancyId) {
        em.createQuery(
                "DELETE FROM DispositionSignalEntity e"
                    + " WHERE e.id.agentId = :agentId"
                    + " AND e.id.tenancyId = :tenancyId")
            .setParameter("agentId", agentId)
            .setParameter("tenancyId", tenancyId)
            .executeUpdate();
    }
}
