package xyz.tcheeric.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question put to the evaluator about a state. The three permitted shapes mirror the
 * evaluator's own question types, so that a caller names a shape rather than describing one.
 *
 * <p>Every question carries a {@code name}. The name is the caller's key: it becomes the key of
 * the question in the request's {@code questions} map, comes back as the key of the answer, and
 * is how a fan-out of questions in one request is unpicked into typed answers. The evaluator does
 * not read it during inference, so it carries no meaning to the model.</p>
 *
 * <p>{@code instructions} and every criterion are typed {@link Object} because the API accepts a
 * string, an object or an array in each of those places, and splitting a long question into a
 * structured object is the documented way to point it at reference data. The records check the
 * shape at construction and hold a deep, unmodifiable copy (see {@link Names#requireDescription}).
 * A map passed in becomes a JSON object, a list a JSON array.</p>
 */
public sealed interface Question permits Question.Noul, Question.Choice, Question.Score {

    String name();

    Object instructions();

    /**
     * Is this true. The answer is a probability and nothing else (ADR 0003).
     *
     * <p>{@code whenTrue} and {@code whenFalse} optionally describe what a yes and a no mean.
     * Either may be null, and a question with neither sends no {@code criteria} at all.</p>
     */
    record Noul(String name, Object instructions, Object whenTrue, Object whenFalse) implements Question {
        public Noul {
            Names.require(name, "question name");
            instructions = Names.requireDescription(instructions, "noul instructions");
            whenTrue = whenTrue == null ? null : Names.requireDescription(whenTrue, "noul true criterion");
            whenFalse = whenFalse == null ? null : Names.requireDescription(whenFalse, "noul false criterion");
        }

        public Noul(String name, Object instructions) {
            this(name, instructions, null, null);
        }
    }

    /**
     * Pick one of a fixed set of options. {@code criteria} maps each option to a description of
     * it, or to null where the option's name says enough. Iteration order is the caller's order.
     */
    record Choice(String name, Object instructions, Map<String, ?> criteria) implements Question {

        /** The API's own ceiling, checked here so an oversized question fails before a round trip. */
        public static final int MAX_OPTIONS = 255;

        public Choice {
            Names.require(name, "question name");
            instructions = Names.requireDescription(instructions, "choice instructions");
            if (criteria == null || criteria.size() < 2) {
                throw new JevException("build-question", "invalid-argument",
                        "a choice question needs at least two options, got: "
                                + (criteria == null ? null : criteria.keySet()));
            }
            if (criteria.size() > MAX_OPTIONS) {
                throw new JevException("build-question", "invalid-argument",
                        "a choice question accepts at most " + MAX_OPTIONS + " options, got: " + criteria.size());
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            criteria.forEach((option, description) -> {
                Names.require(option, "choice option");
                copy.put(option, description == null ? null
                        : Names.requireDescription(description, "description of option " + option));
            });
            // Map.copyOf would refuse the null descriptions the API explicitly allows.
            criteria = Collections.unmodifiableMap(copy);
        }

        /**
         * Options that need no description beyond their names.
         */
        public Choice(String name, Object instructions, List<String> options) {
            this(name, instructions, undescribed(options));
        }

        private static Map<String, Object> undescribed(List<String> options) {
            if (options == null) {
                return null;
            }
            Map<String, Object> criteria = new LinkedHashMap<>();
            for (String option : options) {
                if (criteria.containsKey(option)) {
                    throw new JevException("build-question", "invalid-argument",
                            "a choice question lists the option '" + option + "' twice");
                }
                criteria.put(option, null);
            }
            return criteria;
        }
    }

    /**
     * Rate the state against an ordered rubric. {@code levels} runs from the bottom of the scale
     * to the top; the answer refers to each level by its index in this list.
     *
     * <p>The levels are descriptions, not numbers. The evaluator is asked which description fits
     * and reports a probability for each, so a numeric range such as 0 to 10 cannot be expressed
     * except by writing out every level.</p>
     */
    record Score(String name, Object instructions, List<?> levels) implements Question {

        public static final int MIN_LEVELS = 2;
        public static final int MAX_LEVELS = 10;

        public Score {
            Names.require(name, "question name");
            instructions = Names.requireDescription(instructions, "score instructions");
            if (levels == null || levels.size() < MIN_LEVELS || levels.size() > MAX_LEVELS) {
                throw new JevException("build-question", "invalid-argument",
                        "a score question needs between " + MIN_LEVELS + " and " + MAX_LEVELS
                                + " levels, got: " + (levels == null ? null : levels.size()));
            }
            for (int i = 0; i < levels.size(); i++) {
                Names.requireDescription(levels.get(i), "score level " + i);
            }
            levels = (List<?>) Names.requireDescription(levels, "score levels");
        }
    }
}
