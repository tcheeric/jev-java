package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    @Test
    void waitsGrowExponentiallyAcrossAttempts() {
        // With no jitter the schedule is exactly the geometric series, which is what makes the
        // backoff a backoff rather than a fixed pause. Asserted arithmetically, with no sleep.
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(100), 2.0d, Duration.ofSeconds(60), 0.0d);

        assertThat(policy.backoffFor(1, 0.0d)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffFor(2, 0.0d)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffFor(3, 0.0d)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.backoffFor(4, 0.0d)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void theWaitIsCappedSoAnExponentialDoesNotBecomeAnHour() {
        // Without a ceiling, a handful of retries reaches an absurd wait. The cap keeps the
        // worst case bounded and predictable for a caller holding a request open.
        RetryPolicy policy = new RetryPolicy(10, Duration.ofMillis(100), 2.0d, Duration.ofSeconds(1), 0.0d);

        assertThat(policy.backoffFor(9, 0.0d)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void jitterShortensTheWaitAndNeverPushesItPastTheCap() {
        // Jitter spreads a fleet of clients that were rate limited at the same instant. It
        // subtracts, so the configured cap remains a true ceiling.
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(1000), 2.0d, Duration.ofSeconds(60), 0.5d);

        assertThat(policy.backoffFor(1, 1.0d)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoffFor(1, 0.0d)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.backoffFor(1, 0.4d)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void onlyRateLimitingAndOverloadAreWorthRepeatingTheRequestFor() {
        // 429 and 529 say "later"; 401 and 422 say "no". Retrying a "no" spends the caller's
        // rate limit to be told the same thing again.
        assertThat(RetryPolicy.retryable(429)).isTrue();
        assertThat(RetryPolicy.retryable(529)).isTrue();
        assertThat(RetryPolicy.retryable(401)).isFalse();
        assertThat(RetryPolicy.retryable(422)).isFalse();
        assertThat(RetryPolicy.retryable(500)).isFalse();
        assertThat(RetryPolicy.retryable(503)).isFalse();
    }

    @Test
    void aMultiplierBelowOneIsRefusedBecauseItWouldShrinkTheWait() {
        // A sub-unit multiplier would retry harder the worse things got, which is the opposite
        // of backing off.
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMillis(100), 0.5d, Duration.ofSeconds(1), 0.0d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("multiplier");
    }

    @Test
    void aCapBelowTheInitialWaitIsRefused() {
        // A ceiling under the floor is a misconfiguration that would silently truncate the very
        // first wait.
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(10), 2.0d, Duration.ofSeconds(1), 0.0d))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("maxBackoff");
    }
}
