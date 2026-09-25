package xyz.tcheeric.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The single seam between this library's records and the evaluator's HTTP surface: paths, JSON
 * field names and the headers that carry meaning.
 *
 * <p>The format follows the published reference at https://docs.typesafe.ai/api and is
 * documented, with what was verified live and what is still inferred, in {@code WIRE.md}.
 * Nothing else in the library names a JSON field (ADR 0003).</p>
 */
public final class JevWireCodec {

    private static final String EVALUATE_PATH = "/v1/systemone";
    private static final String MODELS_PATH = "/v1/models";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The path appended to the configured base URI for an evaluation.
     */
    public String path() {
        return EVALUATE_PATH;
    }

    public String modelsPath() {
        return MODELS_PATH;
    }

    public String encodeRequest(String model, String state, List<Question> questions) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("state", state);
        ObjectNode byName = root.putObject("questions");
        for (Question question : questions) {
            if (byName.has(question.name())) {
                // The request is a JSON object keyed by name, so a duplicate would silently
                // replace the earlier question and the caller would never get its answer.
                throw new JevException("encode-request", "invalid-argument",
                        "two questions share the name: " + question.name());
            }
            ObjectNode node = byName.putObject(question.name());
            node.set("instructions", mapper.valueToTree(question.instructions()));
            switch (question) {
                case Question.Noul noul -> {
                    node.put("type", "noul");
                    if (noul.whenTrue() != null || noul.whenFalse() != null) {
                        ObjectNode criteria = node.putObject("criteria");
                        if (noul.whenTrue() != null) {
                            criteria.set("true", mapper.valueToTree(noul.whenTrue()));
                        }
                        if (noul.whenFalse() != null) {
                            criteria.set("false", mapper.valueToTree(noul.whenFalse()));
                        }
                    }
                }
                case Question.Choice choice -> {
                    node.put("type", "choice");
                    // Null descriptions are sent as JSON null, which the API documents as "this
                    // option needs no extra detail".
                    node.set("criteria", mapper.valueToTree(choice.criteria()));
                }
                case Question.Score score -> {
                    node.put("type", "score");
                    node.set("criteria", mapper.valueToTree(score.levels()));
                }
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new JevException("encode-request", "serialization-failed",
                    "could not serialize the evaluation request", e);
        }
    }

