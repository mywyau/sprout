# Releasing Sprout

Users install Sprout from GitHub release assets and do not need sbt. The release workflow uses sbt in
CI to build and test those assets.

## Release contents

For version `X.Y.Z`, CI publishes:

- `sprout-X.Y.Z.tar.gz`
- `sprout-X.Y.Z.zip`
- `sprout-X.Y.Z-checksums.txt`
- `sprout.rb`
- `install.sh`

The archives contain a relocatable application jar, Unix and Windows launchers, license, README, and
version marker. They require Java 17 or newer. The installer verifies SHA-256 before changing the
active launcher and retains versions side by side for straightforward rollback.

## Publishing

Before tagging, ensure the intended commit is on `main` and CI is green. A semantic version tag starts
the release workflow:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow validates the tag, injects its version into the jar manifest, runs all tests, packages
the archives, performs a clean installation smoke test, generates a Homebrew formula, and only then
creates the GitHub release.

Do not recreate a published tag. Fix the issue and publish a new version.

## Homebrew tap

The generated `sprout.rb` is a valid release asset and can be installed as a local formula. To enable
`brew install mywyau/tap/sprout`, create a `mywyau/homebrew-tap` repository and copy the generated file
to `Formula/sprout.rb` after each release. Automating the cross-repository update requires a narrowly
scoped token and is intentionally deferred until that repository exists.
