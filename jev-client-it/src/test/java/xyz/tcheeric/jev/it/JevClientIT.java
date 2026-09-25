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
import xyz.tcheeric.jev.ModelCard;
import xyz.tcheeric.jev.Question;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a stub server speaking the published wire format recorded in WIRE.md. The library
 * is exercised end to end over real HTTP and is never mocked against itself.
 */
class JevClientIT {

    private static final String PATH = "/v1/systemone";

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
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okBody("""
                {"model":"jev-1.13.0",
                 "answers":{
                   "is_spam":{"type":"noul","noul":0.93},
                   "intent":{"type":"choice","choice":"refund",
                     "probabilities":{"refund":0.7,"support":0.2,"sales":0.1},"confidence":0.82},
                   "severity":{"type":"score","score":1.4,
                     "legend":{"0":"Minor","1":"Serious","2":"Critical"},
                     "probabilities":{"0":0.1,"1":0.4,"2":0.5},"confidence":0.66}},
                 "usage":{"input_tokens":412,"output_tokens":37}}
                """)));

        Evaluation evaluation = client.evaluate("I want my money back, this is the third time.",
                new Question.Noul("is_spam", "Is this message spam?"),
                new Question.Choice("intent", "What does the sender want?", List.of("refund", "support", "sales")),
                new Question.Score("severity", "How severe is this?", List.of("Minor", "Serious", "Critical")));

        assertThat(evaluation.model()).isEqualTo("jev-1.13.0");
        assertThat(evaluation.noul("is_spam").probability()).isEqualTo(0.93d);
        Answer.Choice intent = evaluation.choice("intent");
        assertThat(intent.chosen()).isEqualTo("refund");
        assertThat(intent.distribution()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("refund", 0.7d, "support", 0.2d, "sales", 0.1d));
        assertThat(intent.confidence()).isEqualTo(0.82d);
        Answer.Score severity = evaluation.score("severity");
        assertThat(severity.score()).isEqualTo(1.4d);
        assertThat(severity.legend()).containsEntry(2, "Critical");
        assertThat(evaluation.usage().inputTokens()).isEqualTo(412L);
        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void theRequestCarriesBearerAuthThePinnedModelAndTheDocumentedBody() {
        // Auth and the pinned model are the client's business, not the caller's, and must
        // actually appear on the wire rather than merely in configuration. The body is compared
        // strictly, so an extra assumed field would fail here.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okBody(noulBody(0.5d))));

        client.evaluate("anything", new Question.Noul("q", "true?", "It is true", null));

        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-test-token"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model":"jev-1.13.0","state":"anything",
                         "questions":{"q":{"type":"noul","instructions":"true?",
                                           "criteria":{"true":"It is true"}}}}
                        """)));
    }

    @Test
    void theModelListIsFetchedWithBearerAuth() {
        // GET /v1/models, so an operator can see which version an alias currently points at.
        server.stubFor(get(urlEqualTo("/v1/models")).willReturn(okBody("""
                {"models":[{"name":"jev-latest","description":"Flagship","release_date":"2026-09-01"},
                           {"name":"jev-preview","description":"Preview","release_date":"2026-09-01"}]}
                """)));

        assertThat(client.models()).extracting(ModelCard::name).containsExactly("jev-latest", "jev-preview");
        server.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-test-token")));
    }

    @Test
    void aMissingKeyRejectedWith403IsNotRetriedAndCarriesTheRequestId() {
        // The live API answers a missing key with 403, not the documented 401. It is just as
        // final, and the request id is what the evaluator's operators ask for.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(403)
                .withHeader("x-typesafe-request-id", "req_abc")
                .withBody("{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"Must supply an API key!\"}}")));

        assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                .isInstanceOfSatisfying(JevApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.apiErrorType()).isEqualTo("authentication_error");
                    assertThat(e.requestId()).contains("req_abc");
                });

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aTokenTheServerRejectsSurfacesWhatTheApiSaidAndIsNotRetried() {
        // A 401 is a statement about the credential. Repeating it cannot change the answer, so
        // exactly one request is made and the API's own words reach the caller.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(401)
                .withBody("{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"invalid api key\"}}")));

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
                .withBody("{\"detail\":{\"error_type\":\"validation_error\",\"message\":\"question is unanswerable\"}}")));

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
        server.stubFor(post(urlEqualTo(PATH)).willReturn(okBody("{\"answers\":{\"q\"")));

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
                {"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":%s}},
                 "usage":{"input_tokens":5,"output_tokens":2}}
                """.formatted(probability);
    }
}
