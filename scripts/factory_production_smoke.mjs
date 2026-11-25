import { spawn } from 'node:child_process'
import { appendFileSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join, resolve } from 'node:path'
import { createHash } from 'node:crypto'

const require = createRequire(resolve('.work/arena-e2e/package.json'))
const { createBot } = require('mineflayer')
const options = Object.fromEntries(process.argv.slice(2).reduce((values, value, index, all) => {
  if (value.startsWith('--')) values.push([value.slice(2), all[index + 1]])
  return values
}, []))

const kind = options.kind
const serverJar = resolve(options['server-jar'])
const pluginJar = resolve(options['plugin-jar'])
const directory = resolve(options.directory)
const runtimeDirectory = resolve(options['runtime-directory'])
const resultPath = resolve(options.result)
const java = resolve(options.java)
const port = Number(options.port)
const ownerName = `SgProd${kind === 'paper' ? 'Paper' : 'Folia'}`
const organizationName = `p6prod_${kind}`
const logPath = join(directory, 'production-smoke.log')
const serverLogPath = join(directory, 'server-lifecycle.log')
let server
let serverOutput = ''
let owner
let guest
const checks = {}
const versions = []

function sleep(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds))
}

function waitFor(predicate, timeout, label) {
  const started = Date.now()
  return new Promise((resolvePromise, reject) => {
    const poll = () => {
      if (predicate()) return resolvePromise()
      if (Date.now() - started >= timeout) return reject(new Error(`Timed out waiting for ${label}`))
      setTimeout(poll, 100)
    }
    poll()
  })
}

function requireFreshDirectory() {
  if (existsSync(directory) && readdirSync(directory).length > 0) {
    throw new Error(`The server directory must be empty: ${directory}`)
  }
  mkdirSync(directory, { recursive: true })
}

function libraryJars(directoryPath) {
  return readdirSync(directoryPath, { withFileTypes: true }).flatMap(entry => {
    const path = join(directoryPath, entry.name)
    if (entry.isDirectory()) return libraryJars(path)
    return entry.isFile() && entry.name.endsWith('.jar') ? [path] : []
  })
}

function configureServer() {
  const plugins = join(directory, 'plugins')
  const config = join(directory, 'config')
  mkdirSync(plugins, { recursive: true })
  mkdirSync(config, { recursive: true })
  for (const name of ['cache', 'libraries', 'versions']) {
    cpSync(join(runtimeDirectory, name), join(directory, name), { recursive: true })
  }
  copyFileSync(pluginJar, join(plugins, 'StarGenesis.jar'))
  writeFileSync(join(directory, 'eula.txt'), 'eula=true\n')
  writeFileSync(join(directory, 'server.properties'), [
    'server-ip=127.0.0.1', `server-port=${port}`, 'online-mode=false', 'enable-rcon=false',
    'view-distance=2', 'simulation-distance=2', 'spawn-protection=0', 'max-players=4',
    'level-type=minecraft:flat', 'generate-structures=false', 'max-tick-time=60000',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}', ''
  ].join('\n'))
  if (kind === 'folia') {
    writeFileSync(join(config, 'folia-global.yml'), '_version: 1\nthreaded-regions:\n  threads: 2\n')
  }
}

async function startServer() {
  serverOutput = ''
  const classPath = [serverJar, ...libraryJars(join(directory, 'libraries'))].join(';')
  server = spawn(java, ['-Xms256M', '-Xmx512M', '-XX:ActiveProcessorCount=4', '-cp', classPath,
    'org.bukkit.craftbukkit.Main', '--nogui'], {
    cwd: directory,
    stdio: ['pipe', 'pipe', 'pipe']
  })
  server.stdout.setEncoding('utf8')
  server.stderr.setEncoding('utf8')
  const record = chunk => {
    serverOutput += chunk
    appendFileSync(serverLogPath, chunk)
  }
  server.stdout.on('data', record)
  server.stderr.on('data', record)
  await waitFor(() => serverOutput.includes('Done (') || server.exitCode !== null,
    180_000, `${kind} startup`)
  if (server.exitCode !== null) throw new Error(`Server exited during startup with ${server.exitCode}`)
  const identity = serverOutput.match(/This server is running (Paper|Folia) version ([^\s]+)/)
  const expectedKind = kind === 'folia' ? 'Folia' : 'Paper'
  if (!identity || identity[1] !== expectedKind) throw new Error(`Expected ${expectedKind} server identity`)
  if (!serverOutput.includes('StarGenesis ready | phases=P1-P10 development')) {
    throw new Error('StarGenesis did not enable')
  }
  versions.push(identity[2])
}

