package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerTest {

    @Test
    void aNoulAnswerCarriesAProbabilityAndTheTypeOffersNoConfidenceToReadOffIt() {
        // A noul answer is a probability and nothing more. The absence of a confidence field is
        // the design (ADR 0003): a caller cannot gate on a number that does not exist, so the
        // record has exactly two components and neither of them is a confidence.
        Answer.Noul answer = new Answer.Noul("is_spam", 0.93d);

        List<String> components = java.util.Arrays.stream(Answer.Noul.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(answer.probability()).isEqualTo(0.93d);
        assertThat(components).containsExactly("name", "probability");
    }

    @Test
    void aChoiceAnswerKeepsTheWholeDistributionRatherThanTheWinnerAlone() {
        // The library hands back every option's probability. Flattening to the winner would
        // make the library the owner of a threshold that belongs to the consumer (ADR 0002).
        Answer.Choice answer = new Answer.Choice("intent",
                Map.of("refund", 0.7d, "support", 0.2d, "sales", 0.1d), "refund", 0.82d);

        assertThat(answer.distribution()).containsOnlyKeys("refund", "support", "sales");
        assertThat(answer.chosen()).isEqualTo("refund");
        assertThat(answer.confidence()).isEqualTo(0.82d);
    }

    @Test
    void aScoreAnswerCarriesAFractionalScoreItsLegendAndTheDistributionAcrossLevels() {
        // The documented example: 1.05 on a three-level rubric lands between two levels, which
        // an integer could not say. The legend maps each index back to the caller's words.
        Answer.Score answer = new Answer.Score("frustration", 1.05d,
                Map.of(0, "Calm", 1, "Frustrated", 2, "Very angry"),
                Map.of(0, 0.0d, 1, 0.95d, 2, 0.05d), 0.92d);

        assertThat(answer.score()).isEqualTo(1.05d);
        assertThat(answer.legend()).containsEntry(1, "Frustrated");
        assertThat(answer.distribution()).containsEntry(1, 0.95d).hasSize(3);
        assertThat(answer.confidence()).isEqualTo(0.92d);
    }

    @Test
    void aProbabilityOutsideZeroToOneIsRefused() {
        // An out-of-range probability is a corrupt answer, not a usable one, and is refused at
        // construction rather than surfacing as nonsense arithmetic in a consumer.
        assertThatThrownBy(() -> new Answer.Noul("is_spam", 1.4d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("0..1");
    }

    @Test
    void aNotANumberProbabilityIsRefusedRatherThanSlippingPastEveryComparison() {
        // NaN fails every comparison, so a consumer's "p > 0.8" would silently be false forever.
        assertThatThrownBy(() -> new Answer.Noul("is_spam", Double.NaN))
                .isInstanceOf(JevException.class);
    }

    @Test
    void aChosenOptionMissingFromItsOwnDistributionIsRefused() {
        // An evaluator that names a winner absent from the distribution it just reported has
        // contradicted itself, and the caller should never see the contradiction.
        assertThatThrownBy(() -> new Answer.Choice("intent", Map.of("refund", 1.0d), "sales", 0.9d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("absent from the distribution");
    }

    @Test
    void aScoreOutsideItsOwnLevelsIsRefused() {
        // A weighted mean of level indices cannot leave the range of those indices. One that
        // does is corrupt, and would skew any consumer that rescales it.
        assertThatThrownBy(() -> new Answer.Score("s", 2.5d,
                Map.of(0, "low", 1, "high"), Map.of(0, 0.5d, 1, 0.5d), 0.5d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("outside its levels");
    }

    @Test
    void aLegendThatDisagreesWithTheDistributionIsRefused() {
        // A level with a probability but no description, or the reverse, leaves the caller
        // unable to say what the evaluator meant.
        assertThatThrownBy(() -> new Answer.Score("s", 0.5d,
                Map.of(0, "low"), Map.of(0, 0.5d, 1, 0.5d), 0.5d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("legend");
    }

    @Test
    void aChoiceDistributionIsUnmodifiableOnceHandedToTheCaller() {
        // One consumer must not be able to edit an answer another consumer is reading.
        Answer.Choice answer = new Answer.Choice("intent", Map.of("a", 0.5d, "b", 0.5d), "a", 0.5d);

        assertThatThrownBy(() -> answer.distribution().put("c", 0.1d))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
