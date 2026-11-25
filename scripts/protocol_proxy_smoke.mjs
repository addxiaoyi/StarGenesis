import { spawn, spawnSync, execFileSync } from 'node:child_process'
import { appendFileSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, statfsSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { delimiter, dirname, join, resolve } from 'node:path'
import { createHash, randomBytes } from 'node:crypto'
import net from 'node:net'
import { inventoryItemText, offlinePlayerUuid } from './protocol_proxy_smoke_helpers.mjs'
import {
  findNewJavaProcesses,
  parseJvmProcessList,
  detachedWorkerArguments,
  launchDetachedWorker,
  libraryJars
} from './server_benchmark.mjs'

const require = createRequire(resolve('.work/arena-e2e/package.json'))
const { createBot } = require('mineflayer')

function parseArguments(arguments_) {
  const values = new Map()
  const flags = new Set(['accept-eula', 'detach', 'factory-only', 'reuse-directory'])
  for (let index = 0; index < arguments_.length; index++) {
    const name = arguments_[index]
    if (!name.startsWith('--')) throw new Error(`Unexpected argument: ${name}`)
    const key = name.slice(2)
    if (values.has(key)) throw new Error(`Duplicate option: ${name}`)
    if (flags.has(key)) {
      values.set(key, true)
      continue
    }
    const value = arguments_[++index]
    if (value === undefined || value.startsWith('--')) throw new Error(`Missing value for ${name}`)
    values.set(key, value)
  }
  for (const name of ['java', 'jcmd', 'velocity-jar', 'plugin-jar', 'runtime-directory',
    'directory', 'result', 'port']) {
    if (!values.has(name)) throw new Error(`Missing required option --${name}`)
  }
  if (!values.has('accept-eula')) throw new Error('Pass --accept-eula for this isolated offline proxy test')
  const serverJar = values.get('server-jar') ?? values.get('paper-jar')
  if (!serverJar) throw new Error('Missing required option --server-jar (or --paper-jar)')
  const kind = values.get('kind') ?? 'paper'
  if (!['paper', 'folia'].includes(kind)) throw new Error('--kind must be paper or folia')
  const port = Number(values.get('port'))
  if (!Number.isInteger(port) || port < 1024 || port > 65533)
    throw new Error('--port must be an integer from 1024 through 65533')
  return {
    java: resolve(values.get('java')),
    jcmd: resolve(values.get('jcmd')),
    kind,
    serverJar: resolve(serverJar),
    backendJar: values.has('backend-jar') ? resolve(values.get('backend-jar')) : null,
    velocityJar: resolve(values.get('velocity-jar')),
    pluginJar: resolve(values.get('plugin-jar')),
    runtimeDirectory: resolve(values.get('runtime-directory')),
    directory: resolve(values.get('directory')),
    result: resolve(values.get('result')),
    proxyPort: port,
    serverAPort: port + 1,
    serverBPort: port + 2,
    detach: values.has('detach'),
    factoryOnly: values.has('factory-only'),
    reuseDirectory: values.has('reuse-directory')
  }
}

const options = parseArguments(process.argv.slice(2))
if (options.detach) {
  const workerPid = await launchDetachedWorker(process.execPath, process.argv[1],
    detachedWorkerArguments(process.argv.slice(2)), process.cwd())
  console.log(JSON.stringify({ worker_pid: workerPid, result: options.result }))
  process.exit(0)
}
const startedAt = Date.now()
const result = {
  phase: `${options.factoryOnly ? 'P6 factory' : 'P4 protocol'} ${options.kind} and Velocity relay`,
  kind: options.kind,
  requested_proxy_build: 'Velocity 3.5.1 build 615',
  benchmark_script_sha256: null,
  server_jar_sha256: null,
  backend_jar_sha256: null,
  velocity_jar_sha256: null,
  plugin_jar_sha256: null,
  runtime_environment: {
    os: `${process.platform} ${process.getSystemVersion?.() ?? ''}`.trim(),
    java_processes_at_start: [],
    java_processes_started_during_run: [],
    java_process_probe_errors: []
  },
  scope: 'Functional cross-server relay test only; concurrent Java workloads are recorded and no performance result is inferred.',
  servers: {},
  clients: {},
  checks: {},
  passed: false
}
const MINIMUM_FREE_BYTES = (options.reuseDirectory ? 64 : options.backendJar ? 256 : 1536) * 1024 * 1024

const children = new Map()
const bots = []
const knownJavaPids = new Set()
let processMonitor = null

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function sleep(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
}

function waitFor(predicate, timeoutMilliseconds, label) {
  const started = Date.now()
  return new Promise((resolvePromise, reject) => {
    const poll = () => {
      try {
        if (predicate()) return resolvePromise()
        for (const [name, child] of children) {
          if (child.spawnError) throw new Error(`${name} could not start: ${child.spawnError.message}`)
          if (child.exitCode !== null) throw new Error(`${name} exited before ${label} with code ${child.exitCode}`)
        }
        if (Date.now() - started >= timeoutMilliseconds) throw new Error(`Timed out waiting for ${label}`)
        setTimeout(poll, 100)
      } catch (failure) {
        reject(failure)
      }
    }
    poll()
  })
}

function listJavaProcesses() {
  const output = execFileSync(options.jcmd, ['-l'], { encoding: 'utf8', timeout: 15_000, windowsHide: true })
  return parseJvmProcessList(output)
}

function observeJavaProcesses() {
  try {
    const current = listJavaProcesses()
    const newlyObserved = findNewJavaProcesses(current, [...knownJavaPids])
    const observedAt = new Date().toISOString()
    result.runtime_environment.java_processes_started_during_run.push(...newlyObserved.map(process => ({
      pid: process.pid,
      jvm: process.description,
      first_observed_at: observedAt
    })))
    for (const process of newlyObserved) knownJavaPids.add(process.pid)
  } catch (failure) {
    result.runtime_environment.java_process_probe_errors.push({
      observed_at: new Date().toISOString(),
      reason: failure.message
    })
  }
}

function requireEmptyDirectory(path) {
  if (existsSync(path) && readdirSync(path).length > 0)
    throw new Error(`The proxy test directory must be empty: ${path}`)
  mkdirSync(path, { recursive: true })
}

async function requirePortAvailable(port) {
  await new Promise((resolvePromise, reject) => {
    const socket = net.createServer()
    socket.once('error', reject)
    socket.listen(port, '127.0.0.1', () => socket.close(resolvePromise))
  }).catch(() => {
    throw new Error(`Proxy test port ${port} is already in use`)
  })
}

function replaceExactlyOnce(value, search, replacement, label) {
  const index = value.indexOf(search)
  if (index < 0 || value.indexOf(search, index + search.length) >= 0)
    throw new Error(`Could not locate one ${label} setting in the Paper default configuration`)
  return value.slice(0, index) + replacement + value.slice(index + search.length)
}

function prepareBackend(serverId, peerId, port, relaySecret) {
  const directory = join(options.directory, serverId)
  const plugins = join(directory, 'plugins')
  const pluginData = join(plugins, 'StarGenesis')
  const configDirectory = join(directory, 'config')
  mkdirSync(plugins, { recursive: true })
  mkdirSync(configDirectory, { recursive: true })
  if (!options.backendJar) {
    for (const name of ['cache', 'libraries', 'versions'])
      cpSync(join(options.runtimeDirectory, name), join(directory, name), { recursive: true })
  }
  copyFileSync(join(options.runtimeDirectory, 'config', 'paper-global.yml'),
    join(configDirectory, 'paper-global.yml'))
  if (options.kind === 'folia')
    writeFileSync(join(configDirectory, 'folia-global.yml'), '_version: 1\nthreaded-regions:\n  threads: 2\n')
  copyFileSync(options.pluginJar, join(plugins, 'StarGenesis.jar'))
  mkdirSync(pluginData, { recursive: true })
  const defaultPluginConfig = readFileSync('stargenesis-paper/src/main/resources/config.yml', 'utf8')
    .replace(/\r\n/g, '\n')
  const defaultRelay = [
    'protocol-relay:',
    '  enabled: false',
    "  server-id: ''",
    '  peers: {}',
    ''
  ].join('\n')
  const enabledRelay = [
    'protocol-relay:',
    '  enabled: true',
    `  server-id: '${serverId}'`,
    '  peers:',
    `    ${peerId}: "${relaySecret}"`,
    ''
  ].join('\n')
  let pluginConfig = replaceExactlyOnce(defaultPluginConfig, defaultRelay, enabledRelay, 'protocol-relay')
  if (options.factoryOnly)
    pluginConfig = replaceExactlyOnce(pluginConfig, 'factory-relay:\n  enabled: false',
      'factory-relay:\n  enabled: true', 'factory-relay')
  writeFileSync(join(pluginData, 'config.yml'), pluginConfig)
  writeFileSync(join(directory, 'eula.txt'), 'eula=true\n')
  writeFileSync(join(directory, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${port}`, 'online-mode=false', 'enable-rcon=false',
    'view-distance=2', 'simulation-distance=2', 'spawn-protection=0', 'max-players=8',
    'level-type=minecraft:flat', 'generate-structures=false', 'max-tick-time=60000',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}', ''
  ].join('\n'))
  const spigotTemplate = readFileSync(join(options.runtimeDirectory, 'spigot.yml'), 'utf8')
  writeFileSync(join(directory, 'spigot.yml'),
    replaceExactlyOnce(spigotTemplate, '  bungeecord: false', '  bungeecord: true', 'bungeecord'))
  return directory
}

function prepareVelocity(directory, secret) {
  mkdirSync(directory, { recursive: true })
  writeFileSync(join(directory, 'forwarding.secret'), `${secret}\n`)
  writeFileSync(join(directory, 'velocity.toml'), [
    'config-version = "2.8"',
    `bind = "127.0.0.1:${options.proxyPort}"`,
    'motd = "StarGenesis P4 protocol relay test"',
    'show-max-players = 8',
    'online-mode = false',
    'force-key-authentication = false',
    'prevent-client-proxy-connections = false',
    'player-info-forwarding-mode = "LEGACY"',
    'forwarding-secret-file = "forwarding.secret"',
    'announce-forge = false',
    'kick-existing-players = false',
    'ping-passthrough = "DISABLED"',
    'sample-players-in-ping = false',
    'enable-player-address-logging = false',
    '',
    '[servers]',
    `hub_a = "127.0.0.1:${options.serverAPort}"`,
    `hub_b = "127.0.0.1:${options.serverBPort}"`,
    'try = ["hub_a"]',
    '',
    '[forced-hosts]',
    '"localhost" = ["hub_a"]',
    '"127.0.0.1" = ["hub_b"]',
    '',
    '[advanced]',
    'bungee-plugin-message-channel = true',
    '',
    '[query]',
    'enabled = false',
    `port = ${options.proxyPort}`,
    'map = "Velocity"',
    'show-plugins = false',
    ''
  ].join('\n'))
}

function launchProcess(name, jar, workingDirectory, heap, processorCount = 2, arguments_ = ['--nogui']) {
  const logPath = join(options.directory, `${name}-${startedAt}.log`)
  writeFileSync(logPath, '')
  const entryPoint = name !== 'velocity' && options.backendJar
    ? ['-cp', [options.backendJar, ...libraryJars(join(options.runtimeDirectory, 'libraries'))].join(delimiter),
      'org.bukkit.craftbukkit.Main']
    : ['-jar', jar]
  const child = spawn(options.java, [
    `-Xms128M`, `-Xmx${heap}`, `-XX:ActiveProcessorCount=${processorCount}`,
    '-XX:+UseG1GC', ...entryPoint, ...arguments_
  ], { cwd: workingDirectory, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
  const record = { name, pid: child.pid ?? null, logPath, output: '', spawnError: null }
  child.stdout.setEncoding('utf8')
  child.stderr.setEncoding('utf8')
  const append = chunk => {
    record.output += chunk
    appendFileSync(logPath, chunk)
  }
  child.stdout.on('data', append)
  child.stderr.on('data', append)
  child.once('error', error => { record.spawnError = error })
  child.once('exit', (code, signal) => {
    record.exit_code = code
    record.signal = signal
  })
  children.set(name, child)
  if (child.pid !== undefined) knownJavaPids.add(child.pid)
  result.servers[name] = record
  return child
}

function waitForMessage(bot, predicate, label, timeoutMilliseconds = 20_000) {
  const responses = []
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for ${bot.username} ${label}; received=${JSON.stringify(responses)}`))
    }, timeoutMilliseconds)
    const listener = message => {
      responses.push(message)
      if (!predicate(message)) return
      clearTimeout(timer)
      bot.removeListener('messagestr', listener)
      resolvePromise(message)
    }
    bot.on('messagestr', listener)
  })
}

