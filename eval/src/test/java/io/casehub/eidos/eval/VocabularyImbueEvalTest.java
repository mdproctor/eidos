package io.casehub.eidos.eval;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.eval.FunctionActivationJudge.FunctionScenario;
import io.casehub.eidos.runtime.registrar.DescriptorCollector;
import io.casehub.eidos.vocab.BelbinTerm;
import io.casehub.eidos.vocab.DiscTerm;
import io.casehub.eidos.vocab.MbtiTypeTerm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("eval")
class VocabularyImbueEvalTest {

    @Inject SystemPromptRenderer renderer;
    @Inject VocabularyRegistry vocabRegistry;
    @Inject MbtiAlignmentJudge mbtiJudge;
    @Inject FunctionActivationJudge functionJudge;
    @Inject PersonalityEvolutionJudge evolutionJudge;
    @Inject DispositionPresenceJudge presenceJudge;
    @Inject DispositionSignalStore signalStore;

    static final List<FunctionScenario> ENTJ_SCENARIOS = List.of(
            new FunctionScenario("te",
                    "You must organize a team to complete a time-critical project. Describe your approach."),
            new FunctionScenario("ni",
                    "You notice a pattern in recent events that others have missed. What do you see and what does it mean?")
    );

    private String renderPrompt(AgentDescriptor descriptor) {
        var derived = DescriptorCollector.deriveDispositionAxes(descriptor, vocabRegistry);
        return renderer.render(derived, AgentPromptContext.forFormat(RenderFormat.MARKDOWN)).content();
    }

    // ── Single vocabulary: Jungian ───────────────────────────────────

    @Test
    void jungian_entj_mbti_alignment() {
        var prompt = renderPrompt(VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ));
        var result = mbtiJudge.evaluate(prompt, "ENTJ");
        assertThat(result.overallAligned()).isTrue();}

    @Test
    void jungian_entj_function_activation() {
        var prompt = renderPrompt(VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ));
        var result = functionJudge.evaluate(prompt, "ENTJ", ENTJ_SCENARIOS);
        assertThat(result.taa()).isGreaterThanOrEqualTo(0.5);}

    @Test
    void jungian_intp_personality_evolution() {
        var descriptor = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.INTP);
        var derived    = DescriptorCollector.deriveDispositionAxes(descriptor, vocabRegistry);
        var result     = evolutionJudge.evaluate(derived, "ne", 4);
        assertThat(result.evolutionType()).isNotNull();}

    // ── Single vocabulary: Belbin ────────────────────────────────────

    @Test
    void belbin_shaper_presence() {
        var prompt = renderPrompt(VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER));
        var result = presenceJudge.evaluate(prompt, BelbinTerm.SHAPER.label(), BelbinTerm.SHAPER.description());
        assertThat(result.aligned()).isTrue();}

    @Test
    void belbin_teamworker_presence() {
        var prompt = renderPrompt(VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.TEAMWORKER));
        var result = presenceJudge.evaluate(prompt, BelbinTerm.TEAMWORKER.label(), BelbinTerm.TEAMWORKER.description());
        assertThat(result.aligned()).isTrue();}

    // ── Single vocabulary: DISC ──────────────────────────────────────

    @Test
    void disc_dominance_presence() {
        var prompt = renderPrompt(VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry));
        var result = presenceJudge.evaluate(prompt, DiscTerm.DOMINANCE.label(), DiscTerm.DOMINANCE.description());
        assertThat(result.aligned()).isTrue();}

    // ── Single vocabulary: Conscientiousness ─────────────────────────

    @Test
    void conscientiousness_strict_presence() {
        var prompt = renderPrompt(VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict",
                       DispositionAxis.RISK_APPETITE, "conservative")));
        var result = presenceJudge.evaluate(prompt, "Strict rule-following",
                                            "Follows rules and procedures strictly, values compliance and predictability");
        assertThat(result.aligned()).isTrue();}

    // ── Pairwise: Jungian + Belbin ───────────────────────────────────

    @Test
    void composite_jungian_belbin_mbti_aligned() {
        var comp = VocabularyImbueFixtures.composite(
                VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ),
                VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER));
        var prompt     = renderPrompt(comp);
        var mbtiResult = mbtiJudge.evaluate(prompt, "ENTJ");
        assertThat(mbtiResult.overallAligned()).isTrue();}

    @Test
    void composite_jungian_belbin_shaper_present() {
        var comp = VocabularyImbueFixtures.composite(
                VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ),
                VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER));
        var prompt         = renderPrompt(comp);
        var presenceResult = presenceJudge.evaluate(prompt, BelbinTerm.SHAPER.label(), BelbinTerm.SHAPER.description());
        assertThat(presenceResult.aligned()).isTrue();}

    // ── Pairwise: Belbin + DISC ──────────────────────────────────────

    @Test
    void composite_belbin_disc_both_present() {
        var comp = VocabularyImbueFixtures.composite(
                VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry),
                VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER));
        var prompt          = renderPrompt(comp);
        var shaperResult    = presenceJudge.evaluate(prompt, BelbinTerm.SHAPER.label(), BelbinTerm.SHAPER.description());
        var dominanceResult = presenceJudge.evaluate(prompt, DiscTerm.DOMINANCE.label(), DiscTerm.DOMINANCE.description());
        assertThat(shaperResult.aligned()).isTrue();
        assertThat(dominanceResult.aligned()).isTrue();
    }
}
