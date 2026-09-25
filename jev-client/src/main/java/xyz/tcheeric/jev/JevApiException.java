package xyz.tcheeric.jev;

import java.time.Duration;
import java.util.Optional;

/**
 * An error the API itself reported, carrying the HTTP status and what the API said, so a caller
 * can tell a rejected token from a rejected question without reading prose.
 */
public final class JevApiException extends JevException {

    private final int status;
    private final String apiErrorType;
    private final String apiMessage;
    private final String requestId;
    private final Duration retryAfter;

    JevApiException(int status, String apiErrorType, String apiMessage, String requestId,
                    Optional<Duration> retryAfter) {
        super("evaluate", "api-error-" + status,
                "the evaluator returned " + status + " (" + apiErrorType + "): " + apiMessage
                        + (requestId == null ? "" : " [request " + requestId + "]"));
        this.status = status;
        this.apiErrorType = apiErrorType;
        this.apiMessage = apiMessage;
        this.requestId = requestId;
        this.retryAfter = retryAfter.orElse(null);
    }

    public int status() {
        return status;
    }

    public String apiErrorType() {
        return apiErrorType;
    }

    public String apiMessage() {
        return apiMessage;
    }

    /**
     * The evaluator's identifier for the failed request, which is what its operators ask for.
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * How long the evaluator asked the caller to wait, when it said. Exposed because a wait
     * longer than the client is configured to hold a request open ends the retries, and the
     * caller may still want to reschedule the work for when the evaluator will take it.
     */
    public Optional<Duration> requestedWait() {
        return Optional.ofNullable(retryAfter);
    }
}
