package xyz.tcheeric.jev.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.jev.Answer;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevApiException;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevConfig;
import xyz.tcheeric.jev.JevException;
import xyz.tcheeric.jev.Question;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a stub server speaking the wire format documented in WIRE.md. The library
 * is exercised end to end over real HTTP and is never mocked against itself.
 */
class JevClientIT {

    private static final String PATH = "/v1/evaluate";

    private WireMockServer server;
    private JevClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new JevClient(config());
    }

    @AfterEach
    void stopServer() {
        client.close();
        server.stop();
    }

    private JevConfig config() {
        return JevConfig.of(URI.create("http://localhost:" + server.port()), "sk-test-token")
                .withRequestTimeout(Duration.ofSeconds(5));
    }

    @Test
    void aFanOutOfThreeQuestionsTravelsAsOneRequestAndComesBackTypedByName() {
        // The headline behaviour: one state, three named typed questions, one HTTP call, and
        // three typed answers retrieved by the names the caller chose.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"answers":[
                          {"name":"is_spam","type":"noul","probability":0.93},
                          {"name":"intent","type":"choice",
                           "distribution":{"refund":0.7,"support":0.2,"sales":0.1},
                           "chosen":"refund","confidence":0.82},
                          {"name":"severity","type":"score",
                           "distribution":{"1":0.05,"2":0.05,"3":0.1,"4":0.6,"5":0.2},
                           "value":4,"confidence":0.66}],
                         "usage":{"input_tokens":412,"output_tokens":37,"total_tokens":449}}
                        """)));

        Evaluation evaluation = client.evaluate("I want my money back, this is the third time.",
                new Question.Noul("is_spam", "This message is spam."),
                new Question.Choice("intent", "What does the sender want?", List.of("refund", "support", "sales")),
                new Question.Score("severity", "How severe is this?", 1, 5));

        assertThat(evaluation.noul("is_spam").probability()).isEqualTo(0.93d);
        Answer.Choice intent = evaluation.choice("intent");
        assertThat(intent.chosen()).isEqualTo("refund");
        assertThat(intent.distribution()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("refund", 0.7d, "support", 0.2d, "sales", 0.1d));
        assertThat(intent.confidence()).isEqualTo(0.82d);
        assertThat(evaluation.score("severity").value()).isEqualTo(4);
        assertThat(evaluation.usage().totalTokens()).isEqualTo(449L);
        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void theRequestCarriesBearerAuthAndThePinnedModel() {
        // Auth and the pinned model are the client's business, not the caller's, and must
        // actually appear on the wire rather than merely in configuration.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okBody(noulBody(0.5d))));

        client.evaluate("anything", new Question.Noul("q", "true?"));

        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-test-token"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model":"jev-1-20260101","state":"anything",
                         "questions":[{"name":"q","type":"noul","statement":"true?"}]}
                        """)));
    }

    @Test
    void aTokenTheServerRejectsSurfacesWhatTheApiSaidAndIsNotRetried() {
        // A 401 is a statement about the credential. Repeating it cannot change the answer, so
        // exactly one request is made and the API's own words reach the caller.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(401)
                .withBody("{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}")));

        assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOf(JevApiException.class)
                .extracting("status", "apiMessage")
                .containsExactly(401, "invalid api key");

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void anUnprocessableRequestSurfacesWhatTheApiSaidAndIsNotRetried() {
        // A 422 means the request itself is wrong. Retrying spends the caller's rate limit to
        // be told the same thing again.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(422)
                .withBody("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"question is unanswerable\"}}")));

        assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOf(JevApiException.class)
                .extracting("status", "apiMessage")
                .containsExactly(422, "question is unanswerable");

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void anUnknownServerErrorIsNotRetriedEither() {
        // Only 429 and 529 are retryable. A client that retries every 5xx turns an evaluator's
        // bad day into an outage.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOf(JevApiException.class);

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aMalformedSuccessBodyIsReportedAsThisLibrarysOwnFailureAndIsNotRetried() {
        // A 200 carrying nonsense is not a transient condition. It surfaces as a malformed
        // response rather than as a raw parser exception crossing the boundary.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okBody("{\"answers\":[{\"name\"")));

        assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("malformed-response");

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aClosedClientRefusesToEvaluateRatherThanFailingObscurelyInsideHttp() {
        // Using a client after close is a lifecycle bug, and the message should say so.
        JevClient closed = new JevClient(config());
        closed.close();

        assertThatThrownBy(() -> closed.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("client-closed");
    }

    @Test
    void closingTwiceIsHarmless() {
        // Close is idempotent, so a try-with-resources inside a caller that also closes
        // explicitly does not blow up.
        JevClient twice = new JevClient(config());
        twice.close();
        twice.close();
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okBody(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    static String noulBody(double probability) {
        return """
                {"answers":[{"name":"q","type":"noul","probability":%s}],
                 "usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}
                """.formatted(probability);
    }
}
