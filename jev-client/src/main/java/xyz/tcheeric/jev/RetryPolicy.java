package xyz.tcheeric.jev;

import java.time.Duration;

/**
 * How hard, and how politely, to retry.
 *
 * <p>Only rate limiting (429), evaluator overload (529) and a failed connection are retried. A
 * 401 or a 422 is a statement about the request itself, and repeating it merely spends the
 * caller's rate limit to be told the same thing again.</p>
 *
 * <p>{@link #backoffFor(int, double)} is a pure function of the attempt number and a random
 * fraction supplied by the caller. Keeping the randomness out of the policy is what lets a unit
 * test assert the schedule without a clock and without a sleep.</p>
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        double multiplier,
        Duration maxBackoff,
        double jitterFactor) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new JevException("configure", "invalid-argument",
                    "maxAttempts must be at least 1, got: " + maxAttempts);
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new JevException("configure", "invalid-argument",
                    "initialBackoff must not be negative, got: " + initialBackoff);
        }
        if (multiplier < 1.0d) {
            throw new JevException("configure", "invalid-argument",
                    "multiplier must be at least 1.0 for the backoff to be exponential, got: " + multiplier);
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new JevException("configure", "invalid-argument",
                    "maxBackoff must be at least initialBackoff, got: " + maxBackoff);
        }
        if (!(jitterFactor >= 0.0d && jitterFactor <= 1.0d)) {
            throw new JevException("configure", "invalid-argument",
                    "jitterFactor must lie in 0..1, got: " + jitterFactor);
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(4, Duration.ofMillis(500), 2.0d, Duration.ofSeconds(30), 0.5d);
    }

    /**
     * Whether a status is worth repeating the request for. Deliberately narrow: an unknown 5xx
     * is not retried, because a client that retries everything turns an evaluator's bad day
     * into an outage.
     */
    public static boolean retryable(int status) {
        return status == 429 || status == 529;
    }

    /**
     * The wait before the attempt numbered {@code attempt} (1 means the wait after the first
     * failure). {@code randomFraction} is in 0..1 and comes from the caller's random source.
     */
    public Duration backoffFor(int attempt, double randomFraction) {
        if (attempt < 1) {
            throw new JevException("retry", "invalid-argument",
                    "attempt must be at least 1, got: " + attempt);
        }
        double exponential = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1d);
        double capped = Math.min(exponential, maxBackoff.toMillis());
        // Jitter shrinks the wait rather than extending it, so the cap stays a real ceiling and
        // a fleet of clients that failed together does not retry together.
        double jittered = capped * (1.0d - jitterFactor * randomFraction);
        return Duration.ofMillis((long) jittered);
    }
}
