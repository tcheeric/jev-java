<!--
  Sync Impact Report
  ==================
  Version change: 1.2.0 -> 1.3.0 (MINOR: new normative "Distribution &
    Deployment" rule)
  Amendment (1.3.0): Added a normative "Distribution & Deployment" bullet
    to the Development Workflow: Dalia-owned Java modules deploy to the
    private Maven repository maven.398ja.xyz via the parent
    <distributionManagement> (398ja-releases / 398ja-snapshots), credentials
    only in settings.xml / CI secrets (never committed); the deployable
    services dalia-engine and dalia-pilot-client publish container images to
    docker.398ja.xyz tagged with ${revision}; the pilot runs from the
    repo-root docker-compose.yml (reusing the cashu-mint compose); the TS SDK
    stays an in-repo file: dependency; CI/CD under .github/workflows runs the
    Java + TS suites and, on a release tag, deploys jars + pushes images.
    Added: parent <distributionManagement>, dalia-engine/Dockerfile,
    dalia-pilot-client/Dockerfile, docker-compose.yml, .github/workflows/
    {ci,release}.yml, settings.xml.example, settings.xml.ci, Makefile,
    spring-boot-starter-actuator on dalia-engine (for /actuator/health).

  ------------------------------------------------------------------
  Prior report (1.1.0 -> 1.2.0)
  ==================
  Version change: 1.1.0 -> 1.2.0 (MINOR: expanded normative version rule)
  Amendment (1.2.0): Strengthened the Development Workflow "Versions"
    bullet into a normative "Dependency & Version Management" rule: the
    single reactor version is the ${revision} property; parent <version>
    and every module <parent><version> use ${revision} (resolved for
    install/deploy by flatten-maven-plugin); internal module deps omit
    <version> and inherit from the parent <dependencyManagement>; plugin
    versions are pinned once in the parent <pluginManagement> via a
    <properties> entry; NO literal version may appear in any pom
    (<project>/<parent>/<dependency>/<plugin>). Applied to the reactor
    and all six module poms (dalia-core/disclosure/ledger/lifecycle/
    trust/scoring); .flattened-pom.xml added to .gitignore.

  ------------------------------------------------------------------
  Prior report (1.0.0 -> 1.1.0)
  ==================
  Version change: 1.0.0 -> 1.1.0 (MINOR: new normative naming rule)
  Amendment (1.1.0): Added "Coordinates & package naming" to the
    Development Workflow section — Dalia-owned artifacts use Maven
    groupId 398ja.xyz; Java base package dalia.core, modules under
    dalia.<component>; external Imani deps (imani-bom, nostr-java-core)
    keep groupId xyz.tcheeric. Propagated to specs/001-v1-pilot/
    (plan, research, data-model, quickstart, tasks, contracts),
    docs/plans/2026-07-07-phase-1-core-primitives.md, and CLAUDE.md.

  ------------------------------------------------------------------
  Prior report (1.0.0 initial ratification)
  ==================
  Version change: 0.0.0 (template) -> 1.0.0
  Modified principles: N/A (initial ratification)
  Added sections:
    - I. Deterministic, Recomputable Scoring (NON-NEGOTIABLE)
    - II. Protocol Compliance (Cashu NUTs, Nostr NIPs, Imani specs)
    - III. Privacy & Consent-Gated Disclosure
    - IV. Layered Architecture & Backend Minimalism
    - V. Testing Discipline (Adversarial)
    - VI. Virtual Threads for Concurrency
    - VII. Secure Coding & Code Quality
    - Security Requirements
    - Development Workflow
    - Governance
  Removed sections: None (template placeholders replaced in 1.0.0)
  Adapted from cashu-mint (1.1.0) and imani-gateway-core (1.0.0)
  constitutions:
    - Renamed Token Integrity -> Deterministic, Recomputable Scoring
      (Dalia's existential invariant is a reproducible, incentive-
      compatible score, not token supply)
    - Kept Protocol Compliance, retargeted at the Cashu/Nostr/Imani
      substrate Dalia rides (NUT-10/11, NIP-05/17/42/44/98, reserved
      kinds 9100-9109 / 39100-39109)
    - Kept Privacy/Data-Custody principle, retargeted at asymmetric
      privacy + consent-gated disclosure + ZK-gates-GA
    - Merged Clean Architecture with the v1 backend-minimalism
      constraint (G7: one backend change only)
    - Removed Stripe/mint-value clauses (Dalia moves no principal)
  Templates requiring updates:
    - .specify/templates/plan-template.md — ✅ compatible
      (Constitution Check gate is constitution-driven, no hardcoded
      principle names)
    - .specify/templates/spec-template.md — ✅ compatible
    - .specify/templates/tasks-template.md — ✅ compatible
  Follow-up TODOs: None
-->

# Dalia Constitution

## Core Principles

### I. Deterministic, Recomputable Scoring (NON-NEGOTIABLE)

Dalia's reason to exist is a credit score that any authorised party
can recompute and trust. Every code path that ingests loan events or
produces a score MUST preserve the following invariants:

- **Determinism**: the score `S` is a pure function of the triple
  *(event set, Disclosure Package, scoring-profile version)*. Two
  verifiers with identical triples MUST produce byte-identical
  scores and scorecards (§12.10, SC-3). Library scoring/serialization
  paths MUST NOT read wall-clock (`Instant.now()`), RNG
  (`SecureRandom`), locale, or environment; timestamps and keys are
  passed in by callers.
- **Cross-language byte-parity**: `dalia-core` is the canonical
  implementation. Canonical JSON, hashing, `loan_id` derivation,
  co-signing, and the IOU secret MUST be byte-identical to the mirror
  TS client. The checked-in `test-vectors/` are the contract; a
  divergence is a build failure, not a warning.
- **Incentive-compatibility invariants**: the formal properties
  **IC-1…IC-6** (§13.1 — abandonment dominance, victim neutrality,
  cure monotonicity, fabrication unprofitability, arbiter
  outcome-neutrality, payment monotonicity) are non-negotiable. Any
  change to the scoring formula, state machine, or weights MUST
  re-verify IC-1…IC-6 with tests before merge (G8).
- **Versioned scoring profile**: every formula constant lives in the
  published, signed scoring profile (kind-39101). A score is
  meaningless without its profile version; the version MUST travel
  with every emitted score. Profile upgrades are announced, overlap
  old+new, and ship a changelog (§12.9).
- **Completeness / anti-omission**: public commitment salts and
  `HMAC(dk_epoch, salt)` linkage tags MUST make dk-only enumeration
  work so a borrower cannot silently omit a loan or a default.
  Undocumented loans score at the worst-case presumption (§10.5).
- **Advisory, never bare**: a score MUST always be delivered with its
  scorecard and the disclosed loan set. The scalar MUST NOT be
  surfaced alone.

Scoring-integrity violations are blocking defects. Performance,
ergonomics, and refactor cleanliness MUST yield to determinism and
the IC properties.

### II. Protocol Compliance (Cashu NUTs, Nostr NIPs, Imani specs)

Dalia rides existing rails and MUST implement its substrate protocols
faithfully and visibly. The authoritative upstream sources are the
Cashu NUT and Nostr NIP specifications and the Imani platform specs.

- **Cashu**: the IOU is a NUT-10 well-known secret (custom kind
  `"IOU"`) on a dedicated **zero-value, blind-issued keyset**; the
  mint MUST refuse all value operations (mint-for-value, swap, melt)
  on that keyset and MUST NOT inspect secrets. Fee/bond escrows use
  NUT-11 P2PK / 2-of-3 spend conditions.
- **Nostr**: identity is anchored on **NIP-05** (bottin); encrypted
  detail events use **NIP-17/44** gift wrap; relay writes are gated
  by **NIP-42 / NIP-98** authenticated identities; relay policy
  requirements **R-1…R-4** (created_at drift rejection, indefinite
  retention of commitment kinds, gated+rate-limited writes,
  authenticated-users definition of "public") are normative.
- **Reserved kinds (locked, O-1)**: Dalia owns **9100–9109**
  (regular) and **39100–39109** (addressable). Kind 9100 carries all
  lifecycle commitments; 39100/39101 carry the profile pointer and
  scoring-profile document. New event kinds MUST come from these
  reserved blocks. The 9100–9109 block MUST be cross-verified against
  backend Java repos before first deploy (§7.3).
- **Faithful advertisement**: the state machine (§8, 25 transitions),
  outcome classes (§7.4), and IOU core shape (§6.2) are the wire
  contract. Non-standard or admin extensions MUST be clearly
  separated from protocol paths.
- Breaking changes to any on-wire shape (IOU secret, commitment
  payload, event kinds, canonical serialization) MUST bump the
  project's MAJOR version.
- Upstream NUT/NIP amendments to specs Dalia relies on MUST be
  tracked: a follow-up issue MUST be filed within one release cycle
  to re-test or drop the affected behaviour.

### III. Privacy & Consent-Gated Disclosure

Dalia is private toward the public and fully disclosable to a chosen
counterparty. Cryptographic non-custodiality (blind issuance) covers
tokens; this principle covers the DATA the protocol records.

- **Asymmetric privacy is architectural**: borrowers appear on-relay
  only as per-loan ephemeral subkeys; lenders appear as stable public
  keys (§10.4). Borrower stable identity MUST NOT appear in any
  public commitment or in the IOU core.
- **Public reveals nothing sensitive**: public commitments MUST NOT
  expose principal, borrower identity, or the borrower's cross-loan
  graph — only outcome class, coarse amount bucket, coarse epoch, and
  the two keys (§7.2).
- **Mint blindness**: the mint MUST never see loan terms; blind
  issuance is mandatory (fixes the v0.1 mint-as-loan-registry hole,
  §6.4).
- **Forward privacy**: disclosure keys are a backward hash-chain of
  epoch keys derived from the wallet master seed; sharing an epoch key
  MUST NOT grant perpetual surveillance to a past verifier (§10.3).
- **Informed consent before disclosure**: because a disclosed file is
  the borrower's *complete itemized history* (past-lender identities,
  amounts, terms, every outcome), a normative informed-consent notice
  MUST be shown before a disclosure key is shared (§10.6).
