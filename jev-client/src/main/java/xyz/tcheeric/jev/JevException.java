package xyz.tcheeric.jev;

/**
 * Every failure this library raises, unchecked, carrying the operation that failed and the
 * kind of failure so a caller can branch on the kind without parsing a message.
 */
public class JevException extends RuntimeException {

    private final String operation;
    private final String failureType;

    public JevException(String operation, String failureType, String message) {
        this(operation, failureType, message, null);
    }

    public JevException(String operation, String failureType, String message, Throwable cause) {
        super(operation + "/" + failureType + ": " + message, cause);
        this.operation = operation;
        this.failureType = failureType;
    }

    public String operation() {
        return operation;
    }

    public String failureType() {
        return failureType;
    }
}
