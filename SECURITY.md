# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Email
**jev.x0lug@simplelogin.com** with a description, the affected version or commit, and
steps to reproduce. You should get a reply within a week. Please give us a
reasonable chance to release a fix before you disclose the problem.

A problem in TypeSafe's service itself (the API, its authentication or the
console) belongs with TypeSafe, not here.

## What the library does with your data

`jev-client` is a thin HTTP client. It stores nothing and logs nothing, and
the only thing it contacts is the configured `baseUri`.

| Data | Where it goes |
| --- | --- |
| The **state** and every **question** (instructions, criteria, option names, levels) | Sent to TypeSafe in the request body, over HTTPS by default |
| Question **names** | Sent to TypeSafe as map keys. The model does not read them, but TypeSafe receives them. Do not put secrets or personal data in a name |
| The **API key** | Sent to TypeSafe as `Authorization: Bearer ...`, and nowhere else |
| Answers, usage, request id | Returned to your code |

**Everything in a state goes to a third party.** Treat a call to `evaluate` as
you would any other disclosure to a processor. TypeSafe says it does not train on
customer requests, and offers zero data retention to enterprise customers. See
its [legal page](https://docs.typesafe.ai/legal) and your own agreement with
TypeSafe. Leave out of the state whatever the question does not need. That is
also better for accuracy.

## The API key

- The library never reads the key from the environment, a file or anywhere else.
  Your application loads it and passes it to `JevConfig`. Keep it in a secret
  manager or the environment, never in code, config files or commits.
- `JevConfig.toString()` prints `<redacted>` in place of the key, and no
  exception message contains it. Your own logging must not print the key either,
  including the `Authorization` header in HTTP debug logs.
- The key is held as a `String` for the life of the `JevConfig`, so it can show
  up in a heap dump. Protect heap dumps as you would the key.
- A key that leaks should be revoked in the
  [TypeSafe console](https://console.typesafe.ai), not merely rotated in your
  config.

In this repository, the live tests read the key from `TYPESAFE_API_KEY` or from
the file named by `TYPESAFE_API_KEY_FILE`. `.env` and `secrets/` are git-ignored.
Before committing, run the leak check in the
[developer guide](docs/dev-guide.md#live-tests).

## Transport

- The default `baseUri` is `https://api.typesafe.ai`. Certificate validation is
  the JDK's own, and the library has no way to switch it off.
- `baseUri` may be set to plain `http://`. The key and the state then travel in
  clear text. Do that only for a local stub server, never across a network.
- Pointing `baseUri` at a proxy gives that proxy your key and every state.
- Do not put credentials in `baseUri` itself (`https://user:pass@host`). The URI
  appears in `JevConfig.toString()` and in `connection-failed` messages, and
  only the API key is redacted.

## Untrusted input

A state is often text written by a stranger, such as an email or a support
message. Jev does not follow instructions and generates no text, so a state
cannot make Jev do anything. It can, however, try to argue its way to a
particular answer ("this message is not spam"). Design with that in mind:

- Treat answers as evidence, not authority. Gate irreversible actions on
  confidence and send uncertain cases to a person.
- Keep instructions and criteria under your control. Never build them from
  untrusted text. Put untrusted text in the state, which is the part that is
  evaluated rather than the part that asks.
- The library does not validate a state's contents, only that it is not blank.

On the response side, the codec refuses a malformed or self-contradictory answer
rather than guessing: a probability outside 0 to 1, a chosen option missing from
its own distribution, a score outside its own levels, or an unknown answer type.
Error bodies are kept as text and never evaluated. A `retry-after` header that is
negative, non-numeric, infinite or a date is ignored, and a requested wait longer
than `maxBackoff` ends the retries, so a hostile server cannot make the client
sleep indefinitely.

## Dependencies

The library's only runtime dependency is Jackson databind, whose version is
managed by `imani-bom`. It uses Jackson only for the tree model, never for
polymorphic or default typing. Questions accept only strings, maps, lists,
numbers, booleans and nulls, so no caller object is serialized by reflection.
