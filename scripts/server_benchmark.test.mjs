import assert from 'node:assert/strict'
import test from 'node:test'
import { spawn } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { appendFileSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import {
  benchmarkStartupConflict,
  captureBenchmarkServerIo,
  cleanupBenchmarkResources,
  describeChildProcessError,
  detachedWorkerArguments,
  evaluatePerformanceGate,
  launchDetachedWorker,
  monitorBenchmarkBot,
  normalizeExitCode,
  findNewJavaProcesses,
  parseArguments,
  parseJvmProcessList,
  parseWindowsJavaProcessList,
  parsePerformanceLines,
  summarizeBenchmarkSamples,
  readJavaProcesses,
  shouldStopForNewJavaProcesses,
  waitForBenchmarkBot,
  writeBenchmarkResult
} from './server_benchmark.mjs'

function isProcessRunning(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    if (error.code === 'ESRCH') return false
    throw error
  }
}

async function removeTemporaryDirectory(directory) {
  const deadline = Date.now() + 5_000
  while (true) {
    try {
      rmSync(directory, { recursive: true, force: true })
      return
    } catch (error) {
      if (!['EBUSY', 'EPERM'].includes(error.code) || Date.now() >= deadline) throw error
      await new Promise(resolvePromise => setTimeout(resolvePromise, 50))
    }
  }
}

test('log I/O failure and a throwing bot quit still stop the owned child and save diagnostics', { timeout: 10_000 }, async t => {
  const directory = mkdtempSync(join(tmpdir(), 'stargenesis-benchmark-io-'))
  t.after(() => removeTemporaryDirectory(directory))
  const resultPath = join(directory, 'result.json')
  writeBenchmarkResult(resultPath, { lifecycle: 'running', passed: false })
  const child = spawn(process.execPath, ['-e', [
    "process.stdin.setEncoding('utf8')",
    "process.stdin.on('data', text => { if (text.includes('stop')) process.exit(0) })",
    "process.stdout.write('ready\\n')"
  ].join(';')], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill() })
  let firstFailure = null
  let observedFailure
  const failed = new Promise(resolvePromise => { observedFailure = resolvePromise })
  const capture = error => { firstFailure ??= error; observedFailure(error) }
  const monitor = captureBenchmarkServerIo(child,
    // Writing to an actual directory produces a real filesystem error on both test hosts.
    chunk => appendFileSync(directory, chunk), capture)
  await failed
  const quitCalls = []
  const cleanupErrors = await cleanupBenchmarkResources([
    { quit() { quitCalls.push('first'); throw new Error('quit failed') } },
    { quit() { quitCalls.push('second') } }
  ], child, monitor, { timeoutMilliseconds: 2_000, forceTimeoutMilliseconds: 2_000, onFailure: capture })
  writeBenchmarkResult(resultPath, {
    lifecycle: 'completed', passed: false,
    error: firstFailure.message, cleanup_errors: cleanupErrors
  })

  const result = JSON.parse(readFileSync(resultPath, 'utf8'))
  assert.equal(result.lifecycle, 'completed')
  assert.equal(result.passed, false)
  assert.equal(result.error, firstFailure.message)
  assert.match(result.cleanup_errors[0].message, /quit failed/)
  assert.deepEqual(quitCalls, ['first', 'second'])
  assert.equal(child.exitCode, 0)
  assert.equal(monitor.isClosed(), true)
})

test('an asynchronous stdin EPIPE is captured and cleanup reaps the child', { timeout: 10_000 }, async t => {
  const child = spawn(process.execPath, ['-e', 'process.stdin.resume(); setInterval(() => {}, 1000)'],
    { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill() })
  const errors = []
  let failed
  const failure = new Promise(resolvePromise => { failed = resolvePromise })
  const monitor = captureBenchmarkServerIo(child, () => {}, error => { errors.push(error); failed() })
  await new Promise((resolvePromise, reject) => { child.once('spawn', resolvePromise); child.once('error', reject) })
  child.stdin.destroy(Object.assign(new Error('pipe closed'), { code: 'EPIPE' }))
  await failure
  await cleanupBenchmarkResources([], child, monitor, {
    timeoutMilliseconds: 100, forceTimeoutMilliseconds: 2_000,
    onFailure: error => errors.push(error)
  })

  assert.equal(errors[0].code, 'EPIPE')
  assert.equal(monitor.isClosed(), true)
})

