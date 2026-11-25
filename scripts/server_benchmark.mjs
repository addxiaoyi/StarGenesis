import { spawn, execFileSync, spawnSync } from 'node:child_process'
import { appendFileSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, statfsSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import net from 'node:net'
import { createHash } from 'node:crypto'
import os from 'node:os'

export const PERFORMANCE_LIMITS = Object.freeze({
  minimumTpsP50: 19,
  maximumTickDurationP95Milliseconds: 50,
  minimumAvailableMemoryMebibytes: 1024,
  maximumRssMebibytes: 1536,
  maximumHeapUsedMebibytes: 480,
  minimumMetricSampleCoverage: 0.8,
  minimumHeapSampleCoverage: 0.8
})

export function parseArguments(arguments_) {
  const options = new Map()
  const allowed = new Set([
    'kind', 'server-jar', 'plugin-jar', 'runtime-directory', 'directory', 'result', 'accept-eula',
    'java', 'jcmd', 'port', 'duration-seconds', 'sample-seconds', 'work-interval-seconds',
    'advance-steps', 'memory-sample-seconds', 'client-module-directory', 'environment-note', 'detach', 'backend-jar',
    'allow-concurrent-java'
  ])
  for (let index = 0; index < arguments_.length; index++) {
    const name = arguments_[index]
    if (!name.startsWith('--')) throw new Error(`Unexpected argument: ${name}`)
    if (!allowed.has(name.slice(2))) throw new Error(`Unknown option: ${name}`)
    if (name === '--accept-eula' || name === '--detach' || name === '--allow-concurrent-java') {
      if (options.has(name.slice(2))) throw new Error(`Duplicate option: ${name}`)
      options.set(name.slice(2), true)
      continue
    }
    const value = arguments_[++index]
    if (value === undefined || value.startsWith('--')) throw new Error(`Missing value for ${name}`)
    if (options.has(name.slice(2))) throw new Error(`Duplicate option: ${name}`)
    options.set(name.slice(2), value)
  }
  const required = ['kind', 'server-jar', 'plugin-jar', 'runtime-directory', 'directory', 'result']
  for (const name of required) {
    if (!options.has(name)) throw new Error(`Missing required option --${name}`)
  }
  if (!options.has('accept-eula')) throw new Error('Pass --accept-eula for this isolated offline benchmark server')
  const integer = (name, fallback, minimum, maximum) => {
    const value = Number(options.get(name) ?? fallback)
    if (!Number.isInteger(value) || value < minimum || value > maximum) {
      throw new Error(`--${name} must be an integer from ${minimum} through ${maximum}`)
    }
    return value
  }
  const kind = options.get('kind')
  if (!['paper', 'folia'].includes(kind)) throw new Error('--kind must be paper or folia')
  const javaExecutable = options.get('java')
  const javaHasPath = javaExecutable !== undefined && /[\\/]/.test(javaExecutable)
  return {
    kind,
    serverJar: resolve(options.get('server-jar')),
    backendJar: options.has('backend-jar') ? resolve(options.get('backend-jar')) : null,
    pluginJar: resolve(options.get('plugin-jar')),
    runtimeDirectory: resolve(options.get('runtime-directory')),
    directory: resolve(options.get('directory')),
    result: resolve(options.get('result')),
    java: javaHasPath ? resolve(javaExecutable) : javaExecutable ?? 'java',
    jcmd: options.get('jcmd') ?? (javaHasPath
      ? join(dirname(resolve(javaExecutable)), process.platform === 'win32' ? 'jcmd.exe' : 'jcmd')
      : 'jcmd'),
    port: integer('port', 25580, 1024, 65535),
    durationSeconds: integer('duration-seconds', 1800, 10, 86400),
    sampleSeconds: integer('sample-seconds', 15, 5, 300),
    workIntervalSeconds: integer('work-interval-seconds', 2, 1, 60),
    advanceSteps: integer('advance-steps', 100, 1, 100),
    memorySampleSeconds: integer('memory-sample-seconds', 60, 15, 600),
    clientModuleDirectory: resolve(options.get('client-module-directory') ?? '.work/arena-e2e'),
    environmentNote: options.get('environment-note') ?? 'not recorded',
    detach: options.has('detach'),
    allowConcurrentJava: options.has('allow-concurrent-java')
  }
}

export function detachedWorkerArguments(arguments_) {
  return arguments_.filter(argument => argument !== '--detach')
}

export function launchDetachedWorker(executable, scriptPath, arguments_, workingDirectory) {
  return new Promise((resolvePromise, reject) => {
    const worker = spawn(executable, [resolve(scriptPath), ...arguments_], {
      cwd: workingDirectory,
      detached: true,
      stdio: 'ignore',
      windowsHide: true
    })
    worker.once('error', reject)
    worker.once('spawn', () => {
      worker.unref()
      resolvePromise(worker.pid)
    })
  })
}
function sha256(path) {
  const digest = createHash('sha256')
  const bytes = readFileSync(path)
  return digest.update(bytes).digest('hex')
}

async function assertPortAvailable(port) {
  await new Promise((resolvePromise, reject) => {
    const socket = net.createServer()
    socket.once('error', reject)
    socket.listen(port, '127.0.0.1', () => socket.close(resolvePromise))
  }).catch(() => {
    throw new Error(`Benchmark port ${port} is already in use`)
  })
}

function prepareDirectory(options) {
  for (const path of [options.serverJar, options.pluginJar, ...(options.backendJar ? [options.backendJar] : [])]) {
    if (!existsSync(path)) throw new Error(`Required file does not exist: ${path}`)
  }
  for (const name of options.backendJar ? ['libraries'] : ['cache', 'libraries', 'versions']) {
    if (!existsSync(join(options.runtimeDirectory, name))) {
      throw new Error(`Runtime directory is missing ${name}: ${options.runtimeDirectory}`)
    }
  }
  const runtimePaperConfig = join(options.runtimeDirectory, 'config', 'paper-global.yml')
  if (!existsSync(runtimePaperConfig)) {
    throw new Error(`Runtime directory is missing config/paper-global.yml: ${options.runtimeDirectory}`)
  }
  if (existsSync(options.directory) && readdirSync(options.directory).length > 0) {
    throw new Error(`The benchmark server directory must be empty: ${options.directory}`)
  }
  mkdirSync(options.directory, { recursive: true })
  const available = statfsSync(options.directory)
  const required = (options.backendJar ? 128 : 768) * 1024 * 1024
  if (available.bavail * available.bsize < required)
    throw new Error(`Benchmark directory requires at least ${required} free bytes`)
  if (!options.backendJar) {
    for (const name of ['cache', 'libraries', 'versions'])
      cpSync(join(options.runtimeDirectory, name), join(options.directory, name), { recursive: true })
  }
  mkdirSync(join(options.directory, 'plugins'), { recursive: true })
  copyFileSync(options.pluginJar, join(options.directory, 'plugins', 'StarGenesis.jar'))
  writeFileSync(join(options.directory, 'eula.txt'), 'eula=true\n')
  writeFileSync(join(options.directory, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${options.port}`, 'online-mode=false', 'enable-rcon=false',
    'view-distance=2', 'simulation-distance=2', 'spawn-protection=0', 'max-players=8',
    'level-type=minecraft:flat', 'generate-structures=false', 'max-tick-time=60000',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}', ''
  ].join('\n'))
  mkdirSync(join(options.directory, 'config'), { recursive: true })
  copyFileSync(runtimePaperConfig, join(options.directory, 'config', 'paper-global.yml'))
  if (options.kind === 'folia') {
    mkdirSync(join(options.directory, 'config'), { recursive: true })
    writeFileSync(join(options.directory, 'config', 'folia-global.yml'),
      '_version: 1\nthreaded-regions:\n  threads: 2\n')
  }
}

export function libraryJars(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return libraryJars(path)
    return entry.isFile() && entry.name.endsWith('.jar') ? [path] : []
  }).sort()
}

function waitFor(predicate, timeoutMilliseconds, label) {
  const started = Date.now()
  return new Promise((resolvePromise, reject) => {
    const poll = () => {
      if (predicate()) return resolvePromise()
      if (Date.now() - started >= timeoutMilliseconds) return reject(new Error(`Timed out waiting for ${label}`))
      setTimeout(poll, 100)
    }
    poll()
  })
}

function normalizeConsoleLine(line) {
  return line.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '').replace(/\r/g, '')
    .replace(/^\s*(?:>\s*)+/, '').trim()
}

export function parsePerformanceLines(rawLines) {
  const lines = rawLines.map(normalizeConsoleLine)
  const stripPrefix = line => line.replace(/^.*\]\s*/, '').replace(/^\[[^\]]+\]\s*/, '')
  const healthReportIndex = lines.findIndex(line => /Server Health Report/i.test(line))
  if (healthReportIndex >= 0) {
    const healthReport = lines.slice(healthReportIndex)
    const medianTps = healthReport.find(line => /Median Region TPS/i.test(line))
      ?.match(/Median Region TPS:\s*([\d.]+)/i)
    const highestRegionMspt = healthReport.find(line => /MSPT\s+at\s+[\d.]+\s+TPS/i.test(line))
      ?.match(/([\d.]+)\s+MSPT\s+at\s+[\d.]+\s+TPS/i)
    if (medianTps && highestRegionMspt) {
      return {
        tps: Number(medianTps[1]),
        tpsWindow: 'median Folia region',
        tickMean: Number(highestRegionMspt[1]),
        tickMinimum: null,
        tickMedian: null,
        tickP95: null,
        tickMaximum: null,
        tickWindow: 'highest-utilization Folia region',
        tickMetric: 'Folia highest-utilization region MSPT',
        lines
      }
    }
  }
  const tpsHeaderIndex = lines.findIndex(line => /TPS from last/i.test(line))
  if (tpsHeaderIndex < 0) return null
  const durationHeaderIndex = lines.findIndex(line => /Tick durations \(min\/med\/95%ile\/max ms\) from last/i.test(line))
  const meanHeaderIndex = lines.findIndex(line => /Server tick times \(avg\/min\/max\) from last/i.test(line))
  const tpsHeader = lines[tpsHeaderIndex]
  const tpsInline = tpsHeader.match(/TPS from last ([^:]+):\s*([\d.]+)/i)
  const tpsRow = lines.slice(tpsHeaderIndex + 1)
    .find(line => /^\*?\s*[\d.]+\s*,/.test(stripPrefix(line)))
  const durationRow = durationHeaderIndex < 0 ? null : lines.slice(durationHeaderIndex + 1)
    .find(line => /[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+/.test(line))
  const meanRow = meanHeaderIndex < 0 ? null : lines.slice(meanHeaderIndex + 1)
    .find(line => /[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+/.test(line))
  const tpsData = tpsRow ? stripPrefix(tpsRow) : ''
  const tps = tpsInline ? Number(tpsInline[2])
    : tpsRow ? Number(tpsData.split(',')[0].replace(/[^\d.]/g, '')) : null
  const durationValues = durationRow?.match(/[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+/)
    ?.[0].split('/').map(value => Number(value.trim()))
  const meanValues = meanRow?.match(/[\d.]+\s*\/\s*[\d.]+\s*\/\s*[\d.]+/)
    ?.[0].split('/').map(value => Number(value.trim()))
  const tickHeaderIndex = durationHeaderIndex >= 0 ? durationHeaderIndex : meanHeaderIndex
  if (tps === null || (!durationValues && !meanValues)) return null
  const tickHeader = lines[tickHeaderIndex]
  return {
    tps,
    tpsWindow: tpsInline?.[1].split(',')[0].trim()
      ?? tpsHeader.replace(/^.*TPS from last\s*/i, '').split(':')[0].split(',')[0].trim(),
    tickMean: meanValues?.[0] ?? null,
    tickMinimum: durationValues?.[0] ?? meanValues?.[1] ?? null,
    tickMedian: durationValues?.[1] ?? null,
    tickP95: durationValues?.[2] ?? null,
    tickMaximum: durationValues?.[3] ?? meanValues?.[2] ?? null,
    tickWindow: tickHeader.replace(/^.*from last\s*/i, '').split(':')[0].split(',')[0].trim(),
    tickMetric: durationValues ? 'spark tick-duration distribution' : 'server average/minimum/maximum tick time',
    lines
  }
}

function createCommand(command, args, timeoutMilliseconds = 15000) {
  return execFileSync(command, args, { encoding: 'utf8', timeout: timeoutMilliseconds, windowsHide: true })
}

export function parseJvmProcessList(output) {
  const processes = String(output ?? '').split(/\r?\n/).map(line => {
    const trimmed = line.trim()
    const match = trimmed.match(/^(\d+)\s+(.+)$/)
    if (!match) return null
    const description = match[2].trim()
    if (/^(?:jdk\.jcmd\/|sun\.tools\.jcmd\.JCmd\b)/.test(description)) return null
    const pid = Number(match[1])
    return Number.isSafeInteger(pid) ? { pid, description } : null
  }).filter(process => process !== null)
  const processesByPid = new Map(processes.map(process => [process.pid, process]))
  return [...processesByPid.values()].sort((left, right) => left.pid - right.pid)
}

export function findNewJavaProcesses(processes, knownProcessIds) {
  const known = new Set(knownProcessIds)
  return processes.filter(process => !known.has(process.pid))
}

export function parseWindowsJavaProcessList(output) {
  const trimmed = output.trim()
  if (!trimmed || trimmed === 'null') return []
  const parsed = JSON.parse(trimmed)
  return (Array.isArray(parsed) ? parsed : [parsed])
    .map(process => ({
      pid: Number(process.pid),
      description: process.description || 'Windows Java process (command line unavailable)'
    }))
    .filter(process => Number.isSafeInteger(process.pid) && process.pid > 0)
    .sort((left, right) => left.pid - right.pid)
}

export function benchmarkStartupConflict(processes, allowConcurrentJava = false) {
  return processes.length === 0 || allowConcurrentJava
    ? null : { elapsed_seconds: 0, java_processes: processes }
}

export function shouldStopForNewJavaProcesses(processes, allowConcurrentJava = false) {
  return !allowConcurrentJava && processes.length > 0
}

export function normalizeExitCode(exitCode, platform = process.platform) {
  if (platform !== 'win32' || !Number.isInteger(exitCode)
      || exitCode <= 0x7fff_ffff || exitCode > 0xffff_ffff) return exitCode
  return exitCode - 0x1_0000_0000
}

export function describeChildProcessError(error) {
  return {
    name: error.name ?? 'Error',
    code: error.code ?? null,
    message: error.message ?? String(error),
    syscall: error.syscall ?? null,
    path: error.path ?? null
  }
}

/** Keep EventEmitter callbacks from bypassing the benchmark's failure/cleanup path. */
export function captureBenchmarkServerIo(server, recordOutput, onFailure) {
  let closed = false
  const completion = new Promise(resolvePromise => {
    server.once('close', () => { closed = true; resolvePromise() })
  })
  for (const output of [server.stdout, server.stderr]) {
    output.on('data', chunk => {
      try { recordOutput(chunk) } catch (error) { onFailure(error) }
    })
    output.on('error', onFailure)
  }
  // EPIPE is asynchronous; a try/catch around stdin.write alone cannot catch it.
  server.stdin.on('error', onFailure)
  return { completion, isClosed: () => closed }
}

export function waitForBenchmarkBot(bot, username, timeoutMilliseconds = 45_000, signal) {
  return new Promise((resolvePromise, reject) => {
    if (signal?.aborted) { reject(signal.reason); return }
    const finish = error => {
      clearTimeout(timer)
      bot.removeListener('spawn', spawned)
      bot.removeListener('kicked', kicked)
      bot.removeListener('error', failed)
      bot.removeListener('end', ended)
      signal?.removeEventListener('abort', aborted)
      if (error) reject(error)
      else resolvePromise()
    }
    const spawned = () => finish()
    const kicked = reason => finish(new Error(username + ' was kicked: ' + reason))
    const failed = error => finish(error)
    const ended = reason => finish(new Error(username + ' disconnected before spawning: ' + (reason ?? '')))
    const aborted = () => finish(signal.reason)
    const timer = setTimeout(() => finish(new Error(username + ' connection timed out')), timeoutMilliseconds)
    bot.once('spawn', spawned)
    bot.once('kicked', kicked)
    bot.once('error', failed)
    bot.once('end', ended)
    signal?.addEventListener('abort', aborted, { once: true })
  })
}

/** Fail the run if a client drops out before intentional benchmark cleanup begins. */
export function monitorBenchmarkBot(bot, username, diagnostics, isClosing, onFailure) {
  bot.on('end', reason => {
    diagnostics.events.push({ event: 'end', reason: String(reason ?? '') })
    if (!isClosing()) onFailure(new Error(`${username} disconnected during benchmark: ${reason ?? ''}`))
  })
  bot.on('kicked', reason => {
    diagnostics.events.push({ event: 'kicked', reason: String(reason) })
    if (!isClosing()) onFailure(new Error(`${username} was kicked during benchmark: ${reason}`))
  })
  bot.on('error', error => {
    diagnostics.events.push({ event: 'error', ...describeChildProcessError(error) })
    if (!isClosing()) onFailure(error)
  })
}

/** Every created bot and the owned server get a cleanup attempt, even after partial setup. */
export async function cleanupBenchmarkResources(bots, server, monitor, {
  timeoutMilliseconds = 45_000, forceTimeoutMilliseconds = 5_000, onFailure = () => {}
} = {}) {
  const errors = []
  const failed = error => { errors.push(describeChildProcessError(error)); onFailure(error) }
  for (const bot of bots) {
    try { bot.quit('benchmark complete') } catch (error) { failed(error) }
  }
  if (!server || !monitor || monitor.isClosed()) return errors
  const waitForClose = async (milliseconds, start) => {
    let timer
    try {
      await new Promise((resolvePromise, reject) => {
        timer = setTimeout(() => reject(new Error('Server stop timed out')), milliseconds)
        monitor.completion.then(resolvePromise, reject)
        if (start) start(reject)
      })
    } finally {
      clearTimeout(timer)
    }
  }
  try {
    await waitForClose(timeoutMilliseconds, reject => {
      if (server.exitCode !== null || server.signalCode !== null) return
      server.stdin.write('stop\n', error => { if (error) reject(error) })
    })
  } catch (error) {
    failed(error)
    try { server.kill() } catch (killError) { failed(killError) }
    try { await waitForClose(forceTimeoutMilliseconds) } catch (closeError) { failed(closeError) }
  }
  return errors
}

/** Preserve the previous complete JSON if the worker dies during a later write. */
export function writeBenchmarkResult(path, result) {
  const serialized = `${JSON.stringify(result, null, 2)}\n`
  mkdirSync(dirname(path), { recursive: true })
  const temporary = `${path}.${process.pid}.tmp`
  writeFileSync(temporary, serialized)
  renameSync(temporary, path)
}

export function readJavaProcesses(jcmd, platform = process.platform, runCommand = createCommand) {
  if (platform === 'win32') {
    try {
      const script = "$ErrorActionPreference='Stop'; $items=Get-CimInstance -ClassName Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" -ErrorAction Stop | Select-Object @{N='pid';E={[int]$_.ProcessId}},@{N='description';E={[string]$_.CommandLine}}; if ($null -ne $items) { $items | ConvertTo-Json -Compress -Depth 3 }"
      return parseWindowsJavaProcessList(runCommand('powershell.exe', [
        '-NoProfile', '-NonInteractive', '-Command', script
      ]))
    } catch (fallbackError) {
      throw new Error(`Could not enumerate Windows Java processes: ${fallbackError.message}`)
    }
  }
  return parseJvmProcessList(runCommand(jcmd, ['-l']))
}

function listJavaProcesses(jcmd) {
  return readJavaProcesses(jcmd)
}

function recordNewJavaProcesses(result, initialProcesses, serverPid, elapsedSeconds, jcmd) {
  try {
    const knownProcessIds = [
      ...initialProcesses.map(process => process.pid),
      ...(serverPid === undefined ? [] : [serverPid]),
      ...result.runtime_environment.java_processes_started_during_run.map(process => process.pid)
    ]
    const newlyObserved = findNewJavaProcesses(listJavaProcesses(jcmd), knownProcessIds).map(process => ({
      pid: process.pid,
      jvm: process.description,
      first_observed_elapsed_seconds: elapsedSeconds
    }))
    result.runtime_environment.java_processes_started_during_run.push(...newlyObserved)
    return newlyObserved
  } catch (error) {
    result.runtime_environment.java_process_probe_errors.push({
      elapsed_seconds: elapsedSeconds,
      reason: error.message
    })
    return null
  }
}

function observeBenchmarkJavaProcesses(result, initialProcesses, serverPid, elapsedSeconds, jcmd, allowConcurrentJava) {
  const previousProbeErrors = result.runtime_environment.java_process_probe_errors.length
  const newlyObserved = recordNewJavaProcesses(result, initialProcesses, serverPid, elapsedSeconds, jcmd)
  if (result.runtime_environment.java_process_probe_errors.length !== previousProbeErrors) {
    throw new Error('Could not verify Java process isolation during the benchmark')
  }
  result.runtime_environment.java_process_observations.push({
    elapsed_seconds: elapsedSeconds,
    newly_observed_count: newlyObserved.length,
    java_processes_started_during_run_count: result.runtime_environment.java_processes_started_during_run.length
  })
  if (shouldStopForNewJavaProcesses(newlyObserved, allowConcurrentJava)) {
    result.environment_conflict = {
      elapsed_seconds: elapsedSeconds,
      java_processes: newlyObserved
    }
    throw new Error(`Another Java process started during the benchmark: ${JSON.stringify(newlyObserved)}`)
  }
}

function sampleProcess(pid) {
  if (process.platform === 'win32') {
    const script = `$p=Get-Process -Id ${pid} -ErrorAction Stop; [ordered]@{cpu=$p.CPU; rss=$p.WorkingSet64; private=$p.PrivateMemorySize64} | ConvertTo-Json -Compress`
    return JSON.parse(createCommand('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script]))
  }
  const values = createCommand('ps', ['-o', 'rss=,time=', '-p', String(pid)]).trim().split(/\s+/)
  const time = values[1] ?? '0:00'
  const parts = time.split(':').map(Number)
  const cpu = parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : parts[0] * 60 + parts[1]
  const rss = Number(values[0]) * 1024
  return { cpu, rss, private: null }
}

function sampleHeap(jcmd, pid) {
  const output = createCommand(jcmd, [String(pid), 'GC.heap_info'])
  const match = output.match(/used\s+(\d+)\s*K/i)
  return match ? Number(match[1]) * 1024 : null
}

function javaVersion(java) {
  const result = spawnSync(java, ['-version'], { encoding: 'utf8', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`java -version exited with ${result.status}`)
  return (result.stderr || result.stdout).trim()
}

function percentile(values, fraction) {
  if (values.length === 0) return null
  const sorted = [...values].sort((left, right) => left - right)
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]
}

function summarize(values) {
  if (values.length === 0) return { count: 0, min: null, mean: null, p50: null, p95: null, max: null }
  return {
    count: values.length,
    min: Math.min(...values),
    mean: values.reduce((sum, value) => sum + value, 0) / values.length,
    p50: percentile(values, 0.5),
    p95: percentile(values, 0.95),
    max: Math.max(...values)
  }
}

export function summarizeBenchmarkSamples(samples) {
  const mebibytes = bytes => Number.isFinite(bytes) ? bytes / (1024 * 1024) : null
  const performance = {
    tps: summarize(samples.map(sample => sample.tps).filter(Number.isFinite)),
    tick_duration_mean_ms: summarize(samples.map(sample => sample.tick_duration_mean_ms).filter(Number.isFinite)),
    tick_duration_median_ms: summarize(samples.map(sample => sample.tick_duration_median_ms).filter(Number.isFinite)),
    tick_duration_p95_ms: summarize(samples.map(sample => sample.tick_duration_p95_ms).filter(Number.isFinite)),
    tick_duration_max_ms: summarize(samples.map(sample => sample.tick_duration_max_ms).filter(Number.isFinite)),
    cpu_percent_of_one_core: summarize(samples.map(sample => sample.cpu_percent_of_one_core).filter(Number.isFinite)),
    available_memory_mib: summarize(samples
      .map(sample => mebibytes(sample.available_memory_bytes)).filter(Number.isFinite)),
    rss_mib: summarize(samples.map(sample => mebibytes(sample.rss_bytes)).filter(Number.isFinite)),
    private_mib: summarize(samples.map(sample => mebibytes(sample.private_bytes)).filter(Number.isFinite)),
    heap_used_mib: summarize(samples.map(sample => mebibytes(sample.heap_used_bytes)).filter(Number.isFinite))
  }
  const telemetryComplete = performance.tps.count > 0
    && (performance.tick_duration_mean_ms.count > 0 || performance.tick_duration_p95_ms.count > 0)
    && performance.heap_used_mib.count > 0
  return { performance, telemetry_complete: telemetryComplete }
}

export function evaluatePerformanceGate(performance, options) {
  const expectedMetricSamples = Math.floor(options.durationSeconds / options.sampleSeconds)
  const expectedHeapSamples = Math.floor(options.durationSeconds / options.memorySampleSeconds)
  const minimumMetricSamples = Math.max(1,
    Math.ceil(expectedMetricSamples * PERFORMANCE_LIMITS.minimumMetricSampleCoverage))
  const minimumHeapSamples = Math.max(1,
    Math.ceil(expectedHeapSamples * PERFORMANCE_LIMITS.minimumHeapSampleCoverage))
  const tickDuration = performance.tick_duration_p95_ms.count > 0
    ? performance.tick_duration_p95_ms : performance.tick_duration_mean_ms
  const metricSampleCounts = {
    tps: performance.tps.count,
    tickDuration: tickDuration.count,
    availableMemory: performance.available_memory_mib.count,
    rss: performance.rss_mib.count
  }
  const checks = {
    metric_sample_coverage: {
      passed: Object.values(metricSampleCounts).every(count => count >= minimumMetricSamples),
      actual: metricSampleCounts,
      minimum: minimumMetricSamples
    },
    heap_sample_coverage: {
      passed: performance.heap_used_mib.count >= minimumHeapSamples,
      actual: performance.heap_used_mib.count,
      minimum: minimumHeapSamples
    },
    median_tps: {
      passed: performance.tps.p50 !== null && performance.tps.p50 >= PERFORMANCE_LIMITS.minimumTpsP50,
      actual: performance.tps.p50,
      minimum: PERFORMANCE_LIMITS.minimumTpsP50
    },
    tick_duration_p95_ms: {
      passed: tickDuration.p95 !== null
        && tickDuration.p95 <= PERFORMANCE_LIMITS.maximumTickDurationP95Milliseconds,
      actual: tickDuration.p95,
      maximum: PERFORMANCE_LIMITS.maximumTickDurationP95Milliseconds,
      source: performance.tick_duration_p95_ms.count > 0 ? 'server p95' : 'sampled MSPT mean'
    },
    available_memory_mib: {
      passed: performance.available_memory_mib.min !== null
        && performance.available_memory_mib.min >= PERFORMANCE_LIMITS.minimumAvailableMemoryMebibytes,
      actual_minimum: performance.available_memory_mib.min,
      minimum: PERFORMANCE_LIMITS.minimumAvailableMemoryMebibytes
    },
    rss_mib: {
      passed: performance.rss_mib.max !== null
        && performance.rss_mib.max <= PERFORMANCE_LIMITS.maximumRssMebibytes,
      actual_maximum: performance.rss_mib.max,
      maximum: PERFORMANCE_LIMITS.maximumRssMebibytes
    },
    heap_used_mib: {
      passed: performance.heap_used_mib.max !== null
        && performance.heap_used_mib.max <= PERFORMANCE_LIMITS.maximumHeapUsedMebibytes,
      actual_maximum: performance.heap_used_mib.max,
      maximum: PERFORMANCE_LIMITS.maximumHeapUsedMebibytes
    }
  }
  return {
    passed: Object.values(checks).every(check => check.passed),
    limits: PERFORMANCE_LIMITS,
    checks
  }
}

async function main() {
  const arguments_ = process.argv.slice(2)
  const options = parseArguments(arguments_)
  if (options.detach) {
    const pid = await launchDetachedWorker(process.execPath, process.argv[1],
      detachedWorkerArguments(arguments_), process.cwd())
    process.stdout.write(`${JSON.stringify({ detached: true, worker_pid: pid, result: options.result })}\n`)
    return
  }
  await assertPortAvailable(options.port)
  prepareDirectory(options)
  const require = createRequire(join(options.clientModuleDirectory, 'package.json'))
  const { createBot } = require('mineflayer')
  const logPath = join(options.directory, 'benchmark-server.log')
  const serverLines = []
  const commandLatencies = { status: [], advance: [] }
  let server
  let initialJavaProcesses = []
  let outputFragment = ''
  let serverReady = false
  let pluginReady = false
  let serverIdentity = null
  let serverExit = null
  let serverSpawnError = null
  let serverMonitor = null
  let asynchronousFailure = null
  const failureController = new AbortController()
  const recordFailure = error => {
    if (asynchronousFailure === null) asynchronousFailure = error
    failureController.abort(asynchronousFailure)
  }
  const throwIfFailed = () => { if (asynchronousFailure !== null) throw asynchronousFailure }
  let bots = []
  let currentTick = 0
  let workloadCommands = 0
  let benchmarkStarted = null
  let runCompleted = false
  let cleanupStarted = false
  const clientDiagnostics = new Map()
  const botNames = new WeakMap()
  const botDiagnostics = new WeakMap()
  const recordOutput = chunk => {
    const text = chunk.toString('utf8')
    appendFileSync(logPath, text)
    outputFragment += text
    const lines = outputFragment.split(/\r?\n/)
    outputFragment = lines.pop() ?? ''
    for (const line of lines) {
      const normalizedLine = normalizeConsoleLine(line)
      const identity = normalizedLine.match(/This server is running (Paper|Folia) version ([^\s]+)/)
      if (identity) serverIdentity = { kind: identity[1].toLowerCase(), build: identity[2] }
      if (normalizedLine.includes('Done (')) serverReady = true
      if (normalizedLine.includes('StarGenesis ready | phases=P1-P10 development')) pluginReady = true
      serverLines.push(normalizedLine)
    }
  }

  const entryPoint = options.backendJar
    ? ['-cp', [options.backendJar, ...libraryJars(join(options.runtimeDirectory, 'libraries'))].join(delimiter),
      'org.bukkit.craftbukkit.Main']
    : ['-jar', options.serverJar]
  const command = [
    '-Xms128M', '-Xmx512M', '-XX:ActiveProcessorCount=4', '-XX:+UseG1GC',
    ...entryPoint, '--nogui'
  ]
  const result = {
    phase: 'P11 Paper/Folia runtime load and memory benchmark',
    lifecycle: 'running',
    passed: false,
    kind: options.kind,
    benchmark_script_sha256: sha256(resolve(process.argv[1])),
    requested_duration_seconds: options.durationSeconds,
    sample_interval_seconds: options.sampleSeconds,
    work_interval_seconds: options.workIntervalSeconds,
    advance_steps_per_write: options.advanceSteps,
    memory_sample_interval_seconds: options.memorySampleSeconds,
    jvm_arguments: command,
    server_jar_sha256: sha256(options.serverJar),
    backend_jar_sha256: options.backendJar ? sha256(options.backendJar) : null,
    plugin_jar_sha256: sha256(options.pluginJar),
    server_log: logPath,
    server_process: { pid: null, spawned_at: null },
    samples: [],
    workload: { connected_clients: 0, status_commands: 0, advance_commands: 0, simulated_ticks: 0 },
    runtime_environment: {
      os: `${os.platform()} ${os.release()}`,
      architecture: os.arch(),
      logical_processors: os.cpus().length,
      total_memory_bytes: os.totalmem(),
      available_memory_bytes_at_start: os.freemem(),
      other_server_workload: options.environmentNote,
      library_mode: options.backendJar ? 'shared classpath' : 'copied runtime',
      java_processes_at_start: [],
      java_processes_started_during_run: [],
      java_process_probe_errors: [],
      java_process_observations: [],
      measurement_mode: options.allowConcurrentJava ? 'shared-host' : 'isolated-host'
    },
    scope: options.allowConcurrentJava
      ? 'Four offline Mineflayer clients exercise the persisted P6 factory on a shared host. Concurrent Java processes are observed and recorded; measurements are not an isolated-host comparison.'
      : 'Four offline Mineflayer clients exercise the persisted P6 factory on an isolated server. Metrics are observational and do not by themselves establish human usability.'
  }

  // The existence of a result file never implies completion: the initial snapshot is
  // explicitly running/failed-closed, including if the host later kills this worker.
  writeBenchmarkResult(options.result, result)
  try {
    writeFileSync(logPath, '')
    result.java = javaVersion(options.java)
    try {
      initialJavaProcesses = listJavaProcesses(options.jcmd)
      result.runtime_environment.java_processes_at_start = initialJavaProcesses
    } catch (error) {
      result.runtime_environment.java_process_probe_errors.push({ elapsed_seconds: 0, reason: error.message })
    }
    if (result.runtime_environment.java_process_probe_errors.length > 0) {
      throw new Error('Could not capture the Java process baseline before startup')
    }
    const startupConflict = benchmarkStartupConflict(initialJavaProcesses)
    if (startupConflict !== null && !options.allowConcurrentJava) {
      result.environment_conflict = startupConflict
      throw new Error(`Other Java processes are active at benchmark startup: ${JSON.stringify(startupConflict.java_processes)}`)
    }
    if (startupConflict !== null && options.allowConcurrentJava) {
      result.concurrent_java_processes_at_start = startupConflict.java_processes
    }
    const spawnedAt = new Date().toISOString()
    server = spawn(options.java, command, { cwd: options.directory, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    result.server_process = { pid: server.pid ?? null, spawned_at: spawnedAt }
    serverMonitor = captureBenchmarkServerIo(server, recordOutput, recordFailure)
    server.once('error', error => {
      serverSpawnError = describeChildProcessError(error)
      result.server_spawn_error = serverSpawnError
      serverExit = { code: null, signal: null, spawn_error: serverSpawnError }
    })
    server.once('exit', (code, signal) => {
      const normalizedCode = normalizeExitCode(code)
      serverExit = {
        process_id: server.pid ?? null,
        code: normalizedCode,
        ...(normalizedCode === code ? {} : { reported_code: code }),
        signal,
        observed_at: new Date().toISOString()
      }
      if (benchmarkStarted !== null) {
        serverExit.elapsed_seconds = Math.round((Date.now() - benchmarkStarted) / 1000)
      }
    })
    await waitFor(() => serverReady || serverExit !== null || asynchronousFailure !== null,
      180_000, `${options.kind} server startup`)
    throwIfFailed()
    if (serverSpawnError !== null) {
      throw new Error(`Could not start the Java server process: ${JSON.stringify(serverSpawnError)}`)
    }
    if (serverExit !== null) {
      throw new Error(`Server exited before becoming ready: ${JSON.stringify(serverExit)}`)
    }
    if (!serverReady) throw new Error('Server did not become ready')
    if (!serverIdentity || serverIdentity.kind !== options.kind) throw new Error(`Expected ${options.kind} server identity`)
    if (!pluginReady) throw new Error('StarGenesis did not enable with the expected development build')

    const prefix = options.kind === 'paper' ? 'SgBenchP' : 'SgBenchF'
    const connectedBots = await Promise.all([1, 2, 3, 4].map(async number => {
      const username = `${prefix}${number}`
      const bot = createBot({ host: '127.0.0.1', port: options.port, username,
        auth: 'offline', version: '1.21.11' })
      bots.push(bot)
      const diagnostics = { events: [], recent_messages: [] }
      botNames.set(bot, username)
      botDiagnostics.set(bot, diagnostics)
      clientDiagnostics.set(username, diagnostics)
      bot.on('messagestr', message => {
        diagnostics.recent_messages.push(String(message).slice(0, 240))
        if (diagnostics.recent_messages.length > 8) diagnostics.recent_messages.shift()
      })
      monitorBenchmarkBot(bot, username, diagnostics, () => cleanupStarted, recordFailure)
      await waitForBenchmarkBot(bot, username, 45_000, failureController.signal)
      return bot
    }))
    result.workload.connected_clients = connectedBots.length

    const playerCommand = (bot, input, expected, category) => new Promise((resolvePromise, reject) => {
      if (failureController.signal.aborted) { reject(failureController.signal.reason); return }
      const started = performance.now()
      const finish = error => {
        clearTimeout(timer)
        bot.removeListener('messagestr', listener)
        bot.removeListener('end', ended)
        bot.removeListener('error', failed)
        failureController.signal.removeEventListener('abort', aborted)
        if (error) reject(error)
      }
      const timer = setTimeout(() => finish(new Error('Timed out waiting for ' + bot.username + ': ' + input)), 20_000)
      const ended = reason => finish(new Error(bot.username + ' disconnected: ' + (reason ?? '')))
      const failed = error => finish(error)
      const aborted = () => finish(failureController.signal.reason)
      const listener = message => {
        if (!message.includes(expected)) return
        finish()
        const elapsed = performance.now() - started
        commandLatencies[category].push(elapsed)
        workloadCommands++
        resolvePromise({ message, elapsed })
      }
      bot.on('messagestr', listener)
      bot.once('end', ended)
      bot.once('error', failed)
      failureController.signal.addEventListener('abort', aborted, { once: true })
      bot.chat(input)
    })

    const identities = await Promise.all(bots.map(bot => playerCommand(bot, '/genesis factory identity', '你的 UUID', 'status')))
    const memberIds = identities.map(({ message }) => message.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0])
    if (memberIds.some(id => !id) || new Set(memberIds).size !== 4) throw new Error('Could not collect four distinct offline player UUIDs')
    const orgName = `p11_${options.kind}`
    await playerCommand(bots[0], `/genesis factory org create ${orgName}`, `组织 ${orgName} 已创建`, 'status')
    for (const memberId of memberIds.slice(1)) {
      await playerCommand(bots[0], `/genesis factory org add ${memberId}`, `已将 ${memberId} 加入组织`, 'status')
    }
    await playerCommand(bots[0], '/genesis factory create', '四人虚拟工厂已创建并保存', 'status')

    benchmarkStarted = Date.now()
    let nextWork = benchmarkStarted
    let nextSample = benchmarkStarted + options.sampleSeconds * 1000
    let nextProcessPoll = benchmarkStarted + 5000
    let previousProcessSample = null
    let sampleIndex = 0
    while (Date.now() - benchmarkStarted < options.durationSeconds * 1000) {
      throwIfFailed()
      if (serverExit !== null) throw new Error(`Server exited during benchmark: ${JSON.stringify(serverExit)}`)
      const now = Date.now()
      if (now >= nextProcessPoll) {
        observeBenchmarkJavaProcesses(result, initialJavaProcesses, server.pid,
          Math.round((now - benchmarkStarted) / 1000), options.jcmd, options.allowConcurrentJava)
        nextProcessPoll = Date.now() + 5000
      }
      if (now >= nextWork) {
        await Promise.all(bots.map(bot => playerCommand(bot, '/genesis factory status', '虚拟工厂 factory-main', 'status')))
        result.workload.status_commands += bots.length
        if (currentTick < 72_000) {
          const steps = Math.min(options.advanceSteps, 72_000 - currentTick)
          await playerCommand(bots[sampleIndex % bots.length],
            `/genesis factory advance ${currentTick} ${steps}`, `当前 tick ${currentTick + steps}`, 'advance')
          currentTick += steps
          result.workload.advance_commands++
          result.workload.simulated_ticks = currentTick
        }
        sampleIndex++
        nextWork += options.workIntervalSeconds * 1000
        if (nextWork < Date.now()) nextWork = Date.now() + options.workIntervalSeconds * 1000
      }
      if (Date.now() >= nextSample) {
        const beforeMetrics = serverLines.length
        if (!server.stdin.write('spark tps\ntps\nmspt\n')) throw new Error('Could not query server performance metrics')
        const readMetrics = () => parsePerformanceLines(serverLines.slice(beforeMetrics))
        await waitFor(() => readMetrics() !== null || serverExit !== null || asynchronousFailure !== null,
          8000, 'TPS and tick-time output')
        throwIfFailed()
        const sampledMetrics = readMetrics()
        if (!sampledMetrics) throw new Error('Server exited before returning TPS and tick-time metrics')
        const processSample = sampleProcess(server.pid)
        const wallSample = Date.now()
        const cpuPercent = previousProcessSample
          ? Math.max(0, (processSample.cpu - previousProcessSample.cpu) * 100 /
              ((wallSample - previousProcessSample.at) / 1000))
          : null
        const sample = {
          elapsed_seconds: Math.round((wallSample - benchmarkStarted) / 1000),
          tps: sampledMetrics.tps,
          tps_window: sampledMetrics.tpsWindow,
          tick_duration_mean_ms: sampledMetrics.tickMean,
          tick_duration_min_ms: sampledMetrics.tickMinimum,
          tick_duration_median_ms: sampledMetrics.tickMedian,
          tick_duration_p95_ms: sampledMetrics.tickP95,
          tick_duration_max_ms: sampledMetrics.tickMaximum,
          tick_duration_window: sampledMetrics.tickWindow,
          tick_metric: sampledMetrics.tickMetric,
          performance_lines: sampledMetrics.lines,
          cpu_percent_of_one_core: cpuPercent,
          available_memory_bytes: os.freemem(),
          rss_bytes: processSample.rss,
          private_bytes: processSample.private,
          heap_used_bytes: null
        }
        const samplesPerMemoryRead = Math.max(1, Math.round(options.memorySampleSeconds / options.sampleSeconds))
        if (result.samples.length % samplesPerMemoryRead === 0) {
          try {
            sample.heap_used_bytes = sampleHeap(options.jcmd, server.pid)
          } catch (error) {
            sample.heap_sample_error = error.message
          }
        }
        previousProcessSample = { ...processSample, at: wallSample }
        result.samples.push(sample)
        nextSample += options.sampleSeconds * 1000
        if (nextSample < Date.now()) nextSample = Date.now() + options.sampleSeconds * 1000
      }
      const nextDue = Math.min(nextWork, nextSample, benchmarkStarted + options.durationSeconds * 1000)
      if (nextDue > Date.now()) await new Promise(resolvePromise => setTimeout(resolvePromise, Math.min(250, nextDue - Date.now())))
    }

    result.measured_duration_seconds = Math.round((Date.now() - benchmarkStarted) / 1000)
    observeBenchmarkJavaProcesses(result, initialJavaProcesses, server.pid, result.measured_duration_seconds,
      options.jcmd, options.allowConcurrentJava)
    runCompleted = true
    result.server = serverIdentity
  } catch (error) {
    const primaryFailure = asynchronousFailure ?? error
    result.error = primaryFailure.stack ?? String(primaryFailure)
    result.server = serverIdentity
    result.workload.total_client_commands = workloadCommands
    if (benchmarkStarted !== null)
      result.failed_after_seconds = Math.round((Date.now() - benchmarkStarted) / 1000)
    recordNewJavaProcesses(result, initialJavaProcesses, server?.pid,
      benchmarkStarted === null ? 0 : Math.round((Date.now() - benchmarkStarted) / 1000), options.jcmd)
    result.passed = false
  } finally {
    cleanupStarted = true
    result.cleanup_errors = await cleanupBenchmarkResources(bots, server, serverMonitor,
      { onFailure: recordFailure })
    result.client_diagnostics = Object.fromEntries(clientDiagnostics.entries())
  }

  if (asynchronousFailure !== null) {
    result.error ??= asynchronousFailure.stack ?? String(asynchronousFailure)
    runCompleted = false
  }
  result.lifecycle = 'completed'
  result.workload.total_client_commands = workloadCommands
  result.commands = {
    status_latency_ms: summarize(commandLatencies.status),
    advance_latency_ms: summarize(commandLatencies.advance)
  }
  const sampleSummary = summarizeBenchmarkSamples(result.samples)
  result.performance = sampleSummary.performance
  result.telemetry_complete = sampleSummary.telemetry_complete
  result.performance_gate = evaluatePerformanceGate(result.performance, options)
  result.server_exit = serverExit
  result.passed = runCompleted
    && result.measured_duration_seconds >= options.durationSeconds
    && result.workload.connected_clients === 4 && result.workload.status_commands > 0
    && result.workload.advance_commands > 0 && result.telemetry_complete
    && result.performance_gate.passed
    && serverExit?.code === 0
  writeBenchmarkResult(options.result, result)
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.passed) process.exitCode = 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    process.stderr.write(`${error.stack ?? error}\n`)
    process.exitCode = 1
  })
}
