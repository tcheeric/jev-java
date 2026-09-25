# Developer guide

For anyone changing `jev-client`. Read [AGENTS.md](../AGENTS.md) and
[CONSTITUTION.md](../CONSTITUTION.md) first. They are normative, and this guide
does not repeat them. It covers how this repository builds, where things live,
and the rules the code keeps.

AGENTS.md was written for the support-agent repository. `SPEC.md`,
`CONTEXT.md`, `STRUCTURE.md`, `tickets/` and `docs/adr/` do not exist here. In
this repository, `TICKET.md` is the ticket, `WIRE.md` is the specification, and
the ADRs are the support agent's (see [Design background](#design-background)).
Its module-boundary table describes the support agent's modules, not these.

## Build

Requirements: JDK 21, Maven 3.9+, and network access to `maven.398ja.xyz` the
first time (it hosts `imani-bom`, which manages every external version).

| Command | Runs |
| --- | --- |
| `mvn -q test` | unit tests (`*Test.java`). No network, no files, no sleeps. **Must pass before every commit.** |
| `mvn -q verify` | the unit tests, plus the integration tests (`*IT.java`) under failsafe |
| `mvn install -DskipTests` | installs the jar locally for a consumer such as imani-support-agent |

Add `-o` to work offline once the dependencies are cached.

## Layout

```
pom.xml                       reactor: ${revision}, imani-bom import, plugin versions
jev-client/                   the library, package xyz.tcheeric.jev
  Question, Answer            sealed interfaces over records: the three types each
  Evaluation, Usage,          what one call returns
  ModelCard
  JevClient                   HTTP, auth, timeout, retry loop. Knows no JSON
  JevWireCodec                the only class that knows paths, field names, headers
  JevConfig, RetryPolicy      configuration. RetryPolicy is pure (no clock, no RNG)
  JevException,               unchecked failures carrying operation + failureType
  JevApiException
  Names                       package-private validation shared by the records
jev-client-it/                integration tests only, no main code
  JevClientIT                 client over HTTP against a WireMock stub
  JevClientRetryIT            backoff and retry-after timing, measured on the stub
  JevLiveIT                   the real API, when a key is available
WIRE.md                       the wire format, and how sure we are of each part
```

## Rules the code keeps

Each rule has a test that fails if it is broken. Do not weaken the test. If you
think a rule is wrong, say so and stop.

| Rule | Why | Enforced by |
| --- | --- | --- |
| No method counts, compares dates, or turns an answer into a decision | Consumers own their thresholds (ADR 0002). Jev is unreliable at arithmetic and date ordering | `NoDecisionHelpersTest`, which scans public method names for fragments such as `count`, `before`, `after`, `threshold` and `decide`, and rejects any boolean method on an answer |
| `Answer.Noul` has no confidence | Gating a noul on confidence is a category error (ADR 0003) | `AnswerTest` asserts the record's components exactly |
| Only `JevWireCodec` knows JSON field names, paths and headers | A wire change is one class and `WIRE.md` (ADR 0003) | Review. `grep '"' JevClient.java` should find no field names |
| Moving model aliases are refused | An upstream release must not silently change calibrated answers | `JevConfigTest` |
| The API key never appears in `toString` or in a message | Constitution VII | `JevConfigTest` |
| Only 429, 529 and connection failures are retried | Retrying a "no" only spends the caller's rate limit | `RetryPolicyTest`, `JevClientIT` |
| `ReentrantLock`, never `synchronized`. Virtual-thread executor for HTTP | Constitution VI, to avoid pinning | Review |
| No literal version in any pom | Constitution, dependency rule | Review. Versions live in parent `<properties>` or `imani-bom` |

`NoDecisionHelpersTest` matches name **fragments**, so an innocent name can trip
it. `waitBefore` became `nextWait`, and the exception's `retryAfter()` became
`requestedWait()`. Rename the method. Do not edit the fragment list.

## Tests

Unit tests follow Arrange-Act-Assert. Every test starts with a plain-English
comment saying what it demonstrates and why it matters. A test that shows only
the happy path is unfinished. Each change needs its failure case: malformed
input, a hostile header, or a server that contradicts itself.

