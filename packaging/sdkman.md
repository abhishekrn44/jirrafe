# SDKMAN

SDKMAN distributes candidates as zip archives with a `bin/` directory, which is exactly what
`./gradlew :jirrafe-cli:distZip` produces (`jirrafe-cli/build/distributions/jirrafe-<version>.zip`),
and what the release workflow attaches to every release.

To publish a version once the candidate `jirrafe` is registered with the SDKMAN vendor API:

```
curl -X POST -H "Consumer-Key: $SDKMAN_KEY" -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -d '{"candidate": "jirrafe", "version": "0.1.0", "url": "https://github.com/abhishekrn44/jirrafe/releases/download/v0.1.0/jirrafe-0.1.0.zip"}' \
  https://vendors.sdkman.io/release

curl -X PUT -H "Consumer-Key: $SDKMAN_KEY" -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -d '{"candidate": "jirrafe", "version": "0.1.0"}' \
  https://vendors.sdkman.io/default
```

Then `sdk install jirrafe`.
