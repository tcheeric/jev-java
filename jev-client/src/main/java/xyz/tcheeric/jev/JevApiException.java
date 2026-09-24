package xyz.tcheeric.jev;

/**
 * An error the API itself reported, carrying the HTTP status and what the API said, so a caller
 * can tell a rejected token from a rejected question without reading prose.
 */
public final class JevApiException extends JevException {

    private final int status;
    private final String apiErrorType;
    private final String apiMessage;

    JevApiException(int status, String apiErrorType, String apiMessage) {
        super("evaluate", "api-error-" + status,
                "the evaluator returned " + status + " (" + apiErrorType + "): " + apiMessage);
        this.status = status;
        this.apiErrorType = apiErrorType;
        this.apiMessage = apiMessage;
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
}
