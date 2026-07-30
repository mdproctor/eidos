package io.casehub.eidos.eval;

import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.runtime.registrar.DescriptorCollector;
import io.casehub.eidos.vocab.BelbinTerm;
import io.casehub.eidos.vocab.DiscTerm;
import io.casehub.eidos.vocab.MbtiTypeTerm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class VocabularyImbueStructuralTest {

    @Inject SystemPromptRenderer renderer;
    @Inject VocabularyRegistry vocabRegistry;

    // ── Jungian ──────────────────────────────────────────────────────

    @Test
    void jungian_entj_has_8_function_profile() {
        var desc = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var derived = DescriptorCollector.deriveDispositionAxes(desc, vocabRegistry);
        assertThat(derived.disposition().dispositionProfile()).hasSize(8);
        assertThat(derived.disposition().dispositionProfile().get(0).term()).isEqualTo("te");
        assertThat(derived.disposition().dispositionProfile().get(0).weight()).isEqualTo(0.35);
        assertThat(derived.disposition().dispositionProfile().get(1).term()).isEqualTo("ni");
        assertThat(derived.disposition().dispositionProfile().get(1).weight()).isEqualTo(0.20);
    }

    @Test
    void jungian_entj_derives_all_5_axes() {
        var desc = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var derived = DescriptorCollector.deriveDispositionAxes(desc, vocabRegistry);
        assertThat(derived.disposition().primaryTerm(DispositionAxis.SOCIAL_ORIENTATION)).isEqualTo("independent");
        assertThat(derived.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING)).isNotEmpty();
        assertThat(derived.disposition().primaryTerm(DispositionAxis.RISK_APPETITE)).isNotEmpty();
        assertThat(derived.disposition().primaryTerm(DispositionAxis.AUTONOMY)).isNotEmpty();
        assertThat(derived.disposition().primaryTerm(DispositionAxis.CONFLICT_MODE)).isNotEmpty();
    }

    @Test
    void jungian_prompt_contains_disposition_content() {
        var desc = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var derived = DescriptorCollector.deriveDispositionAxes(desc, vocabRegistry);
        var prompt = renderer.render(derived, AgentPromptContext.forFormat(RenderFormat.MARKDOWN));
        assertThat(prompt.content()).isNotEmpty();
        assertThat(prompt.content().toLowerCase()).containsAnyOf("independent", "thinking", "strict");
    }

    // ── Belbin ───────────────────────────────────────────────────────

    @Test
    void belbin_shaper_has_no_profile() {
        var desc = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        assertThat(desc.disposition().dispositionProfile()).isEmpty();
    }

    @Test
    void belbin_shaper_has_no_derived_axes() {
        var desc = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        for (var axis : DispositionAxis.values()) {
            assertThat(desc.disposition().get(axis)).as("axis " + axis).isEmpty();
        }
    }

    @Test
    void belbin_prompt_contains_slot_label() {
        var desc = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        var prompt = renderer.render(desc, AgentPromptContext.forFormat(RenderFormat.MARKDOWN));
        assertThat(prompt.content().toLowerCase()).contains("shaper");
    }

    // ── DISC ─────────────────────────────────────────────────────────

    @Test
    void disc_dominance_derives_all_5_axes() {
        var desc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        assertThat(desc.disposition().primaryTerm(DispositionAxis.SOCIAL_ORIENTATION)).isEqualTo("independent");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING)).isEqualTo("flexible");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RISK_APPETITE)).isEqualTo("bold");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.AUTONOMY)).isEqualTo("autonomous");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.CONFLICT_MODE)).isEqualTo("competing");
    }

    @Test
    void disc_descriptor_preserves_single_profile_entry() {
        var desc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        assertThat(desc.disposition().dispositionProfile()).hasSize(1);
    }

    // ── Conscientiousness ────────────────────────────────────────────

    @Test
    void conscientiousness_flat_axes_preserved() {
        var desc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict",
                       DispositionAxis.RISK_APPETITE, "conservative",
                       DispositionAxis.AUTONOMY, "directed"));
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING)).isEqualTo("strict");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RISK_APPETITE)).isEqualTo("conservative");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.AUTONOMY)).isEqualTo("directed");
    }

    @Test
    void conscientiousness_prompt_contains_axis_values() {
        var desc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict"));
        var prompt = renderer.render(desc, AgentPromptContext.forFormat(RenderFormat.MARKDOWN));
        assertThat(prompt.content().toLowerCase()).contains("strict");
    }

    // ── Pairwise: additive ───────────────────────────────────────────

    @Test
    void jungian_plus_belbin_both_signals_present() {
        var jungian = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var belbin = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        var comp = VocabularyImbueFixtures.composite(jungian, belbin);
        var derived = DescriptorCollector.deriveDispositionAxes(comp, vocabRegistry);
        assertThat(derived.disposition().primaryTerm(DispositionAxis.SOCIAL_ORIENTATION)).isNotEmpty();
        assertThat(derived.disposition().primaryTerm(DispositionAxis.CONFLICT_MODE)).isNotEmpty();
        assertThat(derived.slotVocabulary()).isEqualTo("urn:casehub:vocab:belbin");
        assertThat(derived.slot()).isEqualTo("shaper");
    }

    @Test
    void belbin_plus_disc_both_signals_present() {
        var belbin = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        var disc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        var comp = VocabularyImbueFixtures.composite(disc, belbin);
        assertThat(comp.disposition().primaryTerm(DispositionAxis.RISK_APPETITE)).isEqualTo("bold");
        assertThat(comp.slotVocabulary()).isEqualTo("urn:casehub:vocab:belbin");
        assertThat(comp.slot()).isEqualTo("shaper");
    }

    @Test
    void belbin_plus_conscientiousness_both_signals_present() {
        var consc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict"));
        var belbin = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.TEAMWORKER);
        var comp = VocabularyImbueFixtures.composite(consc, belbin);
        assertThat(comp.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING)).isEqualTo("strict");
        assertThat(comp.slotVocabulary()).isEqualTo("urn:casehub:vocab:belbin");
        assertThat(comp.slot()).isEqualTo("teamworker");
    }

    // ── Pairwise: redundant ──────────────────────────────────────────

    @Test
    void jungian_plus_disc_axes_conflict() {
        var jungian = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var jungianDerived = DescriptorCollector.deriveDispositionAxes(jungian, vocabRegistry);
        var disc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        assertThat(jungianDerived.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING))
                .isNotEqualTo(disc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING));
    }

    @Test
    void jungian_plus_conscientiousness_axes_differ() {
        var jungian = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var jungianDerived = DescriptorCollector.deriveDispositionAxes(jungian, vocabRegistry);
        var consc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "flexible",
                       DispositionAxis.RISK_APPETITE, "bold"));
        assertThat(jungianDerived.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING))
                .isNotEqualTo(consc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING));
    }

    @Test
    void disc_plus_conscientiousness_axes_differ() {
        var disc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        var consc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict"));
        assertThat(disc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING))
                .isNotEqualTo(consc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING));
    }
}