- **No selective hiding**: disclosure MUST expose defaults; a borrower
  MUST NOT be able to omit a loan or a bad outcome to look better.
- **ZK gates general availability**: v1 ships consent-gated full
  disclosure as the interim; there MUST be no open onboarding of real
  borrowers until the zero-knowledge privacy-from-lenders build (v2)
  ships (D-G, D-G′). Pilots run closed.

Scoring integrity (Principle I) takes precedence: privacy measures
that would weaken determinism, completeness, or the IC properties are
rejected. The two are designed to coexist.

### IV. Layered Architecture & Backend Minimalism

Dalia is a layered protocol (L0 money legs, L1 identity/sybil,
L2 instrument, L3 ledger, L4 scoring, §5) implemented as clean,
inward-pointing Java modules.

- **Layer independence**: each layer MUST be usable without the layer
  below except where explicitly composed. A loan with no arbiter and
  off-protocol money uses no L0 (at the price of evidence discounts).
- **Clean modules**: `dalia-core` is pure, I/O-free primitives
  (canonical serialization, hashing, cores, co-signing, delegation
  certs, IOU secret) with **no relay, no mint, no network, no
  wall-clock, no RNG** in library paths. Higher modules depend only
  inward. Mint, relay, and identity access MUST go through ports;
  infrastructure MUST NOT leak into core or scoring layers.
