package io.casehub.eidos.runtime.renderer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentConstraint;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.eidos.api.GoalContext;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.Visibility;
import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyTerm;
import io.casehub.eidos.runtime.vocabulary.CdiVocabularyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.casehub.eidos.api.SystemPromptRenderer.RenderFormat.A2A_CARD;
import static io.casehub.eidos.api.SystemPromptRenderer.RenderFormat.MARKDOWN;
import static io.casehub.eidos.api.SystemPromptRenderer.RenderFormat.PROSE;
import static org.assertj.core.api.Assertions.assertThat;

class EidosRenderPipelineTest {
    static io.casehub.eidos.api.TemplateRegistry emptyTemplateRegistry() {
        return new io.casehub.eidos.api.TemplateRegistry() {
            @Override
            public void register(io.casehub.eidos.api.DescriptorTemplate t)                       {}

            @Override
            public java.util.Optional<io.casehub.eidos.api.DescriptorTemplate> resolve(String id) {return java.util.Optional.empty();}

            @Override
            public java.util.List<io.casehub.eidos.api.DescriptorTemplate> all()                  {return java.util.List.of();}
        };
    }


    // Used in disposition payload tests and structural renderer tests
    @VocabularyMetadata(uri = "urn:test:disp", name = "Test Disposition Vocab", version = "1.0",
                        description = "A test disposition vocabulary description")
    enum TestDispTerm implements VocabularyTerm {
        INDEPENDENT("independent", "Independent", "Works alone by preference", List.of("alone"));
        private final String value, label, description;
        private final List<String> aliases;
        TestDispTerm(String v, String l, String d, List<String> a) {
            value = v; label = l; description = d; aliases = a;
        }
        @Override public String value()         { return value; }
        @Override public String label()         { return label; }
        @Override public String description()   { return description; }
        @Override public List<String> aliases() { return aliases; }
    }

    @VocabularyMetadata(uri = "urn:test:slot", name = "Test Slot Vocab", version = "1.0",
                        description = "A test slot vocabulary description")
    enum TestSlotTerm implements VocabularyTerm {
        REVIEWER("reviewer", "Reviewer", "Reviews the work", List.of());
        private final String value, label, description;
        private final List<String> aliases;
        TestSlotTerm(String v, String l, String d, List<String> a) {
            value = v; label = l; description = d; aliases = a;
        }
        @Override public String value()         { return value; }
        @Override public String label()         { return label; }
        @Override public String description()   { return description; }
        @Override public List<String> aliases() { return aliases; }
    }

    /** Non-blank name, blank description — for frameworks description-omission test. */
    @VocabularyMetadata(uri = "urn:test:nodesc", name = "No Description Vocab")
    enum TestNoDescTerm implements VocabularyTerm {
        TERM("term", "Term", List.of());
        private final String value, label;
        private final List<String> aliases;
        TestNoDescTerm(String v, String l, List<String> a) {
            value = v; label = l; aliases = a;
        }
        @Override public String value()         { return value; }
        @Override public String label()         { return label; }
        @Override public List<String> aliases() { return aliases; }
    }

    @VocabularyMetadata(uri = "urn:test:noname")
    enum TestNoNameTerm implements VocabularyTerm {
        TERM("term", "Term", List.of());
        private final String value, label;
        private final List<String> aliases;
        TestNoNameTerm(String v, String l, List<String> a) {
            value = v; label = l; aliases = a;
        }
        @Override public String value()         { return value; }
        @Override public String label()         { return label; }
        @Override public List<String> aliases() { return aliases; }
    }

    static final ObjectMapper MAPPER = new ObjectMapper();
    CdiVocabularyRegistry vocab;
    EidosRenderPipeline pipeline;

    @BeforeEach
    void setUp() {
        vocab = new CdiVocabularyRegistry();
        pipeline = new EidosRenderPipeline(vocab, emptyTemplateRegistry(), MAPPER);
    }

    static AgentDescriptor fullDescriptor() {
        return AgentDescriptor.builder()
            .agentId("reviewer-1")
            .name("Code Reviewer")
            .version("1.0")
            .provider("anthropic")
            .modelFamily("claude")
            .modelVersion("claude-3-7-sonnet")
            .slot("reviewer")
            .capabilities(List.of(AgentCapability.builder()
                .name("code-review").qualityHint(0.95).latencyHintP50Ms(150L).costHint("low")
                .inputTypes(List.of("code")).outputTypes(List.of("review")).tags(List.of())
                .epistemicDomains(Map.of("java", 0.95, "rust", 0.3)).build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent")
                .ruleFollowing("strict")
                .riskAppetite("conservative")
                .autonomy("directed")
                .build())
            .jurisdiction("EU")
            .dataHandlingPolicy("gdpr-compliant")
            .tenancyId("default")
            .build();
    }

    static AgentPromptContext fullContext() {
        return AgentPromptContext.forFormat(MARKDOWN)
                .withGoal(new GoalContext("Review PR #42", List.of("Check style", "Check tests"), "case-123"))
                .withResources(List.of(new Resource("/src/main/java", "Source", "filesystem")))
                .withSituationalContext("Critical release branch");
    }

    // ── Payload building (Stage 1) ────────────────────────────────────────────

    @Test
    void descriptor_payload_includes_agent_id_and_name() {
        final var node = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN);
        assertThat(node.get("agentId").asText()).isEqualTo("reviewer-1");
        assertThat(node.get("name").asText()).isEqualTo("Code Reviewer");
    }

    @Test
    void descriptor_payload_prose_omits_numeric_capability_metadata() {
        final var cap = pipeline.buildDescriptorPayload(fullDescriptor(), PROSE)
                .get("capabilities").get(0);
        assertThat(cap.has("qualityHint")).isFalse();
        assertThat(cap.has("latencyHintP50Ms")).isFalse();
        assertThat(cap.has("costHint")).isFalse();
        assertThat(cap.has("epistemicDomains")).isFalse();
        assertThat(cap.get("name").asText()).isEqualTo("code-review");
        assertThat(cap.get("inputTypes").get(0).asText()).isEqualTo("code");
        assertThat(cap.get("outputTypes").get(0).asText()).isEqualTo("review");
    }