    public Evaluation decodeResponse(String body) {
        JsonNode root = read(body);
        JsonNode answers = root.get("answers");
        if (answers == null || !answers.isObject()) {
            throw new JevException("decode-response", "malformed-response",
                    "the response carries no 'answers' object");
        }
        JsonNode model = root.get("model");
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            throw new JevException("decode-response", "malformed-response",
                    "the response does not say which model answered");
        }
        Map<String, Answer> byName = new LinkedHashMap<>();
        answers.fields().forEachRemaining(entry -> byName.put(entry.getKey(), decodeAnswer(entry.getKey(), entry.getValue())));
        return new Evaluation(model.asText(), byName, decodeUsage(root.get("usage")));
    }

    public List<ModelCard> decodeModels(String body) {
        JsonNode models = read(body).get("models");
        if (models == null || !models.isArray()) {
            throw new JevException("decode-response", "malformed-response",
                    "the model list carries no 'models' array");
        }
        List<ModelCard> cards = new ArrayList<>();
        for (JsonNode model : models) {
            cards.add(new ModelCard(
                    text(model, "name", "model"),
                    model.path("description").asText(""),
                    model.path("release_date").asText("")));
        }
        return List.copyOf(cards);
    }

    /**
     * Pulls the API's own words out of an error response.
     *
     * <p>The reference does not document the error body. The shape decoded first,
     * {@code {"detail":{"error_type":..., "message":...}}}, is what the live API returned for a
     * missing and for an invalid key on 25 September 2026. A {@code detail} that is a list or a
     * string is also accepted, because the {@code detail} envelope is the convention of the
     * framework the API appears to be served by, which uses those shapes for validation failures.
     * Anything else, including an HTML page from a gateway, is still reported with its status and
     * raw body rather than lost.</p>
     */
    public JevApiException decodeError(int status, String body, HttpHeaders headers) {
        String requestId = headers.firstValue("x-typesafe-request-id").orElse(null);
        Optional<Duration> retryAfter = retryAfter(headers);
        String raw = body == null ? "" : body;
        JsonNode detail;
        try {
            detail = read(raw).path("detail");
        } catch (JevException notJson) {
            return new JevApiException(status, "unparseable", raw, requestId, retryAfter);
        }
        if (detail.isObject() && detail.has("message")) {
            return new JevApiException(status, detail.path("error_type").asText("unknown"),
                    detail.path("message").asText(""), requestId, retryAfter);
        }
        if (detail.isArray()) {
            List<String> problems = new ArrayList<>();
            for (JsonNode problem : detail) {
                String where = problem.path("loc").isArray() ? join(problem.path("loc")) : "";
                String what = problem.path("msg").asText(problem.toString());
                problems.add(where.isEmpty() ? what : where + ": " + what);
            }
            return new JevApiException(status, "validation_error", String.join("; ", problems), requestId, retryAfter);
        }
        if (detail.isTextual()) {
            return new JevApiException(status, "unknown", detail.asText(), requestId, retryAfter);
        }
        return new JevApiException(status, "unparseable", raw, requestId, retryAfter);
    }

    /**
     * The wait the server asked for, from {@code retry-after-ms} or, failing that, from
     * {@code retry-after} in seconds. These are the two headers the official SDKs honour. An
     * HTTP-date form of {@code retry-after} is ignored rather than parsed, because turning it into
     * a wait needs the wall clock and the policy is kept a pure function of its inputs.
     */
    Optional<Duration> retryAfter(HttpHeaders headers) {
        OptionalDouble millis = number(headers.firstValue("retry-after-ms").orElse(null));
        if (millis.isPresent()) {
            return Optional.of(Duration.ofMillis((long) Math.ceil(millis.getAsDouble())));
        }
        OptionalDouble seconds = number(headers.firstValue("retry-after").orElse(null));
        if (seconds.isPresent()) {
            return Optional.of(Duration.ofMillis((long) Math.ceil(seconds.getAsDouble() * 1000d)));
        }
        return Optional.empty();
    }

    private static OptionalDouble number(String header) {
        if (header == null) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(header.trim());
            // A negative, infinite or NaN wait is a broken header, not an instruction.
            return value >= 0 && Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private Answer decodeAnswer(String name, JsonNode node) {
        if (!node.isObject()) {
            throw new JevException("decode-response", "malformed-response", "answer '" + name + "' is not an object");
        }
        String type = text(node, "type", name);
        return switch (type) {
            case "noul" -> new Answer.Noul(name, requiredDouble(node, "noul", name));
            case "choice" -> new Answer.Choice(name,
                    probabilities(node, name),
                    text(node, "choice", name),
                    requiredDouble(node, "confidence", name));
            case "score" -> new Answer.Score(name,
                    requiredDouble(node, "score", name),
                    byLevel(node, "legend", name, JsonNode::isTextual, JsonNode::asText),
                    byLevel(node, "probabilities", name, JsonNode::isNumber, JsonNode::asDouble),
                    requiredDouble(node, "confidence", name));
            default -> throw new JevException("decode-response", "unknown-answer-type",
                    "answer '" + name + "' has unknown type: " + type);
        };
    }

    private Map<String, Double> probabilities(JsonNode node, String name) {
        JsonNode probabilities = requireObject(node, "probabilities", name);
        Map<String, Double> out = new LinkedHashMap<>();
        probabilities.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNumber()) {
                throw new JevException("decode-response", "malformed-response",
                        "answer '" + name + "' gives option '" + entry.getKey() + "' a non-numeric probability");
            }
            out.put(entry.getKey(), entry.getValue().asDouble());
        });
        return out;
    }

    /**
     * Reads a map keyed by level index. The API sends the index as a JSON string key.
     */
    private <V> Map<Integer, V> byLevel(JsonNode node, String field, String name,
                                        java.util.function.Predicate<JsonNode> accepts,
                                        java.util.function.Function<JsonNode, V> value) {
        JsonNode map = requireObject(node, field, name);
        Map<Integer, V> out = new LinkedHashMap<>();
        map.fields().forEachRemaining(entry -> {
            int level;
            try {
                level = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                throw new JevException("decode-response", "malformed-response",
                        "score answer '" + name + "' has a non-numeric level in '" + field + "': " + entry.getKey(), e);
            }
            if (!accepts.test(entry.getValue())) {
                throw new JevException("decode-response", "malformed-response",
                        "score answer '" + name + "' has an unexpected value for level " + level + " in '" + field + "'");
            }
            out.put(level, value.apply(entry.getValue()));
        });
        return out;
    }

    private JsonNode requireObject(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject() || value.isEmpty()) {
            throw new JevException("decode-response", "malformed-response",
                    "answer '" + name + "' carries no '" + field + "'");
        }
        return value;
    }

    private Usage decodeUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            throw new JevException("decode-response", "malformed-response",
                    "the response carries no 'usage' object");
        }
        for (String field : List.of("input_tokens", "output_tokens")) {
            if (!usage.path(field).isIntegralNumber()) {
                throw new JevException("decode-response", "malformed-response",
                        "the response's usage carries no integer '" + field + "'");
            }
        }
        return new Usage(usage.get("input_tokens").asLong(), usage.get("output_tokens").asLong());
    }

    private JsonNode read(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || root.isMissingNode()) {
                throw new JevException("decode-response", "malformed-response", "the evaluator's response was empty");
            }
            return root;
        } catch (JevException e) {
            throw e;
        } catch (Exception e) {
            throw new JevException("decode-response", "malformed-response",
                    "the evaluator's response was not valid JSON", e);
        }
    }

    private static String join(JsonNode path) {
        List<String> parts = new ArrayList<>();
        path.forEach(part -> parts.add(part.asText()));
        return String.join(".", parts);
    }

    private String text(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new JevException("decode-response", "malformed-response",
                    "answer '" + name + "' is missing its '" + field + "'");
        }
        return value.asText();
    }

    private double requiredDouble(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new JevException("decode-response", "malformed-response",
                    "answer '" + name + "' is missing a numeric '" + field + "'");
        }
        return value.asDouble();
    }
}