test('partially connected bots remain available for cleanup and drop their connection listeners', async () => {
  const bots = [new EventEmitter(), new EventEmitter()]
  const quitCalls = []
  bots.forEach((bot, index) => { bot.quit = () => { quitCalls.push(index); bot.emit('end', 'cleanup') } })
  const connecting = Promise.all(bots.map((bot, index) => waitForBenchmarkBot(bot, `bot-${index}`, 1_000)))
  bots[0].emit('kicked', 'denied')
  await assert.rejects(connecting, /was kicked: denied/)
  await cleanupBenchmarkResources(bots, null, null)

  assert.deepEqual(quitCalls, [0, 1])
  for (const bot of bots) assert.equal(bot.eventNames().length, 0)

  const abortedBot = new EventEmitter()
  const controller = new AbortController()
  const pending = waitForBenchmarkBot(abortedBot, 'aborted', 1_000, controller.signal)
  const originalFailure = new Error('server log unavailable')
  controller.abort(originalFailure)
  await assert.rejects(pending, error => error === originalFailure)
  assert.equal(abortedBot.eventNames().length, 0)
})

test('an idle benchmark client disconnect aborts the run but intentional cleanup does not', () => {
  const bot = new EventEmitter()
  const diagnostics = { events: [] }
  const controller = new AbortController()
  let closing = false
  monitorBenchmarkBot(bot, 'idle-client', diagnostics, () => closing,
    error => controller.abort(error))

  bot.emit('end', 'connection lost between commands')
  assert.equal(controller.signal.aborted, true)
  assert.match(controller.signal.reason.message, /idle-client disconnected during benchmark/)
  assert.deepEqual(diagnostics.events, [{ event: 'end', reason: 'connection lost between commands' }])

  closing = true
  bot.emit('end', 'intentional quit')
  assert.equal(diagnostics.events.length, 2)
  assert.match(diagnostics.events[1].reason, /intentional quit/)
  assert.equal(bot.listenerCount('end'), 1)
})

test('cleanup force-stops a child that ignores the graceful stop request', { timeout: 10_000 }, async t => {
  const child = spawn(process.execPath, ['-e', 'process.stdin.resume(); setInterval(() => {}, 1000)'],
    { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill() })
  const monitor = captureBenchmarkServerIo(child, () => {}, () => {})
  await new Promise((resolvePromise, reject) => { child.once('spawn', resolvePromise); child.once('error', reject) })
  const errors = await cleanupBenchmarkResources([], child, monitor,
    { timeoutMilliseconds: 50, forceTimeoutMilliseconds: 2_000 })

  assert.match(errors[0].message, /Server stop timed out/)
  assert.equal(monitor.isClosed(), true)
})

test('a worker terminated after its initial snapshot leaves an explicit incomplete result', { timeout: 10_000 }, async t => {
  const directory = mkdtempSync(join(tmpdir(), 'stargenesis-benchmark-result-'))
  t.after(() => removeTemporaryDirectory(directory))
  const resultPath = join(directory, 'result.json')
  const workerScript = join(directory, 'snapshot-worker.mjs')
  writeFileSync(workerScript, [
    `import { writeBenchmarkResult } from ${JSON.stringify(new URL('./server_benchmark.mjs', import.meta.url).href)}`,
    `writeBenchmarkResult(${JSON.stringify(resultPath)}, { lifecycle: 'running', passed: false })`,
    'process.exit(23)', ''
  ].join('\n'))
  const child = spawn(process.execPath, [workerScript], { stdio: 'ignore', windowsHide: true })
  const code = await new Promise((resolvePromise, reject) => {
    child.once('close', resolvePromise)
    child.once('error', reject)
  })

  assert.equal(code, 23)
  assert.deepEqual(JSON.parse(readFileSync(resultPath, 'utf8')), { lifecycle: 'running', passed: false })
})