    @Test
    void descriptor_payload_a2a_includes_all_numeric_capability_metadata() {
        final var cap = pipeline.buildDescriptorPayload(fullDescriptor(), A2A_CARD)
                .get("capabilities").get(0);
        assertThat(cap.get("qualityHint").asDouble()).isEqualTo(0.95);
        assertThat(cap.get("latencyHintP50Ms").asLong()).isEqualTo(150L);
        assertThat(cap.get("costHint").asText()).isEqualTo("low");
        assertThat(cap.get("epistemicDomains").get("java").asDouble()).isEqualTo(0.95);
        assertThat(cap.get("inputTypes").get(0).asText()).isEqualTo("code");
        assertThat(cap.get("outputTypes").get(0).asText()).isEqualTo("review");
    }

    @Test
    void descriptor_payload_format_differences_produce_different_descriptor_hash() {
        final var s1Prose = pipeline.buildStage1(fullDescriptor(), AgentPromptContext.forFormat(PROSE));
        final var s1A2a  = pipeline.buildStage1(fullDescriptor(), AgentPromptContext.forFormat(A2A_CARD));
        assertThat(s1Prose.descriptorHash()).isNotEqualTo(s1A2a.descriptorHash());
    }

    @Test
    void descriptor_payload_prose_and_markdown_produce_same_descriptor_hash() {
        final var s1Prose    = pipeline.buildStage1(fullDescriptor(), AgentPromptContext.forFormat(PROSE));
        final var s1Markdown = pipeline.buildStage1(fullDescriptor(), AgentPromptContext.forFormat(MARKDOWN));
        assertThat(s1Prose.descriptorHash()).isEqualTo(s1Markdown.descriptorHash());
    }

