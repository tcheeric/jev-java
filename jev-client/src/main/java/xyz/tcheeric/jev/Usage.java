package xyz.tcheeric.jev;

/**
 * What the evaluator reports it spent answering. Returned rather than discarded, because a
 * consumer that pays per token needs to see the bill it just incurred.
 */
public record Usage(long inputTokens, long outputTokens, long totalTokens) {

    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new JevException("read-usage", "malformed-response",
                    "token counts must not be negative, got input=" + inputTokens
                            + " output=" + outputTokens + " total=" + totalTokens);
        }
    }
}
