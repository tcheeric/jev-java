# jev-java

A Java 21 client for [TypeSafe's Jev](https://docs.typesafe.ai), the evaluator
that answers typed questions about a piece of text with calibrated probabilities.
TypeSafe publishes SDKs for Python and JavaScript only. This library covers Java.

```java
try (JevClient jev = new JevClient(JevConfig.of(System.getenv("TYPESAFE_API_KEY")))) {
    Evaluation result = jev.evaluate("Help! My payouts have been failing for 3 days.",
            new Question.Noul("is_urgent", "Does this convey urgency?"),
            new Question.Choice("department", "Which team should handle this?",
                    List.of("billing", "technical", "sales")),
            new Question.Score("frustration", "How frustrated is the customer?",
                    List.of("Calm", "Frustrated", "Very angry")));

    double urgent = result.noul("is_urgent").probability();          // 0.0 .. 1.0
    Answer.Choice team = result.choice("department");                // chosen, distribution, confidence
    Answer.Score mood = result.score("frustration");                 // score 0.0 .. 2.0, legend, distribution
}
```

## What it is

- **Typed questions and answers.** The three question types are `Noul` (yes or
  no), `Choice` (one of a set) and `Score` (a point on an ordered rubric). Each
  has a matching answer record. A noul answer has no confidence, so code that
  tries to read one does not compile.
- **One request for many questions.** Every question about a state goes out in a
  single HTTP call, and the answers come back keyed by the names you chose.
- **Nothing decided for you.** The library returns full probability
  distributions and applies no threshold. Deciding what counts as "spam" or
  "urgent enough" is the caller's job, so there is no `isSpam()`.
- **Pinned model.** The default is `jev-1.13.0`. The moving aliases
  `jev-latest` and `jev-preview` are refused, so an upstream release cannot
  silently change answers you have tuned against.
- **Polite retries.** Only `429`, `529` and connection failures are retried,
  with exponential backoff and jitter. A `retry-after` header is honoured.

## Status

`0.1.0-SNAPSHOT`. It speaks the published API and is tested against the live
service. It is **not yet published** to a Maven repository, so for now you
install it locally (see below). The public types changed incompatibly in
`0b5e099`. See [CHANGELOG.md](CHANGELOG.md).

## Getting it

```sh
git clone <this repository> && cd jev-java
mvn install -DskipTests
```

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>jev-client</artifactId>
    <version>${jev-client.version}</version>
</dependency>
```

Define `jev-client.version` as a property in your parent pom. The only
runtime dependency is Jackson.

## Documentation

| Document | For |
| --- | --- |
| [User guide](docs/user-guide.md) | Calling Jev from your application: questions, answers, errors, retries, configuration |
| [Developer guide](docs/dev-guide.md) | Changing this library: build, tests, live tests, the rules the code keeps |
| [WIRE.md](WIRE.md) | The HTTP wire format, and which parts are documented, observed live, or inferred |
| [CHANGELOG.md](CHANGELOG.md) | What changed, and how to migrate |
| [TypeSafe docs](https://docs.typesafe.ai) | The evaluator itself: how to write good questions, confidence, limits |

## Layout

```
jev-client/      the library (xyz.tcheeric.jev)
jev-client-it/   integration tests: a WireMock stub server, and the live API
WIRE.md          the wire format
docs/            user and developer guides
```

## Licence

No licence has been chosen yet. Until one is, this is not open for reuse.