async function stopServer() {
  if (!server || server.exitCode !== null) return
  await new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => reject(new Error('Server did not stop cleanly')), 45_000)
    server.once('exit', code => {
      clearTimeout(timer)
      if (code !== 0) reject(new Error(`Server exited with ${code}`))
      else resolvePromise()
    })
    server.stdin.write('stop\n')
  })
}

async function crashServer() {
  if (!server || server.exitCode !== null) return
  const crashedServer = server
  const exited = new Promise(resolvePromise => crashedServer.once('exit', (code, signal) => {
    resolvePromise({ code, signal })
  }))
  if (!crashedServer.kill('SIGKILL')) throw new Error('Could not terminate the isolated test server process')
  const result = await exited
  server = null
  if (result.code === 0) throw new Error('The isolated server exited cleanly instead of being terminated')
}

async function connectAs(username) {
  const bot = createBot({ host: '127.0.0.1', port, username, auth: 'offline', version: '1.21.11' })
  await new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username} connection timed out`)), 45_000)
    bot.once('spawn', () => {
      clearTimeout(timer)
      resolvePromise()
    })
    bot.once('kicked', reason => {
      clearTimeout(timer)
      reject(new Error(`${username} was kicked: ${reason}`))
    })
    bot.once('error', error => {
      clearTimeout(timer)
      reject(error)
    })
  })
  return bot
}

async function connect() {
  owner = await connectAs(ownerName)
  return owner
}

function waitForMessage(bot, expected) {
  const responses = []
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for ${bot.username} message containing ${expected}; received=${JSON.stringify(responses)}`))
    }, 20_000)
    const listener = message => {
      responses.push(message)
      if (!message.includes(expected)) return
      clearTimeout(timer)
      bot.removeListener('messagestr', listener)
      resolvePromise({ message, responses })
    }
    bot.on('messagestr', listener)
  })
}

function waitForNextWindow(bot) {
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('windowOpen', listener)
      reject(new Error(`Timed out waiting for ${bot.username} inventory window`))
    }, 20_000)
    const listener = window => {
      clearTimeout(timer)
      resolvePromise(window)
    }
    bot.once('windowOpen', listener)
  })
}

function itemLabel(item) {
  const customName = item?.customName
  if (!customName) return ''
  if (typeof customName !== 'string') return componentText(customName) || JSON.stringify(customName)
  try {
    return componentText(JSON.parse(customName)) || customName
  } catch {
    return customName
  }
}

function componentText(component) {
  if (typeof component === 'string') return component
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (component && typeof component === 'object') {
    const ownText = typeof component.text === 'string' ? component.text : ''
    return ownText + componentText(component.extra ?? [])
  }
  return ''
}

async function findNamedSlot(bot, name, first, end) {
  let found = -1
  try {
    await waitFor(() => {
      const slots = bot.currentWindow?.slots ?? []
      found = slots.findIndex((item, slot) => slot >= first && slot < end && itemLabel(item).includes(name))
      return found >= 0
    }, 20_000, `${bot.username} inventory item ${name}`)
  } catch (error) {
    const visible = (bot.currentWindow?.slots ?? []).slice(first, end).map((item, index) => ({
      slot: first + index,
      label: itemLabel(item),
      customName: item?.customName ?? null,
      displayName: item?.displayName ?? null
    }))
    throw new Error(`${error.message}; visible=${JSON.stringify(visible)}`, { cause: error })
  }
  return found
}

