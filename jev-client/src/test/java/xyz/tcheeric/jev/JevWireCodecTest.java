package xyz.tcheeric.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire format these tests assert is the published one (https://docs.typesafe.ai/api),
 * recorded in WIRE.md. Response fixtures are the reference's own examples, copied verbatim.
 */
class JevWireCodecTest {

    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);

    private final JevWireCodec codec = new JevWireCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode encode(Question... questions) throws Exception {
        return mapper.readTree(codec.encodeRequest("jev-1.13.0", "a support email", List.of(questions)));
    }

    @Test
    void anEvaluationIsPostedToTheSystemOneEndpoint() {
        // The assumed /v1/evaluate did not exist. The published endpoint is /v1/systemone.
        assertThat(codec.path()).isEqualTo("/v1/systemone");
        assertThat(codec.modelsPath()).isEqualTo("/v1/models");
    }

    @Test
    void questionsTravelAsAMapKeyedByTheCallersNameWithTypeAndInstructions() throws Exception {
        // The request's questions are an object keyed by id, not an array of named entries, and
        // every question carries type and instructions. This is the documented request shape.
        Map<String, Object> departments = new LinkedHashMap<>();
        departments.put("billing", "Payments, invoicing, refunds");
        departments.put("technical", null);
        JsonNode root = encode(
                new Question.Noul("is_urgent", "Does this convey urgency?"),
                new Question.Choice("department", "Which team should handle this?", departments),
                new Question.Score("frustration", "How frustrated is the customer?",
                        List.of("Calm", "Frustrated", "Very angry")));

        assertThat(root.get("model").asText()).isEqualTo("jev-1.13.0");
        assertThat(root.get("state").asText()).isEqualTo("a support email");
        JsonNode questions = root.get("questions");
        assertThat(questions.isObject()).isTrue();
        assertThat(questions.get("is_urgent")).isEqualTo(mapper.readTree("""
                {"type":"noul","instructions":"Does this convey urgency?"}"""));
        assertThat(questions.get("department")).isEqualTo(mapper.readTree("""
                {"type":"choice","instructions":"Which team should handle this?",
                 "criteria":{"billing":"Payments, invoicing, refunds","technical":null}}"""));
        assertThat(questions.get("frustration")).isEqualTo(mapper.readTree("""
                {"type":"score","instructions":"How frustrated is the customer?",
                 "criteria":["Calm","Frustrated","Very angry"]}"""));
    }

    @Test
    void theQuestionNameNeverLeaksIntoTheQuestionBody() throws Exception {
        // The id is a key the model never reads. A stray "name" field inside the body would be
        // an unknown field, which a strict server answers with 422.
        JsonNode root = encode(new Question.Noul("q", "true?"));

        assertThat(root.get("questions").get("q").has("name")).isFalse();
    }

    @Test
    void noulCriteriaAreSentOnlyWhenGivenAndOnlyTheSidesGiven() throws Exception {
        // criteria is optional on a noul. An empty object or a null side is noise at best and a
        // validation failure at worst, so absent sides are omitted rather than sent as null.
        JsonNode root = encode(
                new Question.Noul("both", "Urgent?", "Explicitly time-sensitive", "No urgency expressed"),
                new Question.Noul("yes_only", "Urgent?", "Explicitly time-sensitive", null));

        assertThat(root.at("/questions/both/criteria")).isEqualTo(mapper.readTree("""
                {"true":"Explicitly time-sensitive","false":"No urgency expressed"}"""));
        assertThat(root.at("/questions/yes_only/criteria")).isEqualTo(mapper.readTree("""
                {"true":"Explicitly time-sensitive"}"""));
    }

    @Test
    void structuredInstructionsAndCriteriaAreSentAsJsonNotAsStrings() throws Exception {
        // The API accepts an object or an array wherever it accepts a string. A map must arrive
        // as a JSON object, not as its toString().
        JsonNode root = encode(
                new Question.Noul("dup", Map.of("candidate", Map.of("name", "John Smith"),
                        "question", "Is this the same person as `candidate`?")),
                new Question.Score("fit", "How well does it fit?",
                        List.of(Map.of("label", "poor"), Map.of("label", "good"))));

        assertThat(root.at("/questions/dup/instructions/candidate/name").asText()).isEqualTo("John Smith");
        assertThat(root.at("/questions/fit/criteria/1/label").asText()).isEqualTo("good");
    }

    @Test
    void twoQuestionsSharingANameAreRefusedRatherThanOneSilentlyReplacingTheOther() {
        // The questions map has one slot per name. A duplicate would drop a question the caller
        // believes it asked, and its answer would simply never come back.
        assertThatThrownBy(() -> codec.encodeRequest("jev-1.13.0", "s", List.of(
                new Question.Noul("q", "a?"), new Question.Noul("q", "b?"))))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("two questions share the name");
    }

    @Test
    void theDocumentedResponseDecodesIntoAllThreeTypedAnswers() {
        // The reference's three answer examples merged into one response. Field names are
        // "noul", "choice", "probabilities", "score" and "legend", and answers is a map.
        String body = """
                {"model":"jev-1.13.0",
                 "answers":{
                   "is_urgent":{"type":"noul","noul":0.95},
                   "department":{"type":"choice","choice":"billing",
                     "probabilities":{"billing":0.88,"technical":0.12,"sales":0.0},"confidence":0.81},
                   "frustration":{"type":"score","score":1.05,
                     "legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},
                     "probabilities":{"0":0.0,"1":0.95,"2":0.05},"confidence":0.92}},
                 "usage":{"input_tokens":304,"output_tokens":18}}
                """;

        Evaluation evaluation = codec.decodeResponse(body);

        assertThat(evaluation.model()).isEqualTo("jev-1.13.0");
        assertThat(evaluation.noul("is_urgent").probability()).isEqualTo(0.95d);
        Answer.Choice department = evaluation.choice("department");
        assertThat(department.chosen()).isEqualTo("billing");
        assertThat(department.distribution()).containsEntry("technical", 0.12d).containsEntry("sales", 0.0d);
        assertThat(department.confidence()).isEqualTo(0.81d);
        Answer.Score frustration = evaluation.score("frustration");
        assertThat(frustration.score()).isEqualTo(1.05d);
        assertThat(frustration.legend()).containsEntry(2, "Very angry");
        assertThat(frustration.distribution()).containsEntry(1, 0.95d);
        assertThat(frustration.confidence()).isEqualTo(0.92d);
        assertThat(evaluation.usage()).isEqualTo(new Usage(304L, 18L));
    }

    @Test
    void theAnsweringModelIsTheVersionTheServerReportsNotTheOneRequested() {
        // A request may name one model and be answered by another if an alias is ever used
        // upstream of this client. The caller logs what actually answered.
        String body = """
                {"model":"jev-1.14.2","answers":{"q":{"type":"noul","noul":0.5}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThat(codec.decodeResponse(body).model()).isEqualTo("jev-1.14.2");
    }

    @Test
    void aNoulAnswerCarryingAnUndocumentedConfidenceStillYieldsNoConfidence() {
        // Even if the server one day adds a confidence to noul, the typed answer has nowhere to
        // put it, so a caller's code cannot start gating on it by accident (ADR 0003).
        String body = """
                {"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.88,"confidence":0.99}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThat(codec.decodeResponse(body).noul("q")).isEqualTo(new Answer.Noul("q", 0.88d));
    }

    @Test
    void theOldAssumedProbabilityFieldIsRefusedOnANoul() {
        // A noul without its "noul" value is malformed. Reading a guessed field name instead
        // would hide a real format change.
        String body = """
                {"model":"jev-1.13.0","answers":{"q":{"type":"noul","probability":0.5}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("'noul'");
    }

    @Test
    void anAnswersArrayInsteadOfAMapIsRefused() {
        // The assumed array shape is not what the API sends, and must not be half-parsed.
        String body = """
                {"model":"jev-1.13.0","answers":[{"name":"q","type":"noul","noul":0.5}],
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("'answers' object");
    }

    @Test
    void aResponseThatDoesNotSayWhichModelAnsweredIsRefused() {
        // The model is a required response field and the only record of what answered.
        String body = """
                {"answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("which model answered");
    }

    @Test
    void anAnswerOfAnUnknownTypeIsRefusedRatherThanGuessedAt() {
        // A type this client does not know is a wire format change. Guessing would hand the
        // caller a silently wrong answer.
        String body = """
                {"model":"jev-1.13.0","answers":{"q":{"type":"oracle","noul":0.5}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("unknown-answer-type");
    }

    @Test
    void aScoreWhoseLevelKeysAreNotIndicesIsRefused() {
        // Levels are keyed by index as strings. A key such as "Calm" means the shape changed.
        String body = """
                {"model":"jev-1.13.0","answers":{"s":{"type":"score","score":0.5,
                  "legend":{"Calm":"Calm"},"probabilities":{"Calm":1.0},"confidence":0.5}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("non-numeric level");
    }

    @Test
    void aChoiceProbabilityThatIsNotANumberIsRefusedRatherThanReadAsZero() {
        // Jackson reads "high" as 0.0 when asked for a double. A silent zero would look like a
        // confident rejection of that option.
        String body = """
                {"model":"jev-1.13.0","answers":{"c":{"type":"choice","choice":"a",
                  "probabilities":{"a":0.9,"b":"high"},"confidence":0.5}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("non-numeric probability");
    }

    @Test
    void aChoiceAnswerArrivingWithoutItsProbabilitiesIsRefused() {
        // The distribution is the point. An answer without one has already been flattened by
        // something upstream, and the caller must not mistake it for a full answer.
        String body = """
                {"model":"jev-1.13.0","answers":{"c":{"type":"choice","choice":"a","confidence":0.9}},
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("'probabilities'");
    }

    @Test
    void aTruncatedResponseBodyIsReportedAsMalformedRatherThanThrowingFromJackson() {
        // A half-delivered body must surface as this library's own failure type, carrying the
        // operation, not as a raw Jackson exception leaking through the boundary.
        assertThatThrownBy(() -> codec.decodeResponse("{\"answers\":{\"q\""))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("malformed-response");
    }

    @Test
    void anEmptyResponseBodyIsReportedAsMalformedRatherThanAsANullPointer() {
        // An empty 200 body parses to nothing, and must not become an NPE deeper in.
        assertThatThrownBy(() -> codec.decodeResponse(""))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("malformed-response");
    }

    @Test
    void usageWithoutItsTokenCountsIsRefusedRatherThanReportedAsZero() {
        // Usage is part of the contract. A fabricated zero would under-bill silently.
        String body = """
                {"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":3}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("output_tokens");
    }

    @Test
    void theModelListDecodesNameDescriptionAndReleaseDate() {
        // GET /v1/models, in the documented shape.
        List<ModelCard> models = codec.decodeModels("""
                {"models":[{"name":"jev-latest","description":"Flagship","release_date":"2026-09-01"}]}
                """);

        assertThat(models).containsExactly(new ModelCard("jev-latest", "Flagship", "2026-09-01"));
    }

    @Test
    void theLiveErrorBodyIsTurnedIntoAnApiExceptionCarryingWhatTheApiSaid() {
        // The error body is undocumented. This is the shape the live API returned for a bad key
        // on 25 September 2026, together with its request id header.
        HttpHeaders headers = HttpHeaders.of(Map.of("x-typesafe-request-id", List.of("req_01a0")), (k, v) -> true);

        JevApiException error = codec.decodeError(401, """
                {"detail":{"error_type":"authentication_error","message":"Cannot authenticate with the server."}}
                """, headers);

        assertThat(error.status()).isEqualTo(401);
        assertThat(error.apiErrorType()).isEqualTo("authentication_error");
        assertThat(error.apiMessage()).isEqualTo("Cannot authenticate with the server.");
        assertThat(error.requestId()).contains("req_01a0");
        assertThat(error.getMessage()).contains("req_01a0");
    }

    @Test
    void aValidationErrorListNamesEachOffendingField() {
        // The 422 the live server returned on 25 September 2026 for a request with no state.
        // Each problem keeps its location so the caller can see which field was wrong, and the
        // echoed input is dropped because it would copy the caller's state into a log line.
        JevApiException error = codec.decodeError(422, """
                {"detail":[{"type":"missing","loc":["body","state"],"msg":"Field required",
                            "input":{"questions":{},"model":"jev-1.13.0"}}]}
                """, NO_HEADERS);

        assertThat(error.apiErrorType()).isEqualTo("validation_error");
        assertThat(error.apiMessage()).isEqualTo("body.state: Field required");
    }

    @Test
    void aBareStringDetailIsUsedAsTheMessage() {
        // Observed live as a 400 for a noul with neither instructions nor criteria. Without this
        // case the server's explanation would be buried in an "unparseable" raw body.
        JevApiException error = codec.decodeError(400, """
                {"detail":"Noul question must have criteria or instructions: q"}
                """, NO_HEADERS);

        assertThat(error.status()).isEqualTo(400);
        assertThat(error.apiMessage()).isEqualTo("Noul question must have criteria or instructions: q");
    }

    @Test
    void anErrorBodyThatIsNotJsonIsStillReportedWithItsStatus() {
        // A gateway in front of the evaluator may answer in HTML. Losing the status because the
        // body was not JSON would leave the caller with nothing to act on.
        JevApiException error = codec.decodeError(529, "<html>overloaded</html>", NO_HEADERS);

        assertThat(error.status()).isEqualTo(529);
        assertThat(error.apiErrorType()).isEqualTo("unparseable");
        assertThat(error.apiMessage()).contains("overloaded");
        assertThat(error.requestId()).isEmpty();
    }

    @Test
    void retryAfterMsIsPreferredAndRetryAfterSecondsIsTheFallback() {
        // The two headers the official SDKs honour. The millisecond one is the more precise.
        HttpHeaders both = HttpHeaders.of(Map.of("retry-after-ms", List.of("1500"), "retry-after", List.of("9")),
                (k, v) -> true);
        HttpHeaders seconds = HttpHeaders.of(Map.of("retry-after", List.of("2")), (k, v) -> true);

        assertThat(codec.retryAfter(both)).contains(Duration.ofMillis(1500));
        assertThat(codec.retryAfter(seconds)).contains(Duration.ofSeconds(2));
        assertThat(codec.decodeError(429, "{}", seconds).requestedWait()).contains(Duration.ofSeconds(2));
    }

    @Test
    void aHostileOrUnreadableRetryAfterIsIgnoredRatherThanObeyed() {
        // A negative, non-numeric or date-form header must not become a negative sleep or an
        // exception. The computed backoff applies instead.
        for (String value : List.of("-5", "soon", "NaN", "Infinity", "Wed, 21 Oct 2026 07:28:00 GMT")) {
            HttpHeaders headers = HttpHeaders.of(Map.of("retry-after", List.of(value)), (k, v) -> true);
            assertThat(codec.retryAfter(headers)).as(value).isEmpty();
        }
    }
}
