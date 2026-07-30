package io.casehub.eidos.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.runtime.registrar.DescriptorCollector;
import io.casehub.eidos.vocab.BelbinTerm;
import io.casehub.eidos.vocab.DiscTerm;
import io.casehub.eidos.vocab.MbtiTypeTerm;

import java.util.List;
import java.util.Map;

final class VocabularyImbueFixtures {

    private VocabularyImbueFixtures() {}

    static AgentDescriptor jungianDescriptor(MbtiTypeTerm type) {
        return AgentDescriptor.builder()
                .agentId("imbue-" + type.value()).name("Test " + type.label())
                .slot("test-agent").tenancyId("imbue-test")
                .dispositionVocabulary("urn:casehub:vocab:jungian")
                .disposition(AgentDisposition.builder()
                        .dispositionProfile(type.defaultProfile())
                        .build())
                .build();
    }

    static AgentDescriptor belbinDescriptor(BelbinTerm role) {
        return AgentDescriptor.builder()
                .agentId("imbue-belbin-" + role.value()).name("Test " + role.label())
                .slot(role.value()).tenancyId("imbue-test")
                .slotVocabulary("urn:casehub:vocab:belbin")
                .disposition(AgentDisposition.builder().build())
                .build();
    }

    static AgentDescriptor discDescriptor(DiscTerm style, VocabularyRegistry vocabRegistry) {
        var raw = AgentDescriptor.builder()
                .agentId("imbue-disc-" + style.value()).name("Test " + style.label())
                .slot("test-agent").tenancyId("imbue-test")
                .dispositionVocabulary("urn:casehub:vocab:disc")
                .disposition(AgentDisposition.builder()
                        .dispositionProfile(List.of(new DispositionValue(style.value(), 1.0)))
                        .build())
                .build();
        return DescriptorCollector.deriveDispositionAxes(raw, vocabRegistry);
    }

    static AgentDescriptor conscientiousnessDescriptor(Map<DispositionAxis, String> axes) {
        var builder = AgentDisposition.builder();
        axes.forEach((axis, value) -> {
            switch (axis) {
                case SOCIAL_ORIENTATION -> builder.socialOrient(value);
                case RULE_FOLLOWING -> builder.ruleFollowing(value);
                case RISK_APPETITE -> builder.riskAppetite(value);
                case AUTONOMY -> builder.autonomy(value);
                case CONFLICT_MODE -> builder.conflictMode(value);
            }
        });
        return AgentDescriptor.builder()
                .agentId("imbue-conscientiousness").name("Test Conscientiousness")
                .slot("test-agent").tenancyId("imbue-test")
                .disposition(builder.build())
                .build();
    }

    static AgentDescriptor composite(AgentDescriptor base, AgentDescriptor overlay) {
        return AgentDescriptor.builder()
                              .agentId(base.agentId() + "+" + overlay.agentId())
                              .name(base.name() + " + " + overlay.name())
                              .slot(overlay.slot() != null ? overlay.slot() : base.slot())
                              .tenancyId(base.tenancyId())
                              .dispositionVocabulary(base.dispositionVocabulary())
                              .slotVocabulary(overlay.slotVocabulary())
                              .briefing(base.briefing())
                              .disposition(base.disposition())
                              .build();
    }
}
