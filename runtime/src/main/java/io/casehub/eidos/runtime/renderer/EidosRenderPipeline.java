package io.casehub.eidos.runtime.renderer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentConstraint;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.DispositionValue;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.SystemPromptRenderer.RenderedPrompt;
import io.casehub.eidos.api.TemplateRegistry;
import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.api.VocabularyTerm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
class EidosRenderPipeline {


    // PROMPT_TEMPLATE must be declared before TEMPLATE_HASH — static initializers run
    // in declaration order. Reversing them causes fingerprint(null) at class load:
    // NullPointerException wrapped in ExceptionInInitializerError, not a quiet wrong value.
    static final String PROMPT_TEMPLATE = """
            You are writing disposition and goal narratives for an AI agent's system prompt.

            Given the agent context in JSON, produce a JSON object with prose for two fields.
            Write in second person, addressing the agent directly.

            The payload may contain:
            - name: the agent's name
            - slot: the agent's role type
            - slotLabel, slotDescription, slotVocabularyName: vocabulary-resolved role context (when present)
            - disposition: an object with one key per axis, each having a "value" field and optionally \
            "label", "vocabularyName"
            - goal: the current task (when present)
            - briefing: additional behavioral principles not expressible as structured axes (when present)
            - templates: shared behavioral conventions — genre/style guides that frame \
            the agent's personality (when present)

            FIELDS:
            - dispositionNarrative (2-4 sentences): Use name and slot to frame the narrative for this \
            agent's specific role. Cover ALL disposition axes present in the payload — omitting any \
            present axis is incorrect. Use vocabulary framework language when vocabularyName is present \
            on an axis. Weave briefing principles and template conventions naturally when present — do not quote verbatim. \
            Empty string if no disposition object is in the payload.
            - goalNarrative (1-3 sentences): The agent's current task and objectives in flowing prose. \
            Sub-goals as natural continuation, not bullets. Empty string if no goal is present.

            RULES:
            - Second person only: "You are...", "Your role is...", "You have...".
            - Plain prose. No markdown, no bullet points, no headers.
            - Be concise. Every sentence must carry information the agent needs to act on.
            - Return ONLY the JSON object. No explanation, no preamble, no code fences.""";
    static final String A2A_PROMPT_TEMPLATE = """
            You are writing per-capability descriptions for an AI agent's A2A (agent-to-agent) card.

            Given the agent's capabilities in JSON, produce a JSON object with one prose description
            per declared capability. Write in second person, addressing the agent directly.

            RULES:
            - Copy the capability name exactly as given — do not paraphrase or change capitalisation.
            - Each description is 1-2 sentences. Second person ("You can...").
            - Plain prose. No markdown, no bullet points.
            - Return ONLY the JSON object. No explanation, no preamble, no code fences.
            - If no capabilities are declared, return {"capabilityNarratives": []}.""";
    // Schema descriptions extracted as constants so TEMPLATE_HASH can include them.
    // Changing any description changes the LLM output contract — cache must invalidate.
    static final List<String> RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS = List.of(
            "How the agent operates — role-specific, covering all declared disposition axes. " +
            "Use vocabulary framework language when present. 2-4 sentences. Empty string if no disposition.",
            "Current task and objectives in flowing prose. Empty string if no goal."
    );
    static final List<String> A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS = List.of(
            "One entry per declared capability. Empty array [] if none.",
            "Capability name — must match exactly as given.",
            "1-2 sentences, second person, what this agent can do with this capability."
    );
    static final ResponseFormat RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("SemanticEnrichment")
                    .rootElement(JsonObjectSchema.builder()
                            .addStringProperty("dispositionNarrative",
                                    RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS.get(0))
                            .addStringProperty("goalNarrative",
                                    RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS.get(1))
                            .required("dispositionNarrative", "goalNarrative")
                            .build())
                    .build())
            .build();
    static final ResponseFormat A2A_RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("A2AEnrichment")
                    .rootElement(JsonObjectSchema.builder()
                            .addProperty("capabilityNarratives", JsonArraySchema.builder()
                                    .description(A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS.get(0))
                                    .items(JsonObjectSchema.builder()
                                            .addStringProperty("name",
                                                    A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS.get(1))
                                            .addStringProperty("description",
                                                    A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS.get(2))
                                            .required("name", "description")
                                            .build())
                                    .build())
                            .required("capabilityNarratives")
                            .build())
                    .build())
            .build();
    static final int STREAMING_TIMEOUT_SECONDS = 30;
    private static final String JUNGIAN_VOCAB_URI = "urn:casehub:vocab:jungian";
    private static final String TEMPLATE_HASH = fingerprint(
            PROMPT_TEMPLATE + A2A_PROMPT_TEMPLATE
            + String.join("", RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS)
            + String.join("", A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS)
    ).substring(0, 8);
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private final VocabularyRegistry vocab;
    private final TemplateRegistry templateRegistry;
    private final ObjectMapper mapper;

    // ── Stage 1: payload building ─────────────────────────────────────────────

    @Inject
    EidosRenderPipeline(final VocabularyRegistry vocab,
                        final TemplateRegistry templateRegistry,
                        final ObjectMapper mapper) {
        this.vocab            = vocab;
        this.templateRegistry = templateRegistry;
        this.mapper           = mapper;
    }

    private static void copyIfPresent(final ObjectNode dest, final ObjectNode src, final String key) {
        if (src != null && src.has(key)) dest.set(key, src.get(key).deepCopy());
    }

    static boolean usesEnrichment(final RenderFormat format) {
        return switch (format) {
            case MARKDOWN, PROSE -> true;
            case A2A_CARD        -> false;
        };
    }

    static String substitute(String content, Map<String, String> args) {
        if (args == null || args.isEmpty()) {return content;}
        return TEMPLATE_PLACEHOLDER.matcher(content).replaceAll(match -> {
            var param = match.group(1);
            var value = args.get(param);
            return value != null ? Matcher.quoteReplacement(value) : match.group();
        });
    }

    // ── Stage 1: build + fingerprint ─────────────────────────────────────────

    private static String combinedModel(final AgentDescriptor descriptor) {
        if (descriptor.modelFamily() != null && descriptor.modelVersion() != null)
            return descriptor.modelFamily() + "/" + descriptor.modelVersion();
        if (descriptor.modelFamily() != null) return descriptor.modelFamily();
        return descriptor.modelVersion();
    }

    // ── Cache utilities ──────────────────────────────────────────────────────

    private static void addIfPresent(final ObjectNode node, final String key, final String value) {
        if (value != null) node.put(key, value);
    }

    // ── Stage 2 predicate ────────────────────────────────────────────────────

    private static void addIfNonBlank(final ObjectNode node, final String key, final String value) {
        if (value != null && !value.isEmpty()) node.put(key, value);
    }

    // ── Stage 3: format assembly ──────────────────────────────────────────────

    private static String axisJsonKey(final DispositionAxis axis) {
        return switch (axis) {
            case SOCIAL_ORIENTATION -> "socialOrient";
            case RULE_FOLLOWING     -> "ruleFollowing";
            case RISK_APPETITE      -> "riskAppetite";
            case AUTONOMY           -> "autonomy";
            case CONFLICT_MODE      -> "conflictMode";
        };
    }

    // ── Format-specific assembly ─────────────────────────────────────────────

    private static String axisLabel(final DispositionAxis axis) {
        return switch (axis) {
            case SOCIAL_ORIENTATION -> "Social orientation";
            case RULE_FOLLOWING     -> "Rule following";
            case RISK_APPETITE      -> "Risk appetite";
            case AUTONOMY           -> "Autonomy";
            case CONFLICT_MODE      -> "Conflict mode";
        };
    }

    /**
     * Returns a 16-char hex prefix of the SHA-256 hash of {@code input}.
     * 16 hex chars = 64 bits. Birthday-bound collision probability is negligible
     * for the number of descriptors and contexts in a single deployment.
     * Not a full SHA-256 hash — use only for cache keys and display fingerprints,
     * not for security-sensitive purposes.
     */
    static String fingerprint(final String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }


    private static String perceivingFunction(final String dominant, final String auxiliary) {
        final String domLower = dominant.toLowerCase();
        final String auxLower = auxiliary.toLowerCase();
        if (domLower.startsWith("s") || domLower.startsWith("n")) {return domLower;}
        if (auxLower.startsWith("s") || auxLower.startsWith("n")) {return auxLower;}
        return null;
    }

    private static String cognitiveCoreName(final String functionTerm) {
        return switch (functionTerm.substring(0, 1).toLowerCase()) {
            case "t" -> "analytical";
            case "f" -> "values-driven";
            case "s" -> "experiential";
            case "n" -> "intuitive";
            default -> "cognitive";
        };
    }

    private static String capitalizeAbbrev(final String term) {
        if (term == null || term.isEmpty()) {return term;}
        return Character.toUpperCase(term.charAt(0)) + term.substring(1).toLowerCase();
    }

    private static String formatWeight(final double weight) {
        var bd = BigDecimal.valueOf(weight);
        if (bd.scale() > 2) {
            bd = bd.setScale(2, RoundingMode.HALF_UP);
        }
        return bd.stripTrailingZeros().toPlainString();
    }

    ObjectNode buildDescriptorPayload(final AgentDescriptor descriptor, final RenderFormat format) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("agentId", descriptor.agentId());
        node.put("name", descriptor.name());
        addIfPresent(node, "version", descriptor.version());
        addIfPresent(node, "provider", descriptor.provider());

        // model: combined form
        final String model = combinedModel(descriptor);
        if (model != null) node.put("model", model);

        addIfPresent(node, "weightsFingerprint", descriptor.weightsFingerprint());
        node.put("slot", descriptor.slot());

        // Vocabulary-resolved slot labels and vocabulary context — uses vocabUriForSlot()
        // so domainVocabulary is honoured as a fallback when slotVocabulary is absent.
        descriptor.vocabUriForSlot().ifPresent(uri -> {
            vocab.resolve(uri, descriptor.slot()).ifPresent(term -> {
                addIfNonBlank(node, "slotLabel",       term.label());
                addIfNonBlank(node, "slotDescription", term.description());
            });
            vocab.vocabularyMetadata(uri).ifPresent(meta -> {
                addIfNonBlank(node, "slotVocabularyName",        meta.name());
                addIfNonBlank(node, "slotVocabularyDescription", meta.description());
            });
        });

        // Capabilities — format-discriminated for the LLM payload and cache key.
        // Numeric routing signals (A2A_CARD only): qualityHint, latencyHintP50Ms, costHint,
        // epistemicDomains. These are engine dispatch signals, not behavioural instructions.
        // inputTypes/outputTypes are qualitative descriptors included in all formats.
        // Excluded always: tags (internal routing labels).
        if (descriptor.capabilities() != null && !descriptor.capabilities().isEmpty()) {
            final ArrayNode capsArray = node.putArray("capabilities");
            for (final AgentCapability cap : descriptor.capabilities()) {
                final ObjectNode capNode = capsArray.addObject();
                capNode.put("name", cap.name());
                if (cap.description() != null) capNode.put("description", cap.description());
                if (format == RenderFormat.A2A_CARD) {
                    if (cap.qualityHint() != null)      capNode.put("qualityHint", cap.qualityHint());
                    if (cap.latencyHintP50Ms() != null) capNode.put("latencyHintP50Ms", cap.latencyHintP50Ms());
                    if (cap.costHint() != null)         capNode.put("costHint", cap.costHint());
                    if (cap.epistemicDomains() != null && !cap.epistemicDomains().isEmpty()) {
                        final ObjectNode domains = capNode.putObject("epistemicDomains");
                        cap.epistemicDomains().forEach(domains::put);
                    }
                }
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty()) {
                    final ArrayNode arr = capNode.putArray("inputTypes");
                    cap.inputTypes().forEach(arr::add);
                }
                if (cap.outputTypes() != null && !cap.outputTypes().isEmpty()) {
                    final ArrayNode arr = capNode.putArray("outputTypes");
                    cap.outputTypes().forEach(arr::add);
                }
            }
        }

        // Disposition — per-axis objects with values array + vocabulary context
        if (descriptor.disposition() != null) {
            final AgentDisposition d = descriptor.disposition();
            final ObjectNode dispNode = node.putObject("disposition");
            for (DispositionAxis axis : DispositionAxis.values()) {
                var axisValues = d.get(axis);
                if (!axisValues.isEmpty()) {
                    final ObjectNode axisNode = dispNode.putObject(axisJsonKey(axis));
                    final ArrayNode valuesArray = axisNode.putArray("values");
                    for (final DispositionValue dv : axisValues) {
                        final ObjectNode valNode = valuesArray.addObject();
                        valNode.put("term", dv.term());
                        valNode.put("weight", dv.weight());
                        descriptor.vocabUriForAxis(axis).ifPresent(uri ->
                            vocab.resolve(uri, dv.term()).ifPresent(term -> {
                                addIfNonBlank(valNode, "label", term.label());
                                addIfNonBlank(valNode, "description", term.description());
                            }));
                    }
                    descriptor.vocabUriForAxis(axis).ifPresent(uri ->
                        vocab.vocabularyMetadata(uri).ifPresent(meta -> {
                            addIfNonBlank(axisNode, "vocabularyName", meta.name());
                            addIfNonBlank(axisNode, "vocabularyDescription", meta.description());
                        }));
                }
            }
            dispNode.put("canDelegate", d.delegation());
            if (!d.dispositionProfile().isEmpty()) {
                final ArrayNode profileArray = dispNode.putArray("dispositionProfile");
                for (final DispositionValue dv : d.dispositionProfile()) {
                    final ObjectNode pNode = profileArray.addObject();
                    pNode.put("term", dv.term());
                    pNode.put("weight", dv.weight());
                }
                if (JUNGIAN_VOCAB_URI.equals(descriptor.dispositionVocabulary())) {
                    final var profileSorted = d.dispositionProfile().stream().sorted(Comparator.comparingDouble(DispositionValue::weight).reversed()).toList();
                    if (profileSorted.size() >= 2) {
                        final String percFunc = perceivingFunction(profileSorted.get(0).term(), profileSorted.get(1).term());
                        if (percFunc != null && percFunc.startsWith("n")) {
                            dispNode.put("perceptionStyle", "intuitive — explores underlying patterns, big picture implications, and novel possibilities over concrete details and established methods");
                        } else if (percFunc != null && percFunc.startsWith("s")) {
                            dispNode.put("perceptionStyle", "concrete — focuses on specific, tangible data and immediate practical realities over abstract patterns; prefers proven approaches with track records");
                        }
                    }
                }
            }
        }

        addIfPresent(node, "jurisdiction",       descriptor.jurisdiction());
        addIfPresent(node, "dataHandlingPolicy", descriptor.dataHandlingPolicy());
        addIfPresent(node, "briefing",           descriptor.briefing());

        String resolvedTemplates = resolveTemplates(descriptor);
        if (resolvedTemplates != null) {
            node.put("templates", resolvedTemplates);
        }

        if (!descriptor.goals().isEmpty()) {
            final List<AgentGoal> goalsToInclude = format == RenderFormat.A2A_CARD
                ? descriptor.publicGoals() : descriptor.goals();
            if (!goalsToInclude.isEmpty()) {
                final ArrayNode goalsArray = node.putArray("goals");
                for (final AgentGoal goal : goalsToInclude) {
                    final ObjectNode goalNode = goalsArray.addObject();
                    goalNode.put("name", goal.name());
                    goalNode.put("description", goal.description());
                    goalNode.put("priority", goal.priority().name());
                    goalNode.put("visibility", goal.visibility().name());
                }
            }
        }

        if (!descriptor.constraints().isEmpty()) {
            final List<AgentConstraint> constraintsToInclude = format == RenderFormat.A2A_CARD
                ? descriptor.publicConstraints() : descriptor.constraints();
            if (!constraintsToInclude.isEmpty()) {
                final ArrayNode constraintsArray = node.putArray("constraints");
                for (final AgentConstraint c : constraintsToInclude) {
                    final ObjectNode cNode = constraintsArray.addObject();
                    cNode.put("name", c.name());
                    cNode.put("description", c.description());
                    cNode.put("visibility", c.visibility().name());
                    cNode.put("severity", c.severity().name());
                }
            }
        }

        return node;
    }

    ObjectNode buildContextPayload(final AgentPromptContext context) {
        final ObjectNode node = mapper.createObjectNode();
        context.goal().ifPresent(goal -> {
            final ObjectNode goalNode = node.putObject("goal");
            goalNode.put("description", goal.description());
            if (!goal.subGoals().isEmpty()) {
                final ArrayNode subGoals = goalNode.putArray("subGoals");
                goal.subGoals().forEach(subGoals::add);
            }
            addIfPresent(goalNode, "caseRef", goal.caseRef());
        });
        // situationalContext and resources affect rendered output, so they must be
        // part of the context hash to ensure cache correctness.
        if (context.situationalContext() != null) {
            node.put("situationalContext", context.situationalContext());
        }
        if (!context.resources().isEmpty()) {
            final ArrayNode resourcesArray = node.putArray("resources");
            for (final Resource r : context.resources()) {
                final ObjectNode rNode = resourcesArray.addObject();
                rNode.put("uri", r.uri());
                addIfPresent(rNode, "label", r.label());
                addIfPresent(rNode, "type", r.type());
            }
        }
        return node;
    }

    /**
     * Focused payload for enrichment — name/slot/disposition/goal/briefing only.
     * Role context (name, slot, vocab labels) improves disposition prose quality.
     *
     * Intentionally excluded: identity details (agentId, model, weightsFingerprint),
     * capabilities, jurisdiction, dataHandlingPolicy, situationalContext, resources.
     * Those sections render structurally always — sending them to the LLM is noise.
     * They are included in descriptorNode/contextNode for cache-key correctness only.
     */
    ObjectNode buildEnrichmentPayload(final ObjectNode descriptorNode,
                                       final ObjectNode contextNode) {
        final ObjectNode payload = mapper.createObjectNode();
        copyIfPresent(payload, descriptorNode, "name");
        copyIfPresent(payload, descriptorNode, "slot");
        copyIfPresent(payload, descriptorNode, "slotLabel");
        copyIfPresent(payload, descriptorNode, "slotDescription");
        copyIfPresent(payload, descriptorNode, "slotVocabularyName");
        copyIfPresent(payload, descriptorNode, "disposition");
        copyIfPresent(payload, contextNode,    "goal");
        copyIfPresent(payload, descriptorNode, "briefing");
        copyIfPresent(payload, descriptorNode, "templates");
        return payload;
    }

    StageOneResult buildStage1(final AgentDescriptor descriptor, final AgentPromptContext context) {
        final ObjectNode descriptorNode = buildDescriptorPayload(descriptor, context.format());
        final ObjectNode contextNode    = buildContextPayload(context);
        final String descriptorHash     = fingerprint(descriptorNode.toString());
        final String contextHash        = fingerprint(contextNode.toString());
        final String key                = cacheKey(descriptorHash, contextHash, context.format());
        return new StageOneResult(descriptorNode, contextNode, descriptorHash, contextHash, key);
    }

    String cacheKey(final String descriptorHash, final String contextHash,
                    final RenderFormat format) {
        return descriptorHash + ":" + contextHash + ":" + format.name() + ":" + TEMPLATE_HASH;
    }

    RenderedPrompt assemble(final StageOneResult s1,
                             final Optional<SemanticEnrichment> enrichment,
                             final Optional<A2AEnrichment> a2aEnrichment,
                             final AgentDescriptor descriptor,
                             final AgentPromptContext context) {
        final String content = switch (context.format()) {
            case MARKDOWN  -> assembleMarkdown(enrichment, descriptor, context);
            case PROSE     -> assembleProse(enrichment, descriptor, context);
            case A2A_CARD  -> assembleA2aCard(a2aEnrichment, descriptor);
        };
        final boolean enriched = enrichment.map(e -> e.dispositionNarrative().isPresent()).orElse(false)
            || a2aEnrichment.isPresent();
        return new RenderedPrompt(content, context.format(), s1.descriptorHash(), s1.contextHash(), enriched);
    }

    String resolveTemplates(AgentDescriptor descriptor) {
        if (descriptor.templates() == null || descriptor.templates().isEmpty()) {return null;}
        var sb = new StringBuilder();
        for (var ref : descriptor.templates()) {
            var template = templateRegistry.resolve(ref.templateId())
                                           .orElseThrow(() -> new IllegalStateException("Unknown template: " + ref.templateId()));
            sb.append(substitute(template.content(), ref.args())).append("\n\n");
        }
        return sb.toString().trim();
    }

    private void assembleMarkdownRole(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.slot() != null) {
            sb.append("\n## Role\n");
            descriptor.vocabUriForSlot().ifPresentOrElse(
                uri -> vocab.resolve(uri, descriptor.slot()).ifPresentOrElse(
                    term -> {
                        if (term.label() != null)       sb.append(term.label()).append("\n");
                        if (term.description() != null) sb.append(term.description()).append("\n");
                    },
                    () -> sb.append(descriptor.slot()).append("\n")
                ),
                () -> sb.append(descriptor.slot()).append("\n")
            );
        }
    }

    // ── Shared utilities ───────────────────────────────────────────────────────

    private void assembleMarkdownCapabilities(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.capabilities() != null && !descriptor.capabilities().isEmpty()) {
            sb.append("\n## Capabilities\n");
            for (final AgentCapability cap : descriptor.capabilities()) {
                sb.append("- **").append(cap.name()).append("**");
                if (cap.description() != null)
                    sb.append(" — ").append(cap.description());
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty())
                    sb.append(": accepts ").append(String.join(", ", cap.inputTypes()));
                if (cap.outputTypes() != null && !cap.outputTypes().isEmpty())
                    sb.append(" → ").append(String.join(", ", cap.outputTypes()));
                sb.append("\n");
            }
        }
    }

    private void assembleMarkdownObjectives(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.goals().isEmpty()) {return;}
        sb.append("\n## Objectives\n");
        descriptor.goals().stream()
                  .sorted(java.util.Comparator.comparing(AgentGoal::priority)
                                              .thenComparing(AgentGoal::name))
                  .forEach(g -> sb.append("- **[").append(g.priority().name()).append("]** ")
                                  .append(g.description()).append("\n"));
    }

    private void assembleMarkdownConstraints(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.constraints().isEmpty()) {return;}
        sb.append("\n## Constraints\n");
        descriptor.constraints().stream()
                  .sorted(java.util.Comparator.comparing(AgentConstraint::severity)
                                              .thenComparing(AgentConstraint::name))
                  .forEach(c -> sb.append("- **[").append(c.severity().name()).append("]** ")
                                  .append(c.description()).append("\n"));
    }

    private void assembleMarkdownDisposition(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.disposition() != null) {
            final AgentDisposition d = descriptor.disposition();
            sb.append("\n## How You Operate\n");
            for (final DispositionAxis axis : DispositionAxis.values()) {
                final List<DispositionValue> values = d.get(axis);
                if (!values.isEmpty()) {
                    sb.append("- ").append(axisLabel(axis)).append(": ");
                    if (values.size() == 1 && values.getFirst().weight() == 1.0) {
                        sb.append(resolveAxisDisplay(axis, values.getFirst().term(), descriptor));
                    } else {
                        sb.append(renderWeightedValues(axis, values, descriptor));
                    }
                    sb.append("\n");
                }
            }
            sb.append("- Can delegate: ").append(d.delegation() ? "yes" : "no").append("\n");
        }
    }

    private void assembleMarkdownCognitiveProfile(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.disposition() == null) {return;}
        final List<DispositionValue> profile = descriptor.disposition().dispositionProfile();
        if (profile.isEmpty()) {return;}
        final String vocabUri = descriptor.dispositionVocabulary();
        if (!JUNGIAN_VOCAB_URI.equals(vocabUri)) {return;}
        final var sorted = profile.stream().sorted(Comparator.comparingDouble(DispositionValue::weight).reversed()).toList();
        sb.append("\n## Cognitive Style\n\nYour personality is structured around Jungian cognitive functions:\n");
        if (sorted.size() >= 1) {
            final DispositionValue dominant = sorted.get(0);
            vocab.resolve(vocabUri, dominant.term()).ifPresent(term -> {
                sb.append("\n**Dominant — ").append(term.label()).append(" (").append(capitalizeAbbrev(dominant.term())).append("):** ").append(term.description()).append(" This is your primary mode of engagement.\n");
            });
        }
        if (sorted.size() >= 2) {
            final DispositionValue auxiliary = sorted.get(1);
            vocab.resolve(vocabUri, auxiliary.term()).ifPresent(term -> {
                sb.append("\n**Auxiliary — ").append(term.label()).append(" (").append(capitalizeAbbrev(auxiliary.term())).append("):** ").append(term.description()).append(" This complements your ").append(cognitiveCoreName(sorted.get(0).term())).append(" core.\n");
            });
        }
        if (sorted.size() >= 1) {
            final String dt = sorted.get(0).term().toLowerCase();
            if (dt.endsWith("e")) {
                sb.append("\n**Orientation — Outward:** You think out loud, actively seek input from others, and prefer brainstorming with people over solo analysis. You process by engaging — talking through problems, organizing teams, and collaborating while working, not by withdrawing to reflect.\n");
            } else if (dt.endsWith("i")) {
                sb.append("\n**Orientation — Inward:** You prefer to work through problems internally before sharing conclusions, favouring focused solo analysis over group brainstorming. You form judgments independently first, then engage with the external world once your thinking is clear.\n");
            }
        }
        if (sorted.size() >= 2) {
            final String percFunc = perceivingFunction(sorted.get(0).term(), sorted.get(1).term());
            if (percFunc != null && percFunc.startsWith("n")) {
                sb.append("\n**Perception — Intuitive:** You look past surface data to identify underlying patterns and big picture implications. When given a problem, your first instinct is to explore possibilities and strategic meaning, not to catalogue immediate details. You prefer innovative approaches over proven-but-conventional ones.\n");
            } else if (percFunc != null && percFunc.startsWith("s")) {
                sb.append("\n**Perception — Concrete:** You focus on specific, tangible data and present-moment facts before anything else. When given a problem, your first instinct is to examine the concrete details and practical realities, not to speculate about abstract possibilities. You prefer proven approaches with track records over untested innovations.\n");
            }
        }
        sb.append("\nWhen your dominant and auxiliary functions cannot effectively address a situation, draw on other cognitive functions. Recognize that compensatory function use produces less controlled but potentially valuable responses.\n");
    }

    private Optional<String> deriveMbtiType(final String dominantTerm, final String auxiliaryTerm) {
        final String mbtiUri = "urn:casehub:vocab:mbti";
        if (!vocab.isRegistered(mbtiUri)) {return Optional.empty();}
        for (var type : vocab.allTerms(mbtiUri)) {
            var specializes = type.specializes();
            if (specializes.size() >= 2
                && specializes.get(0).value().equals(dominantTerm)
                && specializes.get(1).value().equals(auxiliaryTerm)) {
                return Optional.of(type.value().toUpperCase());
            }
        }
        return Optional.empty();
    }

    private void assembleMarkdownDataHandling(final StringBuilder sb, final AgentDescriptor descriptor) {
        if (descriptor.jurisdiction() != null || descriptor.dataHandlingPolicy() != null) {
            sb.append("\n## Data Handling\n");
            if (descriptor.jurisdiction() != null)
                sb.append("Jurisdiction: ").append(descriptor.jurisdiction()).append("\n");
            if (descriptor.dataHandlingPolicy() != null)
                sb.append("Policy: ").append(descriptor.dataHandlingPolicy()).append("\n");
        }
    }

    private void assembleMarkdownGoal(final StringBuilder sb, final AgentPromptContext context) {
        context.goal().ifPresent(goal -> {
            sb.append("\n## Current Goal\n");
            sb.append(goal.description()).append("\n");
            if (!goal.subGoals().isEmpty()) {
                goal.subGoals().forEach(sub -> sb.append("- ").append(sub).append("\n"));
            }
            if (goal.caseRef() != null) sb.append("Case: ").append(goal.caseRef()).append("\n");
        });
    }

    private String assembleMarkdown(final Optional<SemanticEnrichment> enrichment,
                                     final AgentDescriptor descriptor,
                                     final AgentPromptContext context) {
        final var sb = new StringBuilder();

        // Header — always structural
        sb.append("# ").append(descriptor.name()).append("\n");
        sb.append("**Agent ID:** ").append(descriptor.agentId());
        final String model = combinedModel(descriptor);
        if (model != null) {sb.append("  **Model:** ").append(model);}
        if (descriptor.provider() != null) {sb.append("  **Provider:** ").append(descriptor.provider());}
        sb.append("\n");

        // Role — always structural
        assembleMarkdownRole(sb, descriptor);

        // Capabilities — always structural
        assembleMarkdownCapabilities(sb, descriptor);

        // Templates — resolved prose, before disposition
        String templates = resolveTemplates(descriptor);
        if (templates != null) {
            sb.append("\n## Behavioral Conventions\n").append(templates).append("\n");
        }

        // Goals and constraints — always structural, after capabilities, before disposition
        assembleMarkdownObjectives(sb, descriptor);
        assembleMarkdownConstraints(sb, descriptor);

        // Cognitive profile — Jungian JPAF rendering before disposition axes
        assembleMarkdownCognitiveProfile(sb, descriptor);

        // Disposition — enriched OR structural (selective override)
        if (enrichment.isPresent() && enrichment.get().dispositionNarrative().isPresent()) {
            sb.append("\n## How You Operate\n")
              .append(enrichment.get().dispositionNarrative().get()).append("\n");
        } else {
            assembleMarkdownDisposition(sb, descriptor);
        }

        // Briefing structural fallback
        if (!(enrichment.isPresent() && enrichment.get().dispositionNarrative().isPresent())
            && descriptor.briefing() != null) {
            sb.append("\n## Operating Principles\n").append(descriptor.briefing()).append("\n");
        }

        // Data Handling — always structural
        assembleMarkdownDataHandling(sb, descriptor);

        // Goal — enriched OR structural (selective override)
        if (enrichment.isPresent() && enrichment.get().goalNarrative().isPresent()) {
            sb.append("\n## Current Goal\n")
              .append(enrichment.get().goalNarrative().get()).append("\n");
        } else {
            assembleMarkdownGoal(sb, context);
        }

        // Resources — always structural
        if (!context.resources().isEmpty()) {
            sb.append("\n## Resources\n");
            for (final Resource r : context.resources()) {
                sb.append("- **").append(r.label() != null ? r.label() : r.uri()).append("**: ").append(r.uri());
                if (r.type() != null) {sb.append(" (").append(r.type()).append(")");}
                sb.append("\n");
            }
        }

        // Situational context — always structural
        if (context.situationalContext() != null) {
            sb.append("\n## Context\n").append(context.situationalContext()).append("\n");
        }

        return sb.toString().trim();}

    private String assembleProse(final Optional<SemanticEnrichment> enrichment,
                                 final AgentDescriptor descriptor,
                                 final AgentPromptContext context) {
        final var sb = new StringBuilder();

        sb.append(descriptor.name());
        if (descriptor.slot() != null) {sb.append(", ").append(descriptor.slot());}
        sb.append(".");
        if (descriptor.version() != null) {sb.append(" Version ").append(descriptor.version()).append(".");}
        sb.append("\n");

        if (descriptor.capabilities() != null && !descriptor.capabilities().isEmpty()) {
            sb.append("\nCapabilities: ");
            final var parts = descriptor.capabilities().stream()
                                        .map(cap -> cap.description() != null
                                                    ? cap.name() + " (" + cap.description() + ")"
                                                    : cap.name())
                                        .collect(Collectors.joining(", "));
            sb.append(parts).append(".\n");
        }

        // Templates — resolved prose, before disposition
        String proseTemplates = resolveTemplates(descriptor);
        if (proseTemplates != null) {
            sb.append("\n").append(proseTemplates).append("\n");
        }

        if (!descriptor.goals().isEmpty()) {
            var sorted = descriptor.goals().stream()
                                   .sorted(java.util.Comparator.comparing(AgentGoal::priority).thenComparing(AgentGoal::name))
                                   .toList();
            var primary   = sorted.stream().filter(g -> g.priority() == GoalPriority.PRIMARY).toList();
            var secondary = sorted.stream().filter(g -> g.priority() == GoalPriority.SECONDARY).toList();
            sb.append("\nPrimary objectives: ");
            sb.append(primary.stream().map(AgentGoal::description).collect(Collectors.joining("; ")));
            sb.append(".");
            if (!secondary.isEmpty()) {
                sb.append(" Also: ");
                sb.append(secondary.stream().map(AgentGoal::description).collect(Collectors.joining("; ")));
                sb.append(".");
            }
            sb.append("\n");
        }

        if (!descriptor.constraints().isEmpty()) {
            var csorted = descriptor.constraints().stream()
                                    .sorted(java.util.Comparator.comparing(AgentConstraint::severity)
                                                                .thenComparing(AgentConstraint::name))
                                    .toList();
            var hard = csorted.stream().filter(c -> c.severity() == ConstraintSeverity.HARD).toList();
            var soft = csorted.stream().filter(c -> c.severity() == ConstraintSeverity.SOFT).toList();
            if (!hard.isEmpty()) {
                sb.append("\nHard constraints: ");
                sb.append(hard.stream().map(AgentConstraint::description).collect(Collectors.joining(". ")));
                sb.append(".");
            }
            if (!soft.isEmpty()) {
                sb.append(hard.isEmpty() ? "\nConstraints: " : " Also: ");
                sb.append(soft.stream().map(AgentConstraint::description).collect(Collectors.joining(". ")));
                sb.append(".");
            }
            sb.append("\n");
        }

        // Cognitive profile — Jungian JPAF rendering before disposition axes
        assembleMarkdownCognitiveProfile(sb, descriptor);

        // Disposition — enriched OR structural (selective override)
        if (enrichment.isPresent() && enrichment.get().dispositionNarrative().isPresent()) {
            sb.append("\n").append(enrichment.get().dispositionNarrative().get()).append("\n");
        } else if (descriptor.disposition() != null) {
            final AgentDisposition d = descriptor.disposition();
            sb.append("\nOperating style:");
            for (DispositionAxis axis : DispositionAxis.values()) {
                final List<DispositionValue> values = d.get(axis);
                if (!values.isEmpty()) {
                    sb.append(" ").append(axisLabel(axis)).append(": ");
                    if (values.size() == 1 && values.getFirst().weight() == 1.0) {
                        sb.append(resolveAxisDisplay(axis, values.getFirst().term(), descriptor));
                    } else {
                        sb.append(renderWeightedValues(axis, values, descriptor));
                    }
                    sb.append(".");
                }
            }
            sb.append(" Can delegate: ").append(d.delegation() ? "yes" : "no").append(".\n");
        }

        if (!(enrichment.isPresent() && enrichment.get().dispositionNarrative().isPresent())
            && descriptor.briefing() != null) {
            sb.append("\n").append(descriptor.briefing()).append("\n");
        }

        if (enrichment.isPresent() && enrichment.get().goalNarrative().isPresent()) {
            sb.append("\n").append(enrichment.get().goalNarrative().get()).append("\n");
        } else {
            context.goal().ifPresent(goal -> {
                sb.append("\nGoal: ").append(goal.description()).append(".\n");
                if (!goal.subGoals().isEmpty()) {
                    sb.append("Sub-goals: ").append(String.join(", ", goal.subGoals())).append(".\n");
                }
            });
        }

        if (!context.resources().isEmpty()) {
            sb.append("\nResources: ");
            final var resources = context.resources().stream()
                                         .map(r -> (r.label() != null ? r.label() : r.uri()) + " (" + r.uri() + ")")
                                         .collect(Collectors.joining(", "));
            sb.append(resources).append(".\n");
        }

        if (context.situationalContext() != null) {
            sb.append("\n").append(context.situationalContext()).append("\n");
        }

        return sb.toString().trim();
    }

    private String assembleA2aCard(final Optional<A2AEnrichment> enrichment,
                                   final AgentDescriptor descriptor) {
        final ObjectNode card = mapper.createObjectNode();
        card.put("name", descriptor.name());
        card.put("agentId", descriptor.agentId());
        addIfPresent(card, "version", descriptor.version());

        // slot — always present (required field), vocab-enriched via vocabUriForSlot()
        final ObjectNode slotNode = card.putObject("slot");
        slotNode.put("value", descriptor.slot());
        descriptor.vocabUriForSlot().ifPresent(uri -> {
            slotNode.put("vocabularyUri", uri);
            vocab.resolve(uri, descriptor.slot())
                 .ifPresent(term -> addIfNonBlank(slotNode, "label", term.label()));
            vocab.vocabularyMetadata(uri)
                 .ifPresent(meta -> addIfNonBlank(slotNode, "vocabularyName", meta.name()));
        });

        // disposition — per-axis objects with values array (axes in DispositionAxis declaration order),
        // canDelegate last. Omitted entirely when descriptor.disposition() is null.
        if (descriptor.disposition() != null) {
            final AgentDisposition d        = descriptor.disposition();
            final ObjectNode       dispNode = card.putObject("disposition");
            for (final DispositionAxis axis : DispositionAxis.values()) {
                var axisValues = d.get(axis);
                if (!axisValues.isEmpty()) {
                    final ObjectNode axisNode    = dispNode.putObject(axisJsonKey(axis));
                    final ArrayNode  valuesArray = axisNode.putArray("values");
                    for (final DispositionValue dv : axisValues) {
                        final ObjectNode valNode = valuesArray.addObject();
                        valNode.put("term", dv.term());
                        valNode.put("weight", dv.weight());
                        descriptor.vocabUriForAxis(axis).ifPresent(uri ->
                                                                           vocab.resolve(uri, dv.term())
                                                                                .ifPresent(term -> addIfNonBlank(valNode, "label", term.label())));
                    }
                    descriptor.vocabUriForAxis(axis).ifPresent(uri -> {
                        axisNode.put("vocabularyUri", uri);
                        vocab.vocabularyMetadata(uri)
                             .ifPresent(meta -> addIfNonBlank(axisNode, "vocabularyName", meta.name()));
                    });
                }
            }
            dispNode.put("canDelegate", d.delegation());
        }

        // dispositionProfile — Jungian cognitive profile with roles and derived MBTI type
        if (descriptor.disposition() != null && !descriptor.disposition().dispositionProfile().isEmpty()) {
            final List<DispositionValue> profile     = descriptor.disposition().dispositionProfile();
            final String                 vocabUri    = descriptor.dispositionVocabulary();
            final ObjectNode             profileNode = card.putObject("dispositionProfile");
            if (vocabUri != null) {profileNode.put("vocabulary", vocabUri);}

            final var sorted = profile.stream()
                                      .sorted(Comparator.comparingDouble(DispositionValue::weight).reversed())
                                      .toList();

            final ArrayNode funcsArray = profileNode.putArray("functions");
            for (int i = 0; i < sorted.size(); i++) {
                final DispositionValue dv = sorted.get(i);
                final ObjectNode       fn = funcsArray.addObject();
                fn.put("term", dv.term());
                fn.put("weight", dv.weight());
                fn.put("role", i == 0 ? "dominant" : i == 1 ? "auxiliary" : "supporting");
            }

            // Derive MBTI type by matching dominant+auxiliary against MbtiTypeTerm.specializes()
            if (JUNGIAN_VOCAB_URI.equals(vocabUri) && sorted.size() >= 2) {
                deriveMbtiType(sorted.get(0).term(), sorted.get(1).term())
                        .ifPresent(mbti -> profileNode.put("derivedMbtiType", mbti));
            }
        }

        // frameworks — deduplicated index of actively-instantiated vocabulary URIs.
        // Invariant: contains exactly those URIs reachable by vocabUriForSlot() or
        // vocabUriForAxis(axis) for an active axis, AND registered with a non-blank name().
        final LinkedHashSet<String> frameworkUris = new LinkedHashSet<>();
        descriptor.vocabUriForSlot().ifPresent(frameworkUris::add);
        if (descriptor.disposition() != null) {
            for (final DispositionAxis axis : DispositionAxis.values()) {
                if (!descriptor.disposition().get(axis).isEmpty()) {
                    descriptor.vocabUriForAxis(axis).ifPresent(frameworkUris::add);
                }
            }
        }
        if (!frameworkUris.isEmpty()) {
            final ArrayNode frameworksArray = mapper.createArrayNode();
            for (final String uri : frameworkUris) {
                vocab.vocabularyMetadata(uri).ifPresent(meta -> {
                    if (!meta.name().isEmpty()) {
                        final ObjectNode fw = frameworksArray.addObject();
                        fw.put("uri", uri);
                        fw.put("name", meta.name());
                        addIfNonBlank(fw, "description", meta.description());
                    }
                });
            }
            if (!frameworksArray.isEmpty()) {
                card.set("frameworks", frameworksArray);
            }
        }

        // capabilities — full numeric + type schema; descriptions enriched via A2AEnrichment when available
        if (descriptor.capabilities() != null && !descriptor.capabilities().isEmpty()) {
            final Map<String, String> descriptionByName = enrichment
                                                                  .map(e -> e.capabilityNarratives().stream()
                                                                             .collect(Collectors.toMap(
                                                                                     A2AEnrichment.CapabilityNarrative::name,
                                                                                     A2AEnrichment.CapabilityNarrative::description,
                                                                                     (a, b) -> a)))
                                                                  .orElse(Map.of());

            final ArrayNode capsArray = card.putArray("capabilities");
            for (final AgentCapability cap : descriptor.capabilities()) {
                final ObjectNode capNode = capsArray.addObject();
                capNode.put("name", cap.name());
                if (cap.qualityHint() != null) {capNode.put("qualityHint", cap.qualityHint());}
                if (cap.latencyHintP50Ms() != null) {capNode.put("latencyHintP50Ms", cap.latencyHintP50Ms());}
                if (cap.costHint() != null) {capNode.put("costHint", cap.costHint());}
                if (cap.epistemicDomains() != null && !cap.epistemicDomains().isEmpty()) {
                    final ObjectNode domains = capNode.putObject("epistemicDomains");
                    cap.epistemicDomains().forEach(domains::put);
                }
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty()) {
                    final ArrayNode arr = capNode.putArray("inputTypes");
                    cap.inputTypes().forEach(arr::add);
                }
                if (cap.outputTypes() != null && !cap.outputTypes().isEmpty()) {
                    final ArrayNode arr = capNode.putArray("outputTypes");
                    cap.outputTypes().forEach(arr::add);
                }
                if (cap.excludedDomains() != null && !cap.excludedDomains().isEmpty()) {
                    final ArrayNode arr = capNode.putArray("excludedDomains");
                    cap.excludedDomains().forEach(arr::add);
                }
                final String enrichedDesc = descriptionByName.get(cap.name());
                if (enrichedDesc != null && !enrichedDesc.isBlank()) {
                    capNode.put("description", enrichedDesc);
                } else if (cap.description() != null) {
                    capNode.put("description", cap.description());
                }
            }
        }

        final List<AgentGoal> publicGoals = descriptor.publicGoals();
        if (!publicGoals.isEmpty()) {
            final ArrayNode goalsArray = card.putArray("goals");
            for (final AgentGoal goal : publicGoals.stream()
                                                   .sorted(java.util.Comparator.comparing(AgentGoal::priority).thenComparing(AgentGoal::name))
                                                   .toList()) {
                final ObjectNode goalNode = goalsArray.addObject();
                goalNode.put("name", goal.name());
                goalNode.put("description", goal.description());
                goalNode.put("priority", goal.priority().name());
            }
        }

        final List<AgentConstraint> publicConstraints = descriptor.publicConstraints();
        if (!publicConstraints.isEmpty()) {
            final ArrayNode constraintsArray = card.putArray("constraints");
            for (final AgentConstraint c : publicConstraints.stream()
                                                            .sorted(java.util.Comparator.comparing(AgentConstraint::severity)
                                                                                        .thenComparing(AgentConstraint::name))
                                                            .toList()) {
                final ObjectNode cNode = constraintsArray.addObject();
                cNode.put("name", c.name());
                cNode.put("description", c.description());
                cNode.put("severity", c.severity().name());
            }
        }

        try {
            return mapper.writeValueAsString(card);
        } catch (final com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("A2A card serialization failed", ex);
        }
    }

    private String resolveAxisDisplay(final DispositionAxis axis, final String raw,
                                       final AgentDescriptor descriptor) {
        final Optional<String> vocabUri = descriptor.vocabUriForAxis(axis);
        final String label = vocabUri
            .flatMap(uri -> vocab.resolve(uri, raw))
            .map(VocabularyTerm::label)
            .filter(l -> !l.isEmpty())
            .orElse(raw);
        final String vocabName = vocabUri
            .flatMap(uri -> vocab.vocabularyMetadata(uri))
            .map(VocabularyMetadata::name)
            .filter(n -> !n.isEmpty())
            .orElse(null);
        return vocabName != null ? label + " (" + vocabName + ")" : label;
    }

    private String renderWeightedValues(final DispositionAxis axis,
                                        final List<DispositionValue> values,
                                        final AgentDescriptor descriptor) {
        final var sorted = values.stream()
                                 .sorted(Comparator.comparingDouble(DispositionValue::weight).reversed())
                                 .toList();
        final Optional<String> vocabUri = descriptor.vocabUriForAxis(axis);
        final var              sb       = new StringBuilder("primarily ");
        for (int i = 0; i < sorted.size(); i++) {
            final DispositionValue dv    = sorted.get(i);
            final String           label = resolveTermLabel(vocabUri, dv.term());
            if (i == 0) {
                sb.append(label).append(" (").append(formatWeight(dv.weight())).append(")");
            } else if (i == 1 && sorted.size() == 2) {
                sb.append(", with ").append(label).append(" tendencies (").append(formatWeight(dv.weight())).append(")");
            } else {
                sb.append(", ").append(label).append(" (").append(formatWeight(dv.weight())).append(")");
            }
        }
        return sb.toString();
    }

    private String resolveTermLabel(final Optional<String> vocabUri, final String raw) {
        return vocabUri
                       .flatMap(uri -> vocab.resolve(uri, raw))
                       .map(VocabularyTerm::label)
                       .filter(l -> !l.isEmpty())
                       .orElse(raw);
    }


}
