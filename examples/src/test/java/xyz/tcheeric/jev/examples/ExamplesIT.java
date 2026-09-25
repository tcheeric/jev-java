package xyz.tcheeric.jev.examples;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.jev.Evaluation;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevConfig;
import xyz.tcheeric.jev.RetryPolicy;

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the examples' own code over HTTP against a stub server, so an example that no longer
 * compiles or no longer works against the library fails the build instead of rotting in a
 * README. It needs no API key and spends nothing.
 */
class ExamplesIT {

    private static final String PATH = "/v1/systemone";

    private WireMockServer server;
    private JevClient jev;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        jev = new JevClient(JevConfig.of(URI.create("http://localhost:" + server.port()), "sk-example")
                .withRequestTimeout(Duration.ofSeconds(5))
                .withRetryPolicy(new RetryPolicy(1, Duration.ZERO, 1.0d, Duration.ZERO, 0.0d)));
    }

    @AfterEach
    void stop() {
        jev.close();
        server.stop();
    }

    private void answer(String body) {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void aConfidentAnswerIsRoutedToTheChosenTeam() {
        // The example's happy path: a confident choice routes without a person, and the score's
        // fractional value is turned back into the nearest level's words through the legend.
        answer("""
                {"model":"jev-1.13.0","answers":{
                  "urgent":{"type":"noul","noul":0.93},
                  "team":{"type":"choice","choice":"billing",
                    "probabilities":{"billing":0.9,"technical":0.08,"sales":0.02},"confidence":0.85},
                  "mood":{"type":"score","score":1.2,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},
                    "probabilities":{"0":0.0,"1":0.8,"2":0.2},"confidence":0.8}},
                 "usage":{"input_tokens":300,"output_tokens":40}}
                """);

        Triage.Decision decision = Triage.triage(jev, "My payouts have failed for 3 days!");

        assertThat(decision.queue()).isEqualTo("billing");
        assertThat(decision.urgent()).isTrue();
        assertThat(decision.mood()).isEqualTo("Frustrated");
        server.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.questions.team.criteria.billing"))
                .withRequestBody(matchingJsonPath("$.questions.mood.criteria[2]")));
    }

    @Test
    void anUnsureAnswerGoesToAPersonRatherThanBeingActedOn() {
        // Below the example's confidence bar the message is not routed, whatever Jev chose. This
        // is the pattern the user guide recommends, so the example must actually do it.
        answer("""
                {"model":"jev-1.13.0","answers":{
                  "urgent":{"type":"noul","noul":0.2},
                  "team":{"type":"choice","choice":"sales",
                    "probabilities":{"billing":0.3,"technical":0.3,"sales":0.4},"confidence":0.1},
                  "mood":{"type":"score","score":0.1,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},
                    "probabilities":{"0":0.9,"1":0.1,"2":0.0},"confidence":0.9}},
                 "usage":{"input_tokens":300,"output_tokens":40}}
                """);

        Triage.Decision decision = Triage.triage(jev, "hello?");

        assertThat(decision.queue()).isEqualTo("human");
        assertThat(decision.urgent()).isFalse();
        assertThat(decision.reason()).contains("team unclear");
    }

    @Test
    void anEvaluatorThatRefusesTheRequestSendsTheMessageToAPerson() {
        // A failure must never turn into an action. The example falls back to a person and says
        // why, including the request id a TypeSafe operator would ask for.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("x-typesafe-request-id", "req_example")
                .withBody("{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"bad key\"}}")));

        Triage.Decision decision = Triage.triage(jev, "My payouts have failed!");

        assertThat(decision.queue()).isEqualTo("human");
        assertThat(decision.reason()).contains("401").contains("req_example");
    }

    @Test
    void anUnreachableEvaluatorAlsoSendsTheMessageToAPerson() {
        // The same rule when there is no response at all.
        server.stop();

        Triage.Decision decision = Triage.triage(jev, "My payouts have failed!");

        assertThat(decision.queue()).isEqualTo("human");
        assertThat(decision.reason()).contains("unavailable");
    }

    @Test
    void theQuestionTourSendsStructuredInstructionsAndPrintsEveryAnswer() {
        // The structured noul must reach the wire as a JSON object, not as a Map's toString, or
        // the backtick reference to `candidate` would point at nothing.
        answer("""
                {"model":"jev-1.13.0","answers":{
                  "same_person":{"type":"noul","noul":0.9},
                  "seniority":{"type":"choice","choice":"senior",
                    "probabilities":{"junior":0.0,"mid":0.1,"senior":0.8,"staff":0.1},"confidence":0.7},
                  "python_fit":{"type":"score","score":2.4,
                    "legend":{"0":"No fit","1":"Weak fit","2":"Good fit","3":"Excellent fit"},
                    "probabilities":{"0":0.0,"1":0.1,"2":0.4,"3":0.5},"confidence":0.6}},
                 "usage":{"input_tokens":400,"output_tokens":50}}
                """);

        Evaluation result = QuestionTypes.ask(jev);
        QuestionTypes.print(result);

        assertThat(result.score("python_fit").score()).isEqualTo(2.4d);
        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.questions.same_person.instructions.candidate.name",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("John Smith"))));
    }
}
