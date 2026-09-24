package xyz.tcheeric.jev;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one entry point into the evaluator.
 *
 * <p>A caller hands over a state and a fan-out of named, typed questions, and gets back the
 * typed answers keyed by those names, in a single request. The client owns bearer auth, the
 * request timeout, and retry with exponential backoff and jitter.</p>
 *
 * <p>It owns nothing else. There is no method here that counts an answer, compares a date, or
 * turns a probability into a decision (ADR 0002). Several consumers are ranked for this
 * library and each calibrates its own thresholds; a convenience such as {@code isSpam()} would
 * quietly make this library the owner of all of their judgement calls.</p>
 */
public final class JevClient implements AutoCloseable {

    private final JevConfig config;
    private final HttpClient http;
    private final JevWireCodec codec;
    private final ReentrantLock lifecycle = new ReentrantLock();
    private boolean closed;

    public JevClient(JevConfig config) {
        this(config, new JevWireCodec());
    }

    /**
     * Takes the codec explicitly so that a corrected wire format can be supplied without
     * touching this class. See {@code WIRE.md}.
     */
    public JevClient(JevConfig config, JevWireCodec codec) {
        if (config == null || codec == null) {
            throw new JevException("configure", "invalid-argument", "config and codec are required");
        }
        this.config = config;
        this.codec = codec;
        // Virtual threads per the constitution: HTTP here is I/O bound and a platform pool
        // would cap the fan-out at the pool size for no gain.
        this.http = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(config.requestTimeout())
                .build();
    }

    /**
     * Asks every question about one state in one request and returns the answers by name.
     */
    public Evaluation evaluate(String state, List<Question> questions) {
        if (state == null || state.isBlank()) {
            throw new JevException("evaluate", "invalid-argument", "the state must not be blank");
        }
        if (questions == null || questions.isEmpty()) {
            throw new JevException("evaluate", "invalid-argument", "at least one question is required");
        }
        requireOpen();

        String body = codec.encodeRequest(config.model(), state, List.copyOf(questions));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUri().toString().replaceAll("/+$", "") + codec.path()))
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return send(request);
    }

    public Evaluation evaluate(String state, Question... questions) {
        return evaluate(state, List.of(questions));
    }

    private Evaluation send(HttpRequest request) {
        RetryPolicy policy = config.retryPolicy();
        JevException last = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return codec.decodeResponse(response.body());
                }
                JevApiException error = codec.decodeError(status, response.body());
                if (!RetryPolicy.retryable(status)) {
                    throw error;
                }
                last = error;
            } catch (IOException e) {
                // A refused or dropped connection carries no evidence the evaluator saw the
                // request, so it is retried on the same schedule as an explicit 429.
                last = new JevException("evaluate", "connection-failed",
                        "could not reach the evaluator at " + config.baseUri(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JevException("evaluate", "interrupted",
                        "the evaluation was interrupted while waiting for the evaluator", e);
            }

            if (attempt < policy.maxAttempts()) {
                backoff(policy.backoffFor(attempt, ThreadLocalRandom.current().nextDouble()));
            }
        }

        throw new JevException("evaluate", "retries-exhausted",
                "gave up after " + policy.maxAttempts() + " attempts, last failure: " + last.getMessage(), last);
    }

    private void backoff(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevException("evaluate", "interrupted",
                    "the evaluation was interrupted while backing off", e);
        }
    }

    private void requireOpen() {
        lifecycle.lock();
        try {
            if (closed) {
                throw new JevException("evaluate", "client-closed",
                        "this client has been closed and cannot evaluate");
            }
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Idempotent, and guarded by a ReentrantLock rather than a synchronized block, which would
     * pin the carrier thread of a virtual thread mid-request.
     */
    @Override
    public void close() {
        lifecycle.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            http.close();
        } finally {
            lifecycle.unlock();
        }
    }
}
