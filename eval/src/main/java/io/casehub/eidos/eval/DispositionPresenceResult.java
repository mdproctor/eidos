package io.casehub.eidos.eval;

public record DispositionPresenceResult(
        String termLabel,
        double score,
        String reasoning,
        boolean aligned) {}
