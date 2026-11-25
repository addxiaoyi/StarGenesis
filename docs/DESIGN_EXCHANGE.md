# Signed Design Exchange

`DesignExchange` signs canonical design JSON with Java 21 Ed25519. The `.sgdesign` v1 package contains the declared author UUID, public-key fingerprint, Ed25519 X.509 public key, canonical JSON and signature. The signature binds the domain `StarGenesis/DesignSignature/1`, author UUID, SHA-256 fingerprint and SHA-256 digest of the complete canonical design. `DesignCodec` verifies the design's own content hash and schema before signing or after reading.

`SignedDesignArtifact.decode` rejects unknown formats, malformed UTF-8, truncated fields, trailing bytes and artifacts larger than the design byte budget plus 512 bytes. Public keys and signatures use defensive copies. Verification requires the package author to match `DesignMetadata.author` and the exact public-key bytes returned by the server-owned `DesignSigningTrust` for that author UUID. A public key carried only in the package is not trusted.

Paper persists up to 256 author keys under `plugins/StarGenesis/design-trust/`. Each `.key` record binds the author UUID, Ed25519 key bytes and a checksum, and replaces keys atomically. OP commands manage the trust pins:

| Command | Operation |
|---|---|
| `/genesis design keys` | List pinned author UUIDs and SHA-256 public-key fingerprints |
| `/genesis design trust <author-uuid> <base64-ed25519-key>` | Add or rotate an author's X.509-encoded Ed25519 public key |
| `/genesis design revoke <author-uuid>` | Remove trust for future imports from the author |
| `/genesis design reviewers list` | List pinned arena reviewer UUIDs and public-key fingerprints |
| `/genesis design reviewers trust <reviewer-uuid> <base64-ed25519-key>` | Add or rotate a reviewer's X.509-encoded Ed25519 public key |
| `/genesis design reviewers revoke <reviewer-uuid>` | Remove trust for new arena decisions from the reviewer |
| `/genesis arena decisions [page]` | Read 20 immutable arena decisions per page; requires `stargenesis.arena.review` |
| `/genesis arena decision <sha256>` | Inspect the recorded statement, signature and actual publication state; requires `stargenesis.arena.review` |
| `/genesis arena recover <sha256> <reviewer-uuid> <original-signature>` | Explicitly resume an existing signed approval, from console or an authorized player; requires `stargenesis.arena.review` |
| `/genesis design import <file-key>` | Verify `design-imports/<file-key>.sgdesign`; submit an arena ruleset for moderation or archive another design kind |
| `/genesis design imports [page]` | List archived blueprints, programs, protocols and contracts |
| `/genesis design inspect <artifact-sha256>` | Inspect an archived package by its complete artifact hash |

The signed import directory is `plugins/StarGenesis/design-imports/`. The file key is limited to 64 lowercase ASCII letters, digits, dots, underscores and dashes. Imports must be regular files within this directory and are size-bounded during reading. The administrator provisions publisher public keys out-of-band. The signing private key belongs to the author and must never be placed on the Minecraft server.

Paper submits signed arena rulesets to the existing moderation queue and persists their verified key fingerprints with review submissions and immutable review decisions. Reviewers still validate current author permissions and published dependencies. The design author cannot approve their own rule. Signed blueprints, programs, protocols and contracts are archived as their original `.sgdesign` packages under `plugins/StarGenesis/design-library/`. The archive is bounded to 256 packages, keyed by the SHA-256 of the complete signed package, written with atomic replacement, and revalidates each package signature and strict design schema when listed or inspected. Import requires the author's currently pinned key. Existing archived packages remain inspectable after a trust rotation or revocation because archive reads validate the signature without treating the embedded key as new authorization.

Archived non-arena designs are metadata-visible and remain declarative data. The server does not activate them, execute their contents or load classes from packages. The archive does not grant design edit, dependency, publication or gameplay permissions.

The design module supplies a signed-data SDK rather than a Java plugin class loader. Loading third-party bytecode, automatically trusting its embedded key, runtime capability activation, encrypted key storage and external publisher coordination remain outstanding.

Arena review decisions use a separate UUID-to-key trust store under `plugins/StarGenesis/arena-review-trust/`. Generate or hold reviewer private keys outside the server, for example with `openssl genpkey -algorithm Ed25519 -out reviewer-private.pem`. Run `node scripts/arena_review_sign.mjs public-key --private-key reviewer-private.pem` and have an OP pin the printed `publicKeyBase64` with `/genesis design reviewers trust <reviewer-uuid> <base64-ed25519-key>`. To sign a decision, run `node scripts/arena_review_sign.mjs --private-key reviewer-private.pem --hash <sha256> --reviewer <uuid> --decision approve`; for rejection add `--reason "<reason>"`. Copy `signatureBase64` from its JSON output into `/genesis arena approve <sha256> <signature>` or `/genesis arena reject <sha256> <signature> <reason>`.

The signature covers `StarGenesis/ArenaReviewDecision/1`, the candidate hash, reviewer UUID, decision and exact rejection reason. The server timestamp is recorded separately. New v3 decision records retain the reviewer public key, fingerprint and signature so they remain verifiable after trust revocation. Existing v1/v2 decisions remain readable; their unsigned retry commands only recover or repeat the same already-persisted decision and cannot create new decisions. A signature proves possession of the key pinned to the account UUID; it does not establish a real-world identity or server/operator trustworthiness.

Read the stored proof with `/genesis arena decision <sha256>`, available to authorized players and the server console. When it reports `APPROVED_AWAITING_PUBLICATION`, use the stored reviewer UUID and original signature with `/genesis arena recover <sha256> <reviewer-uuid> <original-signature>` to explicitly resume publication. The command requires an existing signed approval and cannot approve a new candidate. It rechecks the latest author/editor authorization and exact release dependencies, keeps the immutable decision and supports retry after write failures. Existing signed approvals remain recoverable after reviewer-key rotation or revocation. Unsigned legacy approvals require their original reviewer's in-game retry.

Independently verify an approval without any private key:

```sh
node scripts/arena_review_sign.mjs verify --hash <sha256> --reviewer <uuid> --decision approve --public-key <publicKeyBase64> --signature <signatureBase64> --fingerprint <expected-reviewer-fingerprint>
```

For a rejection, use `--decision reject --reason "<exact reason>"`. Obtain the expected fingerprint from the independently established reviewer trust record, rather than trusting an unknown public key carried with a signature. Verification returns JSON with `valid` and exits with status 0 only for a valid statement and matching fingerprint. A mismatched fingerprint or statement returns `valid: false` and status 1; malformed input also exits with status 1. Neither the verifier nor archive queries authorize new decisions. Reasons use at most 240 UTF-16 code units; malformed surrogate sequences are rejected before signing. Do not normalize Unicode or change spaces when copying a reason.
