# User guide

How to call Jev from a Java application using `jev-client`. For how Jev itself
thinks (writing good questions, what confidence means, which jobs it is bad at),
read [TypeSafe's documentation](https://docs.typesafe.ai). This guide covers the
Java side.

- [Setup](#setup)
- [Asking questions](#asking-questions)
- [Reading answers](#reading-answers)
- [Structured instructions](#structured-instructions)
- [Configuration](#configuration)
- [Errors](#errors)
- [Retries and rate limits](#retries-and-rate-limits)
- [Choosing and changing the model](#choosing-and-changing-the-model)
- [What the library will not do](#what-the-library-will-not-do)
- [Limits](#limits)

## Setup

Requirements: Java 21, Jackson databind 2.10 or later on the classpath (the
library is built and tested with 2.21.5), and a TypeSafe API
key from the [console](https://console.typesafe.ai). An older Jackson fails at
the first response with `NoSuchMethodError: JsonNode.isEmpty()`.

Install the library (see the [README](../README.md#getting-it)) and add the
dependency. Load the key from your own secret store or environment. The library
never reads the environment itself.

```java
String key = System.getenv("TYPESAFE_API_KEY");
JevClient jev = new JevClient(JevConfig.of(key));
```

`JevClient` is thread-safe, and each client keeps its own HTTP connection pool.
Create one per application and share it, rather than one per request. Close it
when the application stops. It is `AutoCloseable`, and `close()` is safe to call
twice. A closed client refuses further calls with failure type `client-closed`.

## Asking questions

One call sends one **state** (the text being judged) and any number of named
**questions** about it. They go out as a single HTTP request and are evaluated in
parallel, so adding questions to a call costs far less than making more calls.

```java
Evaluation result = jev.evaluate(ticketText, List.of(q1, q2, q3));
// or
Evaluation result = jev.evaluate(ticketText, q1, q2, q3);
```

Every question has a **name**. The name is your key for the answer. The model
never sees it, so name questions for your code, not for the model. Names must be
unique within a call. A duplicate is refused before sending, because the second
would silently replace the first.

### Noul: is it true?

```java
new Question.Noul("is_urgent", "Does this convey urgency?")

// optionally say what yes and no mean
new Question.Noul("is_urgent", "Does this convey urgency?",
        "Explicitly time-sensitive",   // what a yes means
        "No urgency expressed")        // what a no means, or null
```

### Choice: which one?

```java
// options with no extra description
new Question.Choice("department", "Which team should handle this?",
        List.of("billing", "technical", "sales"))

// options with descriptions (use a LinkedHashMap to keep your order)
Map<String, Object> teams = new LinkedHashMap<>();
teams.put("billing", "Payments, invoicing, refunds");
teams.put("technical", "Bugs, outages, integrations");
teams.put("other", null);                               // null: the name says enough
new Question.Choice("department", "Which team should handle this?", teams)
```

A choice needs between 2 and 255 options, with no duplicates.

### Score: how much?

A score rates the state against **ordered level descriptions**, lowest first.
The levels are words, not numbers.

```java
new Question.Score("frustration", "How frustrated is the customer?",
        List.of("Calm", "Frustrated", "Very angry"))
```

A score needs between 2 and 10 levels. Describe each level so a reader could
tell neighbouring levels apart. "1", "2", "3" gives the model nothing to go on.

## Reading answers

Fetch each answer by the name you gave its question, with the accessor for its
type:

```java
Answer.Noul urgent = result.noul("is_urgent");
Answer.Choice team = result.choice("department");
Answer.Score mood  = result.score("frustration");
```

A missing name fails with `missing-answer`. Asking for the wrong type fails with
`answer-type-mismatch`, and the message names both types.

| Answer | Fields |
| --- | --- |
| `Answer.Noul` | `probability()`: 0 to 1, the probability the answer is yes. **No confidence.** |
| `Answer.Choice` | `chosen()`: the most likely option. `distribution()`: every option mapped to its probability, in the order the server sent them (not always the order you asked, so look options up by name). `confidence()`: 0 to 1. |
| `Answer.Score` | `score()`: see below. `legend()`: level index to your description. `distribution()`: level index to probability. Both iterate lowest level first. `confidence()`: 0 to 1. |

Also on `Evaluation`:

- `model()` is the versioned model that actually answered, for example
  `jev-1.13.0`. Log it next to anything you store, so you know which model
  produced it.
- `usage()` has `inputTokens()` and `outputTokens()`. Jev bills input tokens
  only.

### The score scale

`score()` is the probability-weighted average of the **level indices**. The
first level is 0. With three levels, a score of `1.05` means "almost exactly the
middle level, leaning slightly up".

**It is not on a 0 to 1 scale.** It runs from 0 to `levels - 1`. If you need
0 to 1, rescale it yourself:

```java
double unit = mood.score() / (mood.legend().size() - 1);
```

Do this only if your levels really are evenly spaced. If they are not, read
`distribution()` instead.

### Probability and confidence are different things

For choice and score, `confidence()` tells you how sure the model is, and
`distribution()` tells you what it thinks. A choice can put 0.55 on `billing`
with high confidence (the question is close) or with low confidence (the model
is unsure). TypeSafe's [Confidence](https://docs.typesafe.ai/confidence) page
explains the difference. The usual pattern is to act on the answer only when
confidence clears a bar you have set, and send everything else to a person.

A noul answer has no confidence. Its probability already says how sure the model
is, so there is nothing to gate on but the probability.

## Structured instructions

`instructions`, noul criteria, choice descriptions and score levels can each be
a `String`, a `Map` or a `List`. Maps and lists may nest, and may hold strings,
numbers, booleans and nulls. This is how you point a question at reference data
without putting it in the state:

```java
new Question.Noul("same_person", Map.of(
        "candidate", Map.of("name", "John Smith", "location", "Oakland, California"),
        "question", "Is the resume for the same person as `candidate`?"))
```

Anything else, such as a POJO, an `Instant` or a `StringBuilder`, is refused when
the question is built. The library copies what you pass, so changing your map
afterwards does not change a question you have already built.

## Configuration

`JevConfig` is an immutable record. Start from a default and adjust it:

```java
JevConfig config = JevConfig.of(key)                         // https://api.typesafe.ai, jev-1.13.0
        .withRequestTimeout(Duration.ofSeconds(20))
        .withRetryPolicy(new RetryPolicy(3, Duration.ofMillis(500), 2.0, Duration.ofSeconds(10), 0.5));

JevConfig proxied = JevConfig.of(URI.create("https://jev-proxy.internal"), key);
```

| Setting | Default | Notes |
| --- | --- | --- |
| `baseUri` | `https://api.typesafe.ai` | Paths are appended to it |
| `model` | `jev-1.13.0` | Aliases are refused. See [below](#choosing-and-changing-the-model) |
| `requestTimeout` | 30 s | Applies to each attempt, and to connecting |
| `retryPolicy` | 4 attempts, 500 ms initial, ×2, 30 s cap, 0.5 jitter | See [Retries](#retries-and-rate-limits) |

The key is printed as `<redacted>` in `JevConfig.toString()` and never appears in
exception messages. Keep it out of your own logs too.

## Errors

Every failure is an unchecked `JevException` with two fields you can branch on
without parsing the message:

- `operation()`, for example `evaluate`, `build-question`, `decode-response`
- `failureType()`, for example `invalid-argument`, `malformed-response`

When the API itself said no, the exception is a `JevApiException`, which adds:

| Method | |
| --- | --- |
| `status()` | HTTP status |
| `apiErrorType()` | the API's own error type, for example `authentication_error`, `api_usage_error` |
| `apiMessage()` | the API's explanation |
| `requestId()` | TypeSafe's request id. Quote it when asking TypeSafe for help |
| `requestedWait()` | the wait the server asked for, if it gave one |

| Status | Usually means | Retried |
| --- | --- | --- |
| 400 | unknown model, or a question the server rejects | no |
| 401 | invalid API key | no |
| 403 | missing API key | no |
| 422 | request failed validation | no |
| 429 | rate limited | yes |
| 529 | overloaded | yes |
| other | anything else | no |

Failure types raised by the client itself:

| `failureType()` | Cause |
| --- | --- |
| `invalid-argument` | a question, config or call argument broke a rule. Raised before anything is sent |
| `malformed-response` | the server's 200 response did not match the documented format |
| `unknown-answer-type` | the server returned an answer type this client does not know |
| `missing-answer` / `answer-type-mismatch` | you asked for a name that is not there, or as the wrong type |
| `connection-failed` | the server could not be reached. Retried |
| `retries-exhausted` | every attempt failed. The last failure is the cause |
| `retry-after-exceeds-backoff` | the server asked for a longer wait than your policy allows. See below |
| `api-error-<status>` | a `JevApiException` |
| `client-closed`, `interrupted` | lifecycle |

A typical handler:

```java
try {
    return jev.evaluate(text, questions);
} catch (JevApiException e) {
    if (e.status() == 401 || e.status() == 403) throw new IllegalStateException("bad Jev key", e);
    log.warn("Jev refused request {}: {}", e.requestId().orElse("?"), e.apiMessage());
    return fallbackToHuman();
} catch (JevException e) {
    log.warn("Jev unavailable: {} {}", e.operation(), e.failureType());
    return fallbackToHuman();
}
```

If you cannot get an answer, fail toward a safe default, such as a person
looking at it. Never fail toward acting as if the answer were yes.

## Retries and rate limits

Only 429, 529 and connection failures are retried. Every other status is a
statement about the request, and repeating the request would only spend your
rate limit to be told the same thing again. Other 5xx statuses are deliberately
not retried either, because a client that retries every 5xx makes an outage
worse.

Between attempts the client waits `initialBackoff × multiplier^(attempt-1)`,
capped at `maxBackoff`. Jitter then shortens each wait by up to `jitterFactor`,
so clients that failed together do not all retry at the same moment.

If the server sends `retry-after-ms` or `retry-after` (in seconds), that wait
replaces the computed one. If the server asks for longer than `maxBackoff`, the
client stops at once with `retry-after-exceeds-backoff` instead of retrying too
early. The requested wait is on the cause:

```java
} catch (JevException e) {
    if (e.getCause() instanceof JevApiException api && api.requestedWait().isPresent()) {
        scheduler.schedule(retryTask, api.requestedWait().get());
    }
}
```

The worst-case time `evaluate` can block is about
`maxAttempts × requestTimeout + the sum of the waits`. With the defaults, that is
roughly two minutes. Tighten the timeout and policy if a caller is waiting on the
result.

TypeSafe's rate limits are 1,200 requests a minute and 250,000 tokens a second.
TypeSafe says these change without notice. Batching questions into one call is
the cheapest way to stay under them.

## Choosing and changing the model

The default is the pinned version `jev-1.13.0`. The aliases `jev-latest` and
`jev-preview` are refused. An alias moves when TypeSafe ships a release, and
any threshold you tuned against the old model would silently start meaning
something else.

To move to a new version:

1. Read TypeSafe's [Models](https://docs.typesafe.ai/models) page and the
   "jaggedness" notes for the new version.
2. Run your own questions against both versions, using `withModel("jev-x.y.z")`,
   and compare the distributions.
3. Re-tune your thresholds, then change the configured model in one commit.

`jev.models()` lists the names your account may send, with a description and a
release date for each. Today it lists only the aliases. A versioned ID is
accepted even when it is not in the list.

## What the library will not do

These are design decisions, not missing features:

- **No decisions.** There is no `isSpam()`, no threshold, and no "best answer
  above X". Your code owns every threshold, as a named constant that a test can
  assert.
- **No counting or date comparison.** Jev is unreliable at arithmetic and at
  ordering dates. Do those in code, and ask Jev only for judgements.
- **No confidence on a noul.** See above.
- **No key loading.** Your application owns its secrets.

## Limits

| Limit | Enforced by |
| --- | --- |
| 2 to 255 choice options | the client, when the question is built |
| 2 to 10 score levels | the client, when the question is built |
| 64k tokens per request (state plus all questions) | the server |
| 32k tokens for the state plus the longest question | the server |
| Text input only | the server |

The client cannot count tokens, so an oversized request comes back as an error
from the server. Keep states small and relevant, because accuracy drops as a
state fills up with irrelevant detail.
