# Release Process

The release workflow runs when a GitHub Release is published. Its tag must be `v<version>` and exactly match the root Maven project version. Snapshot versions are rejected.

The workflow runs `clean verify` on Java 21 and Java 25, checks module boundaries, then builds the Java 21 plugin and aggregate CycloneDX SBOM. It publishes `StarGenesis.jar`, `StarGenesis-Integrations-SDK.jar`, `bom.json`, `bom.xml` and `SHA256SUMS.txt` to the release. GitHub Artifact Attestations bind each asset digest to the workflow identity and source revision.

Verify an asset checksum with `sha256sum -c SHA256SUMS.txt`. Verify its provenance from a checkout with GitHub CLI using `gh attestation verify StarGenesis.jar --repo addxiaoyi/StarGenesis`.

This workflow has not yet run against a non-SNAPSHOT project version or a published GitHub Release.