The library is never mocked against itself. HTTP behaviour is tested against a
real server: WireMock locally, or the real API.

### Response fixtures

Fixtures in `JevWireCodecTest` are copied **verbatim** from the API reference or
from a live response, and a comment says which. When the server surprises you,
capture the real body (strip the key and any caller data) and turn it into a
fixture before changing the codec.

### Timing tests

`JevClientRetryIT` measures waits from the stub server's own request log rather
than from a sleep in the test. Keep tolerances loose (floors, not exact values),
because CI machines are slow.

## Live tests

`JevLiveIT` calls the real API. It spends real, if tiny, amounts of money. It
runs under `mvn verify` when a key is available, and is skipped when none is.

The key is read, in order, from:

1. `TYPESAFE_API_KEY`
2. the file named by `TYPESAFE_API_KEY_FILE`

A file that is configured but unreadable fails the run rather than skipping it.
`TYPESAFE_BASE_URL` points the tests at another endpoint.

Local setup, both files git-ignored:

```sh
mkdir -p secrets && chmod 700 secrets
printf '%s' "<your key>" > secrets/typesafe-api-key && chmod 600 secrets/typesafe-api-key
printf 'TYPESAFE_API_KEY_FILE=%s\n' "$PWD/secrets/typesafe-api-key" > .env

set -a; . ./.env; set +a
mvn verify                                   # everything
mvn verify -pl jev-client-it -Dit.test=JevLiveIT   # live only
```

Live tests assert **shapes and invariants**: types decode, keys match, the model
is the pinned version, errors have the observed form. They never assert a
particular probability, because the model's answers are not ours to pin.

Before committing, check the key has not leaked:

```sh
git grep -F "$(cat secrets/typesafe-api-key)" && echo LEAK
```

## Common changes

### The wire format changed

1. Reproduce the new behaviour with `curl` and save the body.
2. Add it as a fixture in `JevWireCodecTest`, first as a failing test.
3. Change `JevWireCodec` only. If a record must change, that is a breaking
   change: mark the commit with `!` and add a `BREAKING CHANGE:` footer.
4. Update `WIRE.md`, and say whether the change is documented, observed live,
   or inferred.
5. `mvn verify` with a key.

### A new model version

1. Change `JevConfig.DEFAULT_MODEL`. The live test
   `theDefaultModelIsOneTheEvaluatorAcceptsAndAnswersAs` confirms the server
   answers as that version.
2. Record the change in `CHANGELOG.md`. For a consumer this is a behaviour
   change, even though no API changed, because their thresholds may need
   re-tuning.

### A new question or answer type

Add a record to the sealed `Question` or `Answer` interface. The compiler then
points to every `switch` that must handle it. Update the codec, `WIRE.md`, the
user guide and `NoDecisionHelpersTest.PUBLIC_SURFACE`.

## Consumers

| Consumer | Uses | Notes |
| --- | --- | --- |
| `imani-support-agent` | `support-application/.../JevEvaluator` | The only place it sees Jev types. Its `ProductionPorts` builds the client from `SUPPORT_JEV_BASE_URL` and `SUPPORT_JEV_API_KEY` |

After a breaking change, install locally and recompile each consumer from clean.
A plain `compile` can use stale class files and report a false success:

```sh
mvn install -DskipTests
(cd ../imani-support-agent && mvn clean compile -pl support-application -am)
```

## Commits and releases

- Conventional Commits, with the module as the scope:
  `fix(jev-client): ...`, `test(jev-client-it): ...`, `docs: ...`.
- No em dashes in commits or code comments.
- A change to a public type is `!` plus a `BREAKING CHANGE:` footer, and gets a
  `CHANGELOG.md` entry with migration steps.
- The version is `${revision}` in the parent pom. Change it there only.
  Deployment to `maven.398ja.xyz` is configured in the parent
  `<distributionManagement>`. Credentials go in `~/.m2/settings.xml`, never in
  the repository. The library has not been deployed yet.

## Design background

The decisions behind the library live in the imani-support-agent repository,
which commissioned it:

- ADR 0002: Jev decides, a language model writes, and code owns every threshold
- ADR 0003: the Jev client is a standalone library, typed against the API

`TICKET.md` is the original ticket. Its acceptance criteria still hold.
