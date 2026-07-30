package io.casehub.eidos.runtime.registrar;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.TemplateRegistry;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.api.spi.AgentDescriptorRegistrar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class DescriptorCollector {

    private DescriptorCollector() {}

    static List<AgentDescriptor> collectAndValidate(Iterable<AgentDescriptorRegistrar> registrars,
                                                    TemplateRegistry templateRegistry,
                                                    VocabularyRegistry vocabRegistry) {
        var all = new ArrayList<AgentDescriptor>();
        registrars.forEach(r -> all.addAll(r.descriptors()));

        var result = new ArrayList<AgentDescriptor>();
        var seen   = new HashSet<String>();
        for (var d : all) {
            var key = d.agentId() + "\0" + d.tenancyId();
            if (!seen.add(key)) {
                throw new IllegalStateException(
                        "Duplicate descriptor: agentId=" + d.agentId()
                        + ", tenancyId=" + d.tenancyId());
            }

            if (d.templates() != null) {
                for (var ref : d.templates()) {
                    var template = templateRegistry.resolve(ref.templateId())
                                                   .orElseThrow(() -> new IllegalStateException(
                                                           "Descriptor '" + d.agentId() + "' references unknown template: " + ref.templateId()));
                    var declared = Set.copyOf(template.parameters());
                    var provided = ref.args().keySet();
                    var missing  = new TreeSet<>(declared);
                    missing.removeAll(provided);
                    if (!missing.isEmpty()) {
                        throw new IllegalStateException("Descriptor '" + d.agentId()
                                                        + "', template '" + ref.templateId() + "': missing args " + missing);
                    }
                    var extra = new TreeSet<>(provided);
                    extra.removeAll(declared);
                    if (!extra.isEmpty()) {
                        throw new IllegalStateException("Descriptor '" + d.agentId()
                                                        + "', template '" + ref.templateId() + "': unexpected args " + extra);
                    }
                }
            }

            result.add(deriveDispositionAxes(d, vocabRegistry));
        }
        return List.copyOf(result);
    }

    public static AgentDescriptor deriveDispositionAxes(AgentDescriptor descriptor,
                                                        VocabularyRegistry vocabRegistry) {
        if (descriptor.disposition() == null) {return descriptor;}
        var profile = descriptor.disposition().dispositionProfile();
        if (profile == null || profile.isEmpty()) {return descriptor;}

        String vocabUri = descriptor.dispositionVocabulary();
        if (vocabUri == null || vocabUri.isBlank()) {
            vocabUri = descriptor.domainVocabulary();
        }
        if (vocabUri == null || vocabUri.isBlank()) {return descriptor;}
        if (!vocabRegistry.isRegistered(vocabUri)) {return descriptor;}

        var targetUris     = vocabRegistry.registeredUris();
        var axisAggregates = new java.util.EnumMap<DispositionAxis, java.util.LinkedHashMap<String, AxisContribution>>(DispositionAxis.class);
        for (var axis : DispositionAxis.values()) {
            axisAggregates.put(axis, new java.util.LinkedHashMap<>());
        }

        for (var dv : profile) {
            for (var axis : DispositionAxis.values()) {
                for (var toUri : targetUris) {
                    if (toUri.equals(vocabUri)) {continue;}
                    var mapped = vocabRegistry.equivalentValues(vocabUri, dv.term(), toUri, axis);
                    mapped.ifPresent(targetTerm -> {
                        var agg = axisAggregates.get(axis);
                        agg.computeIfAbsent(targetTerm, k -> new AxisContribution(toUri))
                           .addWeight(dv.weight());
                    });
                }
            }
        }

        var disposition = descriptor.disposition();
        var builder = AgentDisposition.builder()
                                      .delegation(disposition.delegation())
                                      .dispositionProfile(disposition.dispositionProfile());

        var axisVocabularies = new java.util.EnumMap<DispositionAxis, String>(DispositionAxis.class);
        if (descriptor.axisVocabularies() != null) {
            axisVocabularies.putAll(descriptor.axisVocabularies());
        }

        for (var axis : DispositionAxis.values()) {
            var existing = disposition.get(axis);
            if (!existing.isEmpty()) {
                setAxisOnBuilder(builder, axis, existing);
                continue;
            }
            var agg = axisAggregates.get(axis);
            if (agg.isEmpty()) {continue;}

            double totalWeight = agg.values().stream().mapToDouble(c -> c.weight).sum();
            var derived = agg.entrySet().stream()
                             .map(e -> new DispositionValue(e.getKey(), e.getValue().weight / totalWeight))
                             .sorted(java.util.Comparator.comparingDouble(DispositionValue::weight).reversed())
                             .toList();
            setAxisOnBuilder(builder, axis, derived);
            if (!axisVocabularies.containsKey(axis)) {
                axisVocabularies.put(axis, agg.values().iterator().next().vocabUri);
            }
        }

        return AgentDescriptor.builder()
                              .agentId(descriptor.agentId()).name(descriptor.name())
                              .version(descriptor.version()).provider(descriptor.provider())
                              .modelFamily(descriptor.modelFamily()).modelVersion(descriptor.modelVersion())
                              .weightsFingerprint(descriptor.weightsFingerprint())
                              .slot(descriptor.slot()).jurisdiction(descriptor.jurisdiction())
                              .dataHandlingPolicy(descriptor.dataHandlingPolicy())
                              .briefing(descriptor.briefing())
                              .domainVocabulary(descriptor.domainVocabulary())
                              .slotVocabulary(descriptor.slotVocabulary())
                              .dispositionVocabulary(descriptor.dispositionVocabulary())
                              .axisVocabularies(axisVocabularies.isEmpty() ? null : new java.util.HashMap<>(axisVocabularies))
                              .capabilities(descriptor.capabilities())
                              .goals(descriptor.goals())
                              .constraints(descriptor.constraints())
                              .templates(descriptor.templates())
                              .disposition(builder.build())
                              .tenancyId(descriptor.tenancyId())
                              .build();
    }

    private static void setAxisOnBuilder(AgentDisposition.Builder builder,
                                         DispositionAxis axis,
                                         java.util.List<DispositionValue> values) {
        switch (axis) {
            case SOCIAL_ORIENTATION -> builder.socialOrient(values);
            case RULE_FOLLOWING -> builder.ruleFollowing(values);
            case RISK_APPETITE -> builder.riskAppetite(values);
            case AUTONOMY -> builder.autonomy(values);
            case CONFLICT_MODE -> builder.conflictMode(values);
        }
    }

    private static final class AxisContribution {
        final String vocabUri;
        double weight;

        AxisContribution(String vocabUri) {this.vocabUri = vocabUri;}

        void addWeight(double w)          {weight += w;}
    }

}
