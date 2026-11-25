import assert from 'node:assert/strict'
import { generateKeyPairSync, verify } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import { arenaReviewPayload, arenaReviewerKeyInfo, signArenaReview, verifyArenaReview } from './arena_review_sign.mjs'

const REVIEWER = '00000000-0000-0000-0000-000000000032'
const HASH = 'a'.repeat(64)

test('signs the same domain-separated payload consumed by the Java verifier', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const result = signArenaReview({
    privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    hash: HASH,
    reviewer: REVIEWER,
    decision: 'approve',
  })
  const payload = arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'approve' })

  assert.equal(payload.toString('utf8'), `StarGenesis/ArenaReviewDecision/1\n${HASH}\n${REVIEWER}\nAPPROVE\n`)
  assert.equal(result.reviewer, REVIEWER)
  assert.equal(result.signatureBase64.length, 88)
  assert.equal(result.publicKeyBase64, publicKey.export({ type: 'spki', format: 'der' }).toString('base64'))
  assert.equal(verify(null, payload, publicKey, Buffer.from(result.signatureBase64, 'base64')), true)
  assert.deepEqual(arenaReviewerKeyInfo(privateKey.export({ type: 'pkcs8', format: 'pem' })), {
    publicKeyBase64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    keyFingerprint: result.keyFingerprint,
  })
})

test('rejection signatures include the exact reason and cannot be changed', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const result = signArenaReview({
    privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    hash: HASH,
    reviewer: REVIEWER,
    decision: 'reject',
    reason: 'Map spawn overlaps an obstacle.',
  })

  assert.equal(verify(null, arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'reject',
    reason: 'Map spawn overlaps an obstacle.' }), publicKey, Buffer.from(result.signatureBase64, 'base64')), true)
  assert.equal(verify(null, arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'reject',
    reason: 'Different reason.' }), publicKey, Buffer.from(result.signatureBase64, 'base64')), false)
})

test('rejects invalid hashes, outcomes, key types, and rejection text', () => {
  const { privateKey } = generateKeyPairSync('ed25519')
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' })

  assert.throws(() => arenaReviewPayload({ hash: HASH.toUpperCase(), reviewer: REVIEWER, decision: 'approve' }))
  assert.throws(() => arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'reject', reason: 'bad\ntext' }))
  assert.throws(() => arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'approve', reason: 'ok' }))
  assert.throws(() => signArenaReview({ privateKeyPem: 'not a key', hash: HASH,
    reviewer: REVIEWER, decision: 'approve' }))
})

test('public-key CLI prints the trust-store key format without exposing private material', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519')
  const directory = mkdtempSync(join(tmpdir(), 'stargenesis-review-key-'))
  const privateKeyPath = join(directory, 'reviewer.pem')
  writeFileSync(privateKeyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
  try {
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./arena_review_sign.mjs', import.meta.url)),
      'public-key', '--private-key', privateKeyPath], { encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    const output = JSON.parse(result.stdout)
    assert.equal(output.publicKeyBase64, publicKey.export({ type: 'spki', format: 'der' }).toString('base64'))
    assert.equal(output.keyFingerprint.length, 64)
    assert.equal(result.stdout.includes(privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()), false)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('rejection text rejects unpaired surrogates before UTF-8 encoding', () => {
  for (const reason of ['bad\uD800text', 'bad\uDC00text'])
    assert.throws(() => arenaReviewPayload({ hash: HASH, reviewer: REVIEWER, decision: 'reject', reason }))
  assert.doesNotThrow(() => arenaReviewPayload({ hash: HASH, reviewer: REVIEWER,
    decision: 'reject', reason: '地图需要调整🌍' }))
})

test('offline verification binds the statement to an independently expected fingerprint', () => {
  const { privateKey } = generateKeyPairSync('ed25519')
  const statement = { hash: HASH, reviewer: REVIEWER, decision: 'reject', reason: '地图需要调整🌍' }
  const signed = signArenaReview({ ...statement, privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }) })
  const proof = { ...statement, publicKeyBase64: signed.publicKeyBase64,
    signatureBase64: signed.signatureBase64, expectedFingerprint: signed.keyFingerprint }

  assert.equal(verifyArenaReview(proof).valid, true)
  assert.equal(verifyArenaReview({ ...proof, reason: '不同理由' }).valid, false)
  assert.equal(verifyArenaReview({ ...proof, expectedFingerprint: '0'.repeat(64) }).valid, false)
  assert.equal(verifyArenaReview({ ...proof, hash: 'b'.repeat(64) }).valid, false)
  assert.throws(() => verifyArenaReview({ ...proof, signatureBase64: signed.signatureBase64.slice(0, -2) }))
  assert.throws(() => verifyArenaReview({ ...proof, publicKeyBase64: `${signed.publicKeyBase64}\n` }))
  assert.throws(() => verifyArenaReview({ ...proof, publicKeyBase64: 'A'.repeat(4096) }))
  assert.throws(() => verifyArenaReview({ ...proof, expectedFingerprint: '' }))
})

test('verify CLI needs no private key and exits unsuccessfully for an invalid proof', () => {
  const { privateKey } = generateKeyPairSync('ed25519')
  const signed = signArenaReview({ privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    hash: HASH, reviewer: REVIEWER, decision: 'approve' })
  const cli = fileURLToPath(new URL('./arena_review_sign.mjs', import.meta.url))
  const args = [cli, 'verify', '--hash', HASH, '--reviewer', REVIEWER, '--decision', 'approve',
    '--public-key', signed.publicKeyBase64, '--signature', signed.signatureBase64,
    '--fingerprint', signed.keyFingerprint]
  const accepted = spawnSync(process.execPath, args, { encoding: 'utf8' })
  assert.equal(accepted.status, 0, accepted.stderr)
  assert.equal(JSON.parse(accepted.stdout).valid, true)
  const rejected = spawnSync(process.execPath, [...args.slice(0, -1), '0'.repeat(64)], { encoding: 'utf8' })
  assert.equal(rejected.status, 1)
  assert.equal(JSON.parse(rejected.stdout).valid, false)
  const missingPin = spawnSync(process.execPath, args.slice(0, -2), { encoding: 'utf8' })
  assert.equal(missingPin.status, 1)
  assert.match(missingPin.stderr, /Missing required argument --fingerprint/)
})

test('Java and Node use identical multilingual signature vectors', () => {
  const fixture = JSON.parse(readFileSync(new URL(
    '../stargenesis-design/src/test/resources/arena-review-signatures.json', import.meta.url), 'utf8'))
  for (const vector of fixture.vectors) {
    assert.equal(arenaReviewPayload(vector).toString('hex'), vector.payloadHex)
    assert.equal(verifyArenaReview({ ...vector, publicKeyBase64: fixture.publicKeyBase64,
      expectedFingerprint: fixture.keyFingerprint }).valid, true)
  }
})
