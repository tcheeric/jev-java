package xyz.tcheeric.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Argument checks shared by the question and answer records. Kept package private because a
 * caller has no reason to validate on the library's behalf.
 */
final class Names {

    private Names() {
    }

    static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new JevException("build-question", "invalid-argument", what + " must not be blank");
        }
        return value;
    }

    static double requireProbability(double value, String what) {
        if (!(value >= 0.0d && value <= 1.0d)) {
            throw new JevException("read-answer", "invalid-argument",
                    what + " must lie in 0..1, got: " + value);
        }
        return value;
    }

    /**
     * Checks that an instruction or criterion is something the API accepts (a string, an object
     * or an array, per the published reference) and returns a deep, unmodifiable copy of it.
     *
     * <p>Anything else is refused here rather than handed to Jackson, which would happily turn an
     * arbitrary bean into JSON by reflection and send the evaluator whatever getters it found.
     * The copy is deep because a caller editing its own map after building a question must not
     * retroactively change what was asked.</p>
     */
    static Object requireDescription(Object value, String what) {
        if (value == null) {
            throw new JevException("build-question", "invalid-argument", what + " must not be null");
        }
        if (value instanceof String text && text.isBlank()) {
            throw new JevException("build-question", "invalid-argument", what + " must not be blank");
        }
        if (!(value instanceof String || value instanceof Map<?, ?> || value instanceof List<?>)) {
            throw new JevException("build-question", "invalid-argument",
                    what + " must be a string, a map or a list, got: " + value.getClass().getName());
        }
        return freeze(value, what);
    }

    private static Object freeze(Object value, String what) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Number number -> number;
            case Boolean bool -> bool;
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, nested) -> {
                    if (!(key instanceof String name)) {
                        throw new JevException("build-question", "invalid-argument",
                                what + " contains a map key that is not a string: " + key);
                    }
                    copy.put(name, freeze(nested, what));
                });
                yield Collections.unmodifiableMap(copy);
            }
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(nested -> copy.add(freeze(nested, what)));
                yield Collections.unmodifiableList(copy);
            }
            default -> throw new JevException("build-question", "invalid-argument",
                    what + " contains a value that is not JSON: " + value.getClass().getName());
        };
    }
}
