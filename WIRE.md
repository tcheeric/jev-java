# Jev wire format

Source: the published reference at <https://docs.typesafe.ai/api> and
<https://docs.typesafe.ai/models>, read on 25 September 2026. The first version of
this client was written before that reference existed and guessed at every field.
None of those guesses survived, so this file records which parts are documented,
which were checked against the live server, and which are still inferred.

`JevWireCodec` is the only class in the library that knows paths, JSON field names
and meaningful headers (ADR 0003). `JevClient` handles records and never touches a
JSON node.

## Endpoints

```
POST {baseUri}/v1/systemone      evaluate a state against typed questions
GET  {baseUri}/v1/models         list the model names the account may send
Authorization: Bearer {token}
Content-Type: application/json   (POST only)
Accept: application/json
```

`baseUri` defaults to `https://api.typesafe.ai` (`JevConfig.DEFAULT_BASE_URI`).

## Request

`questions` is a **map** keyed by the caller's question id. The id never reaches
the model. Every question has `type` and `instructions`. What `criteria` holds
depends on the type.

```json
{
  "model": "jev-1.13.0",
  "state": "Help! My payouts have been failing for 3 days.",
  "questions": {
    "is_urgent":   { "type": "noul",   "instructions": "Does this convey urgency?",
                     "criteria": { "true": "Explicitly time-sensitive",
                                   "false": "No urgency expressed" } },
    "department":  { "type": "choice", "instructions": "Which team should handle this?",
                     "criteria": { "billing": "Payments, invoicing, refunds",
                                   "technical": null } },
    "frustration": { "type": "score",  "instructions": "How frustrated is the customer?",
                     "criteria": ["Calm", "Frustrated", "Very angry"] }
  }
}
```

| Type | `criteria` | Library type |
| --- | --- | --- |
| `noul` | optional `{"true": d, "false": d}`, either side optional | `Question.Noul(name, instructions, whenTrue, whenFalse)` |
| `choice` | required map of option to description or `null`, at most 255 options | `Question.Choice(name, instructions, Map<String, ?>)` or `(name, instructions, List<String>)` |
| `score` | required ordered array of 2 to 10 level descriptions | `Question.Score(name, instructions, List<?>)` |

`instructions` and every description may be a string, an object or an array.
The library accepts a `String`, `Map` or `List` (nested maps and lists of
strings, numbers, booleans and nulls) and refuses anything else at construction,
so no arbitrary object is serialized by reflection. The client also refuses, before
sending, a choice with fewer than 2 or more than 255 options, a score with fewer
than 2 or more than 10 levels, and two questions with the same id. The server
would reject the last case silently: a JSON object keeps only one of them.

`state` is sent as a string. The API also accepts an object or an array. That is
not exposed yet because no consumer needs it.

## Response, 200