    @Test
    void descriptor_payload_excludes_tenancy_id() {
        final var node = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN);
        assertThat(node.has("tenancyId")).isFalse();
    }

    @Test
    void descriptor_payload_excludes_vocabulary_uris() {
        final var node = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN);
        assertThat(node.has("slotVocabulary")).isFalse();
        assertThat(node.has("domainVocabulary")).isFalse();
        assertThat(node.has("dispositionVocabulary")).isFalse();
    }

    @Test
    void descriptor_payload_combines_model_family_and_version() {
        final var node = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN);
        assertThat(node.get("model").asText()).isEqualTo("claude/claude-3-7-sonnet");
    }

    @Test
    void descriptor_payload_capability_includes_input_and_output_types() {
        final var node = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN);
        final var cap = node.get("capabilities").get(0);
        assertThat(cap.get("inputTypes").get(0).asText()).isEqualTo("code");
        assertThat(cap.get("outputTypes").get(0).asText()).isEqualTo("review");
    }

    @Test
    void descriptor_payload_markdown_capability_excludes_numeric_signals_and_tags() {
        final var cap = pipeline.buildDescriptorPayload(fullDescriptor(), MARKDOWN)
                .get("capabilities").get(0);
        assertThat(cap.has("costHint")).isFalse();
        assertThat(cap.has("qualityHint")).isFalse();
        assertThat(cap.has("latencyHintP50Ms")).isFalse();
        assertThat(cap.has("epistemicDomains")).isFalse();
        assertThat(cap.has("tags")).isFalse();
    }

    @Test
    void descriptor_payload_includes_weights_fingerprint_when_set() {
        final var desc = AgentDescriptor.builder()
            .agentId("id")
            .name("Name")
            .version("1.0")
            .weightsFingerprint("fp-abc123")
            .slot("slot")
            .capabilities(List.of())
            .tenancyId("t")
            .build();
        final var node = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        assertThat(node.get("weightsFingerprint").asText()).isEqualTo("fp-abc123");
    }

    @Test
    void context_payload_includes_goal_when_present() {
        final var node = pipeline.buildContextPayload(fullContext());
        assertThat(node.get("goal").get("description").asText()).isEqualTo("Review PR #42");
    }

    @Test
    void context_payload_includes_resources_and_situational_context_for_hash() {
        // Per design: buildContextPayload includes resources and situationalContext
        // to ensure cache correctness (they affect the rendered output in Stage 3).
        // They are excluded from LLM payload in buildLlmPayload.
        final var node = pipeline.buildContextPayload(fullContext());
        assertThat(node.has("resources")).isTrue();
        assertThat(node.get("resources").get(0).get("uri").asText()).isEqualTo("/src/main/java");
        assertThat(node.has("situationalContext")).isTrue();
        assertThat(node.get("situationalContext").asText()).isEqualTo("Critical release branch");
    }

    @Test
    void context_payload_is_empty_when_no_goal() {
        final var ctx = AgentPromptContext.forFormat(MARKDOWN);
        final var node = pipeline.buildContextPayload(ctx);
        assertThat(node.isEmpty()).isTrue();
    }

    // ── Fingerprint utility ───────────────────────────────────────────────────

    @Test
    void same_fingerprint_for_same_input() {
        final String a = EidosRenderPipeline.fingerprint("hello world");
        final String b = EidosRenderPipeline.fingerprint("hello world");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void different_fingerprint_for_different_input() {
        final String a = EidosRenderPipeline.fingerprint("hello");
        final String b = EidosRenderPipeline.fingerprint("world");
        assertThat(a).isNotEqualTo(b);
    }

    // ── TEMPLATE_HASH scope ───────────────────────────────────────────────────

    @Test
    void template_hash_covers_prompt_template_and_all_schema_descriptions() {
        // TEMPLATE_HASH must be fingerprint(PROMPT_TEMPLATE + A2A_PROMPT_TEMPLATE
        //   + all RESPONSE_FORMAT schema descriptions + all A2A_RESPONSE_FORMAT schema descriptions).
        // If any of these change without TEMPLATE_HASH updating, cache serves stale enriched prompts.
        final String expectedInput = EidosRenderPipeline.PROMPT_TEMPLATE
                + EidosRenderPipeline.A2A_PROMPT_TEMPLATE
                + String.join("", EidosRenderPipeline.RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS)
                + String.join("", EidosRenderPipeline.A2A_RESPONSE_FORMAT_SCHEMA_DESCRIPTIONS);
        final String expectedHash = EidosRenderPipeline.fingerprint(expectedInput).substring(0, 8);
        final String key = pipeline.cacheKey("x", "y", MARKDOWN);
        assertThat(key).endsWith(":" + expectedHash);
    }

    @Test
    void template_hash_differs_from_prompt_template_only_hash() {
        // Before fix: TEMPLATE_HASH = fingerprint(PROMPT_TEMPLATE). After fix: includes more.
        // This test guards against regression to the old single-input hash.
        final String promptOnlyHash = EidosRenderPipeline.fingerprint(
                EidosRenderPipeline.PROMPT_TEMPLATE).substring(0, 8);
        final String key = pipeline.cacheKey("x", "y", MARKDOWN);
        assertThat(key).doesNotEndWith(":" + promptOnlyHash);
    }

    // ── usesEnrichment predicate ──────────────────────────────────────────────

    @Test
    void uses_enrichment_true_for_markdown() {
        assertThat(EidosRenderPipeline.usesEnrichment(MARKDOWN)).isTrue();
    }

    @Test
    void uses_enrichment_false_for_a2a_card() {
        assertThat(EidosRenderPipeline.usesEnrichment(A2A_CARD)).isFalse();
    }

    // ── buildStage1 ───────────────────────────────────────────────────────────

    @Test
    void buildStage1_returns_matching_hashes_and_key() {
        final var desc = EidosRenderPipelineTest.fullDescriptor();
        final var ctx  = EidosRenderPipelineTest.fullContext();
        final StageOneResult s1 = pipeline.buildStage1(desc, ctx);
        assertThat(s1.descriptorHash()).hasSize(16);
        assertThat(s1.contextHash()).hasSize(16);
        assertThat(s1.lookupKey()).contains(s1.descriptorHash());
        assertThat(s1.lookupKey()).contains(s1.contextHash());
        assertThat(s1.lookupKey()).contains("MARKDOWN");
    }

    @Test
    void buildStage1_is_deterministic() {
        final var desc = EidosRenderPipelineTest.fullDescriptor();
        final var ctx  = EidosRenderPipelineTest.fullContext();
        assertThat(pipeline.buildStage1(desc, ctx).lookupKey())
            .isEqualTo(pipeline.buildStage1(desc, ctx).lookupKey());
    }

    // ── Slot vocabulary context ───────────────────────────────────────────────

    @Test
    void slot_payload_includes_vocabulary_name_and_description() {
        vocab.register(TestSlotTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slotVocabulary("urn:test:slot").slot("reviewer").tenancyId("t").build();
        var node = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        assertThat(node.get("slotVocabularyName").asText()).isEqualTo("Test Slot Vocab");
        assertThat(node.get("slotVocabularyDescription").asText()).isEqualTo("A test slot vocabulary description");
    }

    @Test
    void empty_vocab_name_not_emitted_in_payload() {
        vocab.register(TestNoNameTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slotVocabulary("urn:test:noname").slot("term").tenancyId("t").build();
        var node = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        // TestNoNameTerm has name="" and description="" — addIfNonBlank must suppress both keys
        assertThat(node.has("slotVocabularyName")).isFalse();
        assertThat(node.has("slotVocabularyDescription")).isFalse();
    }

    // ── Disposition payload (nested per-axis objects) ─────────────────────────

    @Test
    void disposition_payload_is_nested_object_per_axis() {
        vocab.register(TestDispTerm.class);
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary("urn:test:disp")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var dispNode     = pipeline.buildDescriptorPayload(desc, MARKDOWN).get("disposition");
        var socialOrient = dispNode.get("socialOrient");
        assertThat(socialOrient.isObject()).isTrue();
        assertThat(socialOrient.get("values").get(0).get("term").asText()).isEqualTo("independent");
        assertThat(socialOrient.get("values").get(0).get("label").asText()).isEqualTo("Independent");
        assertThat(socialOrient.get("vocabularyName").asText()).isEqualTo("Test Disposition Vocab");
        assertThat(socialOrient.get("vocabularyDescription").asText()).isEqualTo("A test disposition vocabulary description");
        assertThat(socialOrient.get("values").get(0).get("description").asText()).isEqualTo("Works alone by preference");
    }

    @Test
    void conflict_mode_included_in_payload_when_set() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().conflictMode("avoiding").build())
                                  .tenancyId("t").build();
        var dispNode = pipeline.buildDescriptorPayload(desc, MARKDOWN).get("disposition");
        assertThat(dispNode.has("conflictMode")).isTrue();
        assertThat(dispNode.get("conflictMode").get("values").get(0).get("term").asText()).isEqualTo("avoiding");
        assertThat(dispNode.has("socialOrient")).isFalse();
    }

    @Test
    void disposition_without_registered_vocab_has_value_only() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary("urn:test:unregistered")
                                  .disposition(AgentDisposition.builder().socialOrient("custom-value").build())
                                  .tenancyId("t").build();
        var axisNode = pipeline.buildDescriptorPayload(desc, MARKDOWN).get("disposition").get("socialOrient");
        assertThat(axisNode.isObject()).isTrue();
        assertThat(axisNode.get("values").get(0).get("term").asText()).isEqualTo("custom-value");
        assertThat(axisNode.get("values").get(0).has("label")).isFalse();
        assertThat(axisNode.has("vocabularyName")).isFalse();
    }

    @Test
    void different_disposition_vocab_produces_different_descriptor_hash() {
        vocab.register(TestDispTerm.class);
        var descWithVocab = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .dispositionVocabulary("urn:test:disp")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var descWithout = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        assertThat(pipeline.buildStage1(descWithVocab, ctx).descriptorHash())
            .isNotEqualTo(pipeline.buildStage1(descWithout, ctx).descriptorHash());
    }

    // ── Structural renderers ─────────────────────────────────────────────────

    @Test
    void structural_markdown_capability_shows_name_and_io_types_no_numeric() {
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var s1  = pipeline.buildStage1(fullDescriptor(), ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), fullDescriptor(), ctx);
        assertThat(result.content()).contains("**code-review**: accepts code → review");
        assertThat(result.content()).doesNotContain(": quality 0.95");
        assertThat(result.content()).doesNotContain(", p50 150ms");
        assertThat(result.content()).doesNotContain("Domains: {");
    }

    @Test
    void structural_markdown_shows_axis_label_not_raw_value() {
        vocab.register(TestDispTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .dispositionVocabulary("urn:test:disp")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var s1 = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Independent (Test Disposition Vocab)");
        assertThat(result.content()).doesNotContain("Social orientation: independent\n");
        assertThat(result.content()).doesNotContain("independent");
    }

    @Test
    void structural_markdown_slot_label_via_domain_vocabulary_fallback() {
        vocab.register(TestSlotTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .domainVocabulary("urn:test:slot") // no slotVocabulary — must fall through
            .slot("reviewer")
            .tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var s1 = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        // vocabUriForSlot() fallback should resolve label from TestSlotTerm
        assertThat(result.content()).contains("Reviewer");
        assertThat(result.content()).doesNotContain("## Role\nreviewer\n");
    }

    @Test
    void assemble_sets_enriched_true_when_semantic_enrichment_applied() {
        final var enrichment = Optional.of(
            new SemanticEnrichment(Optional.of("You are independent."), Optional.empty()));
        final var desc = fullDescriptor();
        final var ctx = AgentPromptContext.forFormat(MARKDOWN);
        final var s1 = pipeline.buildStage1(desc, ctx);
        final var result = pipeline.assemble(s1, enrichment, Optional.empty(), desc, ctx);
        assertThat(result.enriched()).isTrue();
    }

    @Test
    void assemble_sets_enriched_false_when_no_enrichment() {
        final var desc = fullDescriptor();
        final var ctx = AgentPromptContext.forFormat(MARKDOWN);
        final var s1 = pipeline.buildStage1(desc, ctx);
        final var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.enriched()).isFalse();
    }

    @Test
    void structural_markdown_includes_conflict_mode() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .disposition(AgentDisposition.builder().conflictMode("avoiding").build())
            .tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var s1 = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Conflict mode: avoiding");
    }

    // ── capability description rendering ──────────────────────────────────

    @Test
    void descriptor_payload_includes_description_for_all_formats() {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("s")
            .capabilities(List.of(AgentCapability.builder()
                .name("review").description("Reviews code for quality").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();

        for (var format : new RenderFormat[]{MARKDOWN, PROSE, A2A_CARD}) {
            var payload = pipeline.buildDescriptorPayload(desc, format);
            var cap = payload.get("capabilities").get(0);
            assertThat(cap.has("description")).as("description present in " + format).isTrue();
            assertThat(cap.get("description").asText()).isEqualTo("Reviews code for quality");
        }
    }

    @Test
    void descriptor_payload_omits_description_when_null() {
        var desc = fullDescriptor();
        var payload = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        var cap = payload.get("capabilities").get(0);
        assertThat(cap.has("description")).isFalse();
    }

    @Test
    void structural_markdown_includes_description_after_name() {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("reviewer")
            .capabilities(List.of(AgentCapability.builder()
                .name("code-review").description("Reviews code for quality")
                .inputTypes(List.of("code")).outputTypes(List.of("review")).build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(
            pipeline.buildStage1(desc, ctx), Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("**code-review** — Reviews code for quality");
    }

    @Test
    void structural_prose_includes_description_in_parentheses() {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("reviewer")
            .capabilities(List.of(
                AgentCapability.builder().name("code-review")
                    .description("Reviews code for quality").build(),
                AgentCapability.builder().name("test-writing").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var ctx = AgentPromptContext.forFormat(PROSE);
        var result = pipeline.assemble(
            pipeline.buildStage1(desc, ctx), Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("code-review (Reviews code for quality)");
        assertThat(result.content()).contains("test-writing");
        assertThat(result.content()).doesNotContain("test-writing (");
    }

    @Test
    void a2a_card_declared_descriptions_satisfy_completeness_without_enrichment() {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("engineer")
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("pipeline-orchestration")
                    .description("Designs and operates data pipelines").build(),
                AgentCapability.builder()
                    .name("data-quality")
                    .description("Evaluates dataset completeness").build(),
                AgentCapability.builder()
                    .name("schema-evolution")
                    .description("Manages schema changes").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var card = renderA2aCard(desc);
        var caps = card.get("capabilities");
        assertThat(caps).hasSize(3);
        for (int i = 0; i < caps.size(); i++) {
            var cap = caps.get(i);
            assertThat(cap.has("description"))
                .as("capability %d (%s) has description", i, cap.get("name").asText())
                .isTrue();
            assertThat(cap.get("description").asText())
                .as("capability %d description is non-blank", i)
                .isNotBlank();
        }
        assertThat(caps.get(0).get("description").asText()).isEqualTo("Designs and operates data pipelines");
        assertThat(caps.get(1).get("description").asText()).isEqualTo("Evaluates dataset completeness");
        assertThat(caps.get(2).get("description").asText()).isEqualTo("Manages schema changes");
    }

    @Test
    void a2a_card_uses_declared_description_when_no_enrichment() {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("reviewer")
            .capabilities(List.of(AgentCapability.builder()
                .name("review").description("Declared description").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var card = renderA2aCard(desc);
        assertThat(card.at("/capabilities/0/description").asText()).isEqualTo("Declared description");
    }

    @Test
    void a2a_card_enriched_description_wins_over_declared() throws Exception {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("reviewer")
            .capabilities(List.of(AgentCapability.builder()
                .name("review").description("Declared description").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var enrichment = Optional.of(new A2AEnrichment(
            List.of(new A2AEnrichment.CapabilityNarrative("review", "Enriched description"))));
        var ctx = AgentPromptContext.forFormat(A2A_CARD);
        var result = pipeline.assemble(
            pipeline.buildStage1(desc, ctx), Optional.empty(), enrichment, desc, ctx);
        var card = MAPPER.readTree(result.content());
        assertThat(card.at("/capabilities/0/description").asText()).isEqualTo("Enriched description");
    }

    @Test
    void a2a_card_blank_enriched_description_falls_through_to_declared() throws Exception {
        var desc = AgentDescriptor.builder()
            .agentId("a1").name("Agent").version("1.0").provider("p")
            .modelFamily("m").modelVersion("v").slot("reviewer")
            .capabilities(List.of(AgentCapability.builder()
                .name("review").description("Declared description").build()))
            .disposition(AgentDisposition.builder()
                .socialOrient("independent").ruleFollowing("strict")
                .riskAppetite("conservative").autonomy("directed").build())
            .tenancyId("default").build();
        var enrichment = Optional.of(new A2AEnrichment(
            List.of(new A2AEnrichment.CapabilityNarrative("review", ""))));
        var ctx = AgentPromptContext.forFormat(A2A_CARD);
        var result = pipeline.assemble(
            pipeline.buildStage1(desc, ctx), Optional.empty(), enrichment, desc, ctx);
        var card = MAPPER.readTree(result.content());
        assertThat(card.at("/capabilities/0/description").asText()).isEqualTo("Declared description");
    }

    // ── A2A card assembly ────────────────────────────────────────────────────

    private com.fasterxml.jackson.databind.JsonNode renderA2aCard(final AgentDescriptor desc) {
        final var ctx = AgentPromptContext.forFormat(A2A_CARD);
        final var s1 = pipeline.buildStage1(desc, ctx);
        final var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        try {
            return MAPPER.readTree(result.content());
        } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A2A card is not valid JSON: " + result.content(), e);
        }
    }

    // buildDescriptorPayload — slot via domainVocabulary fallback

    @Test
    void descriptor_payload_slot_vocab_via_domain_vocabulary_fallback() {
        vocab.register(TestSlotTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .domainVocabulary("urn:test:slot") // no slotVocabulary — must fall through
            .slot("reviewer")
            .tenancyId("t").build();
        var node = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        assertThat(node.get("slotVocabularyName").asText()).isEqualTo("Test Slot Vocab");
        assertThat(node.get("slotLabel").asText()).isEqualTo("Reviewer");
    }

    // slot block

    @Test
    void a2a_card_capability_includes_all_numeric_and_type_fields() {
        var card = renderA2aCard(fullDescriptor());
        var cap  = card.get("capabilities").get(0);
        assertThat(cap.get("qualityHint").asDouble()).isEqualTo(0.95);
        assertThat(cap.get("latencyHintP50Ms").asLong()).isEqualTo(150L);
        assertThat(cap.get("costHint").asText()).isEqualTo("low");
        assertThat(cap.get("epistemicDomains").get("java").asDouble()).isEqualTo(0.95);
        assertThat(cap.get("inputTypes").get(0).asText()).isEqualTo("code");
        assertThat(cap.get("outputTypes").get(0).asText()).isEqualTo("review");
    }

    @Test
    void a2a_card_capability_numeric_fields_absent_when_null() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .capabilities(List.of(AgentCapability.builder().name("simple-cap").build()))
            .tenancyId("t").build();
        var cap = renderA2aCard(desc).get("capabilities").get(0);
        assertThat(cap.get("name").asText()).isEqualTo("simple-cap");
        assertThat(cap.has("qualityHint")).isFalse();
        assertThat(cap.has("latencyHintP50Ms")).isFalse();
        assertThat(cap.has("costHint")).isFalse();
        assertThat(cap.has("epistemicDomains")).isFalse();
        assertThat(cap.has("inputTypes")).isFalse();
        assertThat(cap.has("outputTypes")).isFalse();
    }

    @Test
    void a2a_card_slot_value_always_present() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("reviewer").tenancyId("t").build();
        var card = renderA2aCard(desc);
        assertThat(card.get("slot").get("value").asText()).isEqualTo("reviewer");
    }

    @Test
    void a2a_card_slot_includes_vocab_fields_when_slot_vocabulary_registered() {
        vocab.register(TestSlotTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slotVocabulary("urn:test:slot").slot("reviewer").tenancyId("t").build();
        var slot = renderA2aCard(desc).get("slot");
        assertThat(slot.get("value").asText()).isEqualTo("reviewer");
        assertThat(slot.get("label").asText()).isEqualTo("Reviewer");
        assertThat(slot.get("vocabularyUri").asText()).isEqualTo("urn:test:slot");
        assertThat(slot.get("vocabularyName").asText()).isEqualTo("Test Slot Vocab");
    }

    @Test
    void a2a_card_slot_includes_vocab_fields_via_domain_vocabulary_fallback() {
        vocab.register(TestSlotTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .domainVocabulary("urn:test:slot") // no slotVocabulary — fallback via vocabUriForSlot()
            .slot("reviewer").tenancyId("t").build();
        var slot = renderA2aCard(desc).get("slot");
        assertThat(slot.get("vocabularyUri").asText()).isEqualTo("urn:test:slot");
        assertThat(slot.get("vocabularyName").asText()).isEqualTo("Test Slot Vocab");
        assertThat(slot.get("label").asText()).isEqualTo("Reviewer");
    }

    @Test
    void a2a_card_slot_omits_vocab_fields_when_no_vocabulary() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("reviewer").tenancyId("t").build();
        var slot = renderA2aCard(desc).get("slot");
        assertThat(slot.has("vocabularyUri")).isFalse();
        assertThat(slot.has("vocabularyName")).isFalse();
        assertThat(slot.has("label")).isFalse();
    }

    // disposition block

    @Test
    void a2a_card_disposition_axis_present_when_value_set() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var disp = renderA2aCard(desc).get("disposition");
        assertThat(disp.has("socialOrient")).isTrue();
        assertThat(disp.get("socialOrient").get("values").get(0).get("term").asText()).isEqualTo("independent");
    }

    @Test
    void a2a_card_disposition_axis_omitted_when_value_null() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var disp = renderA2aCard(desc).get("disposition");
        assertThat(disp.has("ruleFollowing")).isFalse();
        assertThat(disp.has("riskAppetite")).isFalse();
        assertThat(disp.has("conflictMode")).isFalse();
    }

    @Test
    void a2a_card_disposition_with_delegation_only_emits_can_delegate_no_frameworks() {
        // delegation is primitive boolean — always has a value; all String axes null
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .disposition(AgentDisposition.builder().build()) // all axes null, delegation=false
            .tenancyId("t").build();
        var card = renderA2aCard(desc);
        var disp = card.get("disposition");
        assertThat(disp.has("canDelegate")).isTrue();
        assertThat(disp.get("canDelegate").asBoolean()).isFalse();
        assertThat(disp.has("socialOrient")).isFalse();
        assertThat(disp.has("ruleFollowing")).isFalse();
        assertThat(card.has("frameworks")).isFalse();
    }

    @Test
    void a2a_card_disposition_includes_vocab_uri_and_name_when_registered() {
        vocab.register(TestDispTerm.class);
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary("urn:test:disp")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var axis = renderA2aCard(desc).get("disposition").get("socialOrient");
        assertThat(axis.get("vocabularyUri").asText()).isEqualTo("urn:test:disp");
        assertThat(axis.get("vocabularyName").asText()).isEqualTo("Test Disposition Vocab");
        assertThat(axis.get("values").get(0).get("label").asText()).isEqualTo("Independent");
        assertThat(axis.has("description")).isFalse();
        assertThat(axis.has("vocabularyDescription")).isFalse();
    }

    @Test
    void a2a_card_disposition_omits_vocab_fields_when_no_uri() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("custom-value").build())
                                  .tenancyId("t").build();
        var axis = renderA2aCard(desc).get("disposition").get("socialOrient");
        assertThat(axis.get("values").get(0).get("term").asText()).isEqualTo("custom-value");
        assertThat(axis.has("vocabularyUri")).isFalse();
        assertThat(axis.has("vocabularyName")).isFalse();
        assertThat(axis.get("values").get(0).has("label")).isFalse();
    }

    @Test
    void a2a_card_disposition_null_produces_no_disposition_block() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s").tenancyId("t").build();
        var card = renderA2aCard(desc);
        assertThat(card.has("disposition")).isFalse();
    }

    // frameworks array

    @Test
    void a2a_card_frameworks_lists_instantiated_vocabularies() {
        vocab.register(TestSlotTerm.class);
        vocab.register(TestDispTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .slotVocabulary("urn:test:slot").slot("reviewer")
            .dispositionVocabulary("urn:test:disp")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var frameworks = renderA2aCard(desc).get("frameworks");
        assertThat(frameworks.isArray()).isTrue();
        assertThat(frameworks.size()).isEqualTo(2);
        // slot-first ordering
        assertThat(frameworks.get(0).get("uri").asText()).isEqualTo("urn:test:slot");
        assertThat(frameworks.get(1).get("uri").asText()).isEqualTo("urn:test:disp");
    }

    @Test
    void a2a_card_frameworks_deduplicates_same_uri() {
        vocab.register(TestDispTerm.class);
        // same URI on slot vocab AND disposition vocab
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .slotVocabulary("urn:test:disp").slot("reviewer")
            .dispositionVocabulary("urn:test:disp")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var frameworks = renderA2aCard(desc).get("frameworks");
        assertThat(frameworks.isArray()).isTrue();
        assertThat(frameworks.size()).isEqualTo(1);
        assertThat(frameworks.get(0).get("uri").asText()).isEqualTo("urn:test:disp");
    }

    @Test
    void a2a_card_frameworks_omitted_when_no_vocabularies() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s").tenancyId("t").build();
        assertThat(renderA2aCard(desc).has("frameworks")).isFalse();
    }

    @Test
    void a2a_card_frameworks_excludes_unregistered_uri_present_in_axis() {
        // unregistered URI → absent from frameworks but present as vocabularyUri in axis object
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .dispositionVocabulary("urn:test:unregistered")
            .disposition(AgentDisposition.builder().socialOrient("custom").build())
            .tenancyId("t").build();
        var card = renderA2aCard(desc);
        // not in frameworks
        assertThat(card.has("frameworks")).isFalse();
        // but IS present as vocabularyUri in the axis object
        assertThat(card.get("disposition").get("socialOrient").get("vocabularyUri").asText())
            .isEqualTo("urn:test:unregistered");
    }

    @Test
    void a2a_card_frameworks_omits_description_when_blank() {
        vocab.register(TestNoDescTerm.class);
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N")
            .slotVocabulary("urn:test:nodesc").slot("term")
            .tenancyId("t").build();
        var frameworks = renderA2aCard(desc).get("frameworks");
        assertThat(frameworks.size()).isEqualTo(1);
        assertThat(frameworks.get(0).get("name").asText()).isEqualTo("No Description Vocab");
        assertThat(frameworks.get(0).has("description")).isFalse();
    }

    @Test
    void a2a_card_frameworks_includes_uri_from_domain_vocabulary_fallback() {
        vocab.register(TestDispTerm.class);
        // domainVocabulary only — no dispositionVocabulary, no slotVocabulary
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .domainVocabulary("urn:test:disp")
            .disposition(AgentDisposition.builder().socialOrient("independent").build())
            .tenancyId("t").build();
        var frameworks = renderA2aCard(desc).get("frameworks");
        assertThat(frameworks.isArray()).isTrue();
        assertThat(frameworks.size()).isEqualTo(1);
        assertThat(frameworks.get(0).get("uri").asText()).isEqualTo("urn:test:disp");
    }

    @Test
    void structural_prose_includes_all_disposition_axes() {
        var desc = AgentDescriptor.builder()
            .agentId("a").name("N").slot("s")
            .disposition(AgentDisposition.builder()
                .socialOrient("independent")
                .ruleFollowing("strict")
                .riskAppetite("conservative")
                .autonomy("directed")
                .conflictMode("avoiding")
                .build())
            .tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(PROSE);
        var s1 = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Social orientation: independent");
        assertThat(result.content()).contains("Rule following: strict");
        assertThat(result.content()).contains("Risk appetite: conservative");
        assertThat(result.content()).contains("Autonomy: directed");
        assertThat(result.content()).contains("Conflict mode: avoiding");
    }

    @Test
    void a2a_card_capability_includes_excluded_domains_when_populated() {
        var desc = AgentDescriptor.builder()
            .agentId("renderer-excl").name("Agent").slot("reviewer").tenancyId("t1")
            .capabilities(List.of(AgentCapability.builder()
                .name("security-review")
                .excludedDomains(java.util.Set.of("rust", "go"))
                .build()))
            .build();
        var card = renderA2aCard(desc);
        var capNode = card.get("capabilities").get(0);
        assertThat(capNode.get("excludedDomains")).isNotNull();
        var excluded = new java.util.HashSet<String>();
        capNode.get("excludedDomains").forEach(n -> excluded.add(n.asText()));
        assertThat(excluded).containsExactlyInAnyOrder("rust", "go");
    }

    @Test
    void a2a_card_capability_excludes_domains_absent_when_null() {
        var desc = AgentDescriptor.builder()
            .agentId("renderer-no-excl").name("Agent").slot("reviewer").tenancyId("t1")
            .capabilities(List.of(AgentCapability.builder().name("code-review").build()))
            .build();
        var card = renderA2aCard(desc);
        var capNode = card.get("capabilities").get(0);
        assertThat(capNode.has("excludedDomains")).isFalse();
    }

    @Test
    void markdown_render_does_not_include_excluded_domains() {
        var desc = AgentDescriptor.builder()
            .agentId("renderer-md-excl").name("Agent").slot("reviewer").tenancyId("t1")
            .capabilities(List.of(AgentCapability.builder()
                .name("code-review")
                .excludedDomains(java.util.Set.of("rust"))
                .build()))
            .build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var s1 = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).doesNotContain("excludedDomains");
        assertThat(result.content()).doesNotContain("rust");
    }

    static AgentDescriptor descriptorWithGoalsAndConstraints() {
        return AgentDescriptor.builder()
                              .agentId("hooded-claw").name("The Hooded Claw").slot("villain").tenancyId("wacky-manor")
                              .goals(List.of(
                                      new AgentGoal("win-treasure", "Win the treasure hunt",
                                                    GoalPriority.SECONDARY, Visibility.PUBLIC),
                                      new AgentGoal("eliminate-penelope", "Kill Penelope Pitstop",
                                                    GoalPriority.PRIMARY, Visibility.PRIVATE)))
                              .constraints(List.of(
                                      new AgentConstraint("elaborate-schemes", "Schemes must be elaborate", Visibility.PUBLIC, ConstraintSeverity.SOFT),
                                      new AgentConstraint("never-break-cover", "Never reveal your true identity", Visibility.PRIVATE, ConstraintSeverity.HARD)))
                              .build();
    }

    @Test
    void markdown_renders_objectives_section_sorted_by_priority_then_name() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("## Objectives");
        int primaryIdx   = result.content().indexOf("Kill Penelope");
        int secondaryIdx = result.content().indexOf("Win the treasure");
        assertThat(primaryIdx).isGreaterThan(-1);
        assertThat(secondaryIdx).isGreaterThan(-1);
        assertThat(primaryIdx).isLessThan(secondaryIdx);
    }

    @Test
    void markdown_renders_constraints_sorted_by_severity_then_name() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("## Constraints");
        assertThat(result.content()).contains("**[HARD]**");
        assertThat(result.content()).contains("**[SOFT]**");
        int hardIdx = result.content().indexOf("[HARD]");
        int softIdx = result.content().indexOf("[SOFT]");
        assertThat(hardIdx).isLessThan(softIdx);
    }

    @Test
    void prose_renders_constraints_grouped_by_severity() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(PROSE);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("Hard constraints:");
        assertThat(result.content()).contains("Also:");
    }

    @Test
    void a2a_card_includes_constraint_severity() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(A2A_CARD);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("\"severity\":\"SOFT\"");
    }


    @Test
    void markdown_objectives_before_disposition() {
        var d = AgentDescriptor.builder()
                               .agentId("a").name("n").slot("s").tenancyId("t")
                               .goals(List.of(new AgentGoal("g", "d", GoalPriority.PRIMARY, Visibility.PUBLIC)))
                               .disposition(AgentDisposition.builder().autonomy("high").build())
                               .build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        int objectivesIdx  = result.content().indexOf("## Objectives");
        int dispositionIdx = result.content().indexOf("## How You Operate");
        assertThat(objectivesIdx).isLessThan(dispositionIdx);
    }

    @Test
    void prose_renders_goals_and_constraints() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(PROSE);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("Kill Penelope");
        assertThat(result.content()).contains("Schemes must be elaborate");
    }

    @Test
    void a2a_card_excludes_private_goals_and_constraints() {
        var d   = descriptorWithGoalsAndConstraints();
        var ctx = AgentPromptContext.forFormat(A2A_CARD);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("win-treasure");
        assertThat(result.content()).doesNotContain("eliminate-penelope");
        assertThat(result.content()).contains("elaborate-schemes");
        assertThat(result.content()).doesNotContain("never-break-cover");
    }

    @Test
    void empty_goals_omits_objectives_section() {
        var d = AgentDescriptor.builder()
                               .agentId("a").name("n").slot("s").tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).doesNotContain("Objectives");
    }

    @Test
    void empty_constraints_omits_constraints_section() {
        var d = AgentDescriptor.builder()
                               .agentId("a").name("n").slot("s").tenancyId("t").build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).doesNotContain("Constraints");
    }

    @Test
    void a2a_card_omits_goals_key_when_no_public_goals() {
        var d = AgentDescriptor.builder()
                               .agentId("a").name("n").slot("s").tenancyId("t")
                               .goals(List.of(new AgentGoal("secret", "d", GoalPriority.PRIMARY, Visibility.PRIVATE)))
                               .build();
        var ctx = AgentPromptContext.forFormat(A2A_CARD);
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).doesNotContain("\"goals\"");
    }

    @Test
    void combined_standing_and_current_goals_both_render() {
        var d = AgentDescriptor.builder()
                               .agentId("a").name("n").slot("s").tenancyId("t")
                               .goals(List.of(new AgentGoal("find-diamond", "Find it", GoalPriority.PRIMARY, Visibility.PUBLIC)))
                               .build();
        var ctx = AgentPromptContext.forFormat(MARKDOWN)
                                    .withGoal(GoalContext.of("Search room 3 for clues"));
        var result = pipeline.assemble(pipeline.buildStage1(d, ctx),
                                       Optional.empty(), Optional.empty(), d, ctx);
        assertThat(result.content()).contains("## Objectives");
        assertThat(result.content()).contains("Find it");
        assertThat(result.content()).contains("## Current Goal");
        assertThat(result.content()).contains("Search room 3");
    }

    @Test
    void descriptor_payload_includes_goals_for_cache_key() {
        var d       = descriptorWithGoalsAndConstraints();
        var payload = pipeline.buildDescriptorPayload(d, MARKDOWN);
        assertThat(payload.has("goals")).isTrue();
        assertThat(payload.has("constraints")).isTrue();
    }

