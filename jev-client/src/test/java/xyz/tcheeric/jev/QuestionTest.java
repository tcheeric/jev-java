package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionTest {

    @Test
    void theThreeQuestionShapesAreTheOnlyOnesThatExist() {
        // The interface is sealed, so the set of question types is closed and a switch over it
        // is exhaustive without a default that quietly swallows a new shape.
        assertThat(Question.class.isSealed()).isTrue();
        assertThat(Question.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Question.Noul.class, Question.Choice.class, Question.Score.class);
    }

    @Test
    void aChoiceWithOnlyOneOptionIsRefused() {
        // A choice among one is not a choice; it is a leading question, and its answer would be
        // a foregone conclusion the caller might mistake for a judgement.
        assertThatThrownBy(() -> new Question.Choice("intent", "pick one", List.of("refund")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("at least two options");
    }

    @Test
    void aChoiceWithMoreOptionsThanTheApiAcceptsIsRefusedBeforeTheRoundTrip() {
        // The API caps a choice at 255 options. Catching it here names the problem instead of
        // spending a request on a 422.
        List<String> options = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            options.add("option-" + i);
        }

        assertThatThrownBy(() -> new Question.Choice("big", "pick", options))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("at most 255");
    }

    @Test
    void aChoiceListingTheSameOptionTwiceIsRefusedRatherThanSilentlyCollapsed() {
        // Options become map keys on the wire. A duplicate would vanish, and the caller would
        // believe it had offered a choice it never did.
        assertThatThrownBy(() -> new Question.Choice("dup", "pick", List.of("a", "b", "a")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void aChoiceOptionMayCarryNoDescriptionBecauseTheApiAllowsNull() {
        // The API documents null as "this option needs no extra detail", so a null must survive
        // construction where Map.copyOf would have thrown.
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("billing", "Payments, invoicing, refunds");
        criteria.put("other", null);

        Question.Choice question = new Question.Choice("department", "Which team?", criteria);

        assertThat(question.criteria()).containsEntry("other", null).containsKeys("billing");
    }

    @Test
    void aScoreNeedsBetweenTwoAndTenLevels() {
        // The API's documented bounds. One level cannot be rated against, and eleven is refused.
        assertThatThrownBy(() -> new Question.Score("s", "how bad", List.of("only")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("between 2 and 10");
        assertThatThrownBy(() -> new Question.Score("s", "how bad",
                List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("between 2 and 10");
    }

    @Test
    void aBlankScoreLevelIsRefusedBecauseTheModelWouldBeRatingAgainstNothing() {
        // An empty rubric step is a caller bug that would silently distort every answer.
        assertThatThrownBy(() -> new Question.Score("s", "how bad", List.of("fine", " ")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("score level 1");
    }

    @Test
    void aBlankQuestionNameIsRefusedBecauseTheNameIsTheKey() {
        // The name is how the answer is retrieved. A blank one makes the answer unreachable.
        assertThatThrownBy(() -> new Question.Noul("  ", "this is true"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("question name");
    }

    @Test
    void instructionsMayBeStructuredButNotAnArbitraryObject() {
        // A map or list is a documented way to attach reference data. Any other object would be
        // serialized by reflection and send the evaluator whatever its getters happen to expose.
        Question.Noul structured = new Question.Noul("dup", Map.of(
                "candidate", Map.of("name", "John Smith"),
                "question", "Is this the same person as `candidate`?"));

        assertThat(structured.instructions()).isInstanceOf(Map.class);
        assertThatThrownBy(() -> new Question.Noul("q", new StringBuilder("true?")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("string, a map or a list");
        assertThatThrownBy(() -> new Question.Noul("q", Map.of("when", java.time.Instant.EPOCH)))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("not JSON");
    }

    @Test
    void structuredInstructionsAreCopiedDeeplySoALaterEditCannotChangeTheQuestion() {
        // A caller mutating the nested map it passed in must not retroactively change what was
        // asked, including one level down.
        Map<String, Object> inner = new LinkedHashMap<>(Map.of("name", "John"));
        Map<String, Object> instructions = new LinkedHashMap<>(Map.of("candidate", inner, "question", "same?"));
        Question.Noul question = new Question.Noul("q", instructions);

        inner.put("name", "Mallory");
        instructions.put("question", "different?");

        @SuppressWarnings("unchecked")
        Map<String, Object> asked = (Map<String, Object>) question.instructions();
        assertThat(asked).containsEntry("question", "same?");
        assertThat(asked.get("candidate")).isEqualTo(Map.of("name", "John"));
    }

    @Test
    void theOptionListIsCopiedSoALaterEditCannotChangeTheQuestionAlreadyAsked() {
        // A caller mutating its own list after building the question must not retroactively
        // change what was asked.
        List<String> options = new ArrayList<>(List.of("refund", "sales"));
        Question.Choice question = new Question.Choice("intent", "pick one", options);

        options.add("support");

        assertThat(question.criteria()).containsOnlyKeys("refund", "sales");
    }
}
