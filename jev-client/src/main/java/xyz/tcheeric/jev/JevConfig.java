package xyz.tcheeric.jev;

import java.net.URI;
import java.time.Duration;

/**
 * Everything the client needs that is not a question: where the evaluator lives, which model
 * answers, how long to wait, and how hard to retry.
 *
 * <p>The bearer token is passed in rather than read here, but the intended source is the
 * environment. It is never logged and never appears in an exception message.</p>
 */
public record JevConfig(
        URI baseUri,
        String apiToken,
        String model,
        Duration requestTimeout,
        RetryPolicy retryPolicy) {

    /**
     * A pinned, dated model. A moving alias such as {@code jev-latest} is refused, because an
     * upstream model swap would silently change answers a consumer has already calibrated
     * thresholds against.
     */
    public static final String DEFAULT_MODEL = "jev-1-20260101";

    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public JevConfig {
        if (baseUri == null) {
            throw new JevException("configure", "invalid-argument", "baseUri must not be null");
        }
        if (apiToken == null || apiToken.isBlank()) {
            throw new JevException("configure", "invalid-argument", "an api token is required");
        }
        Names.require(model, "model");
        if (model.endsWith("-latest") || model.equals("latest")) {
            throw new JevException("configure", "invalid-argument",
                    "the model must be pinned to a version, not a moving alias: " + model);
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new JevException("configure", "invalid-argument",
                    "requestTimeout must be positive, got: " + requestTimeout);
        }
        if (retryPolicy == null) {
            throw new JevException("configure", "invalid-argument", "retryPolicy must not be null");
        }
    }

    public static JevConfig of(URI baseUri, String apiToken) {
        return new JevConfig(baseUri, apiToken, DEFAULT_MODEL, DEFAULT_REQUEST_TIMEOUT, RetryPolicy.defaults());
    }

    public JevConfig withModel(String model) {
        return new JevConfig(baseUri, apiToken, model, requestTimeout, retryPolicy);
    }

    public JevConfig withRequestTimeout(Duration requestTimeout) {
        return new JevConfig(baseUri, apiToken, model, requestTimeout, retryPolicy);
    }

    public JevConfig withRetryPolicy(RetryPolicy retryPolicy) {
        return new JevConfig(baseUri, apiToken, model, requestTimeout, retryPolicy);
    }

    /**
     * Keeps the token out of logs and crash dumps. A record's generated toString would print it.
     */
    @Override
    public String toString() {
        return "JevConfig[baseUri=" + baseUri + ", apiToken=<redacted>, model=" + model
                + ", requestTimeout=" + requestTimeout + ", retryPolicy=" + retryPolicy + "]";
    }
}
