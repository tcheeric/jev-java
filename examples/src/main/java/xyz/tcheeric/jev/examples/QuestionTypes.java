package xyz.tcheeric.jev.examples;

import xyz.tcheeric.jev.Answer;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.ModelCard;
import xyz.tcheeric.jev.Question;

import java.util.List;
import java.util.Map;

/**
 * A tour of the three question types and everything each answer carries, including structured
 * instructions that point the model at reference data. It prints the raw answers and makes no
 * decision, so you can see what Jev returns before deciding what to do with it.
 *
 * <pre>
 * mvn -q -pl examples exec:java -Dexample=QuestionTypes
 * </pre>
 */
public final class QuestionTypes {

    private QuestionTypes() {
    }

    public static void main(String[] args) {
        try (JevClient jev = new JevClient(ExampleConfig.fromEnvironment())) {
            System.out.println("models your key may name:");
            for (ModelCard model : jev.models()) {
                System.out.println("  " + model.name() + "  (" + model.description() + ")");
            }
            System.out.println();
            print(ask(jev));
        }
    }

    static Evaluation ask(JevClient jev) {
        String state = """
                Resume: Jonathan Smith. Senior engineer at Google, 2019 to 2025.
                Lives in Oakland, CA. Python, Go, distributed systems.""";

        return jev.evaluate(state,
                // A noul with structured instructions: the question refers to the data by name,
                // in backticks, so the data does not have to be pasted into the state.
                new Question.Noul("same_person", Map.of(
                        "candidate", Map.of(
                                "name", "John Smith",
                                "location", "Oakland, California",
                                "last_employer", "Google"),
                        "question", "Is the resume for the same person as `candidate`?")),
                new Question.Choice("seniority", "What seniority level is this candidate?",
                        List.of("junior", "mid", "senior", "staff")),
                new Question.Score("python_fit", "How well does this resume fit a senior Python role?",
                        List.of("No fit", "Weak fit", "Good fit", "Excellent fit")));
    }

    static void print(Evaluation result) {
        System.out.println("answered by " + result.model()
                + ", " + result.usage().inputTokens() + " input tokens");

        Answer.Noul same = result.noul("same_person");
        System.out.printf("%nnoul   same_person  probability %.2f  (a noul has no confidence)%n",
                same.probability());

        Answer.Choice seniority = result.choice("seniority");
        System.out.printf("choice seniority    chosen %s  confidence %.2f%n",
                seniority.chosen(), seniority.confidence());
        seniority.distribution().forEach((option, p) -> System.out.printf("         %-9s %.2f%n", option, p));

        Answer.Score fit = result.score("python_fit");
        System.out.printf("score  python_fit   score %.2f on 0..%d  confidence %.2f%n",
                fit.score(), fit.legend().size() - 1, fit.confidence());
        fit.distribution().forEach((level, p) ->
                System.out.printf("         %d %-14s %.2f%n", level, fit.legend().get(level), p));
    }
}
