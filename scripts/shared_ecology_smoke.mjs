import { spawn } from 'node:child_process'
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'

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
const java = resolve(options.java)
const cachedMojangServerJar = options['mojang-server-jar'] ? resolve(options['mojang-server-jar']) : null
const port = Number(options.port)
const resultPath = resolve(options.result ?? join(directory, 'shared-ecology-result.json'))
const suffix = kind === 'paper' ? 'Paper' : 'Folia'
const ownerName = `SgOwn${suffix}`
const memberName = `SgMem${suffix}`
const guestName = `SgGst${suffix}`
const serverLog = join(directory, 'server.log')
const commandLog = join(directory, 'commands.log')
const checks = {}
const serverVersions = []
let server
let serverOutput = ''
let owner
let member
let guest
let failure
let groupId

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex')
}

function offlinePlayerUuid(username) {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`).digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

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

function configureServer() {
  const plugins = join(directory, 'plugins')
  const config = join(directory, 'config')
  mkdirSync(plugins, { recursive: true })
  mkdirSync(config, { recursive: true })
  copyFileSync(pluginJar, join(plugins, 'StarGenesis.jar'))
  if (cachedMojangServerJar) {
    const cache = join(directory, 'cache')
    mkdirSync(cache, { recursive: true })
    copyFileSync(cachedMojangServerJar, join(cache, 'mojang_1.21.11.jar'))
  }
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
  server = spawn(java, ['-Xms128M', '-Xmx512M', '-XX:ActiveProcessorCount=4', '-jar', serverJar, '--nogui'], {
    cwd: directory,
    stdio: ['pipe', 'pipe', 'pipe']
  })
  server.stdout.setEncoding('utf8')
  server.stderr.setEncoding('utf8')
  const record = chunk => {
    serverOutput += chunk
    appendFileSync(serverLog, chunk)
  }
  server.stdout.on('data', record)
  server.stderr.on('data', record)
  await waitFor(() => serverOutput.includes('Done (') || server.exitCode !== null,
    180_000, `${kind} startup`)
  if (server.exitCode !== null) throw new Error(`Server exited during startup with ${server.exitCode}`)
  const identity = serverOutput.match(/This server is running (Paper|Folia) version ([^\s]+)/)
  const expectedKind = kind === 'folia' ? 'Folia' : 'Paper'
  if (!identity || identity[1] !== expectedKind) throw new Error(`Expected ${expectedKind} server identity`)
  if (!serverOutput.includes('StarGenesis ready | phases=')) throw new Error('StarGenesis did not enable')
  serverVersions.push(identity[2])
}

async function stopServer() {
  if (!server || server.exitCode !== null) return
  const current = server
  try {
    await new Promise((resolvePromise, reject) => {
      const timer = setTimeout(() => reject(new Error('Server did not stop cleanly')), 45_000)
      current.once('exit', code => {
        clearTimeout(timer)
        if (code !== 0) reject(new Error(`Server exited with ${code}`))
        else resolvePromise()
      })
      current.stdin.write('stop\n')
    })
  } catch (error) {
    current.kill()
    await Promise.race([new Promise(resolvePromise => current.once('exit', resolvePromise)), sleep(10_000)])
    throw error
  } finally {
    if (current.exitCode !== null) server = null
  }
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

function waitForMessage(bot, expected) {
  const received = []
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      bot.removeListener('messagestr', listener)
      reject(new Error(`Timed out waiting for ${bot.username} response containing ${expected}; received=${JSON.stringify(received)}`))
    }, 20_000)
    const listener = message => {
      received.push(message)
      if (!message.includes(expected)) return
      clearTimeout(timer)
      bot.removeListener('messagestr', listener)
      resolvePromise({ message, received })
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

function componentText(component) {
  if (typeof component === 'string') return component
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (component && typeof component === 'object') {
    const ownText = typeof component.text === 'string' ? component.text : ''
    return ownText + componentText(component.extra ?? [])
  }
  return ''
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

async function openSharedMenu(bot) {
  const opened = waitForNextWindow(bot)
  bot.chat('/genesis ecosystem share menu')
  await opened
  if (bot.currentWindow?.slots.length < 54)
    throw new Error(`${bot.username} opened an incomplete shared ecology menu`)
}

async function clickMenu(bot, slot) {
  const opened = waitForNextWindow(bot)
  await bot.clickWindow(slot, 0, 0)
  await opened
}

async function clickMenuForMessage(bot, slot, expected) {
  const response = waitForMessage(bot, expected)
  const opened = waitForNextWindow(bot)
  await bot.clickWindow(slot, 0, 0)
  const [result] = await Promise.all([response, opened])
  appendFileSync(commandLog, `${bot.username} clicked inventory slot ${slot}\n${result.received.join('\n')}\n`)
  return result.message
}

async function clickInventoryUntilItem(bot, slot, expected) {
  await bot.clickWindow(slot, 0, 0)
  await waitFor(() => itemLabel(bot.currentWindow?.slots?.[0]).includes(expected),
    20_000, `${bot.username} ecology inventory item ${expected}`)
}

async function findMenuSlot(bot, expected, first, end) {
  let found = -1
  try {
    await waitFor(() => {
      found = (bot.currentWindow?.slots ?? []).findIndex((item, slot) =>
        slot >= first && slot < end && itemLabel(item).includes(expected))
      return found >= 0
    }, 20_000, `${bot.username} shared ecology menu item ${expected}`)
  } catch (error) {
    const visible = (bot.currentWindow?.slots ?? []).slice(first, end).map((item, index) => ({
      slot: first + index,
      label: itemLabel(item)
    }))
    throw new Error(`${error.message}; visible=${JSON.stringify(visible)}`, { cause: error })
  }
  return found
}

async function command(bot, commandText, expected) {
  const response = waitForMessage(bot, expected)
  bot.chat(`/genesis ecosystem share ${commandText}`)
  const result = await response
  appendFileSync(commandLog, `${bot.username} > ${commandText}\n${result.received.join('\n')}\n`)
  return result.message
}

function check(name, passed) {
  checks[name] = Boolean(passed)
  if (!passed) throw new Error(`Shared ecology check failed: ${name}`)
}

async function disconnect(bot) {
  if (!bot) return
  const ended = new Promise(resolvePromise => bot.once('end', resolvePromise))
  bot.quit('shared ecology smoke test')
  await Promise.race([ended, sleep(5_000)])
}

async function run() {
  if (!['paper', 'folia'].includes(kind)) throw new Error('Supply --kind paper or folia')
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Supply a valid --port')
  if (cachedMojangServerJar && !existsSync(cachedMojangServerJar))
    throw new Error(`Cached Mojang server jar does not exist: ${cachedMojangServerJar}`)
  if ([ownerName, memberName, guestName].some(username => username.length > 16))
    throw new Error('Shared ecology test usernames must fit the Minecraft 16-character limit')
  if (existsSync(directory) && readdirSync(directory).length > 0)
    throw new Error(`The isolated server directory must be empty: ${directory}`)
  mkdirSync(directory, { recursive: true })
  writeFileSync(serverLog, '')
  writeFileSync(commandLog, '')
  configureServer()
  await startServer()

  owner = await connectAs(ownerName)
  member = await connectAs(memberName)
  guest = await connectAs(guestName)
  const memberId = offlinePlayerUuid(memberName)
  const privateOpened = waitForNextWindow(owner)
  owner.chat('/genesis ecosystem')
  await privateOpened
  check('owner_private_ecology_starts_at_tick_zero', itemLabel(owner.currentWindow.slots[0]).includes('生态步 0'))
  await clickInventoryUntilItem(owner, 49, '生态步 1')
  check('owner_private_ecology_advances_to_tick_one', itemLabel(owner.currentWindow.slots[0]).includes('生态步 1'))
  owner.closeWindow(owner.currentWindow)

  const created = await command(owner, 'create', '共享生态组已创建，组 ID ')
  groupId = created.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)?.[0]
  check('owner_created_group', Boolean(groupId))
  check('group_listing_shows_owner', (await command(owner, 'list', groupId)).includes('所有者'))

  const denied = await command(guest, `status ${groupId}`, '你不是该共享生态组成员。')
  check('nonmember_cannot_read_group', denied.includes('共享生态操作未完成'))
  await command(owner, `add ${groupId} ${memberId}`, '共享生态成员已添加。')
  check('member_listing_shows_joined_group', (await command(member, 'list', groupId)).includes('成员'))
  check('new_member_reads_tick_zero', (await command(member, `status ${groupId}`, '步数=0')).includes('步数=0'))
  check('member_advances_shared_tick', (await command(member, `step ${groupId}`, '第 1 步')).includes('第 1 步'))
  check('owner_observes_member_step', (await command(owner, `status ${groupId}`, '步数=1')).includes('步数=1'))

  const deniedDisband = await command(member, `disband ${groupId}`, 'Only the ecology group owner')
  check('member_cannot_disband_group', deniedDisband.includes('共享生态操作未完成'))
  await command(member, `leave ${groupId}`, '你已离开共享生态组。')
  check('left_member_cannot_find_group', (await command(member, 'list', '你还没有加入')).includes('你还没有加入'))
  await command(owner, `add ${groupId} ${memberId}`, '共享生态成员已添加。')
  await command(member, `step ${groupId}`, '第 2 步')

  await Promise.all([disconnect(owner), disconnect(member), disconnect(guest)])
  owner = null
  member = null
  guest = null
  await stopServer()
  check('group_checkpoint_exists_before_restart', existsSync(join(directory, 'plugins', 'StarGenesis', 'ecology-groups', `${groupId}.eco`)))

  await startServer()
  owner = await connectAs(ownerName)
  member = await connectAs(memberName)
  const restoredPrivate = waitForNextWindow(owner)
  owner.chat('/genesis ecosystem')
  await restoredPrivate
  check('owner_private_v4_checkpoint_restores_tick_one',
    itemLabel(owner.currentWindow.slots[0]).includes('生态步 1'))
  await clickInventoryUntilItem(owner, 49, '生态步 2')
  check('owner_private_v4_checkpoint_advances_after_restore',
    itemLabel(owner.currentWindow.slots[0]).includes('生态步 2'))
  owner.closeWindow(owner.currentWindow)
  check('owner_restores_shared_tick', (await command(owner, `status ${groupId}`, '步数=2')).includes('步数=2'))
  check('member_restores_shared_tick', (await command(member, `status ${groupId}`, '步数=2')).includes('步数=2'))

  await openSharedMenu(owner)
  const ownerGroupSlot = await findMenuSlot(owner, `共享生态 ${groupId.slice(0, 8)}`, 10, 44)
  check('owner_opens_shared_group_inventory', owner.currentWindow.slots.length >= 54)
  await clickMenu(owner, ownerGroupSlot)
  check('owner_opens_shared_group_details', itemLabel(owner.currentWindow.slots[4]).includes(groupId.slice(0, 8)))
  const ownerMemberSlot = await findMenuSlot(owner, memberName, 10, 18)
  await clickMenuForMessage(owner, 24, '第 3 步')
  check('owner_advances_group_from_inventory',
    (await command(member, `status ${groupId}`, '步数=3')).includes('步数=3'))
  await clickMenuForMessage(owner, ownerMemberSlot, '共享生态成员已移除。')
  check('owner_removes_member_from_inventory',
    (await command(member, `status ${groupId}`, '你不是该共享生态组成员。')).includes('共享生态操作未完成'))
  const inviteSlot = await findMenuSlot(owner, memberName, 28, 44)
  await clickMenuForMessage(owner, inviteSlot, '共享生态成员已添加。')
  check('owner_invites_member_from_inventory',
    (await command(member, `status ${groupId}`, '步数=3')).includes('步数=3'))
  await openSharedMenu(member)
  const memberGroupSlot = await findMenuSlot(member, `共享生态 ${groupId.slice(0, 8)}`, 10, 44)
  await clickMenu(member, memberGroupSlot)
  check('member_opens_shared_group_details', itemLabel(member.currentWindow.slots[4]).includes(groupId.slice(0, 8)))
  await clickMenuForMessage(member, 24, '第 4 步')
  check('member_advances_group_from_inventory',
    (await command(owner, `status ${groupId}`, '步数=4')).includes('步数=4'))
  await clickMenu(owner, 47)
  check('owner_must_confirm_inventory_disband', itemLabel(owner.currentWindow.slots[22]).includes('确认解散'))
  await clickMenuForMessage(owner, 22, '共享生态组及其检查点已删除。')
  check('owner_listing_empty_after_disband', (await command(owner, 'list', '你还没有加入')).includes('你还没有加入'))
  check('disband_removes_checkpoint', !existsSync(join(directory, 'plugins', 'StarGenesis', 'ecology-groups', `${groupId}.eco`)))

  await Promise.all([disconnect(owner), disconnect(member)])
  owner = null
  member = null
  await stopServer()
  await startServer()
  owner = await connectAs(ownerName)
  const restoredSecondCycle = waitForNextWindow(owner)
  owner.chat('/genesis ecosystem')
  await restoredSecondCycle
  check('owner_private_v4_checkpoint_survives_second_restart',
    itemLabel(owner.currentWindow.slots[0]).includes('生态步 2'))
  await clickInventoryUntilItem(owner, 49, '生态步 3')
  check('owner_private_v4_checkpoint_advances_after_second_restart',
    itemLabel(owner.currentWindow.slots[0]).includes('生态步 3'))
  owner.closeWindow(owner.currentWindow)
}

try {
  await run()
} catch (error) {
  failure = error
} finally {
  await Promise.all([disconnect(owner), disconnect(member), disconnect(guest)])
  try {
    await stopServer()
  } catch (error) {
    failure ??= error
  }
  const result = {
    phase: 'P5 shared ecology multiplayer flow',
    kind,
    server_versions: serverVersions,
    group_id: groupId ?? null,
    players: {
      owner: ownerName,
      member: memberName,
      member_uuid: offlinePlayerUuid(memberName),
      guest: guestName
    },
    checks,
    passed: !failure && Object.values(checks).length > 0 && Object.values(checks).every(Boolean),
    error: failure?.stack ?? null,
    plugin_sha256: existsSync(pluginJar) ? sha256(pluginJar) : null,
    server_sha256: existsSync(serverJar) ? sha256(serverJar) : null,
    server_log_sha256: existsSync(serverLog) ? sha256(serverLog) : null,
    scope: 'Three offline Mineflayer players exercise shared ecology membership, authorization, Inventory management, stepping, and normal server restart recovery on an isolated server. The owner also verifies a personal ecology v4 PDC checkpoint across two normal server restarts.'
  }
  mkdirSync(dirname(resultPath), { recursive: true })
  writeFileSync(resultPath, `${JSON.stringify(result, null, 2)}\n`)
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
}

if (failure) {
  process.stderr.write(`${failure.stack ?? failure}\n`)
  process.exitCode = 1
}