- **Events are authoritative; the token face is best-effort**:
  scoring reads the commitment/settlement trail, never the live IOU
  face (§5).
- **v1 backend-change budget is exactly one** (G7): the cashu-mint
  zero-value IOU keyset. All lifecycle, disclosure, dispute, and
  scoring logic is **client-side + relay**. Any proposed new backend
  service, endpoint, or schema change is presumed **rejected in v1**
  and MUST be justified against G7 in the PR before it can be
  considered.

### V. Testing Discipline (Adversarial)

All code MUST meet the following testing standards:

- **Unit tests** (`*Test.java`): run via `mvn -q test` using JUnit 5
  (Jupiter) and AssertJ; follow Arrange-Act-Assert; fast — no
  network, no file I/O, no sleeps; every scenario carries a
  plain-English comment.
- **Integration tests** (`*IT.java`): run via `mvn -q verify`; cover
  relay publish/scan, mint issuance, and cross-module flows.
- **Golden vectors**: the `test-vectors/` fixtures MUST be asserted by
  both the Java suite and the TS client suite; canonical-JSON, hash,
  `loan_id`, signature, and IOU-secret parity is a required gate.
- **Adversarial coverage**: the five modelled attacks — sybil /
  self-dealing, key abandonment, hiding defaults, exit scams / skim,
  and lender extortion / hold-up — MUST each have tests demonstrating
  the defense, mapped to the threat table (T-1…T-23).
- **IC property tests**: IC-1…IC-6 MUST be covered by the success
  criteria tests (SC-8, SC-9, SC-9b, SC-10, SC-14, etc.); a formula
  change that breaks any IC test MUST NOT merge.
