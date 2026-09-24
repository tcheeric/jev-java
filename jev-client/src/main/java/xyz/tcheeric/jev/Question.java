package xyz.tcheeric.jev;

import java.util.List;

/**
 * A question put to the evaluator about a state. The three permitted shapes mirror the
 * evaluator's own question types, so that a caller names a shape rather than describing one.
 *
 * <p>Every question carries a {@code name}. The name is the caller's key: it travels out with
 * the question and comes back on the answer, and it is how a fan-out of questions in one
 * request is unpicked into typed answers.</p>
 */
public sealed interface Question permits Question.Noul, Question.Choice, Question.Score {

    String name();

    /**
     * Is this statement true. The answer is a probability and nothing else (ADR 0003).
     */
    record Noul(String name, String statement) implements Question {
        public Noul {
            Names.require(name, "question name");
            Names.require(statement, "noul statement");
        }
    }

    /**
     * Pick one of a fixed set of options.
     */
    record Choice(String name, String prompt, List<String> options) implements Question {
        public Choice {
            Names.require(name, "question name");
            Names.require(prompt, "choice prompt");
            if (options == null || options.size() < 2) {
                throw new JevException("build-question", "invalid-argument",
                        "a choice question needs at least two options, got: " + options);
            }
            options = List.copyOf(options);
        }
    }

    /**
     * Rate the state on an inclusive integer scale.
     */
    record Score(String name, String prompt, int min, int max) implements Question {
        public Score {
            Names.require(name, "question name");
            Names.require(prompt, "score prompt");
            if (min >= max) {
                throw new JevException("build-question", "invalid-argument",
                        "a score question needs min < max, got min=" + min + " max=" + max);
            }
        }
    }
}
