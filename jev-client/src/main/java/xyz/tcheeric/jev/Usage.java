package xyz.tcheeric.jev;

/**
 * What the evaluator reports it spent answering. Returned rather than discarded, because a
 * consumer that pays per token needs to see the bill it just incurred.
 *
 * <p>There is no total. The API does not send one, and Jev charges for input tokens only, so a
 * sum of the two would be a number nobody is billed on.</p>
 */
public record Usage(long inputTokens, long outputTokens) {

    public Usage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new JevException("read-usage", "malformed-response",
                    "token counts must not be negative, got input=" + inputTokens + " output=" + outputTokens);
        }
    }
}