- **State-machine coverage**: every one of the 25 transitions (§8),
  including timeout, cure, and partial-cure paths, MUST be exercised.

### VI. Virtual Threads for Concurrency

Java 21 Virtual Threads (Project Loom) are the standard concurrency
model for I/O-bound work (relay fan-out, mint calls, multi-loan scan):

- Use `Executors.newVirtualThreadPerTaskExecutor()` with
  `CompletableFuture` for parallel I/O.
- Use `ReentrantLock` instead of `synchronized` to avoid VT pinning.
- MUST NOT create platform thread pools for I/O work.
- CPU-bound work (hashing, schnorr verification, EigenTrust power
  iteration) MAY use parallel streams but MUST NOT block VT workers
  on I/O.
- Where a Spring Boot surface exists, virtual-thread support MUST be
  enabled in configuration.

### VII. Secure Coding & Code Quality

- Input validation at system boundaries (relay events, mint
  responses, delegation certs, disclosure packages); untrusted event
  fields are validated before they touch scoring.
- **No hand-rolled cryptography**: schnorr/BIP-340 comes from
  `nostr-java` (the same implementation relays and the mint verify
  against); SHA-256 from the JDK; other primitives from BouncyCastle.
  Hand-rolled signatures, HMAC, or hashing are forbidden.
- **Signature verification precedes trust**: a commitment or core is
  used for scoring/state only after its signatures verify and the
  signer set matches the transition's authorised signers (§7.2, §8).
- **Never serialize private keys** into any returned or hashed
  structure; only pubkeys and signatures appear in a core.
- **`long` (minor-unit) arithmetic** for all monetary amounts; `int`
  and `Stream.mapToInt(...)` over amounts are forbidden in scoring
  and validation paths.
- Secrets (wallet seeds, disclosure keys, private keys) MUST live in
  environment/secret managers, never in code, configs, or commits,
  and MUST be redacted from logs, traces, and metrics.
- OWASP Top 10 vulnerabilities are blocking defects.
- YAGNI and SOLID: no speculative abstractions; three similar lines
  beat a premature helper; prefer unchecked exceptions carrying
  operation + failure-type context; use Java records to reduce
  boilerplate.

## Security Requirements

- **Relay policy is load-bearing**: R-1 (±10 min created_at drift
  rejection), R-2 (indefinite retention of Dalia commitment kinds),
  and R-3 (NIP-42/98-gated, rate-limited writes) MUST be verified for
  every relay Dalia depends on; scores are only as trustworthy as the
  epoch clock these rules enforce. Parties MUST publish every
  commitment to **≥ 2 relays** and retain signed local copies for
  re-broadcast.
- **Mint rate-limiting**: IOU issuance MUST be rate-limited per
  authenticated identity (O-13 defaults: ~60/identity/day, burst
  ~10/min) — the entire mint-layer spam control, since blind issuance
  cannot gate on loan validity.
- **Sybil economics**: the Dalia-scoped one-time registration fee and
  `w_cp` / `w_ev` / `w_pair` weightings are the load-bearing sybil
  defenses; changes that weaken them require explicit sign-off.
- **No secrets in commits**: seeds, disclosure keys, and private keys
  MUST be excluded by `.gitignore` and verified before commit.
- **Security-event logging**: rejected events, signature failures,
  premature/invalid transitions, and disclosure operations MUST be
  logged with structured, redacted fields.
- **Dependency vulnerabilities**: critical CVEs in transitive deps
  MUST be addressed within one release cycle.

## Development Workflow

- **Build**: `mvn -q test` MUST pass before committing; `mvn -q
  verify` (including integration tests and cross-language vector
  parity) MUST pass before merge.
- **Commits**: Conventional Commits (`feat(scope):`, `fix(scope):`,
  `docs(scope):`, …); scope SHOULD name the affected module
  (`core`, `ledger`, `scoring`, `instrument`, `identity`, `mint`).
