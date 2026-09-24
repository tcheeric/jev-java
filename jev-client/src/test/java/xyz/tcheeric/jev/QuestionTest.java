package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void aScoreWithAnInvertedScaleIsRefused() {
        // A scale whose minimum is not below its maximum cannot be rated on.
        assertThatThrownBy(() -> new Question.Score("severity", "how bad", 5, 1))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("min < max");
    }

    @Test
    void aBlankQuestionNameIsRefusedBecauseTheNameIsTheKey() {
        // The name is how the answer is retrieved. A blank one makes the answer unreachable.
        assertThatThrownBy(() -> new Question.Noul("  ", "this is true"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("question name");
    }

    @Test
    void theOptionListIsCopiedSoALaterEditCannotChangeTheQuestionAlreadyAsked() {
        // A caller mutating its own list after building the question must not retroactively
        // change what was asked.
        List<String> options = new java.util.ArrayList<>(List.of("refund", "sales"));
        Question.Choice question = new Question.Choice("intent", "pick one", options);

        options.add("support");

        assertThat(question.options()).containsExactly("refund", "sales");
    }
}
