package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0002 as an executable rule rather than a note in a document.
 */
class NoDecisionHelpersTest {

    private static final List<Class<?>> PUBLIC_SURFACE = List.of(
            JevClient.class, JevConfig.class, JevWireCodec.class, RetryPolicy.class,
            Evaluation.class, Usage.class,
            Question.class, Question.Noul.class, Question.Choice.class, Question.Score.class,
            Answer.class, Answer.Noul.class, Answer.Choice.class, Answer.Score.class);

    // Names of the shapes ADR 0002 forbids: counting, date comparison, and threshold or
    // decision helpers. Four consumers are ranked for this library and each owns its own
    // thresholds, so a helper here would silently decide for all of them.
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "count", "howmany", "tally",
            "before", "after", "olderthan", "newerthan", "daysbetween", "elapsedsince",
            "threshold", "abovethreshold", "belowthreshold", "exceeds",
            "isspam", "shouldescalate", "decide", "decision", "verdict", "passes", "classify");

    @Test
    void noPublicMethodInTheLibraryCountsComparesDatesOrTurnsAnAnswerIntoADecision() {
        // Walks the library's whole public surface and fails on any method whose name suggests
        // it makes a judgement. A convenience such as isSpam() is exactly what must not exist
        // here, so this test is the guard that keeps it from being added later by accident.
        List<String> offenders = PUBLIC_SURFACE.stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> FORBIDDEN_FRAGMENTS.stream()
                        .anyMatch(fragment -> name.toLowerCase(Locale.ROOT).contains(fragment)))
                .toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void noPublicMethodReturnsABareBooleanJudgementAboutAnAnswer() {
        // A boolean-returning method on an answer type would be a flattened distribution by
        // another name, which is the decision this library refuses to make.
        List<String> offenders = List.of(Answer.Noul.class, Answer.Choice.class, Answer.Score.class).stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getReturnType() == boolean.class)
                .map(Method::getName)
                .filter(name -> !name.equals("equals"))
                .toList();

        assertThat(offenders).isEmpty();
    }
}