- **Dependency & Version Management** (normative): **No pom — parent or
  module — may contain a literal version anywhere.** All versions are
  defined once, in the parent `pom.xml`, as `<properties>` (or inherited
  from `imani-bom`), and referenced everywhere else. Specifically:
  - The single reactor version is the **`${revision}`** property in the
    parent. The parent's own `<version>` and **every** module's
    `<parent><version>` MUST be `${revision}` — never a literal. The
    `flatten-maven-plugin` (`resolveCiFriendliesOnly`) resolves it in
    installed/deployed poms so no unresolved property leaks to consumers.
  - **Internal Dalia module dependencies** MUST omit `<version>` and
    inherit it from the parent `<dependencyManagement>` (which uses
    `${project.version}`).
  - **External dependency versions** come from the `imani-bom` import;
    any unmanaged one is pinned via a parent `<properties>` entry.
  - **Plugin versions** are pinned **once** in the parent
    `<pluginManagement>` (version held in a `<properties>` entry) and
    inherited — never repeated inline in a module `<plugin>` block.
  - Inline version literals in `<project>`, `<parent>`, `<dependency>`,
    or `<plugin>` blocks are **prohibited**. A pom that hard-codes a
    version is presumed rejected and MUST be justified in the PR.
  - Use `/bumpup` for coordinated bumps (edit the property, not the poms).
- **Coordinates & package naming** (normative): all Dalia-owned Maven
  artifacts MUST use groupId **`398ja.xyz`**. The Java base package is
  **`dalia.core`**, and every module MUST live under the
  **`dalia.<component>`** root (e.g. `dalia.disclosure`, `dalia.ledger`,
  `dalia.scoring`, `dalia.trust`, `dalia.arbiter`, `dalia.registration`,
  `dalia.engine`). The Maven groupId and the Java package root are
  deliberately decoupled: the groupId is `398ja.xyz`, the package root is
  `dalia.*`. External Imani-stack dependencies keep their own coordinates
  (e.g. `imani-bom` and `nostr-java-core` remain groupId `xyz.tcheeric`)
  and MUST NOT be renamed. A new module that deviates from this scheme is
  presumed rejected and MUST be justified in the PR.
- **Distribution & Deployment** (normative): Dalia's own artifacts are
  published only to the private 398ja registries.
  - **Maven**: every Dalia-owned Java module (groupId `398ja.xyz`) MUST be
    deployable to the private Maven repository **`maven.398ja.xyz`** (a
    reposilite instance) via the parent `<distributionManagement>` (server ids
    `reposilite-releases` / `reposilite-snapshots`); the same repositories are
    declared in the parent `<repositories>` for resolution (public read).
    Deploy credentials live only in `~/.m2/settings.xml` (see
    `settings.xml.example`) or CI secrets — **never committed**. The
    `flatten-maven-plugin` keeps deployed poms free of `${revision}` and any
    literal version.
  - **Docker**: the deployable services — `dalia-engine` and
    `dalia-pilot-client` — MUST publish container images to the private
    registry **`docker.398ja.xyz`**, tagged with `${revision}` (and `latest`).
    The full pilot runs from the repo-root `docker-compose.yml`, which reuses
    the cashu-mint's own compose rather than re-dockerising it.
  - **TypeScript**: `dalia-client-sdk` remains an in-repo `file:` dependency
    (not published); only `dalia-pilot-client` is containerised.
  - CI/CD (`.github/workflows/`) MUST run the Java + TS test suites on every
    change, and on a release tag deploy the jars to `maven.398ja.xyz` and push
    the images to `docker.398ja.xyz`.
- **Branching**: feature branches; PRs target the integration branch;
  released versions are tracked on the release branch.
- **Code review**: all PRs require review. Any change to the scoring
  formula, state machine, IC properties, canonical serialization, or
  privacy/disclosure surface MUST be reviewed by a second maintainer
  with explicit attention to Principles I and III, and MUST show the
  re-verified IC and vector-parity tests passing.
- **Task tracking**: on completing a planned task, update the status
  table with the commit id and a note.

## Governance

This constitution is the authoritative source of project standards
for Dalia. It supersedes ad-hoc practices and informal conventions.

- **Amendments**: any change to this constitution MUST be documented
  with rationale, reviewed by a maintainer, reflected in the version
  below, and summarised in the Sync Impact Report at the top of this
  file.
- **Versioning**: MAJOR for principle removals/redefinitions, MINOR
  for new principles or material expansions, PATCH for clarifications
  and wording fixes.
- **Compliance**: all PRs and code reviews MUST verify adherence to
  these principles. Violations of Principle I (Deterministic,
  Recomputable Scoring) — including any unverified change to the IC
  properties — are blocking and require maintainer sign-off to merge
  under any exception clause.
- **Runtime guidance**: see the implementation plans under
  `docs/plans/` and the specification in
  `docs/dalia-reputation-lending-spec.md` for module structure,
  determinism rules, and the cross-language contract.

**Version**: 1.3.0 | **Ratified**: 2026-07-07 | **Last Amended**: 2026-07-11
