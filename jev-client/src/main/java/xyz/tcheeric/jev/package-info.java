/**
 * A Java client for TypeSafe's Jev evaluator.
 *
 * <p>Jev answers typed questions about a piece of text, the <em>state</em>, with calibrated
 * probabilities. Build the questions from the three {@link xyz.tcheeric.jev.Question} types, send
 * them in one call with {@link xyz.tcheeric.jev.JevClient#evaluate(String, java.util.List)}, and read each
 * typed {@link xyz.tcheeric.jev.Answer} back from the returned
 * {@link xyz.tcheeric.jev.Evaluation} by the name you gave its question.</p>
 *
 * <pre>{@code
 * try (JevClient jev = new JevClient(JevConfig.of(apiKey))) {
 *     Evaluation result = jev.evaluate("Help! My payouts have been failing for 3 days.",
 *             new Question.Noul("is_urgent", "Does this convey urgency?"),
 *             new Question.Choice("department", "Which team should handle this?",
 *                     List.of("billing", "technical", "sales")));
 *     double urgent = result.noul("is_urgent").probability();
 *     String team = result.choice("department").chosen();
 * }
 * }</pre>
 *
 * <p>The library returns full probability distributions and applies no threshold. Deciding what
 * an answer means for your application stays in your code. Failures are unchecked
 * {@link xyz.tcheeric.jev.JevException}s carrying an operation and a failure type, and a
 * {@link xyz.tcheeric.jev.JevApiException} when the API itself refused the request.</p>
 *
 * <p>The user guide, the wire format and the security notes are in the project repository.</p>
 */
package xyz.tcheeric.jev;
