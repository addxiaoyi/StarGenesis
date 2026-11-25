import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const DOMAIN = 'StarGenesis/ArenaReviewDecision/1\n'
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function arenaReviewPayload({ hash, reviewer, decision, reason = '' }) {
  if (typeof hash !== 'string' || !/^[0-9a-f]{64}$/.test(hash))
    throw new Error('Review ID must be a full lowercase SHA-256 hash')
  if (typeof reviewer !== 'string' || !UUID_PATTERN.test(reviewer))
    throw new Error('Reviewer must be a canonical UUID')
  if (decision !== 'approve' && decision !== 'reject')
    throw new Error('Decision must be approve or reject')
  if (decision === 'approve' && reason !== '') throw new Error('Approval reason must be empty')
  if (decision === 'reject' && (typeof reason !== 'string' || reason.trim() === '' || reason.length > 240
    || /[\u0000-\u001f\u007f-\u009f]/u.test(reason) || Buffer.from(reason, 'utf8').toString('utf8') !== reason))
    throw new Error('Rejection reason must be 1..240 printable characters')
  const result = decision === 'approve' ? 'APPROVE' : 'REJECT'
  return Buffer.from(`${DOMAIN}${hash}\n${reviewer.toLowerCase()}\n${result}\n${reason}`, 'utf8')
}

export function signArenaReview({ privateKeyPem, hash, reviewer, decision, reason = '' }) {
  const privateKey = createPrivateKey(privateKeyPem)
  const keyInfo = arenaReviewerKeyInfo(privateKeyPem)
  const payload = arenaReviewPayload({ hash, reviewer, decision, reason })
  const signature = sign(null, payload, privateKey)
  return {
    reviewer: reviewer.toLowerCase(),
    decision,
    ...keyInfo,
    signatureBase64: signature.toString('base64'),
  }
}

export function arenaReviewerKeyInfo(privateKeyPem) {
  const privateKey = createPrivateKey(privateKeyPem)
  if (privateKey.asymmetricKeyType !== 'ed25519') throw new Error('Private key must be Ed25519')
  const publicKey = createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
  return {
    publicKeyBase64: publicKey.toString('base64'),
    keyFingerprint: createHash('sha256').update(publicKey).digest('hex'),
  }
}

export function verifyArenaReview({ publicKeyBase64, signatureBase64, expectedFingerprint,
  hash, reviewer, decision, reason = '' }) {
  const payload = arenaReviewPayload({ hash, reviewer, decision, reason })
  if (typeof expectedFingerprint !== 'string' || !/^[0-9a-f]{64}$/.test(expectedFingerprint))
    throw new Error('Expected reviewer fingerprint must be a full lowercase SHA-256 hash')
  const publicKeyBytes = canonicalBase64(publicKeyBase64, 128, 'Reviewer public key')
  const signature = canonicalBase64(signatureBase64, 64, 'Reviewer signature')
  if (signature.length !== 64) throw new Error('Reviewer signature must contain exactly 64 bytes')
  const key = createPublicKey({ key: publicKeyBytes, type: 'spki', format: 'der' })
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Reviewer public key must be Ed25519')
  const keyFingerprint = createHash('sha256').update(publicKeyBytes).digest('hex')
  return {
    valid: keyFingerprint === expectedFingerprint && verify(null, payload, key, signature),
    reviewer: reviewer.toLowerCase(),
    decision,
    keyFingerprint,
  }
}

function canonicalBase64(value, maxBytes, label) {
  if (typeof value !== 'string' || value.length === 0 || value.length > Math.ceil(maxBytes / 3) * 4)
    throw new Error(`${label} must be bounded canonical Base64 data`)
  const decoded = Buffer.from(value, 'base64')
  if (decoded.length > maxBytes || decoded.toString('base64') !== value)
    throw new Error(`${label} must be bounded canonical Base64 data`)
  return decoded
}

function parseArguments(args, mode) {
  const keyArgs = mode === 'verify' ? ['--public-key', '--signature', '--fingerprint'] : ['--private-key']
  const commonArgs = ['--hash', '--reviewer', '--decision']
  const values = new Map()
  for (let index = 0; index < args.length; index += 1) {
    const name = args[index]
    if (![...keyArgs, ...commonArgs, '--reason'].includes(name))
      throw new Error(`Unknown argument: ${name}`)
    if (values.has(name)) throw new Error(`Duplicate argument: ${name}`)
    const value = args[index + 1]
    if (value === undefined || value.startsWith('--')) throw new Error(`Missing value for ${name}`)
    values.set(name, value)
    index += 1
  }
  for (const required of [...keyArgs, ...commonArgs])
    if (!values.has(required)) throw new Error(`Missing required argument ${required}`)
  return values
}

function main() {
  try {
    const rawArgs = process.argv.slice(2)
    const mode = ['sign', 'public-key', 'verify'].includes(rawArgs[0]) ? rawArgs.shift() : 'sign'
    if (mode === 'public-key') {
      if (rawArgs.length !== 2 || rawArgs[0] !== '--private-key')
        throw new Error('Usage: public-key --private-key <pem-file>')
      const keyInfo = arenaReviewerKeyInfo(readFileSync(resolve(rawArgs[1])))
      process.stdout.write(`${JSON.stringify(keyInfo, null, 2)}\n`)
      return
    }
    const args = parseArguments(rawArgs, mode)
    const statement = {
      hash: args.get('--hash'),
      reviewer: args.get('--reviewer'),
      decision: args.get('--decision'),
      reason: args.get('--reason') ?? '',
    }
    const result = mode === 'verify' ? verifyArenaReview({
      ...statement,
      publicKeyBase64: args.get('--public-key'),
      signatureBase64: args.get('--signature'),
      expectedFingerprint: args.get('--fingerprint'),
    }) : signArenaReview({
      ...statement,
      privateKeyPem: readFileSync(resolve(args.get('--private-key'))),
    })
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    if (mode === 'verify' && !result.valid) process.exitCode = 1
  } catch (error) {
    process.stderr.write(`Arena review command failed: ${error.message}\n`)
    process.exitCode = 1
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
