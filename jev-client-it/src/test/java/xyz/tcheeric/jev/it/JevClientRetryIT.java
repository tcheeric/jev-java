package xyz.tcheeric.jev.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.jev.JevApiException;
import xyz.tcheeric.jev.JevClient;
import xyz.tcheeric.jev.JevConfig;
import xyz.tcheeric.jev.JevException;
import xyz.tcheeric.jev.Question;
import xyz.tcheeric.jev.RetryPolicy;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the backoff actually backs off, against a stub server that counts attempts and records
 * when each one arrived. The waits are asserted from the arrival times of real requests, not
 * from a sleep inside the test.
 */
class JevClientRetryIT {

    private static final String PATH = "/v1/systemone";
    private static final String SCENARIO = "retries";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private JevClient clientWith(RetryPolicy policy) {
        return new JevClient(JevConfig.of(URI.create("http://localhost:" + server.port()), "sk-test-token")
                .withRequestTimeout(Duration.ofSeconds(5))
                .withRetryPolicy(policy));
    }

    @Test
    void rateLimitingIsRetriedAndTheWaitBetweenAttemptsGrows() {
        // Two 429s then a 200. Three requests reach the server, and the gap before the third is
        // strictly longer than the gap before the second: the backoff is exponential, not a
        // fixed pause. Measured from the stub's own request log, not from a sleep here.
        stubSequence(429, 429);
        RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(400), 2.0d, Duration.ofSeconds(10), 0.0d);

        List<Long> arrivals;
        try (JevClient client = clientWith(policy)) {
            long probability = Math.round(client.evaluate("state", new Question.Noul("q", "true?"))
                    .noul("q").probability() * 100);
            assertThat(probability).isEqualTo(50L);
            arrivals = arrivalTimes();
        }

        assertThat(arrivals).hasSize(3);
        long firstGap = arrivals.get(1) - arrivals.get(0);
        long secondGap = arrivals.get(2) - arrivals.get(1);
        assertThat(firstGap).isGreaterThanOrEqualTo(300L);
        assertThat(secondGap).isGreaterThan(firstGap);
        server.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void evaluatorOverloadIsRetriedOnTheSameSchedule() {
        // 529 means the evaluator is overloaded rather than the request being wrong, so it is
        // retried exactly as a 429 is.
        stubSequence(529);
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(150), 2.0d, Duration.ofSeconds(5), 0.0d);

        try (JevClient client = clientWith(policy)) {
            assertThat(client.evaluate("state", new Question.Noul("q", "true?")).noul("q").probability())
                    .isEqualTo(0.5d);
        }

        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retriesStopAtTheConfiguredLimitRatherThanRunningForever() {
        // A permanently rate-limited evaluator must not hold the caller hostage. Exactly
        // maxAttempts requests are made and then the client gives up, saying why.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withBody("{\"detail\":{\"error_type\":\"rate_limit_error\",\"message\":\"slow down\"}}")));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(50), 2.0d, Duration.ofSeconds(1), 0.0d);

        try (JevClient client = clientWith(policy)) {
            assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOf(JevException.class)
                    .hasMessageContaining("retries-exhausted")
                    .hasCauseInstanceOf(JevApiException.class);
        }

        server.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aRetryAfterHeaderIsHonouredInPlaceOfTheComputedBackoff() {
        // The server says "wait 700 ms" while the policy alone would wait 50 ms. The second
        // request must not arrive before the server said it could.
        server.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("retry-after-ms", "700")
                        .withBody("{\"detail\":{\"error_type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
                .willSetStateTo("open"));
        server.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO).whenScenarioStateIs("open")
                .willReturn(JevClientIT.okBody(JevClientIT.noulBody(0.5d))));
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(50), 2.0d, Duration.ofSeconds(5), 0.0d);

        List<Long> arrivals;
        try (JevClient client = clientWith(policy)) {
            client.evaluate("state", new Question.Noul("q", "true?"));
            arrivals = arrivalTimes();
        }

        assertThat(arrivals.get(1) - arrivals.get(0)).isGreaterThanOrEqualTo(650L);
    }

    @Test
    void aRetryAfterLongerThanTheCapStopsAtOnceAndTellsTheCallerHowLongTheServerWanted() {
        // Holding the caller for an hour, or retrying early into another 429, are both wrong.
        // One request is made and the requested wait reaches the caller on the cause.
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withHeader("retry-after", "3600")
                .withBody("{\"detail\":{\"error_type\":\"rate_limit_error\",\"message\":\"slow down\"}}")));
        RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(50), 2.0d, Duration.ofSeconds(5), 0.0d);

        try (JevClient client = clientWith(policy)) {
            assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOf(JevException.class)
                    .hasMessageContaining("retry-after-exceeds-backoff")
                    .cause()
                    .isInstanceOfSatisfying(JevApiException.class,
                            e -> assertThat(e.requestedWait()).contains(Duration.ofHours(1)));
        }

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void jitterKeepsEachWaitInsideTheConfiguredBandRatherThanFiringImmediately() {
        // Jitter must spread retries without collapsing the wait to zero, so the observed gap
        // sits between the jittered floor and the unjittered ceiling.
        stubSequence(429);
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(600), 2.0d, Duration.ofSeconds(10), 0.5d);

        List<Long> arrivals;
        try (JevClient client = clientWith(policy)) {
            client.evaluate("state", new Question.Noul("q", "true?"));
            arrivals = arrivalTimes();
        }

        long gap = arrivals.get(1) - arrivals.get(0);
        assertThat(gap).isGreaterThanOrEqualTo(250L).isLessThan(1200L);
    }

    @Test
    void aConnectionThatCannotBeMadeIsRetriedAndThenGivenUpOn() {
        // A refused connection carries no evidence the evaluator saw the request, so it is
        // retried; but an evaluator that is simply not there must not be retried forever.
        int deadPort = server.port();
        server.stop();
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(50), 2.0d, Duration.ofSeconds(1), 0.0d);

        try (JevClient client = new JevClient(JevConfig.of(URI.create("http://localhost:" + deadPort), "sk-test")
                .withRequestTimeout(Duration.ofMillis(500))
                .withRetryPolicy(policy))) {
            assertThatThrownBy(() -> client.evaluate("state", new Question.Noul("q", "true?")))
                    .isInstanceOf(JevException.class)
                    .hasMessageContaining("retries-exhausted")
                    .hasMessageContaining("connection-failed");
        } finally {
            server.start();
        }
    }

    /**
     * Stubs the given failure statuses in order, followed by a success, using a WireMock
     * scenario so the stub server itself counts the attempts.
     */
    private void stubSequence(int... failures) {
        String state = com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
        for (int i = 0; i < failures.length; i++) {
            String next = "attempt-" + (i + 1);
            server.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(failures[i])
                            .withBody("{\"detail\":{\"error_type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
                    .willSetStateTo(next));
            state = next;
        }
        server.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(state)
                .willReturn(JevClientIT.okBody(JevClientIT.noulBody(0.5d))));
    }

    private List<Long> arrivalTimes() {
        return server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getLoggedDate().getTime())
                .sorted()
                .toList();
    }
}
