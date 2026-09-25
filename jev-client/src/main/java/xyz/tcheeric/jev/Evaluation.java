package xyz.tcheeric.jev;

import java.util.Map;

/**
 * The answers to one fan-out of questions, keyed by the names the caller gave them, together
 * with what the evaluation cost.
 *
 * <p>The typed accessors exist so a caller who asked a score question gets a score answer
 * without casting. They retrieve; they do not judge. There is deliberately no method here that
 * counts answers, compares them, or turns one into a decision (ADR 0002): four consumers are
 * ranked for this library and each owns its own thresholds.</p>
 *
 * @param model the versioned model ID that actually answered, as the evaluator reported it. It is
 *              kept so a consumer can record which model produced each answer it calibrated on.
 */
public record Evaluation(String model, Map<String, Answer> answers, Usage usage) {

    public Evaluation {
        Names.require(model, "answering model");
        answers = Map.copyOf(answers);
        if (usage == null) {
            throw new JevException("read-answer", "malformed-response", "an evaluation must carry its usage");
        }
    }

    public Answer.Noul noul(String name) {
        return typed(name, Answer.Noul.class);
    }

    public Answer.Choice choice(String name) {
        return typed(name, Answer.Choice.class);
    }

    public Answer.Score score(String name) {
        return typed(name, Answer.Score.class);
    }

    private <A extends Answer> A typed(String name, Class<A> expected) {
        Answer answer = answers.get(name);
        if (answer == null) {
            throw new JevException("read-answer", "missing-answer",
                    "no answer named '" + name + "', the evaluation carries: " + answers.keySet());
        }
        if (!expected.isInstance(answer)) {
            throw new JevException("read-answer", "answer-type-mismatch",
                    "answer '" + name + "' is a " + answer.getClass().getSimpleName()
                            + ", not a " + expected.getSimpleName());
        }
        return expected.cast(answer);
    }
}
