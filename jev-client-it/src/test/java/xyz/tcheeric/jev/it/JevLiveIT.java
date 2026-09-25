package xyz.tcheeric.jev.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.jev.Answer;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevApiException;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevConfig;
import xyz.tcheeric.jev.ModelCard;
import xyz.tcheeric.jev.Question;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The client against the real evaluator. This is the test the first cut of the library could
 * not have: its wire format was assumed, and every request it made failed against the published
 * API. The stub ITs prove the client speaks the format as documented; this one proves the
 * documentation and the client agree with the server.
 *
 * <p>The key comes from {@code TYPESAFE_API_KEY}, the variable the official SDKs read, or else
 * from the file named by {@code TYPESAFE_API_KEY_FILE}, so a key kept in a git-ignored file never
 * has to be pasted into a shell. With neither set, every test is skipped, so a build without a
 * key still passes and never spends money. The key is read here, in the test harness, and not by
 * the library, whose callers each load their own secret and hand it to {@link JevConfig}.</p>
 *
 * <p>The tests assert shapes and invariants, never particular probabilities, because the model's
 * answers are not this library's to pin. {@code TYPESAFE_BASE_URL} overrides the endpoint, as it
 * does for the SDKs.</p>
 */
class JevLiveIT {

    private static final String API_KEY = apiKey();

    private JevClient client;

    private static String apiKey() {
        String key = System.getenv("TYPESAFE_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.strip();
        }
        String file = System.getenv("TYPESAFE_API_KEY_FILE");
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(file)).strip();
        } catch (IOException e) {
            // A configured but unreadable key file is a broken setup, not a reason to skip, so
            // it fails loudly. The path is named; the contents never are.
            throw new UncheckedIOException("cannot read TYPESAFE_API_KEY_FILE at " + file, e);
        }
    }

    @BeforeEach
    void connect() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(),
                "set TYPESAFE_API_KEY or TYPESAFE_API_KEY_FILE to run against the real evaluator");
        String baseUrl = System.getenv("TYPESAFE_BASE_URL");
        JevConfig config = (baseUrl == null || baseUrl.isBlank()
                ? JevConfig.of(API_KEY)
                : JevConfig.of(java.net.URI.create(baseUrl), API_KEY))
                .withRequestTimeout(Duration.ofSeconds(30));
        client = new JevClient(config);
    }

    @AfterEach
    void close() {
        if (client != null) {
            client.close();
        }
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
        // reference does not list 400 at all; this pins what the server actually does.
        try (JevClient unknownModel = new JevClient(JevConfig.of(API_KEY)
                .withModel("jev-0.0.0-does-not-exist"))) {
            assertThatThrownBy(() -> unknownModel.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOfSatisfying(JevApiException.class, e -> {
                        // Observed 25 September 2026: 400 api_usage_error "Unknown model: ...".
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.apiErrorType()).isEqualTo("api_usage_error");
                        assertThat(e.apiMessage()).contains("jev-0.0.0-does-not-exist");
                    });
        }
    }
}