// ── weighted axis rendering ──────────────────────────────────────────

    @Test
    void weighted_markdown_single_value_renders_without_weight() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Social orientation: independent");
        assertThat(result.content()).doesNotContain("primarily");
        assertThat(result.content()).doesNotContain("tendencies");
    }

    @Test
    void weighted_markdown_two_values_renders_primarily_with_tendencies() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder()
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.7),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.3))
                                                               .build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Social orientation: primarily independent (0.7), with collaborative tendencies (0.3)");
    }

    @Test
    void weighted_markdown_three_values_omits_tendencies_phrasing() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder()
                                                               .riskAppetite(
                                                                       new io.casehub.eidos.api.DispositionValue("bold", 0.5),
                                                                       new io.casehub.eidos.api.DispositionValue("measured", 0.3),
                                                                       new io.casehub.eidos.api.DispositionValue("conservative", 0.2))
                                                               .build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Risk appetite: primarily bold (0.5), measured (0.3), conservative (0.2)");
        assertThat(result.content()).doesNotContain("tendencies");
    }

    @Test
    void weighted_markdown_with_vocab_resolves_labels() {
        vocab.register(io.casehub.eidos.vocab.ConscientiousnessTerm.class);
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.ConscientiousnessTerm.URI)
                                  .disposition(AgentDisposition.builder()
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.7),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.3))
                                                               .build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("primarily Independent (0.7), with Collaborative tendencies (0.3)");
    }

    @Test
    void weighted_prose_two_values_renders_weighted() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder()
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.7),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.3))
                                                               .build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(PROSE);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("primarily independent (0.7), with collaborative tendencies (0.3)");
    }

    // ── A2A weighted axis format ─────────────────────────────────────────

    @Test
    void a2a_card_axis_uses_values_array_format() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder()
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.7),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.3))
                                                               .build())
                                  .tenancyId("t").build();
        var axis = renderA2aCard(desc).get("disposition").get("socialOrient");
        assertThat(axis.has("values")).isTrue();
        assertThat(axis.get("values").isArray()).isTrue();
        assertThat(axis.get("values").size()).isEqualTo(2);
        assertThat(axis.get("values").get(0).get("term").asText()).isEqualTo("independent");
        assertThat(axis.get("values").get(0).get("weight").asDouble()).isEqualTo(0.7);
        assertThat(axis.get("values").get(1).get("term").asText()).isEqualTo("collaborative");
        assertThat(axis.get("values").get(1).get("weight").asDouble()).isEqualTo(0.3);
    }

    @Test
    void a2a_card_single_value_axis_uses_values_array() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var axis = renderA2aCard(desc).get("disposition").get("socialOrient");
        assertThat(axis.has("values")).isTrue();
        assertThat(axis.get("values").isArray()).isTrue();
        assertThat(axis.get("values").size()).isEqualTo(1);
        assertThat(axis.get("values").get(0).get("term").asText()).isEqualTo("independent");
        assertThat(axis.get("values").get(0).get("weight").asDouble()).isEqualTo(1.0);
    }

    // ── cognitive profile rendering ──────────────────────────────────────

    @Test
    void jungian_profile_renders_cognitive_style_before_disposition() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        vocab.register(io.casehub.eidos.vocab.ConscientiousnessTerm.class);
        vocab.register(io.casehub.eidos.vocab.ThomasKilmannTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.INTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder()
                                                               .dispositionProfile(profile)
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.65),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.35))
                                                               .build())
                                  .tenancyId("t").build();
        var ctx         = AgentPromptContext.forFormat(MARKDOWN);
        var s1          = pipeline.buildStage1(desc, ctx);
        var result      = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        int cogStyleIdx = result.content().indexOf("## Cognitive Style");
        int howOpIdx    = result.content().indexOf("## How You Operate");
        assertThat(cogStyleIdx).as("Cognitive Style section present").isGreaterThan(-1);
        assertThat(howOpIdx).as("How You Operate section present").isGreaterThan(-1);
        assertThat(cogStyleIdx).as("Cognitive Style before How You Operate").isLessThan(howOpIdx);
    }

    @Test
    void cognitive_style_identifies_dominant_and_auxiliary_from_weights() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.INTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("Dominant — Introverted Thinking (Ti)");
        assertThat(result.content()).contains("Auxiliary — Extraverted Intuition (Ne)");
    }

    @Test
    void cognitive_style_includes_compensation_instructions() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.INTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("compensatory");
    }

    @Test
    void cognitive_style_includes_orientation_hint_for_extraverted_dominant() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.ENTJ.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).containsIgnoringCase("outward");
    }

    @Test
    void cognitive_style_includes_orientation_hint_for_introverted_dominant() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.INTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).containsIgnoringCase("inward");
    }

    @Test
    void cognitive_style_includes_intuitive_perception_for_n_type() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.ENTJ.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("**Perception — Intuitive:**");
        assertThat(result.content()).containsIgnoringCase("patterns");
    }

    @Test
    void cognitive_style_includes_sensing_perception_for_s_type() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.ESTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).contains("**Perception — Concrete:**");
        assertThat(result.content()).containsIgnoringCase("concrete");
    }


    @Test
    void empty_profile_no_cognitive_style_section() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var ctx    = AgentPromptContext.forFormat(MARKDOWN);
        var s1     = pipeline.buildStage1(desc, ctx);
        var result = pipeline.assemble(s1, Optional.empty(), Optional.empty(), desc, ctx);
        assertThat(result.content()).doesNotContain("Cognitive Style");
    }

    // ── A2A disposition profile ──────────────────────────────────────────

    @Test
    void a2a_card_includes_disposition_profile_with_roles_and_mbti() {
        vocab.register(io.casehub.eidos.vocab.JungianFunctionTerm.class);
        vocab.register(io.casehub.eidos.vocab.MbtiTypeTerm.class);
        var profile = io.casehub.eidos.vocab.MbtiTypeTerm.INTP.defaultProfile();
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var card = renderA2aCard(desc);
        assertThat(card.has("dispositionProfile")).isTrue();
        var profileNode = card.get("dispositionProfile");
        assertThat(profileNode.get("vocabulary").asText()).isEqualTo(io.casehub.eidos.vocab.JungianFunctionTerm.URI);
        assertThat(profileNode.get("functions").isArray()).isTrue();
        assertThat(profileNode.get("functions").get(0).get("role").asText()).isEqualTo("dominant");
        assertThat(profileNode.get("functions").get(1).get("role").asText()).isEqualTo("auxiliary");
        assertThat(profileNode.get("derivedMbtiType").asText()).isEqualTo("INTP");
    }

    @Test
    void a2a_card_no_disposition_profile_when_empty() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder().socialOrient("independent").build())
                                  .tenancyId("t").build();
        var card = renderA2aCard(desc);
        assertThat(card.has("dispositionProfile")).isFalse();
    }

    // ── descriptor payload weighted values ───────────────────────────────

    @Test
    void descriptor_payload_includes_all_weighted_axis_values() {
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .disposition(AgentDisposition.builder()
                                                               .socialOrient(
                                                                       new io.casehub.eidos.api.DispositionValue("independent", 0.7),
                                                                       new io.casehub.eidos.api.DispositionValue("collaborative", 0.3))
                                                               .build())
                                  .tenancyId("t").build();
        var payload = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        var axis    = payload.get("disposition").get("socialOrient");
        assertThat(axis.has("values")).isTrue();
        assertThat(axis.get("values").size()).isEqualTo(2);
    }

    @Test
    void descriptor_payload_includes_disposition_profile() {
        var profile = List.of(
                new io.casehub.eidos.api.DispositionValue("ti", 0.35),
                new io.casehub.eidos.api.DispositionValue("ne", 0.20));
        var desc = AgentDescriptor.builder()
                                  .agentId("a").name("N").slot("s")
                                  .dispositionVocabulary(io.casehub.eidos.vocab.JungianFunctionTerm.URI)
                                  .disposition(AgentDisposition.builder().dispositionProfile(profile).build())
                                  .tenancyId("t").build();
        var payload = pipeline.buildDescriptorPayload(desc, MARKDOWN);
        assertThat(payload.get("disposition").has("dispositionProfile")).isTrue();
    }
}
