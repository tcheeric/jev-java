# Changelog

All notable changes to `jev-client`. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/). The version is the reactor
`${revision}`.

## [Unreleased]

### Changed

- The build no longer imports `imani-bom`. Each dependency version is pinned in
  the parent pom against its upstream BOM (`jackson-bom`, `junit-bom`), and the
  enforcer rejects any Imani artifact. No resolved version changed: Jackson is
  still 2.21.5, with annotations at 2.21. Consumers see no difference except
  that jev-client no longer needs anything from the Imani stack.

## [0.1.0] - 2026-09-25

The first release. Before it, `0.1.0-SNAPSHOT` went through a breaking rewrite,
listed below for anyone who built against an early snapshot.

### Changed (breaking)

The first cut (`10ec087`) was written against a guessed wire format, and every
request it made failed against the real API. `0b5e099` rewrote it against the
published reference. Migration:

| Before | After |
| --- | --- |
| `new Question.Noul(name, statement)` | `new Question.Noul(name, instructions)`, or `(name, instructions, whenTrue, whenFalse)` |
| `new Question.Choice(name, prompt, List<String> options)` | same shape, now `(name, instructions, List<String>)`. Or pass a `Map<String, ?>` of option to description |
| `new Question.Score(name, prompt, min, max)` | `new Question.Score(name, instructions, List<?> levels)`, with 2 to 10 described levels, lowest first |
| `Question.Noul.statement()`, `Choice.prompt()`, `Score.prompt()` | `instructions()` |
| `Question.Choice.options()` | `criteria().keySet()` |
| `Answer.Score.value()` (int, on your min..max scale) | `Answer.Score.score()` (double, **0 to levels - 1**), plus `legend()` |
| `Evaluation(answers, usage)` | `Evaluation(model, answers, usage)`. `model()` is the version that answered |
| `Usage.totalTokens()` | removed. Use `inputTokens()`, which is what Jev bills |
| `JevConfig.DEFAULT_MODEL = "jev-1-20260101"` | `"jev-1.13.0"`. `jev-preview` is now refused as well as `jev-latest` |
| `JevWireCodec.decodeError(status, body)` | `decodeError(status, body, headers)` |

A consumer that rescaled an integer score to 0..1 with `(value - min) / (max - min)`
now uses `score() / (legend().size() - 1)`, and should first check that its
levels are evenly spaced.

### Added

- `JevConfig.of(apiKey)` and `DEFAULT_BASE_URI` (`https://api.typesafe.ai`).
- `JevClient.models()` and `ModelCard`, for `GET /v1/models`.
- `JevApiException.requestId()` (from `x-typesafe-request-id`) and `requestedWait()`.
- `retry-after-ms` and `retry-after` are honoured. A requested wait longer than
  `maxBackoff` ends the retries with `retry-after-exceeds-backoff`.
- Structured `instructions` and criteria: a `String`, `Map` or `List`.
- Client-side validation of the API's limits: 2 to 255 choice options, 2 to 10
  score levels, and unique question names.
- `JevLiveIT`, run against the real API when `TYPESAFE_API_KEY` or
  `TYPESAFE_API_KEY_FILE` is set.

- Sources and javadoc jars, published with the snapshot to `maven.398ja.xyz`.
- `jev-examples/` module (`Triage`, `QuestionTypes`), tested against a stub on every build.
- MIT licence and `SECURITY.md`.

### Fixed

- Score `legend()` and `distribution()` now iterate lowest level first, and a
  choice's `distribution()` keeps the server's order. Both used to be copied with
  `Map.copyOf`, which scrambled the order.

- Endpoint `/v1/evaluate` changed to `/v1/systemone`.
- Request and response `questions` / `answers` are maps, not arrays.
- Answer field names: `noul`, `choice`, `probabilities`, `score`, `legend`.
- Error bodies decoded from the observed `{"detail": ...}` envelope, in all three
  observed shapes, instead of the assumed `{"error": ...}`.
- A 403 for a missing key and a 400 for an unknown model are recognised as final.