`answers` is a **map** keyed by question id.

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "is_urgent":   { "type": "noul", "noul": 0.95 },
    "department":  { "type": "choice", "choice": "billing",
                     "probabilities": { "billing": 0.88, "technical": 0.12 },
                     "confidence": 0.81 },
    "frustration": { "type": "score", "score": 1.05,
                     "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
                     "probabilities": { "0": 0.0, "1": 0.95, "2": 0.05 },
                     "confidence": 0.92 }
  },
  "usage": { "input_tokens": 304, "output_tokens": 18 }
}
```

| Wire | Library |
| --- | --- |
| `model` | `Evaluation.model()`, the versioned ID that answered |
| `noul.noul` | `Answer.Noul.probability()` |
| `choice.choice` / `.probabilities` / `.confidence` | `Answer.Choice.chosen()` / `.distribution()` / `.confidence()` |
| `score.score` | `Answer.Score.score()`, a `double` |
| `score.legend` | `Answer.Score.legend()`, `Map<Integer, String>` |
| `score.probabilities` | `Answer.Score.distribution()`, `Map<Integer, Double>` |
| `usage.input_tokens` / `.output_tokens` | `Usage.inputTokens()` / `.outputTokens()` |

A `noul` answer carries no confidence, in the API and in the library. The absence is
deliberate and load bearing (ADR 0003, story 107): code that tries to gate a noul
on confidence fails to compile. If the server ever adds one, the codec drops it.

A score is the **probability-weighted mean of the level indices**. It is
fractional, lies between 0 and `levels - 1`, and is **not** on a 0 to 1 scale. The
library does not rescale it (ADR 0002). Whether a linear rescale means anything
depends on how evenly the caller spaced its levels.

`usage` has no total. Jev bills input tokens only.

The full distribution is kept for `choice` and `score`. The library never flattens
it and never applies a threshold (ADR 0002).

The codec refuses, rather than guesses at, a response with no `model`, `answers`
sent as an array, an unknown answer type, a missing required field, a non-numeric
probability, a score level key that is not an integer, a legend whose levels
differ from the distribution, a score outside its own levels, or a probability
outside 0..1.

## Errors

The reference lists statuses but does **not** document the error body.

| Status | Meaning | Client behaviour |
| --- | --- | --- |
| 400 | unknown model, unknown question type, or a question the server rejects (observed live, not in the reference) | not retried |
| 401 | invalid API key | not retried |
| 403 | missing API key (observed live, not in the reference) | not retried |
| 422 | request body failed schema validation, for example a missing `state` | not retried |
| 429 | rate limited | retried with backoff, or `retry-after` |
| 529 | overloaded | retried with backoff, or `retry-after` |
| other | anything else | not retried |

Every error seen live on 25 September 2026 carried an `x-typesafe-request-id`
header and a `detail` envelope, in one of three shapes:

```json
401/403: { "detail": { "error_type": "authentication_error", "message": "Cannot authenticate with the server. ..." } }
400:     { "detail": { "error_type": "api_usage_error", "message": "Unknown model: jev-0.0.0-x" } }
400:     { "detail": "Noul question must have criteria or instructions: q" }
422:     { "detail": [ { "type": "missing", "loc": ["body", "state"], "msg": "Field required", "input": {...} } ] }
```

`JevApiException` exposes `status()`, `apiErrorType()` (from `error_type`, or
`validation_error` for the list form, or `unknown` for a bare string),
`apiMessage()` and `requestId()`. The list form becomes `"body.state: Field required"`.
Its `input` echo is dropped, because it would copy the caller's state into a log
line. Any other body, including HTML from a gateway, is kept whole with type
`unparseable`, so the status is never lost.

Also observed: the server **accepts** a score with a single level and answers it
with certainty (score 0.0, confidence 1.0), even though the reference says a score
"should have at least two levels". The client still refuses one level, because an
answer that cannot be anything else carries no information. The server also
ignores an unknown `name` field inside a question body.

The official SDKs retry 408 and every 5xx by default. This client retries only 429,
529 and connection failures, as before: a client that retries every 5xx turns an
evaluator's bad day into an outage.

### Retry-After

The models page says the official SDKs "honor the `retry-after` header when the
response carries one". Their retry policy reads both `retry-after-ms` and
`retry-after`. This client now does the same:

- `retry-after-ms` (milliseconds) is preferred and `retry-after` (seconds) is the fallback.
- The requested wait replaces the computed backoff and is not jittered.
- A requested wait longer than `RetryPolicy.maxBackoff` stops the retries at once,
  with failure type `retry-after-exceeds-backoff`. The wait is on the cause's
  `JevApiException.requestedWait()`, so the caller can reschedule the work.
- An HTTP-date, negative, non-numeric or non-finite value is ignored and the
  computed backoff applies.

Whether the live server sends either header has **not** been observed.

## Model identifier

`JevConfig.DEFAULT_MODEL` is `jev-1.13.0`, the current pinned version. The aliases
`jev-latest` and `jev-preview` are refused when the config is built. The docs'
own advice is to pin a version after tuning confidence thresholds against it.
`GET /v1/models` lists aliases, and a versioned ID is accepted whether or not it
appears there.

## Limits (not enforced by the client)

- Context: 64k tokens per request (state plus all questions), and 32k for the
  state plus the longest question. The client cannot count tokens without the
  model's tokenizer, so an oversized request surfaces as the server's error.
- Rate limits: 1,200 requests per minute and 250,000 tokens per second. The docs
  say these change without notice, which is why they are not hard-coded. Going
  over either one returns 429, which is retried as above.
- Choice options (255) and score levels (2 to 10) are enforced when the question
  is built.

## Verification

- `JevWireCodecTest` decodes the reference's own example responses, copied verbatim.
- `JevClientIT` / `JevClientRetryIT` run the client over HTTP against a WireMock
  stub that speaks this format, including 401/403/422/429/529, `retry-after` and
  the observed error body.
- `JevLiveIT` runs against the real API under `mvn verify` when a key is
  available, and is skipped otherwise. The key comes from `TYPESAFE_API_KEY` or,
  failing that, from the file named by `TYPESAFE_API_KEY_FILE`.
  `TYPESAFE_BASE_URL` optionally overrides the endpoint. Locally, the key lives in
  the git-ignored `secrets/typesafe-api-key`, and the git-ignored `.env` points
  at it:

  ```sh
  set -a; . ./.env; set +a; mvn verify
  ```

  On 25 September 2026 all five live tests passed against `jev-1.13.0`.