async function openOrganizationMenu(bot) {
  const opened = waitForNextWindow(bot)
  await sleep(750)
  bot.chat('/genesis factory org menu')
  await opened
  if (bot.currentWindow?.slots.length < 54) throw new Error(`${bot.username} opened an incomplete organization menu`)
}

async function clickAndWait(bot, slot, expected) {
  const response = waitForMessage(bot, expected)
  const opened = waitForNextWindow(bot)
  await bot.clickWindow(slot, 0, 0)
  const [result] = await Promise.all([response, opened])
  appendFileSync(logPath, `window click ${slot}\n${result.responses.join('\n')}\n`)
  return result.message
}

async function clickForMessage(bot, slot, expected) {
  const response = waitForMessage(bot, expected)
  await bot.clickWindow(slot, 0, 0)
  const result = await response
  appendFileSync(logPath, `window click ${slot}\n${result.responses.join('\n')}\n`)
  return result.message
}

async function command(input, expected) {
  const responses = []
  const listener = message => {
    responses.push(message)
    if (message.includes(expected)) owner.removeListener('messagestr', listener)
  }
  owner.on('messagestr', listener)
  await sleep(750)
  owner.chat(input)
  try {
    await waitFor(() => responses.some(message => message.includes(expected)), 20_000,
      `${input} response containing ${expected}`)
  } catch (error) {
    throw new Error(`${error.message}; received=${JSON.stringify(responses)}`, { cause: error })
  } finally {
    owner.removeListener('messagestr', listener)
    appendFileSync(logPath, `${input}\n${responses.join('\n')}\n`)
  }
  return responses.find(message => message.includes(expected))
}

