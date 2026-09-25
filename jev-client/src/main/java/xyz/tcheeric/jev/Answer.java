package xyz.tcheeric.jev;

import java.util.Map;

/**
 * An answer from the evaluator, typed to match the question that produced it.
 *
 * <p>{@link Noul} carries a probability and no confidence, and that omission is the point
 * (ADR 0003, story 107). A boolean judgement's probability already expresses how sure the
 * evaluator is; a second number beside it invites a caller to gate on the wrong one. Because
 * the field does not exist, code that tries to read confidence off a noul answer fails to
 * compile rather than failing in production. The published API agrees: its noul answer has no
 * confidence either.</p>
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

    /**
     * @param distribution every option the question offered, mapped to its probability
     * @param chosen       the option the evaluator ranked highest
     */
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
     * A rating against the question's ordered levels. Levels are identified by their index in
     * the question's level list, starting at 0.
     *
     * <p>{@code score} is the probability-weighted mean of those indices, so it is fractional and
     * can land between two levels: 1.05 on a three-level rubric means "almost exactly the middle
     * level, leaning slightly up". It is on the scale 0 to {@code legend.size() - 1}, not 0 to 1;
     * a consumer that wants a unit interval divides by that itself, since whether a linear
     * rescale is meaningful depends on how evenly it spaced its own levels.</p>
     *
     * @param distribution each level index mapped to its probability
     * @param legend       each level index mapped back to the description the evaluator was given
     */
    record Score(String name, double score, Map<Integer, String> legend, Map<Integer, Double> distribution,
                 double confidence) implements Answer {
        public Score {
            Names.require(name, "answer name");
            if (distribution == null || distribution.isEmpty()) {
                throw new JevException("read-answer", "invalid-argument",
                        "a score answer must carry its distribution, for answer: " + name);
            }
            if (legend == null || !legend.keySet().equals(distribution.keySet())) {
                throw new JevException("read-answer", "malformed-response",
                        "the legend of score answer '" + name + "' does not name the same levels as its distribution: "
                                + (legend == null ? null : legend.keySet()) + " against " + distribution.keySet());
            }
            distribution.forEach((level, p) -> Names.requireProbability(p, "probability of level " + level));
            int lowest = distribution.keySet().stream().min(Integer::compare).orElseThrow();
            int highest = distribution.keySet().stream().max(Integer::compare).orElseThrow();
            if (!(score >= lowest && score <= highest)) {
                throw new JevException("read-answer", "malformed-response",
                        "score " + score + " of answer '" + name + "' lies outside its levels "
                                + lowest + ".." + highest);
            }
            Names.requireProbability(confidence, "score confidence");
            legend = Map.copyOf(legend);
            distribution = Map.copyOf(distribution);
        }
    }
}
