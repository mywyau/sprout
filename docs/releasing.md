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
creates the GitHub release. After the release assets exist, it commits the generated formula to the
Homebrew tap. Release publication is idempotent so a failed tap update can be retried safely.

Do not recreate a published tag. Fix the issue and publish a new version.

## Homebrew tap

The public tap is `mywyau/homebrew-tap`, exposed to users as `mywyau/tap`. The release workflow checks
it out after publishing the release and updates `Formula/sprout.rb` only when its contents changed.
This makes the new version available through:

```bash
brew update
brew upgrade sprout
```

Cross-repository access uses the `HOMEBREW_TAP_TOKEN` Actions secret in `mywyau/sprout`. The token must
be fine-grained to `mywyau/homebrew-tap` with only `Contents: read and write` and `Metadata: read`.
Never place its value in source, workflow output, or release assets. Rotate the token before it expires
and update the repository secret with:

```bash
gh secret set HOMEBREW_TAP_TOKEN --repo mywyau/sprout
```
