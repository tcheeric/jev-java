package xyz.tcheeric.jev.examples;

import xyz.tcheeric.jev.Answer;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevApiException;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevException;
import xyz.tcheeric.jev.Question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Triages a support message: is it urgent, which team owns it, and how upset is the sender.
 * One request asks all three questions.
 *
 * <p>The point to notice is where the decisions are. Jev returns probabilities and confidence.
 * The thresholds that turn them into "route to billing" or "send to a person" are constants in
 * this class, where a test can assert them and a diff shows when they change.</p>
 *
 * <pre>
 * mvn -q -pl examples exec:java -Dexample=Triage -Dexec.args="My payouts have failed for 3 days!"
 * </pre>
 */
public final class Triage {

    /** How sure Jev must be about the team before the message is routed without a person. */
    static final double ROUTE_CONFIDENCE = 0.70;
    /** Above this, the message jumps the queue. */
    static final double URGENT_PROBABILITY = 0.80;

    /** What this example's code decided, as opposed to what Jev answered. */
    record Decision(String queue, boolean urgent, String mood, String reason) {
    }

    private Triage() {
    }

    public static void main(String[] args) {
        String message = args.length > 0 ? String.join(" ", args)
                : "Help! My payouts have been failing for 3 days and nobody has replied.";
        try (JevClient jev = new JevClient(ExampleConfig.fromEnvironment())) {
            Decision decision = triage(jev, message);
            System.out.println("queue:  " + decision.queue());
            System.out.println("urgent: " + decision.urgent());
            System.out.println("mood:   " + decision.mood());
            System.out.println("why:    " + decision.reason());
        }
    }

    static Decision triage(JevClient jev, String message) {
        Map<String, Object> teams = new LinkedHashMap<>();
        teams.put("billing", "Payments, payouts, invoices, refunds");
        teams.put("technical", "Bugs, outages, integrations, error messages");
        teams.put("sales", "Pricing, upgrades, new accounts");

        Evaluation result;
        try {
            result = jev.evaluate(message,
                    new Question.Noul("urgent", "Does the sender need this resolved urgently?",
                            "Money, access or a deadline is at stake right now",
                            "It can wait for a normal reply"),
                    new Question.Choice("team", "Which team should handle this message?", teams),
                    new Question.Score("mood", "How does the sender feel?",
                            List.of("Calm", "Frustrated", "Very angry")));
        } catch (JevApiException e) {
            // The request was refused. Fail toward a person, never toward acting without an answer.
            return new Decision("human", false, "unknown",
                    "Jev refused the request (" + e.status() + " " + e.apiErrorType() + ", request "
                            + e.requestId().orElse("?") + ")");
        } catch (JevException e) {
            return new Decision("human", false, "unknown",
                    "Jev was unavailable (" + e.failureType() + ")");
        }

        Answer.Choice team = result.choice("team");
        Answer.Score mood = result.score("mood");
        boolean urgent = result.noul("urgent").probability() >= URGENT_PROBABILITY;
        // The score is a weighted mean of level indices, so it can fall between two levels.
        // Rounding picks the nearest one for display. The legend turns the index back into words.
        String moodWords = mood.legend().get((int) Math.round(mood.score()));

        if (team.confidence() < ROUTE_CONFIDENCE) {
            return new Decision("human", urgent, moodWords,
                    "team unclear: " + team.chosen() + " at confidence " + team.confidence()
                            + ", distribution " + team.distribution());
        }
        return new Decision(team.chosen(), urgent, moodWords,
                team.chosen() + " at confidence " + team.confidence() + ", answered by " + result.model());
    }
}