test('parses the optional benchmark environment note', () => {
  const options = parseArguments([
    '--kind', 'paper', '--server-jar', 'paper.jar', '--plugin-jar', 'plugin.jar',
    '--runtime-directory', 'runtime', '--directory', 'server', '--result', 'result.json',
    '--accept-eula', '--environment-note', 'No other Java servers were running'
  ])

  assert.equal(options.durationSeconds, 1800)
  assert.equal(options.environmentNote, 'No other Java servers were running')
})

test('enables explicitly requested shared-host measurements and records policy', () => {
  const options = parseArguments([
    '--kind', 'folia', '--server-jar', 'folia.jar', '--plugin-jar', 'plugin.jar',
    '--runtime-directory', 'runtime', '--directory', 'server', '--result', 'result.json',
    '--accept-eula', '--allow-concurrent-java'
  ])

  assert.equal(options.allowConcurrentJava, true)
  const processes = [{ pid: 101, description: 'Maven verify' }]
  assert.deepEqual(benchmarkStartupConflict(processes), {
    elapsed_seconds: 0, java_processes: processes
  })
  assert.equal(benchmarkStartupConflict(processes, true), null)
  assert.equal(shouldStopForNewJavaProcesses(processes), true)
  assert.equal(shouldStopForNewJavaProcesses(processes, true), false)
  assert.equal(shouldStopForNewJavaProcesses([]), false)
})

test('detaches the benchmark worker without forwarding the launcher flag', () => {
  const args = ['--kind', 'paper', '--detach', '--result', 'run.json', '--accept-eula']
  assert.deepEqual(detachedWorkerArguments(args), [
    '--kind', 'paper', '--result', 'run.json', '--accept-eula'
  ])

  const options = parseArguments(args.concat([
    '--server-jar', 'paper.jar', '--plugin-jar', 'plugin.jar',
    '--runtime-directory', 'runtime', '--directory', 'server'
  ]))
  assert.equal(options.detach, true)
})

test('detached workers outlive the launcher and finish their isolated task', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'stargenesis-detached-test-'))
  t.after(() => removeTemporaryDirectory(directory))
  const workerScript = join(directory, 'worker.mjs')
  const completionFile = join(directory, 'completed.txt')
  writeFileSync(workerScript, [
    "import { writeFileSync } from 'node:fs'",
    "import { dirname, join } from 'node:path'",
    "import { fileURLToPath } from 'node:url'",
    "writeFileSync(join(dirname(fileURLToPath(import.meta.url)), 'completed.txt'), 'done')",
    ''
  ].join('\n'))

  const pid = await launchDetachedWorker(process.execPath, workerScript, [], directory)
  assert.ok(Number.isInteger(pid) && pid > 0)

  const deadline = Date.now() + 5_000
  while (!existsSync(completionFile) && Date.now() < deadline) {
    await new Promise(resolvePromise => setTimeout(resolvePromise, 25))
  }

  assert.equal(readFileSync(completionFile, 'utf8'), 'done')

  const exitDeadline = Date.now() + 5_000
  while (isProcessRunning(pid) && Date.now() < exitDeadline) {
    await new Promise(resolvePromise => setTimeout(resolvePromise, 25))
  }
  assert.equal(isProcessRunning(pid), false)
})

test('resolves a relative Java executable before changing the server working directory', () => {
  const relativeJava = '.work/jdk21/bin/java.exe'
  const options = parseArguments([
    '--kind', 'paper', '--server-jar', 'paper.jar', '--plugin-jar', 'plugin.jar',
    '--runtime-directory', 'runtime', '--directory', 'server', '--result', 'result.json',
    '--accept-eula', '--java', relativeJava, '--backend-jar', 'runtime/versions/paper.jar'
  ])

  assert.equal(options.java, resolve(relativeJava))
  assert.equal(options.backendJar, resolve('runtime/versions/paper.jar'))
  assert.equal(options.jcmd, join(dirname(resolve(relativeJava)),
    process.platform === 'win32' ? 'jcmd.exe' : 'jcmd'))
})

