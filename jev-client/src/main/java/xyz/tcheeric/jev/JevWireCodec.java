package xyz.tcheeric.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single seam between this library's records and the evaluator's JSON.
 *
 * <p>The wire format is documented in {@code WIRE.md} and is, at the time of writing, an
 * assumption: the real Jev specification was not available. This class exists so that when the
 * real format arrives, the correction is one class and one document rather than a rewrite.
 * Nothing else in the library names a JSON field.</p>
 */
public final class JevWireCodec {

    private static final String PATH = "/v1/evaluate";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The path appended to the configured base URI. Part of the seam: a later correction to the
     * endpoint lands here with the field names.
     */
    public String path() {
        return PATH;
    }

    public String encodeRequest(String model, String state, List<Question> questions) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("state", state);
        ArrayNode array = root.putArray("questions");
        for (Question question : questions) {
            ObjectNode node = array.addObject();
            node.put("name", question.name());
            switch (question) {
                case Question.Noul noul -> {
                    node.put("type", "noul");
                    node.put("statement", noul.statement());
                }
                case Question.Choice choice -> {
                    node.put("type", "choice");
                    node.put("prompt", choice.prompt());
                    ArrayNode options = node.putArray("options");
                    choice.options().forEach(options::add);
                }
                case Question.Score score -> {
                    node.put("type", "score");
                    node.put("prompt", score.prompt());
                    node.put("min", score.min());
                    node.put("max", score.max());
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
        if (answers == null || !answers.isArray()) {
            throw new JevException("decode-response", "malformed-response",
                    "the response carries no 'answers' array");
        }
        Map<String, Answer> byName = new LinkedHashMap<>();
        for (JsonNode node : answers) {
            Answer answer = decodeAnswer(node);
            Answer previous = byName.put(answer.name(), answer);
            if (previous != null) {
                throw new JevException("decode-response", "malformed-response",
                        "the response carries two answers named: " + answer.name());
            }
        }
        return new Evaluation(byName, decodeUsage(root.get("usage")));
    }

    /**
     * Pulls the API's own words out of an error body. A body that is missing or unparseable is
     * still reportable, because a gateway in front of the evaluator may answer in HTML.
     */
    public JevApiException decodeError(int status, String body) {
        try {
            JsonNode error = read(body).path("error");
            if (error.isObject()) {
                return new JevApiException(status,
                        error.path("type").asText("unknown"),
                        error.path("message").asText(""));
            }
        } catch (JevException ignored) {
            // Falls through to reporting the raw body below.
        }
        return new JevApiException(status, "unparseable", body == null ? "" : body);
    }

    private Answer decodeAnswer(JsonNode node) {
        String name = text(node, "name");
        String type = text(node, "type");
        return switch (type) {
            case "noul" -> new Answer.Noul(name, requiredDouble(node, "probability", name));
            case "choice" -> new Answer.Choice(name,
                    choiceDistribution(node, name),
                    text(node, "chosen"),
                    requiredDouble(node, "confidence", name));
            case "score" -> new Answer.Score(name,
                    scoreDistribution(node, name),
                    requiredInt(node, "value", name),
                    requiredDouble(node, "confidence", name));
            default -> throw new JevException("decode-response", "unknown-answer-type",
                    "answer '" + name + "' has unknown type: " + type);
        };
    }

    private Map<String, Double> choiceDistribution(JsonNode node, String name) {
        JsonNode distribution = requireDistribution(node, name);
        Map<String, Double> out = new LinkedHashMap<>();
        distribution.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asDouble()));
        return out;
    }

    private Map<Integer, Double> scoreDistribution(JsonNode node, String name) {
        JsonNode distribution = requireDistribution(node, name);
        Map<Integer, Double> out = new LinkedHashMap<>();
        distribution.fields().forEachRemaining(entry -> {
            int point;
            try {
                point = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException e) {
                throw new JevException("decode-response", "malformed-response",
                        "score answer '" + name + "' has a non-numeric scale point: " + entry.getKey(), e);
            }
            out.put(point, entry.getValue().asDouble());
        });
        return out;
    }

    private JsonNode requireDistribution(JsonNode node, String name) {
        JsonNode distribution = node.get("distribution");
        if (distribution == null || !distribution.isObject() || distribution.isEmpty()) {
            throw new JevException("decode-response", "malformed-response",
                    "answer '" + name + "' carries no distribution");
        }
        return distribution;
    }

    private Usage decodeUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            throw new JevException("decode-response", "malformed-response",
                    "the response carries no 'usage' object");
        }
        long input = usage.path("input_tokens").asLong();
        long output = usage.path("output_tokens").asLong();
        // A total the API did not send is derived rather than reported as zero, since a caller
        // billing on the total would otherwise silently under-count.
        long total = usage.has("total_tokens") ? usage.get("total_tokens").asLong() : input + output;
        return new Usage(input, output, total);
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new JevException("decode-response", "malformed-response",
                    "the evaluator's response was not valid JSON", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new JevException("decode-response", "malformed-response",
                    "an answer is missing its '" + field + "'");
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

    private int requiredInt(JsonNode node, String field, String name) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new JevException("decode-response", "malformed-response",
                    "answer '" + name + "' is missing a numeric '" + field + "'");
        }
        return value.asInt();
    }
}
