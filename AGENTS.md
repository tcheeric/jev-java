# Working agreement for implementing agents

Read this before writing any code. It is short because the detail is in
`SPEC.md`, `CONTEXT.md`, `docs/adr/` and your ticket.

## Your inputs, in order of authority

1. **`CONSTITUTION.md`** — normative. Where it conflicts with anything else, it
   wins. The rules that will actually catch you are listed below.
2. **`docs/adr/0001`–`0008`** — the decisions. Never relitigate one in code; if
   you believe an ADR is wrong, say so in your report and stop.
3. **`SPEC.md`** — what the system does.
4. **`CONTEXT.md`** — the vocabulary. Use these words as type names. A concept
   with two names becomes two concepts.
5. **`tickets/NN-*.md`** — your scope, and its acceptance criteria.
6. **`STRUCTURE.md`** — which module owns what.

## Constitution rules you will trip over

- **No literal version in any pom.** Not in `<parent>`, `<dependency>`,
  `<plugin>`, anywhere. The reactor version is `${revision}`. Internal module
  deps omit `<version>` entirely and inherit from the parent
  `<dependencyManagement>`. A new external dependency gets a property in the
  **parent** `<properties>` and a managed entry in the parent. If you find
  yourself typing a digit into a pom, stop.
- **Java 21, virtual threads for I/O.** `Executors.newVirtualThreadPerTaskExecutor()`
  for parallel I/O. `ReentrantLock`, never `synchronized`, to avoid pinning. No
  platform thread pools for I/O.
- **No hand-rolled cryptography.** Schnorr/BIP-340 from `nostr-java`, SHA-256 and
  HMAC from the JDK, everything else from BouncyCastle.
- **`long` for money.** Never `int`, never `Stream.mapToInt` on an amount.
- **Never serialize a private key** into anything returned, logged or hashed.
- **Secrets from the environment**, never in code, config or a commit, and
  redacted from logs.
- **Records over boilerplate. YAGNI.** Three similar lines beat a premature
  helper. No speculative abstraction.
- **Unchecked exceptions** carrying operation and failure-type context.
- **Conventional Commits**, scope = module: `feat(channel-email): ...`.

## Testing, which is not optional

- `*Test.java` are unit tests: JUnit 5 + AssertJ, Arrange-Act-Assert, **no
  network, no file I/O, no sleeps**. Every scenario carries a plain-English
  comment saying what it demonstrates.
- `*IT.java` are integration tests, run by failsafe under `mvn verify`.
- `mvn -q test` MUST pass before you commit. Say so in your report, with the
  output.
- **Adversarial coverage is required**, not a bonus. Your ticket's criteria name
  the attacks. A test that only shows the happy path is an unfinished test.

## The boundaries that are structural, not advisory

These are enforced by `maven-enforcer` and will fail your build. That is
deliberate, and working around one is never the fix:

| Module | Must not depend on | Why |
| --- | --- | --- |
| `support-application` | any transport library | triage must not see the channel (ADR 0008) |
| `support-channel-*` | `support-application` | an adapter that can see the supervisor will call it |
| `support-writer` | `support-application` | it writes and never decides (ADR 0002) |
| `support-investigation` | any channel or transport | a stranger's text must not elicit a send (ADR 0004) |

If your ticket seems to need a banned dependency, you have misread the ticket or
found a real design problem. Report it; do not edit the enforcer.

## House style

Comments explain **why**, never what. A comment that restates the code is
deleted. Where a line exists because of a specific failure, name it: the ADR,
the ticket, or the bug. Prose in Javadoc, not note form.

No em dashes in code comments or commit messages.

## What to do when you finish

Report: what you built, the `mvn -q test` output, which acceptance criteria are
met, which are not, and anything you found that the spec got wrong. **A ticket
criterion you could not satisfy is information, not failure.** Say so plainly
rather than quietly dropping it.