function waitForWindow(bot, label) {
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('windowOpen', listener)
      reject(new Error(`Timed out waiting for ${bot.username} ${label}`))
    }, 20_000)
    const listener = window => {
      clearTimeout(timer)
      resolvePromise(window)
    }
    bot.once('windowOpen', listener)
  })
}

let previousConnectionAt = 0

async function connectBot(username, host) {
  const connectionDelay = 4_500 - (Date.now() - previousConnectionAt)
  if (connectionDelay > 0) await sleep(connectionDelay)
  previousConnectionAt = Date.now()
  const bot = createBot({ host, port: options.proxyPort, username, auth: 'offline', version: '1.21.11' })
  const diagnostics = { username, host, uuid: offlinePlayerUuid(username), messages: [], events: [] }
  botDiagnostics.set(bot, diagnostics)
  bot.on('messagestr', message => {
    diagnostics.messages.push(String(message).slice(0, 300))
    if (diagnostics.messages.length > 30) diagnostics.messages.shift()
  })
  bot.on('end', reason => diagnostics.events.push({ event: 'end', reason: String(reason ?? '') }))
  bot.on('kicked', reason => diagnostics.events.push({ event: 'kicked', reason: String(reason) }))
  bot.on('error', error => diagnostics.events.push({ event: 'error', message: error.message }))
  bots.push(bot)
  result.clients[username] = diagnostics
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username} did not join through Velocity`)), 45_000)
    bot.once('spawn', () => {
      clearTimeout(timer)
      resolvePromise(bot)
    })
    bot.once('kicked', reason => {
      clearTimeout(timer)
      reject(new Error(`${username} was kicked: ${reason}`))
    })
  })
}

const botDiagnostics = new WeakMap()

async function equipStarterProtocol(bot) {
  const opened = waitForWindow(bot, 'protocol chip menu')
  bot.chat('/genesis protocol')
  await opened
  if ((bot.currentWindow?.slots.length ?? 0) < 27)
    throw new Error(`${bot.username} opened an incomplete protocol menu with ${bot.currentWindow?.slots.length ?? 0} slots`)
  const unlocked = waitForMessage(bot, message => message.includes('已解锁公开入门协议芯片'), 'starter chip unlock')
  await bot.clickWindow(15, 0, 0)
  await unlocked
  const equipped = waitForMessage(bot, message => message.includes('已装备协议芯片'), 'starter chip equip')
  await bot.clickWindow(15, 0, 0)
  await equipped
  bot.closeWindow(bot.currentWindow)
}

function waitForChildText(text, label, timeoutMilliseconds = 180_000) {
  const record = result.servers[label]
  return waitFor(() => record.output.includes(text), timeoutMilliseconds, `${label} ${text}`)
}

async function stopProcess(name, commands) {
  const child = children.get(name)
  if (!child || child.exitCode !== null) return
  for (const command of commands) {
    if (child.stdin && !child.stdin.destroyed) child.stdin.write(`${command}\n`)
    const stopped = await new Promise(resolvePromise => {
      const exited = () => {
        clearTimeout(timer)
        resolvePromise(true)
      }
      const timer = setTimeout(() => {
        child.removeListener('exit', exited)
        resolvePromise(false)
      }, 45_000)
      child.once('exit', exited)
    })
    if (stopped) return
  }
  child.kill()
  await Promise.race([
    new Promise(resolvePromise => child.once('exit', () => resolvePromise(true))),
    sleep(10_000)
  ])
}

async function inboxMessages(bot) {
  const opened = waitForWindow(bot, 'protocol inbox')
  bot.chat('/genesis protocol inbox')
  await opened
  await waitFor(() => bot.currentWindow?.slots[49] != null, 20_000, 'protocol inbox contents')
  const messages = bot.currentWindow.slots.slice(0, 45).filter(item => item?.name === 'paper')
    .map(inventoryItemText)
  bot.closeWindow(bot.currentWindow)
  return messages
}

async function commandReply(bot, command, expected) {
  const response = waitForMessage(bot, message => message.includes(expected), command)
  bot.chat(command)
  return response
}

async function factoryRelayCommand(bot, arguments_, expected, outbox) {
  const response = waitForMessage(bot, message => message.includes(expected), arguments_)
  const queued = waitForMessage(bot, message => message.includes('跨服工厂请求已持久排队'), 'factory request identity')
    .then(message => {
      const id = message.match(/requestId=([0-9a-f-]{36})/)?.[1]
      if (!id) throw new Error('Missing factory relay request ID')
      const path = join(outbox, `${id}.bin`)
      return { path, packet: readFileSync(path) }
    })
  bot.chat(`/genesis factory remote hub_b ${arguments_}`)
  const [, request] = await Promise.all([response, queued])
  if (existsSync(request.path)) throw new Error('Acknowledged factory request is still in the outbox')
  return request
}

async function testFactoryRelay(source, target) {
  const memberOne = await connectBot('SgFactoryOne', 'localhost')
  const memberTwo = await connectBot('SgFactoryTwo', 'localhost')
  const outsider = await connectBot('SgFactoryGuest', 'localhost')
  const members = [source, target, memberOne, memberTwo].map(bot => offlinePlayerUuid(bot.username))
  await commandReply(target, `/genesis factory create ${members.join(' ')}`, '四人虚拟工厂已创建并保存')
  result.checks.four_client_factory_created = true
  const outbox = join(options.directory, 'hub_a', 'plugins', 'StarGenesis', 'factory-relay', 'outbox')
  const checkpoint = join(options.directory, 'hub_b', 'plugins', 'StarGenesis', 'factory-v1.checkpoint')
  await factoryRelayCommand(source, 'status', '虚拟工厂 factory-main', outbox)
  result.checks.remote_member_status = true
  const beforeUnauthorized = sha256(checkpoint)
  await factoryRelayCommand(outsider, 'advance 0 1', '玩家不属于此服务器的工厂四人名册', outbox)
  result.checks.non_member_rejected_without_mutation = sha256(checkpoint) === beforeUnauthorized
  await factoryRelayCommand(source, 'station queue factory:frame 1 1', '生产任务已排入', outbox)
  await factoryRelayCommand(memberOne, 'advance 0 2', '当前 tick 2', outbox)
  const collection = await factoryRelayCommand(source, 'station collect 2 2', '已领取 2 个组件', outbox)
  await factoryRelayCommand(memberTwo, 'advance 2 1', '当前 tick 3', outbox)
  result.checks.remote_production_and_collection = true
  const beforeReplay = sha256(checkpoint)
  const acknowledgement = waitForMessage(source, message => message.includes('已领取 2 个组件'),
    'factory cached response after later tick')
  writeFileSync(collection.path, collection.packet)
  await acknowledgement
  result.checks.same_request_after_tick_change_preserves_checkpoint = sha256(checkpoint) === beforeReplay
  result.checks.replayed_factory_outbox_removed = !existsSync(collection.path)
  result.checks.factory_journal_completed = !existsSync(join(options.directory, 'hub_b', 'plugins',
    'StarGenesis', 'factory-relay', 'transaction-v1.checkpoint'))
  result.checks.remote_backend_has_no_factory_ledger = !existsSync(join(options.directory, 'hub_a',
    'plugins', 'StarGenesis', 'factory-v1.checkpoint'))
}

async function main() {
  mkdirSync(dirname(options.result), { recursive: true })
  if (existsSync(options.result)) throw new Error(`Result file already exists: ${options.result}`)
  for (const path of [options.serverJar, options.velocityJar, options.pluginJar,
    ...(options.backendJar ? [options.backendJar] : [])])
    if (!existsSync(path)) throw new Error(`Required file does not exist: ${path}`)
  for (const name of options.backendJar ? ['libraries'] : ['cache', 'libraries', 'versions'])
    if (!existsSync(join(options.runtimeDirectory, name)))
      throw new Error(`Runtime directory is missing ${name}: ${options.runtimeDirectory}`)
  if (!existsSync(join(options.runtimeDirectory, 'config', 'paper-global.yml')))
    throw new Error(`Runtime directory is missing config/paper-global.yml: ${options.runtimeDirectory}`)

  result.benchmark_script_sha256 = sha256(resolve(process.argv[1]))
  const availableSpace = statfsSync(dirname(options.directory))
  const availableBytes = availableSpace.bavail * availableSpace.bsize
  result.runtime_environment.available_bytes_before_start = availableBytes
  result.runtime_environment.minimum_free_bytes = MINIMUM_FREE_BYTES
  if (availableBytes < MINIMUM_FREE_BYTES)
    throw new Error(`Proxy test requires at least ${MINIMUM_FREE_BYTES} free bytes; found ${availableBytes}`)

  result.server_jar_sha256 = sha256(options.serverJar)
  result.backend_jar_sha256 = options.backendJar ? sha256(options.backendJar) : null
  result.runtime_environment.library_mode = options.backendJar ? 'shared classpath' : 'copied runtime'
  result.velocity_jar_sha256 = sha256(options.velocityJar)
  result.plugin_jar_sha256 = sha256(options.pluginJar)
  const javaVersion = spawnSync(options.java, ['-version'], { encoding: 'utf8', windowsHide: true })
  if (javaVersion.error || javaVersion.status !== 0)
    throw javaVersion.error ?? new Error(`Java version check exited with ${javaVersion.status}`)
  result.java = javaVersion.stderr.trim()

  const processesAtStart = listJavaProcesses()
  result.runtime_environment.java_processes_at_start = processesAtStart
  for (const process of processesAtStart) knownJavaPids.add(process.pid)
  for (const port of [options.proxyPort, options.serverAPort, options.serverBPort]) await requirePortAvailable(port)
  if (options.reuseDirectory) {
    if (!options.factoryOnly || !options.backendJar)
      throw new Error('--reuse-directory requires --factory-only and --backend-jar')
    for (const server of ['hub_a', 'hub_b']) {
      const properties = join(options.directory, server, 'server.properties')
      if (!existsSync(properties) || !readFileSync(properties, 'utf8').includes('server-ip=127.0.0.1'))
        throw new Error('Reused backends must be prepared local proxy smoke servers')
      if (existsSync(join(options.directory, server, 'plugins', 'StarGenesis', 'factory-v1.checkpoint')))
        throw new Error('Factory relay scenario requires backends without an existing factory')
    }
  } else {
    requireEmptyDirectory(options.directory)
  }
  const relaySecret = randomBytes(32).toString('base64')
  const serverADirectory = prepareBackend('hub_a', 'hub_b', options.serverAPort, relaySecret)
  const serverBDirectory = prepareBackend('hub_b', 'hub_a', options.serverBPort, relaySecret)
  const proxyDirectory = join(options.directory, 'velocity')
  prepareVelocity(proxyDirectory, relaySecret)

  const poll = () => {
    observeJavaProcesses()
  }
  processMonitor = setInterval(poll, 5_000)
  processMonitor.unref()

  launchProcess('hub_a', options.serverJar, serverADirectory, '512M')
  await waitForChildText('StarGenesis ready', 'hub_a')
  launchProcess('hub_b', options.serverJar, serverBDirectory, '512M')
  await waitForChildText('StarGenesis ready', 'hub_b')
  const expectedKind = options.kind === 'folia' ? 'Folia' : 'Paper'
  for (const name of ['hub_a', 'hub_b']) {
    const identity = result.servers[name].output.match(/This server is running (Paper|Folia) version ([^\s]+)/)
    if (!identity || identity[1] !== expectedKind) throw new Error(`${name} did not report ${expectedKind}`)
    result.servers[name].version = identity[2]
  }
  launchProcess('velocity', options.velocityJar, proxyDirectory, '384M', 2, [])
  await waitForChildText(`127.0.0.1:${options.proxyPort}`, 'velocity')
  result.checks.velocity_started = true

  const sourceName = 'SgRelaySource'
  const targetName = 'SgRelayTarget'
  const source = await connectBot(sourceName, 'localhost')
  let target = await connectBot(targetName, '127.0.0.1')
  const sourceUuid = offlinePlayerUuid(sourceName)
  const targetUuid = offlinePlayerUuid(targetName)
  result.clients[sourceName].server_route = 'hub_a'
  result.clients[targetName].server_route = 'hub_b'
  await waitFor(() => result.servers.hub_a.output.includes(`UUID of player ${sourceName} is ${sourceUuid}`),
    20_000, `${sourceName} forwarded UUID on hub_a`)
  await waitFor(() => result.servers.hub_b.output.includes(`UUID of player ${targetName} is ${targetUuid}`),
    20_000, `${targetName} forwarded UUID on hub_b`)
  result.checks.both_backends_enabled_with_relay =
    result.servers.hub_a.output.includes('Cross-server protocol relay enabled | server=hub_a | trusted-peers=1')
    && result.servers.hub_b.output.includes('Cross-server protocol relay enabled | server=hub_b | trusted-peers=1')
  result.checks.clients_joined_distinct_backends_and_uuid_forwarding = sourceUuid !== targetUuid
    && result.servers.hub_a.output.includes(`${sourceName} joined the game`)
    && result.servers.hub_b.output.includes(`${targetName} joined the game`)
  if (options.factoryOnly) {
    await testFactoryRelay(source, target)
    return
  }
  await equipStarterProtocol(source)
  await equipStarterProtocol(target)
  result.checks.both_clients_equipped_starter_protocol = true

  const acknowledgement = waitForMessage(source,
    message => message.includes('跨服协议消息已写入目标收件箱。'), 'relay acknowledgement')
  source.chat(`/genesis protocol relay hub_b ${targetUuid} 42`)
  await acknowledgement
  result.checks.source_received_authenticated_target_acknowledgement = true

  const firstInbox = await inboxMessages(target)
  result.checks.target_inbox_contains_relayed_message = firstInbox.length === 1
    && firstInbox[0].includes(`来自 ${sourceName}`) && firstInbox[0].includes('sequence=2:42')
  if (!result.checks.target_inbox_contains_relayed_message)
    throw new Error(`Unexpected target inbox contents: ${JSON.stringify(firstInbox)}`)
  result.checks.cross_server_payload_and_ack_via_proxy = true

  target.quit('test delayed cross-server delivery')
  await waitFor(() => result.servers.hub_b.output.includes(`${targetName} left the game`),
    20_000, 'target offline on backend')
  const queued = waitForMessage(source, message => message.includes('跨服协议消息已进入持久发件箱'),
    'offline recipient outbox persistence')
  source.chat(`/genesis protocol relay hub_b ${targetUuid} 43`)
  await queued
  const outbox = join(serverADirectory, 'plugins', 'StarGenesis', 'protocol-relay', 'outbox')
  const pending = readdirSync(outbox).filter(name => name.endsWith('.bin'))
  if (pending.length !== 1) throw new Error(`Expected one pending relay, found ${pending.length}`)
  const pendingPath = join(outbox, pending[0])
  const pendingPacket = readFileSync(pendingPath)
  await sleep(40_000)
  result.checks.outbox_retained_after_40_seconds = existsSync(pendingPath)
  if (!result.checks.outbox_retained_after_40_seconds)
    throw new Error('Pending relay disappeared before its 120-second expiry')

  const delayedAcknowledgement = waitForMessage(source,
    message => message.includes('跨服协议消息已写入目标收件箱。'), 'delayed relay acknowledgement', 45_000)
  const [, reconnectedTarget] = await Promise.all([
    delayedAcknowledgement, connectBot(targetName, '127.0.0.1')
  ])
  target = reconnectedTarget
  const recoveredInbox = await inboxMessages(target)
  result.checks.delayed_delivery_after_reconnect = recoveredInbox.length === 2
    && recoveredInbox.some(text => text.includes('sequence=2:42'))
    && recoveredInbox.some(text => text.includes('sequence=2:43'))
  result.checks.acknowledged_outbox_removed = !existsSync(pendingPath)

  const cleared = waitForMessage(target, message => message.includes('协议收件箱已清空'), 'clear inbox')
  target.chat('/genesis protocol inbox clear')
  await cleared
  const replayAcknowledgement = waitForMessage(source,
    message => message.includes('跨服协议消息已写入目标收件箱。'), 'replayed packet acknowledgement')
  writeFileSync(pendingPath, pendingPacket)
  await replayAcknowledgement
  result.checks.cleared_inbox_suppresses_same_signed_packet = (await inboxMessages(target)).length === 0
  result.checks.replayed_outbox_removed = !existsSync(pendingPath)
}

try {
  await main()
} catch (failure) {
  result.error = failure.stack ?? failure.message ?? String(failure)
} finally {
  if (processMonitor) clearInterval(processMonitor)
  for (const bot of bots) {
    try { bot.quit('protocol relay test complete') } catch { }
  }
  await stopProcess('hub_a', ['stop'])
  await stopProcess('hub_b', ['stop'])
  await stopProcess('velocity', ['shutdown', 'end'])
  result.duration_seconds = Math.round((Date.now() - startedAt) / 100) / 10
  result.runtime_environment.java_processes_at_end = (() => {
    try { return listJavaProcesses() } catch (failure) { return [{ error: failure.message }] }
  })()
  for (const [name, record] of Object.entries(result.servers)) {
    record.exit_code = children.get(name)?.exitCode ?? record.exit_code ?? null
    record.signal = children.get(name)?.signalCode ?? record.signal ?? null
    record.log_sha256 = existsSync(record.logPath) ? sha256(record.logPath) : null
    record.log_file = record.logPath ? record.logPath.slice(options.directory.length + 1) : null
    delete record.output
    delete record.spawnError
    delete record.logPath
  }
  for (const bot of bots) {
    const diagnostics = botDiagnostics.get(bot)
    if (diagnostics) result.clients[diagnostics.username] = diagnostics
  }
  result.passed = result.error === undefined
    && Object.values(result.checks).length > 0
    && Object.values(result.checks).every(Boolean)
    && Object.keys(result.servers).length === 3
    && Object.values(result.servers).every(server => server.exit_code === 0)
  writeFileSync(options.result, `${JSON.stringify(result, null, 2)}\n`)
  console.log(JSON.stringify(result, null, 2))
}

if (!result.passed) process.exitCode = 1
