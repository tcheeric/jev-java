package xyz.tcheeric.jev.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import xyz.tcheeric.jev.Answer;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevApiException;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevConfig;
import xyz.tcheeric.jev.ModelCard;
import xyz.tcheeric.jev.Question;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against the real evaluator. This is the test the first cut of the library could
 * not have: its wire format was assumed, and every request it made failed against the published
 * API. The stub ITs prove the client speaks the format as documented; this one proves the
 * documentation and the client agree with the server.
 *
 * <p>It runs only when {@code TYPESAFE_API_KEY} is set, the variable the official SDKs read, so
 * a build without a key still passes and never spends money. It asserts shapes and invariants,
 * never particular probabilities, because the model's answers are not this library's to pin.
 * {@code TYPESAFE_BASE_URL} overrides the endpoint, as it does for the SDKs.</p>
 */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+")
class JevLiveIT {

    private JevClient client;

    @BeforeEach
    void connect() {
        String baseUrl = System.getenv("TYPESAFE_BASE_URL");
        JevConfig config = (baseUrl == null || baseUrl.isBlank()
                ? JevConfig.of(System.getenv("TYPESAFE_API_KEY"))
                : JevConfig.of(java.net.URI.create(baseUrl), System.getenv("TYPESAFE_API_KEY")))
                .withRequestTimeout(Duration.ofSeconds(30));
        client = new JevClient(config);
    }

    @AfterEach
    void close() {
        client.close();
    }

    @Test
    void allThreeQuestionTypesAreAnsweredByThePinnedModelInOneRequest() {
        // One request carrying one question of each type, in the documented format, against the
        // pinned default model. Every answer must decode into its typed record, which already
        // checks probabilities lie in 0..1, the chosen option is in the distribution, and the
        // score lies within its levels.
        Evaluation evaluation = client.evaluate("Help! My payouts have been failing for 3 days.",
                new Question.Noul("is_urgent", "Does this convey urgency?",
                        "Explicitly time-sensitive", "No urgency expressed"),
                new Question.Choice("department", "Which team should handle this?", java.util.Map.of(
                        "billing", "Payments, invoicing, refunds",
                        "technical", "Bugs, outages, integrations",
                        "sales", "Pricing, upgrades, new accounts")),
                new Question.Score("frustration", "How frustrated is the customer?",
                        List.of("Calm", "Frustrated", "Very angry")));

        assertThat(evaluation.model()).startsWith("jev-");
        assertThat(evaluation.answers()).containsOnlyKeys("is_urgent", "department", "frustration");
        assertThat(evaluation.choice("department").distribution())
                .containsOnlyKeys("billing", "technical", "sales");
        Answer.Score frustration = evaluation.score("frustration");
        assertThat(frustration.legend()).containsEntry(0, "Calm").containsEntry(2, "Very angry");
        assertThat(evaluation.usage().inputTokens()).isPositive();
    }

    @Test
    void theDefaultModelIsOneTheEvaluatorAcceptsAndAnswersAs() {
        // The first default, jev-1-20260101, did not exist. A pinned version must be answered as
        // itself rather than rejected or silently rerouted.
        Evaluation evaluation = client.evaluate("The sky is blue.", new Question.Noul("q", "Is this about the sky?"));

        assertThat(evaluation.model()).isEqualTo(JevConfig.DEFAULT_MODEL);
    }

    @Test
    void theModelListCanBeRead() {
        // GET /v1/models currently lists the aliases; the response must decode.
        List<ModelCard> models = client.models();

        assertThat(models).isNotEmpty();
    }

    @Test
    void anInvalidKeyIsRejectedOnceAndTheApisOwnWordsReachTheCaller() {
        // The error body is undocumented. This pins the shape the codec decodes against the
        // server, so a change to it fails here rather than as an "unparseable" in production.
        try (JevClient wrongKey = new JevClient(JevConfig.of("sk-definitely-not-a-key"))) {
            assertThatThrownBy(() -> wrongKey.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOfSatisfying(JevApiException.class, e -> {
                        assertThat(e.status()).isIn(401, 403);
                        assertThat(e.apiErrorType()).isEqualTo("authentication_error");
                        assertThat(e.apiMessage()).isNotBlank();
                        assertThat(e.requestId()).isPresent();
                    });
        }
    }

    @Test
    void aModelTheEvaluatorDoesNotServeIsRejectedWithAClientErrorTheCodecCanRead() {
        // Every malformed question is refused by the client before it is sent, so an unknown
        // model is the one validation failure that can be provoked through the public API. The
        // status is not pinned because the reference does not say which 4xx this is.
        try (JevClient unknownModel = new JevClient(JevConfig.of(System.getenv("TYPESAFE_API_KEY"))
                .withModel("jev-0.0.0-does-not-exist"))) {
            assertThatThrownBy(() -> unknownModel.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOfSatisfying(JevApiException.class, e -> {
                        assertThat(e.status()).isBetween(400, 499);
                        assertThat(e.apiErrorType()).isNotEqualTo("unparseable");
                    });
        }
    }
}
