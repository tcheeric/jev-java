# Jev wire format (ASSUMED)

The real Jev API specification was not available when this client was written.
Everything below is an **assumption**, invented so the client could be built and
tested end to end. It is documented here so a later correction is a change to one
class, `JevWireCodec`, and this file, rather than a rewrite.

`JevWireCodec` is the only place in the library that knows JSON field names.
`JevClient` speaks records and never touches a JSON node.

## Endpoint

```
POST {baseUri}/v1/evaluate
Authorization: Bearer {token}
Content-Type: application/json
Accept: application/json
```

## Request

One request carries one state and a fan-out of named questions.

```json
{
  "model": "jev-1-20260101",
  "state": "the text or serialized state under evaluation",
  "questions": [
    { "name": "is_spam",   "type": "noul",   "statement": "This message is spam." },
    { "name": "intent",    "type": "choice", "prompt": "What does the sender want?",
      "options": ["refund", "support", "sales"] },
    { "name": "severity",  "type": "score",  "prompt": "How severe is this?",
      "min": 1, "max": 5 }
  ]
}
```

`name` is the caller's key. It is echoed back on every answer and is how the
caller retrieves its typed answer.

## Response, 200

```json
{
  "answers": [
    { "name": "is_spam", "type": "noul", "probability": 0.93 },
    { "name": "intent",  "type": "choice",
      "distribution": { "refund": 0.7, "support": 0.2, "sales": 0.1 },
      "chosen": "refund", "confidence": 0.82 },
    { "name": "severity", "type": "score",
      "distribution": { "1": 0.05, "2": 0.1, "3": 0.15, "4": 0.5, "5": 0.2 },
      "value": 4, "confidence": 0.66 }
  ],
  "usage": { "input_tokens": 412, "output_tokens": 37, "total_tokens": 449 }
}
```

A `noul` answer carries a probability and **no** confidence field. That absence
is deliberate and load bearing (ADR 0003, story 107): a caller that tries to gate
a `noul` on confidence must fail to compile, so the wire format must not offer
one either.

The full probability distribution is preserved for `choice` and `score`. The
library never flattens it, and never applies a threshold: a threshold is the
consumer's decision (ADR 0002).

## Errors

Error bodies share one shape:

```json
{ "error": { "type": "rate_limit_error", "message": "too many requests" } }
```

| Status | Meaning | Client behaviour |
| --- | --- | --- |
| 401 | bad or missing bearer token | not retried, surfaces the API message |
| 422 | malformed request or unanswerable question | not retried, surfaces the API message |
| 429 | rate limited | retried with exponential backoff and jitter |
| 529 | evaluator overloaded | retried with exponential backoff and jitter |
| other 4xx/5xx | anything else | not retried, surfaces status and body |

Connection failures (`IOException`) are retried on the same schedule, because a
refused or dropped connection carries no evidence the request was handled.

`Retry-After` is deliberately **not** honoured yet: the header's presence in the
real API is unknown, and guessing at it would be a second assumption layered on
the first.

## Model identifier

The model is configuration and defaults to a pinned, dated version
(`JevConfig.DEFAULT_MODEL`). A moving alias is rejected at construction time, so
that a silent upstream model change cannot alter a consumer's answers.
