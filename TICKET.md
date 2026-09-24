# 01: Jev client sends one choice question and returns a typed answer

**Destination repo:** new standalone library (ADR 0003)

**What to build:** a Java library a caller can hand a state and one named
`choice` question, and get back the chosen option, the full probability
distribution across the options, and the confidence. Bearer auth, a timeout, and
retry with exponential backoff on `429` and `529`.

This is the narrow first cut of a library that will eventually cover all three
question types. Build only `choice` now, but shape the types so `noul` and
`score` slot in beside it rather than through it: a `noul` answer carries a
probability and *no* confidence, and code that gates on confidence must not be
able to compile against one.

The model is named by configuration, pinned to a version rather than a moving
alias.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] A caller supplies a state and a named choice question and receives the chosen option, the per-option probabilities, and the confidence
- [ ] Token usage from the response is available to the caller rather than discarded
- [ ] The answer type for `choice` exposes confidence; the type system leaves no way to read confidence off an answer that has none
- [ ] `429` and `529` are retried with exponential backoff; `401` and `422` are not retried and surface what the API said
- [ ] The model identifier is configuration and defaults to a pinned version, never `jev-latest`
- [ ] Tested against a stub server speaking the documented wire format, including the error statuses, and never against a mock of the library itself
- [ ] The library contains no helper that counts, compares dates, or converts an answer into a decision
