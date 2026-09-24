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
        assertThat(components).doesNotContain("confidence");
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
    void aScoreAnswerKeepsTheDistributionAcrossTheScalePoints() {
        // A score is a distribution over the points of the scale, plus the point the evaluator
        // settled on and how sure it is.
        Answer.Score answer = new Answer.Score("severity",
                Map.of(1, 0.05d, 2, 0.1d, 3, 0.15d, 4, 0.5d, 5, 0.2d), 4, 0.66d);

        assertThat(answer.distribution()).containsEntry(4, 0.5d).hasSize(5);
        assertThat(answer.value()).isEqualTo(4);
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
    void aChosenOptionMissingFromItsOwnDistributionIsRefused() {
        // An evaluator that names a winner absent from the distribution it just reported has
        // contradicted itself, and the caller should never see the contradiction.
        assertThatThrownBy(() -> new Answer.Choice("intent", Map.of("refund", 1.0d), "sales", 0.9d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("absent from the distribution");
    }

    @Test
    void aScoreValueMissingFromItsOwnDistributionIsRefused() {
        // Same contradiction, on the score shape.
        assertThatThrownBy(() -> new Answer.Score("severity", Map.of(1, 1.0d), 4, 0.5d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("absent from the distribution");
    }

    @Test
    void aChoiceDistributionIsUnmodifiableOnceHandedToTheCaller() {
        // One consumer must not be able to edit an answer another consumer is reading.
        Answer.Choice answer = new Answer.Choice("intent", Map.of("a", 0.5d, "b", 0.5d), "a", 0.5d);

        assertThatThrownBy(() -> answer.distribution().put("c", 0.1d))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
