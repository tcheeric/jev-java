package xyz.tcheeric.jev;

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
}
