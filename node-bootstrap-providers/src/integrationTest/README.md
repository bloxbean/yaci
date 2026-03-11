# Bootstrap Providers Integration Tests

These tests verify `BlockfrostBootstrapProvider` and `KoiosBootstrapProvider` against live preprod APIs.

## Koios

No API key required. Tests are `@Disabled` by default — uncomment the annotation to run:

```bash
./gradlew :node-bootstrap-providers:integrationTest --tests "KoiosBootstrapProviderIT"
```

## Blockfrost

Requires a Blockfrost project ID via the `BF_PROJECT_ID` environment variable. Tests are automatically skipped when the variable is not set.

```bash
export BF_PROJECT_ID=preprodYourProjectIdHere
./gradlew :node-bootstrap-providers:integrationTest --tests "BlockfrostBootstrapProviderIT"
```

## Run Both

```bash
export BF_PROJECT_ID=preprodYourProjectIdHere
./gradlew :node-bootstrap-providers:integrationTest
```

## Re-running Tests

Gradle caches test results and skips tests that already passed if nothing changed. To force a re-run:

```bash
./gradlew :node-bootstrap-providers:integrationTest --rerun --tests "BlockfrostBootstrapProviderIT"
```

The `--rerun` flag ignores cached results and executes all matching tests regardless of prior outcomes.