test('parses JVM descriptions, removes duplicate PIDs, and excludes the jcmd probe', () => {
  assert.deepEqual(parseJvmProcessList([
    '32492 C:\\servers\\paper.jar --nogui',
    '16564 org.codehaus.plexus.classworlds.launcher.Launcher verify',
    '32492 C:\\servers\\paper.jar --nogui',
    '2210 jdk.jcmd/sun.tools.jcmd.JCmd -l',
    'not-a-process'
  ].join('\n')), [
    { pid: 16564, description: 'org.codehaus.plexus.classworlds.launcher.Launcher verify' },
    { pid: 32492, description: 'C:\\servers\\paper.jar --nogui' }
  ])
  assert.deepEqual(parseJvmProcessList(''), [])
})

test('parses Windows Java process fallback output as a single record, list or empty result', () => {
  assert.deepEqual(parseWindowsJavaProcessList(''), [])
  assert.deepEqual(parseWindowsJavaProcessList('{"pid":42,"description":"Paper server"}'), [
    { pid: 42, description: 'Paper server' }
  ])
  assert.deepEqual(parseWindowsJavaProcessList('[{"pid":90,"description":"Folia"},{"pid":12,"description":"Maven"}]'), [
    { pid: 12, description: 'Maven' },
    { pid: 90, description: 'Folia' }
  ])
  assert.deepEqual(parseWindowsJavaProcessList('{"pid":7,"description":""}'), [
    { pid: 7, description: 'Windows Java process (command line unavailable)' }
  ])
})

test('uses the Windows process query as the authoritative inventory and fails closed on query errors', () => {
  const calls = []
  const runCommand = (command, args) => {
    calls.push({ command, args })
    assert.equal(command, 'powershell.exe')
    assert.ok(args.join(' ').includes('-ErrorAction Stop'))
    return '[{"pid":31,"description":"Paper"}]'
  }
  assert.deepEqual(readJavaProcesses('jcmd.exe', 'win32', runCommand), [
    { pid: 31, description: 'Paper' }
  ])
  assert.deepEqual(calls.map(call => call.command), ['powershell.exe'])

  assert.throws(() => readJavaProcesses('jcmd', 'linux', () => {
    throw new Error('probe unavailable')
  }), /probe unavailable/)
  assert.throws(() => readJavaProcesses('jcmd.exe', 'win32', () => {
    throw new Error('both probes unavailable')
  }), /Could not enumerate Windows Java processes/)
})

test('identifies JVMs that were not present in the benchmark baseline', () => {
  const processes = [
    { pid: 100, description: 'Paper server' },
    { pid: 200, description: 'Folia server' }
  ]

  assert.deepEqual(findNewJavaProcesses(processes, [100]), [processes[1]])
})

test('rejects any Java process in the benchmark startup baseline', () => {
  const processes = [
    { pid: 100, description: 'C:\\servers\\paper-1.21.11.jar --nogui' },
    { pid: 200, description: 'C:\\servers\\folia.jar --nogui' },
    { pid: 300, description: 'C:\\proxy\\velocity-3.4.jar' },
    { pid: 400, description: 'org.codehaus.plexus.classworlds.launcher.Launcher verify' }
  ]

  assert.deepEqual(benchmarkStartupConflict(processes), { elapsed_seconds: 0, java_processes: processes })
  assert.equal(benchmarkStartupConflict([]), null)
})

test('normalizes unsigned Windows process exit codes and preserves other values', () => {
  assert.equal(normalizeExitCode(0xffff_ffff, 'win32'), -1)
  assert.equal(normalizeExitCode(0x8000_0000, 'win32'), -0x8000_0000)
  assert.equal(normalizeExitCode(12, 'win32'), 12)
  assert.equal(normalizeExitCode(0xffff_ffff, 'linux'), 0xffff_ffff)
})

