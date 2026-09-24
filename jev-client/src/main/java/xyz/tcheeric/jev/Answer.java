package xyz.tcheeric.jev;

import java.util.Map;

/**
 * An answer from the evaluator, typed to match the question that produced it.
 *
 * <p>{@link Noul} carries a probability and no confidence, and that omission is the point
 * (ADR 0003, story 107). A boolean judgement's probability already expresses how sure the
 * evaluator is; a second number beside it invites a caller to gate on the wrong one. Because
 * the field does not exist, code that tries to read confidence off a noul answer fails to
 * compile rather than failing in production.</p>
 *
 * <p>{@link Choice} and {@link Score} keep the whole probability distribution rather than the
 * winner alone. Where to cut the distribution is the consumer's decision and never the
 * library's (ADR 0002), so the library hands back everything it was told and applies no
 * threshold of its own.</p>
 */
public sealed interface Answer permits Answer.Noul, Answer.Choice, Answer.Score {

    String name();

    record Noul(String name, double probability) implements Answer {
        public Noul {
            Names.require(name, "answer name");
            Names.requireProbability(probability, "noul probability");
        }
    }

    record Choice(String name, Map<String, Double> distribution, String chosen, double confidence)
            implements Answer {
        public Choice {
            Names.require(name, "answer name");
            Names.require(chosen, "chosen option");
            if (distribution == null || distribution.isEmpty()) {
                throw new JevException("read-answer", "invalid-argument",
                        "a choice answer must carry its distribution, for answer: " + name);
            }
            if (!distribution.containsKey(chosen)) {
                throw new JevException("read-answer", "malformed-response",
                        "chosen option '" + chosen + "' is absent from the distribution of answer: " + name);
            }
            distribution.forEach((option, p) -> Names.requireProbability(p, "probability of option " + option));
            Names.requireProbability(confidence, "choice confidence");
            distribution = Map.copyOf(distribution);
        }
    }

    /**
     * A rating on the question's scale. The distribution is keyed by the points of that scale.
     */
    record Score(String name, Map<Integer, Double> distribution, int value, double confidence)
            implements Answer {
        public Score {
            Names.require(name, "answer name");
            if (distribution == null || distribution.isEmpty()) {
                throw new JevException("read-answer", "invalid-argument",
                        "a score answer must carry its distribution, for answer: " + name);
            }
            if (!distribution.containsKey(value)) {
                throw new JevException("read-answer", "malformed-response",
                        "value " + value + " is absent from the distribution of answer: " + name);
            }
            distribution.forEach((point, p) -> Names.requireProbability(p, "probability of point " + point));
            Names.requireProbability(confidence, "score confidence");
            distribution = Map.copyOf(distribution);
        }
    }
}
