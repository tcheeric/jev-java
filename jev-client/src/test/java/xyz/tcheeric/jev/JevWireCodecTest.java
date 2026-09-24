package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire format these tests assert is documented, and assumed, in WIRE.md.
 */
class JevWireCodecTest {

    private final JevWireCodec codec = new JevWireCodec();

    @Test
    void oneRequestCarriesTheStateTheModelAndEveryQuestionWithItsName() {
        // A fan-out is one request, not three. Each question keeps the caller's name so the
        // answers can be handed back keyed by it.
        String json = codec.encodeRequest("jev-1-20260101", "a support email", List.of(
                new Question.Noul("is_spam", "This message is spam."),
                new Question.Choice("intent", "What does the sender want?", List.of("refund", "sales")),
                new Question.Score("severity", "How severe is this?", 1, 5)));

        assertThat(json)
                .contains("\"model\":\"jev-1-20260101\"")
                .contains("\"state\":\"a support email\"")
                .contains("\"name\":\"is_spam\"").contains("\"type\":\"noul\"")
                .contains("\"name\":\"intent\"").contains("\"options\":[\"refund\",\"sales\"]")
                .contains("\"name\":\"severity\"").contains("\"min\":1").contains("\"max\":5");
    }

    @Test
    void allThreeAnswerShapesComeBackTypedAndKeyedByName() {
        // The response is unpicked into the three record types, each carrying what its shape
        // carries and nothing else.
        String body = """
                {"answers":[
                  {"name":"is_spam","type":"noul","probability":0.93},
                  {"name":"intent","type":"choice",
                   "distribution":{"refund":0.7,"sales":0.3},"chosen":"refund","confidence":0.82},
                  {"name":"severity","type":"score",
                   "distribution":{"1":0.1,"4":0.9},"value":4,"confidence":0.66}],
                 "usage":{"input_tokens":412,"output_tokens":37,"total_tokens":449}}
                """;

        Evaluation evaluation = codec.decodeResponse(body);

        assertThat(evaluation.noul("is_spam").probability()).isEqualTo(0.93d);
        assertThat(evaluation.choice("intent").distribution()).containsEntry("sales", 0.3d);
        assertThat(evaluation.score("severity").value()).isEqualTo(4);
        assertThat(evaluation.usage()).isEqualTo(new Usage(412L, 37L, 449L));
    }

    @Test
    void theTokenUsageIsHandedBackRatherThanDiscarded() {
        // A consumer that pays per token needs the bill it just incurred.
        String body = """
                {"answers":[{"name":"q","type":"noul","probability":0.5}],
                 "usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}
                """;

        Usage usage = codec.decodeResponse(body).usage();

        assertThat(usage.inputTokens()).isEqualTo(10L);
        assertThat(usage.outputTokens()).isEqualTo(4L);
        assertThat(usage.totalTokens()).isEqualTo(14L);
    }

    @Test
    void aTotalTheApiOmittedIsDerivedRatherThanReportedAsZero() {
        // Reporting zero for an absent total would make a caller billing on it under-count
        // silently, which is worse than being slightly approximate.
        String body = """
                {"answers":[{"name":"q","type":"noul","probability":0.5}],
                 "usage":{"input_tokens":10,"output_tokens":4}}
                """;

        assertThat(codec.decodeResponse(body).usage().totalTokens()).isEqualTo(14L);
    }

    @Test
    void anAnswerOfAnUnknownTypeIsRefusedRatherThanGuessedAt() {
        // A type this client does not know is a wire format change. Guessing would hand the
        // caller a silently wrong answer.
        String body = """
                {"answers":[{"name":"q","type":"oracle","probability":0.5}],
                 "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("unknown-answer-type");
    }

    @Test
    void aTruncatedResponseBodyIsReportedAsMalformedRatherThanThrowingFromJackson() {
        // A half-delivered body must surface as this library's own failure type, carrying the
        // operation, not as a raw Jackson exception leaking through the boundary.
        assertThatThrownBy(() -> codec.decodeResponse("{\"answers\":[{\"name\""))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("malformed-response");
    }

    @Test
    void aResponseMissingItsUsageIsRefused() {
        // Usage is part of the contract. Fabricating a zero would hide a real format change.
        assertThatThrownBy(() -> codec.decodeResponse("{\"answers\":[]}"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("usage");
    }

    @Test
    void twoAnswersSharingOneNameAreRefusedRatherThanSilentlyOverwriting() {
        // Keying by name means a duplicate would drop an answer the caller asked for. Better to
        // say so than to hand back the last one that happened to be parsed.
        String body = """
                {"answers":[{"name":"q","type":"noul","probability":0.1},
                            {"name":"q","type":"noul","probability":0.9}],
                 "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("two answers named");
    }

    @Test
    void aChoiceAnswerArrivingWithoutItsDistributionIsRefused() {
        // The distribution is the point. An answer without one has already been flattened by
        // something upstream, and the caller must not mistake it for a full answer.
        String body = """
                {"answers":[{"name":"intent","type":"choice","chosen":"refund","confidence":0.9}],
                 "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                """;

        assertThatThrownBy(() -> codec.decodeResponse(body))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("carries no distribution");
    }

    @Test
    void anErrorBodyIsTurnedIntoAnApiExceptionCarryingWhatTheApiSaid() {
        // The caller should be able to read the API's own words and status without parsing a
        // message string.
        JevApiException error = codec.decodeError(422,
                "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"unanswerable question\"}}");

        assertThat(error.status()).isEqualTo(422);
        assertThat(error.apiErrorType()).isEqualTo("invalid_request_error");
        assertThat(error.apiMessage()).isEqualTo("unanswerable question");
    }

    @Test
    void anErrorBodyThatIsNotJsonIsStillReportedWithItsStatus() {
        // A gateway in front of the evaluator may answer in HTML. Losing the status because the
        // body was not JSON would leave the caller with nothing to act on.
        JevApiException error = codec.decodeError(529, "<html>overloaded</html>");

        assertThat(error.status()).isEqualTo(529);
        assertThat(error.apiErrorType()).isEqualTo("unparseable");
        assertThat(error.apiMessage()).contains("overloaded");
    }
}