test('preserves Java process spawn diagnostics', () => {
  const error = Object.assign(new Error('could not create process'), {
    code: 'EACCES',
    syscall: 'spawn java.exe',
    path: 'C:\\jdk\\bin\\java.exe'
  })

  assert.deepEqual(describeChildProcessError(error), {
    name: 'Error',
    code: 'EACCES',
    message: 'could not create process',
    syscall: 'spawn java.exe',
    path: 'C:\\jdk\\bin\\java.exe'
  })
})

test('parses colored Paper Spark TPS and tick-duration output', () => {
  const metrics = parsePerformanceLines([
    '> \r[12:27:37 INFO]: \u001b[37m[\u001b[93m⚡\u001b[0m] TPS from last 5s, 10s, 1m, 5m, 15m:\u001b[0m',
    '> \r[12:27:37 INFO]: \u001b[37m[\u001b[93m⚡\u001b[0m] 20.0, 20.0, 19.72, 19.94, 19.98\u001b[0m',
    '> \r[12:27:37 INFO]: \u001b[37m[\u001b[93m⚡\u001b[0m] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:\u001b[0m',
    '> \r[12:27:37 INFO]: \u001b[37m[\u001b[93m⚡\u001b[0m] 1.1/1.8/4.1/27.3; 0.3/2.0/25.5/225.9\u001b[0m'
  ])

  assert.equal(metrics.tps, 20)
  assert.equal(metrics.tpsWindow, '5s')
  assert.equal(metrics.tickMedian, 1.8)
  assert.equal(metrics.tickP95, 4.1)
  assert.equal(metrics.tickMaximum, 27.3)
  assert.equal(metrics.tickWindow, '10s')
  assert.equal(metrics.tickMetric, 'spark tick-duration distribution')
})

test('parses native Folia TPS and average/minimum/maximum tick-time output', () => {
  const metrics = parsePerformanceLines([
    '[12:33:22 INFO]: TPS from last 1m, 5m, 15m: 19.98, 19.95, 19.92',
    '[12:33:22 INFO]: Server tick times (avg/min/max) from last 5s, 10s, 1m:',
    '[12:33:22 INFO]: ◴ 2.6/1.4/27.0, 2.7/1.5/30.0, 9.5/0.3/1136.1'
  ])

  assert.equal(metrics.tps, 19.98)
  assert.equal(metrics.tpsWindow, '1m')
  assert.equal(metrics.tickMean, 2.6)
  assert.equal(metrics.tickMinimum, 1.4)
  assert.equal(metrics.tickMaximum, 27)
  assert.equal(metrics.tickWindow, '5s')
  assert.equal(metrics.tickMetric, 'server average/minimum/maximum tick time')
  assert.equal(metrics.tickP95, null)
})

test('parses Folia health-report median region TPS and highest-utilization MSPT', () => {
  const metrics = parsePerformanceLines([
    '> [19:21:42 INFO]: \u001b[1m\u001b[96mServer Health Report',
    '\u001b[1m - Online Players: 4',
    ' - Total regions: 1',
    ' - Median Region TPS: 19.93',
    'Highest 3 utilisation regions',
    "Region around block [w:'world',263,80,-9]:",
    '  5.6% util at 2.90 MSPT at 19.93 TPS',
    'Chunks: 143 Players: 4 Entities: 4',
    'Server tick times (avg/min/max) from last 5s, 10s, 1m:',
    '0.0/0.0/0.0, 0.0/0.0/0.0, 0.0/0.0/0.0'
  ])

  assert.equal(metrics.tps, 19.93)
  assert.equal(metrics.tpsWindow, 'median Folia region')
  assert.equal(metrics.tickMean, 2.9)
  assert.equal(metrics.tickP95, null)
  assert.equal(metrics.tickWindow, 'highest-utilization Folia region')
  assert.equal(metrics.tickMetric, 'Folia highest-utilization region MSPT')
})

test('rejects incomplete or unrelated console output', () => {
  assert.equal(parsePerformanceLines(['Unknown command: spark tps']), null)
  assert.equal(parsePerformanceLines([
    'TPS from last 5s, 1m:',
    '20.0, 19.9'
  ]), null)
})