async function disconnect() {
  if (guest) {
    const ended = new Promise(resolvePromise => guest.once('end', resolvePromise))
    guest.quit('factory organization menu smoke')
    await Promise.race([ended, sleep(5_000)])
    guest = null
  }
  if (!owner) return
  const ended = new Promise(resolvePromise => owner.once('end', resolvePromise))
  owner.quit('factory production checkpoint smoke')
  await Promise.race([ended, sleep(5_000)])
  owner = null
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

try {
  requireFreshDirectory()
  writeFileSync(logPath, '')
  writeFileSync(serverLogPath, '')
  configureServer()
  await startServer()
  owner = await connect()
  const identity = await command('/genesis factory identity', '你的 UUID')
  const ownerId = identity.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0]
  if (!ownerId) throw new Error(`Cannot parse owner UUID from ${identity}`)
  await command(`/genesis factory org create ${organizationName}`, `组织 ${organizationName} 已创建`)
  guest = await connectAs(`SgInvite${kind === 'paper' ? 'Paper' : 'Folia'}`)
  await openOrganizationMenu(guest)
  const guestCandidateSlot = await findNamedSlot(guest, guest.username, 28, 46)
  await clickForMessage(guest, guestCandidateSlot, '只有组织所有者可以修改成员')
  checks.non_owner_cannot_edit_roster_from_menu = true

  await openOrganizationMenu(owner)
  checks.organization_menu_opened = owner.currentWindow.slots.length >= 54
  const ownerGuestCandidateSlot = await findNamedSlot(owner, guest.username, 28, 46)
  await clickAndWait(owner, ownerGuestCandidateSlot, '已将')
  const guestMemberSlot = await findNamedSlot(owner, guest.username, 10, 14)
  await clickAndWait(owner, guestMemberSlot, '已将')
  const ownerRosterSlot = await findNamedSlot(owner, ownerName, 10, 14)
  await clickForMessage(owner, ownerRosterSlot, '组织所有者不能从名册中移除')
  checks.owner_cannot_be_removed_from_menu = true

  for (const member of [
    '00000000-0000-0000-0000-000000000102',
    '00000000-0000-0000-0000-000000000103',
    '00000000-0000-0000-0000-000000000104'
  ]) await command(`/genesis factory org add ${member}`, `已将 ${member} 加入组织`)
  await command('/genesis factory create', '四人虚拟工厂已创建并保存')
  checks.factory_created_from_four_member_roster = true
  await openOrganizationMenu(owner)
  checks.menu_reports_frozen_roster = itemLabel(owner.currentWindow.slots[4]).includes('名册已冻结')
  const frozenMemberSlot = await findNamedSlot(owner, '00000000-0000-0000-0000-000000000102', 10, 14)
  await clickForMessage(owner, frozenMemberSlot, '工厂已创建，组织名册已冻结')
  checks.frozen_roster_rejects_menu_mutation = true
  owner.closeWindow(owner.currentWindow)

  const firstQueue = await command('/genesis factory station queue factory:frame 41 2', '预计 tick 4')
  checks.frame_recipe_queued = firstQueue.includes('生产任务已排入')
  const activeStatus = await command('/genesis factory station status', '运行中 factory:frame')
  checks.station_status_reports_active_job = activeStatus.includes('完成 tick 4')
  await command('/genesis factory advance 0 4', '当前 tick 4')
  const firstFinished = await command('/genesis factory station status', '工位矿石 0')
  checks.frame_recipe_completed = firstFinished.includes('组件 8')
  await command('/genesis factory station collect 42 8', '已领取 8 个组件')
  checks.completed_components_collected = true

  await command('/genesis factory station queue factory:plate 43 4', '预计 tick 8')
  checks.second_job_queued_for_restart = true
  await disconnect()
  await stopServer()
  await startServer()
  owner = await connect()
  const resumedStatus = await command('/genesis factory station status', '运行中 factory:plate')
  checks.pending_job_restored_after_restart = resumedStatus.includes('完成 tick 8')
  await command('/genesis factory advance 4 4', '当前 tick 8')
  const secondFinished = await command('/genesis factory station status', '工位矿石 0')
  checks.plate_recipe_completed = secondFinished.includes('组件 12')
  await command('/genesis factory station collect 44 12', '已领取 12 个组件')
  checks.resumed_job_output_collected = true

  await command('/genesis factory station queue factory:frame 45 1', '预计 tick 10')
  checks.job_saved_before_abrupt_termination = true
  await disconnect()
  await crashServer()
  await startServer()
  owner = await connect()
  const afterCrashStatus = await command('/genesis factory station status', '运行中 factory:frame')
  checks.pending_job_restored_after_abrupt_termination = afterCrashStatus.includes('完成 tick 10')
  await command('/genesis factory advance 8 2', '当前 tick 10')
  const crashRecoveredOutput = await command('/genesis factory station status', '工位矿石 0')
  checks.pending_job_completed_after_abrupt_termination = crashRecoveredOutput.includes('组件 4')
  await command('/genesis factory station collect 46 4', '已领取 4 个组件')
  checks.abrupt_restart_output_collected = true

  await disconnect()
  await stopServer()
  const result = {
    phase: 'P6 factory recipes and virtual workstations',
    kind,
    versions,
    owner: ownerName,
    owner_uuid: ownerId,
    checks,
    passed: Object.values(checks).every(Boolean),
    plugin_sha256: sha256(pluginJar),
    server_sha256: sha256(serverJar),
    interaction_log: logPath,
    server_log: serverLogPath,
    scope: 'Offline Mineflayer verification of organization Inventory interactions and factory recipes on Paper/Folia. Covers normal restart and forced server-process termination after an acknowledged checkpoint. This is not human usability or machine power-loss durability evidence.'
  }
  mkdirSync(join(resolve(resultPath, '..')), { recursive: true })
  writeFileSync(resultPath, `${JSON.stringify(result, null, 2)}\n`)
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.passed) process.exitCode = 1
} catch (error) {
  process.stderr.write(`${error.stack ?? error}\n`)
  process.exitCode = 1
} finally {
  await disconnect().catch(error => process.stderr.write(`${error.message}\n`))
  await stopServer().catch(error => process.stderr.write(`${error.message}\n`))
}
