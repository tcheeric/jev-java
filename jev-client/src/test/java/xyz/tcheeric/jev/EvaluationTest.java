package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationTest {

    private final Evaluation evaluation = new Evaluation(Map.of(
            "is_spam", new Answer.Noul("is_spam", 0.9d),
            "intent", new Answer.Choice("intent", Map.of("refund", 0.8d, "sales", 0.2d), "refund", 0.7d)),
            new Usage(10L, 5L, 15L));

    @Test
    void aCallerRetrievesEachAnswerByTheNameItGaveTheQuestion() {
        // The name the caller chose is the key on the way out as well as the way in, so a
        // fan-out is unpicked without positional guessing.
        assertThat(evaluation.noul("is_spam").probability()).isEqualTo(0.9d);
        assertThat(evaluation.choice("intent").chosen()).isEqualTo("refund");
    }

    @Test
    void askingForTheWrongShapeFailsWithTheShapeItActuallyIs() {
        // Reading a noul as a choice is a caller bug. The message names both shapes so the bug
        // is obvious rather than surfacing as a ClassCastException elsewhere.
        assertThatThrownBy(() -> evaluation.choice("is_spam"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("Noul")
                .hasMessageContaining("Choice");
    }

    @Test
    void askingForAnAnswerThatIsNotThereNamesWhatIsThere() {
        // An evaluator that dropped a question should not look like a null. The failure lists
        // what did come back.
        assertThatThrownBy(() -> evaluation.noul("severity"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("no answer named 'severity'")
                .hasMessageContaining("is_spam");
    }

    @Test
    void theAnswerMapHandedToACallerCannotBeEdited() {
        // Answers are shared. One consumer must not be able to mutate what another reads.
        assertThatThrownBy(() -> evaluation.answers().remove("is_spam"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theEvaluationExposesNoMethodThatTurnsAnAnswerIntoADecision() {
        // ADR 0002 at the retrieval surface: accessors retrieve, they do not judge.
        List<String> methods = java.util.Arrays.stream(Evaluation.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(methods).containsExactlyInAnyOrder(
                "answers", "usage", "noul", "choice", "score", "equals", "hashCode", "toString");
    }
}