test('summarizes metrics collected before a benchmark is interrupted', () => {
  const summary = summarizeBenchmarkSamples([
    {
      tps: 20,
      tick_duration_mean_ms: 2.71,
      tick_duration_p95_ms: null,
      cpu_percent_of_one_core: null,
      available_memory_bytes: 2_000 * 1024 * 1024,
      rss_bytes: 900 * 1024 * 1024,
      private_bytes: 925 * 1024 * 1024,
      heap_used_bytes: 388 * 1024 * 1024
    },
    {
      tps: 20,
      tick_duration_mean_ms: 1.24,
      tick_duration_p95_ms: null,
      cpu_percent_of_one_core: 10.4,
      available_memory_bytes: 1_900 * 1024 * 1024,
      rss_bytes: 895 * 1024 * 1024,
      private_bytes: 920 * 1024 * 1024,
      heap_used_bytes: null
    }
  ])

  assert.equal(summary.performance.tps.count, 2)
  assert.equal(summary.performance.tps.mean, 20)
  assert.equal(summary.performance.tick_duration_mean_ms.count, 2)
  assert.equal(summary.performance.heap_used_mib.count, 1)
  assert.equal(summary.telemetry_complete, true)
})

test('requires sample coverage and every TPS, tick-time and memory gate to pass', () => {
  const passingPerformance = {
    tps: { count: 4, p50: 20 },
    tick_duration_p95_ms: { count: 4, p95: 8 },
    tick_duration_mean_ms: { count: 0, p95: null },
    available_memory_mib: { count: 4, min: 1536 },
    rss_mib: { count: 4, max: 1100 },
    heap_used_mib: { count: 1, max: 420 }
  }
  const options = { durationSeconds: 60, sampleSeconds: 15, memorySampleSeconds: 60 }
  const passing = evaluatePerformanceGate(passingPerformance, options)
  assert.equal(passing.passed, true)
  assert.equal(passing.checks.metric_sample_coverage.minimum, 4)

  const slowServer = evaluatePerformanceGate({
    ...passingPerformance,
    tps: { count: 4, p50: 18.9 }
  }, options)
  assert.equal(slowServer.checks.median_tps.passed, false)

  const slowTicks = evaluatePerformanceGate({
    ...passingPerformance,
    tick_duration_p95_ms: { count: 4, p95: 50.1 }
  }, options)
  assert.equal(slowTicks.checks.tick_duration_p95_ms.passed, false)

  const lowMemory = evaluatePerformanceGate({
    ...passingPerformance,
    available_memory_mib: { count: 4, min: 1023.9 }
  }, options)
  assert.equal(lowMemory.checks.available_memory_mib.passed, false)

  const highRss = evaluatePerformanceGate({
    ...passingPerformance,
    rss_mib: { count: 4, max: 1536.1 }
  }, options)
  assert.equal(highRss.checks.rss_mib.passed, false)

  const highHeap = evaluatePerformanceGate({
    ...passingPerformance,
    heap_used_mib: { count: 1, max: 480.1 }
  }, options)
  assert.equal(highHeap.checks.heap_used_mib.passed, false)

  const insufficientMetricSampling = evaluatePerformanceGate({
    ...passingPerformance,
    tps: { count: 3, p50: 20 }
  }, options)
  assert.equal(insufficientMetricSampling.checks.metric_sample_coverage.passed, false)

  const missingMemorySampling = evaluatePerformanceGate({
    ...passingPerformance,
    rss_mib: { count: 1, max: 900 }
  }, options)
  assert.equal(missingMemorySampling.checks.metric_sample_coverage.passed, false)

  const insufficientSampling = evaluatePerformanceGate({
    ...passingPerformance,
    heap_used_mib: { count: 0, max: null }
  }, options)
  assert.equal(insufficientSampling.checks.heap_sample_coverage.passed, false)
  assert.equal(insufficientSampling.checks.heap_used_mib.passed, false)
})
