package io.casehub.eidos.eval;

import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.eidos.vocab.BelbinTerm;
import io.casehub.eidos.vocab.DiscTerm;
import io.casehub.eidos.vocab.MbtiTypeTerm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class VocabularyImbueFixturesTest {

    @Inject VocabularyRegistry vocabRegistry;

    @Test
    void jungian_descriptor_has_profile_and_vocab_uri() {
        var desc = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        assertThat(desc.dispositionVocabulary()).isEqualTo("urn:casehub:vocab:jungian");
        assertThat(desc.disposition().dispositionProfile()).hasSize(8);
        assertThat(desc.disposition().dispositionProfile().get(0).term()).isEqualTo("te");
    }

    @Test
    void belbin_descriptor_has_slot_and_slot_vocab() {
        var desc = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        assertThat(desc.slotVocabulary()).isEqualTo("urn:casehub:vocab:belbin");
        assertThat(desc.slot()).isEqualTo("shaper");
        assertThat(desc.disposition().dispositionProfile()).isEmpty();
    }

    @Test
    void disc_descriptor_has_profile_and_vocab_uri() {
        var desc = VocabularyImbueFixtures.discDescriptor(DiscTerm.DOMINANCE, vocabRegistry);
        assertThat(desc.dispositionVocabulary()).isEqualTo("urn:casehub:vocab:disc");
        assertThat(desc.disposition().dispositionProfile()).hasSize(1);
        assertThat(desc.disposition().dispositionProfile().get(0).term()).isEqualTo("dominance");
    }

    @Test
    void conscientiousness_descriptor_has_flat_axes() {
        var desc = VocabularyImbueFixtures.conscientiousnessDescriptor(
                Map.of(DispositionAxis.RULE_FOLLOWING, "strict",
                       DispositionAxis.RISK_APPETITE, "conservative"));
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RULE_FOLLOWING)).isEqualTo("strict");
        assertThat(desc.disposition().primaryTerm(DispositionAxis.RISK_APPETITE)).isEqualTo("conservative");
        assertThat(desc.disposition().dispositionProfile()).isEmpty();
    }

    @Test
    void composite_merges_jungian_and_belbin() {
        var jungian = VocabularyImbueFixtures.jungianDescriptor(MbtiTypeTerm.ENTJ);
        var belbin = VocabularyImbueFixtures.belbinDescriptor(BelbinTerm.SHAPER);
        var comp = VocabularyImbueFixtures.composite(jungian, belbin);
        assertThat(comp.dispositionVocabulary()).isEqualTo("urn:casehub:vocab:jungian");
        assertThat(comp.disposition().dispositionProfile()).hasSize(8);
        assertThat(comp.slotVocabulary()).isEqualTo("urn:casehub:vocab:belbin");
        assertThat(comp.slot()).isEqualTo("shaper");
    }
}
