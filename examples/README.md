# Examples

Runnable programs that use `jev-client` the way an application would. They are
compiled and tested on every `mvn verify` (`ExamplesIT`, against a stub server),
so they cannot drift out of date with the library.

| Example | Shows |
| --- | --- |
| [`Triage`](src/main/java/xyz/tcheeric/jev/examples/Triage.java) | One request with all three question types, and **your code** owning the thresholds: route when confident, otherwise send to a person, and never act on a failure |
| [`QuestionTypes`](src/main/java/xyz/tcheeric/jev/examples/QuestionTypes.java) | Everything each answer carries, structured instructions that refer to reference data by name, and the model list |

## Running them

They call the real API, which costs a little. From the repository root:

```sh
mvn -q install -DskipTests                  # once, so the examples can find jev-client

export TYPESAFE_API_KEY=...                 # or TYPESAFE_API_KEY_FILE=/path/to/key
mvn -q -pl examples exec:java                                   # Triage, default message
mvn -q -pl examples exec:java -Dexec.args="The export button crashes the app"
mvn -q -pl examples exec:java -Dexample=QuestionTypes
```

In this repository, `set -a; . ./.env; set +a` loads the key file setting.

Sample output from `Triage`, recorded on 25 September 2026:

```
queue:  billing
urgent: true
mood:   Frustrated
why:    billing at confidence 0.98, answered by jev-1.13.0
```

## Copying from them

`ExampleConfig` reads the key from the environment because examples have to get
it from somewhere. The library never does, so load the key your application's
own way. `Triage`'s thresholds (`ROUTE_CONFIDENCE`, `URGENT_PROBABILITY`) are
illustrative. Tune your own against your own data.
