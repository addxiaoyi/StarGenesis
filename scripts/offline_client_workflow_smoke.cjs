"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

function argumentsFrom(argv) {
  const options = {};
  for (let index = 2; index < argv.length; index += 1) {
    const key = argv[index].replace(/^--/, "");
    const value = argv[index + 1];
    if (value && !value.startsWith("--")) {
      options[key] = value;
      index += 1;
    } else options[key] = "true";
  }
  if (!options.host || !options.port || !options["module-root"] || !options["data-directory"]
      || !options["blueprint-source"] || !options.output || !options.kind) {
    throw new Error("Usage: offline_client_workflow_smoke.cjs --host HOST --port PORT --kind KIND --module-root PATH "
      + "--data-directory PATH --blueprint-source PATH --output PATH");
  }
  const sustainedMinutes = Number(options["sustained-lab-minutes"] || 0);
  if (!Number.isInteger(sustainedMinutes) || sustainedMinutes < 0 || sustainedMinutes > 120)
    throw new Error("--sustained-lab-minutes must be between 0 and 120");
  if (sustainedMinutes > 0 && (options.kind !== "folia" || options["region-travel"] !== "true"))
    throw new Error("A sustained lab profile requires Folia clients across separate regions");
  return options;
}

function pause(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function waitForMessage(bot, fromIndex, expected, timeout = 10000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const found = bot.messages.slice(fromIndex).find((message) => message.includes(expected));
    if (found) return found;
    await pause(100);
  }
  const recent = bot.messages.slice(fromIndex).slice(-12).join("\n");
  throw new Error(`${bot.username} timed out waiting for ${JSON.stringify(expected)}. `
    + `Connection error=${bot.connectionError || "none"}. Recent messages:\n${recent}`);
}

function connect(mineflayer, host, port, username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host, port, username, auth: "offline", version: "1.21.11" });
    const timer = setTimeout(() => reject(new Error(`${username} did not spawn within 30 seconds`)), 30000);
    bot.messages = [];
    bot.on("messagestr", (message) => bot.messages.push(message));
    bot.on("error", (error) => {
      bot.connectionError = error;
      if (!bot.spawned) {
        clearTimeout(timer);
        reject(error);
      }
    });
    bot.once("spawn", () => {
      clearTimeout(timer);
      bot.spawned = true;
      resolve(bot);
    });
    bot.once("end", (reason) => {
      if (!bot.spawned) {
        clearTimeout(timer);
        reject(new Error(`${username} disconnected before spawn: ${reason}`));
      } else bot.connectionError = reason;
    });
  });
}

async function command(bot, value, expected, timeout) {
  const fromIndex = bot.messages.length;
  const nextAllowed = (bot.lastCommandAt || 0) + 1200;
  await pause(Math.max(0, nextAllowed - Date.now()));
  bot.lastCommandAt = Date.now();
  bot.chat(`/genesis ${value}`);
  return waitForMessage(bot, fromIndex, expected, timeout);
}

async function commandUntil(bot, value, expected, attempts = 20) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const fromIndex = bot.messages.length;
    const nextAllowed = (bot.lastCommandAt || 0) + 1200;
    await pause(Math.max(0, nextAllowed - Date.now()));
    bot.lastCommandAt = Date.now();
    bot.chat(`/genesis ${value}`);
    try {
      return await waitForMessage(bot, fromIndex, expected, 500);
    } catch (error) {
      if (attempt + 1 === attempts) throw error;
    }
  }
  throw new Error(`No response to ${value}`);
}

function disconnect(bot) {
  const socket = bot._client?.socket;
  if (!socket || socket.destroyed) {
    try { bot.quit(); } catch { }
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    let timer;
    const finish = () => {
      clearTimeout(timer);
      bot.removeListener("end", finish);
      resolve();
    };
    bot.once("end", finish);
    timer = setTimeout(() => {
      socket.destroy();
      finish();
    }, 3000);
    try { bot.quit(); }
    catch {
      socket.destroy();
      finish();
    }
  });
}

function dropConnection(bot) {
  const socket = bot._client?.socket;
  if (!socket || socket.destroyed) throw new Error(`${bot.username} has no active client socket`);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`${bot.username} did not observe the dropped connection`));
    }, 5000);
    bot.once("end", (reason) => {
      clearTimeout(timer);
      resolve(reason);
    });
    socket.destroy();
  });
}

async function runSustainedLabProfile(bots, durationMinutes) {
  const roles = ["alice", "bob", "carol", "dave"];
  let launches = 0;
  let restarts = 0;
  let statusPolls = 0;
  const launch = async (role) => {
    const seed = 910000 + launches;
    launches += 1;
    await command(bots[role], `lab start ${seed} 4`, "Folia 实验已启动");
  };

  await Promise.all(roles.map(launch));
  const startedAt = Date.now();
  const deadline = startedAt + durationMinutes * 60000;
  process.stdout.write(`SUSTAINED_LAB_STARTED minutes=${durationMinutes} concurrentWorlds=${roles.length} agentsPerWorld=4\n`);

  while (Date.now() < deadline) {
    await pause(Math.min(15000, deadline - Date.now()));
    if (Date.now() >= deadline) break;
    const statuses = await Promise.all(roles.map((role) => command(bots[role], "lab status", "试验箱")));
    statusPolls += 1;
    for (let index = 0; index < roles.length; index += 1) {
      if (statuses[index].includes("ERROR")) throw new Error(`${roles[index]} lab entered ERROR during the sustained profile`);
      if (!statuses[index].includes("RUNNING")) {
        await launch(roles[index]);
        restarts += 1;
      }
    }
    if (statusPolls % 20 === 0)
      process.stdout.write(`SUSTAINED_LAB_PROGRESS elapsedSeconds=${Math.round((Date.now() - startedAt) / 1000)} launches=${launches}\n`);
  }

  const finalStatuses = await Promise.all(roles.map((role) => command(bots[role], "lab status", "试验箱")));
  const runningRoles = [];
  for (let index = 0; index < roles.length; index += 1) {
    if (finalStatuses[index].includes("ERROR")) throw new Error(`${roles[index]} lab ended in ERROR`);
    if (finalStatuses[index].includes("RUNNING")) runningRoles.push(roles[index]);
  }
  await Promise.all(runningRoles.map(async (role) => {
    const response = await command(bots[role], "lab stop", "实验");
    if (response.includes("将在下一个逻辑步"))
      await commandUntil(bots[role], "lab status", "试验箱 STOPPED");
    else if (!response.includes("当前没有正在运行的实验"))
      throw new Error(`${role} lab did not accept shutdown: ${response}`);
  }));

  const durationSeconds = Math.round((Date.now() - startedAt) / 1000);
  if (durationSeconds < durationMinutes * 60 || launches < roles.length)
    throw new Error("Sustained lab profile ended before its requested duration");
  const summary = {
    passed: true,
    profile: "four-concurrent-p1-labs",
    requestedMinutes: durationMinutes,
    durationSeconds,
    participants: roles.length,
    concurrentWorlds: roles.length,
    agentsPerWorld: 4,
    totalLabStarts: launches,
    labRestarts: restarts,
    statusPolls,
    separatedRegions: true,
  };
  process.stdout.write(`SUSTAINED_LAB_COMPLETE launches=${launches} restarts=${restarts} durationSeconds=${durationSeconds}\n`);
  return summary;
}

async function runEcologyStudy(bots) {
  const profiles = {
    alice: {
      seed: 52001,
      programs: [["producer", 3, 5, 40, 150], ["grazer", 2, 6, 24, 100], ["decomposer", 2, 5, 32, 80]],
    },
    bob: {
      seed: 52002,
      programs: [["producer", 2, 6, 64, 100], ["grazer", 1, 8, 48, 60], ["decomposer", 1, 7, 64, 80]],
    },
    carol: {
      seed: 52003,
      programs: [["producer", 5, 5, 40, 500], ["grazer", 3, 8, 28, 400], ["decomposer", 3, 5, 36, 350]],
    },
    dave: {
      seed: 52004,
      programs: [["producer", 4, 2, 40, 100], ["grazer", 3, 2, 24, 100], ["decomposer", 3, 2, 32, 100]],
    },
  };
  const roles = Object.keys(profiles);

  await command(bots.alice, "ecology reset", "已删除个人生态舱与回放");
  await Promise.all(roles.map(async (role) => {
    const profile = profiles[role];
    await command(bots[role], `ecology start ${profile.seed}`, "生态舱");
    for (const [species, intake, reproductionMass, lifespan, mutation] of profile.programs) {
      await command(bots[role],
        `ecology program ${species} ${intake} ${reproductionMass} ${lifespan} ${mutation}`,
        "Matter program applied");
    }
  }));

  for (let batch = 0; batch < 25; batch += 1) {
    await Promise.all(roles.map((role) => command(bots[role], "ecology step 20", "生态舱")));
  }

  const results = await Promise.all(roles.map(async (role) => {
    const fromIndex = bots[role].messages.length;
    const dashboard = await command(bots[role], "ecology dashboard", "生态舱");
    const matterLine = await waitForMessage(bots[role], fromIndex, "Matter=");
    const stateLine = await waitForMessage(bots[role], fromIndex, "state=");
    const replay = await command(bots[role], "ecology replay", "生态回放通过");
    const tick = Number(dashboard.match(/生态舱 (\d+)\//)?.[1]);
    const totalMatter = Number(matterLine.match(/Matter=(\d+)/)?.[1]);
    const frameCount = Number(replay.match(/通过 (\d+) ticks/)?.[1]);
    const terminalReason = stateLine.split("state=")[1]?.split(" · ").at(-1);
    if (!Number.isInteger(tick) || !Number.isInteger(totalMatter) || !Number.isInteger(frameCount)
        || tick !== frameCount || frameCount < 20 || frameCount > 500 || totalMatter !== 1000
        || !terminalReason) {
      throw new Error(`P5 ecology study produced invalid bounded evidence for ${role}`);
    }
    return {
      role,
      seed: profiles[role].seed,
      ticks: frameCount,
      totalMatter,
      terminalReason,
      replayVerified: true,
    };
  }));

  const survivalPrograms = [
    ["producer", 6, 2, 512, 0],
    ["grazer", 1, 12, 512, 0],
    ["decomposer", 1, 12, 512, 0],
  ];
  const threeSpeciesCandidateSeeds = [];
  for (let seed = 52008; seed < 52016; seed += 1) {
    await command(bots.alice, "ecology reset", "已删除个人生态舱与回放");
    await command(bots.alice, `ecology start ${seed}`, "生态舱");
    for (const [species, intake, reproductionMass, lifespan, mutation] of survivalPrograms) {
      await command(bots.alice,
        `ecology program ${species} ${intake} ${reproductionMass} ${lifespan} ${mutation}`,
        "Matter program applied");
    }
    for (let batch = 0; batch < 25; batch += 1)
      await command(bots.alice, "ecology step 20", "生态舱");

    const candidateStart = bots.alice.messages.length;
    const candidateDashboard = await command(bots.alice, "ecology dashboard", "生态舱");
    const candidateMatter = await waitForMessage(bots.alice, candidateStart, "Matter=");
    const candidateVitals = await waitForMessage(bots.alice, candidateStart, "Births=");
    const candidateState = await waitForMessage(bots.alice, candidateStart, "state=");
    const populations = {};
    for (const species of ["producer", "grazer", "decomposer"]) {
      const line = await waitForMessage(bots.alice, candidateStart, `${species} `);
      populations[species] = Number(line.match(new RegExp(`${species}\\s+(\\d+)\\s+·\\s+matter=`))?.[1]);
    }
    const candidateReplay = await command(bots.alice, "ecology replay", "生态回放通过");
    const ticks = Number(candidateDashboard.match(/生态舱 (\d+)\//)?.[1]);
    const population = Number(candidateDashboard.match(/population=(\d+)\//)?.[1]);
    const totalMatter = Number(candidateMatter.match(/Matter=(\d+)/)?.[1]);
    const replayFrames = Number(candidateReplay.match(/通过 (\d+) ticks/)?.[1]);
    const births = Number(candidateVitals.match(/Births=(\d+)/)?.[1]);
    const deaths = Number(candidateVitals.match(/deaths=(\d+)/)?.[1]);
    const terminalReason = candidateState.split("state=")[1]?.split(" · ").at(-1);
    if (!Number.isInteger(ticks) || ticks < 1 || ticks > 500 || replayFrames !== ticks || totalMatter !== 1000
        || !Number.isInteger(population) || population < 0
        || Object.values(populations).some((value) => !Number.isInteger(value) || value < 0)
        || Object.values(populations).reduce((total, value) => total + value, 0) !== population
        || !Number.isInteger(births) || !Number.isInteger(deaths)
        || !["EXTINCTION", "DURATION_BUDGET"].includes(terminalReason)) {
      throw new Error(`P5 candidate seed ${seed} violated bounded tick, population, matter, or replay invariants`);
    }
    const extinctSpecies = Object.entries(populations).filter(([, value]) => value === 0).map(([species]) => species);
    threeSpeciesCandidateSeeds.push({
      seed,
      ticks,
      population,
      totalMatter,
      populationBySpecies: populations,
      extinctSpecies,
      births,
      deaths,
      terminalReason,
      replayVerified: true,
    });
    process.stdout.write(`P5_CANDIDATE seed=${seed} ticks=${ticks} population=${population} species=${populations.producer}/${populations.grazer}/${populations.decomposer}\n`);
  }
  return {
    passed: true,
    requestedTicksPerTank: 500,
    maximumWorlds: roles.length,
    profiles: Object.fromEntries(roles.map((role) => [role, profiles[role].programs])),
    results,
    threeSpeciesCandidate: {
      seedRange: [52008, 52015],
      worldOwnerRole: "alice",
      programs: survivalPrograms,
      runs: threeSpeciesCandidateSeeds,
      fullSpeciesSurvivalRuns: threeSpeciesCandidateSeeds.filter((run) => run.extinctSpecies.length === 0).length,
      survivalFraction: threeSpeciesCandidateSeeds.filter((run) => run.extinctSpecies.length === 0).length
        / threeSpeciesCandidateSeeds.length,
    },
    scope: "Four isolated parameter profiles were stepped to extinction or the 500-tick budget. An additional eight-seed candidate sweep used Alice's same private world owner and repeated deterministic parameters. Matter conservation and replay were checked. This remains a bounded automated study, not human ecological research or ecological balance acceptance.",
  };
}

async function walkTo(bot, x, z) {
  const initial = bot.entity.position.clone();
  const target = new initial.constructor(x, initial.y, z);
  await bot.lookAt(target);
  bot.setControlState("forward", true);
  const deadline = Date.now() + 210000;
  try {
    while (Date.now() < deadline) {
      await pause(250);
      if (!bot.entity) throw new Error(`${bot.username} left during region travel`);
      if (bot.entity.position.distanceTo(target) <= 8) return bot.entity.position.clone();
    }
    throw new Error(`${bot.username} could not reach Folia test region ${x},${z}`);
  } finally {
    bot.setControlState("forward", false);
  }
}

async function movePlayersAcrossRegions(bots) {
  const destinations = {
    alice: [0, 0],
    bob: [192, 0],
    carol: [0, 192],
    dave: [192, 192],
    observer: [384, 384],
  };
  const results = await Promise.all(Object.entries(destinations).map(async ([role, [x, z]]) => {
    const position = await walkTo(bots[role], x, z);
    return {
      role,
      x: Math.round(position.x),
      z: Math.round(position.z),
      distanceFromTarget: Math.round(position.distanceTo(new position.constructor(x, position.y, z))),
    };
  }));
  return results;
}

function javaStringHash(value) {
  let hash = 0;
  for (const character of value) hash = (Math.imul(hash, 31) + character.charCodeAt(0)) | 0;
  return BigInt(hash);
}

function mix64(value) {
  const mask = (1n << 64n) - 1n;
  let mixed = BigInt.asUintN(64, value);
  mixed = BigInt.asUintN(64, (mixed ^ (mixed >> 30n)) * 0xbf58476d1ce4e5b9n);
  mixed = BigInt.asUintN(64, (mixed ^ (mixed >> 27n)) * 0x94d049bb133111ebn);
  return mixed ^ (mixed >> 31n);
}

function faultSeedForFactoryOrder(organization, orderId, createdAt, completesAt) {
  const orderHash = javaStringHash(`${organization}:${orderId}`);
  for (let seed = 0; seed < 10000; seed += 1) {
    const value = mix64(BigInt(seed) ^ BigInt(createdAt) ^ BigInt(completesAt) ^ orderHash);
    if (Number(BigInt.asUintN(64, value) % 1000n) < 200) return seed;
  }
  throw new Error(`Could not derive a bounded factory fault seed for ${organization}:${orderId}`);
}

function organizationForPair(bots, firstRole, secondRole) {
  return [bots[firstRole].player.uuid, bots[secondRole].player.uuid].sort()[0];
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
  }
  return value;
}

function stageSignedDesign(dataDirectory, blueprintSource, suffix) {
  const keyId = `smoke.publisher-${suffix}`;
  const fileAlias = `publisher-${suffix.replace(/[^a-z0-9_.-]/g, "-")}`;
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ed25519");
  const blueprint = JSON.parse(fs.readFileSync(blueprintSource, "utf8"));
  const designId = `smoke:signed${suffix}`;
  blueprint.metadata.id.value = designId;
  delete blueprint.metadata.hash;
  const unsignedDocument = JSON.stringify(canonicalize(blueprint));
  blueprint.metadata.hash = crypto.createHash("sha256").update(unsignedDocument, "utf8").digest("hex");
  const document = JSON.stringify(canonicalize(blueprint));
  const payload = Buffer.from(`StarGenesis-SignedDesign/v1\n${keyId}\n${document}`, "utf8");
  const signature = crypto.sign(null, payload, privateKey).toString("base64").replace(/=+$/, "");
  const envelope = JSON.stringify({ envelopeVersion: 1, algorithm: "Ed25519", keyId, document, signature });
  const publicBody = publicKey.export({ type: "spki", format: "der" }).toString("base64").match(/.{1,64}/g).join("\n");
  const publisherInbox = path.join(dataDirectory, "publisher-inbox");
  const inboxDirectory = path.join(dataDirectory, "design-inbox");
  fs.mkdirSync(publisherInbox, { recursive: true });
  fs.mkdirSync(inboxDirectory, { recursive: true });
  fs.writeFileSync(path.join(publisherInbox, `${fileAlias}.pub`),
    `-----BEGIN PUBLIC KEY-----\n${publicBody}\n-----END PUBLIC KEY-----\n`, "ascii");
  fs.writeFileSync(path.join(inboxDirectory, "offline_fixture.json"), `${envelope}\n`, "utf8");
  return { keyId, designId, fileAlias };
}

async function main() {
  const options = argumentsFrom(process.argv);
  const moduleRoot = path.resolve(options["module-root"]);
  const mineflayer = require(path.join(moduleRoot, "mineflayer"));
  const outputPath = path.resolve(options.output);
  const suffix = crypto.randomBytes(3).toString("hex");
  const names = {
    alice: `SGA${suffix}`,
    bob: `SGB${suffix}`,
    carol: `SGC${suffix}`,
    dave: `SGD${suffix}`,
    observer: "SGReview",
  };
  const bots = {};
  const checks = [];
  const result = {
    kind: "automated-offline-client-workflows",
    host: options.host,
    port: Number(options.port),
    minecraftVersion: "1.21.11",
    players: Object.keys(names).length,
    humanAcceptance: false,
    checks,
    passed: false,
  };

  try {
    for (const [role, username] of Object.entries(names)) {
      bots[role] = await connect(mineflayer, options.host, Number(options.port), username);
    }
    checks.push({ stage: "client-login", passed: true, players: Object.values(bots).length });
    if (options["region-travel"] === "true") {
      result.regionTravel = await movePlayersAcrossRegions(bots);
      checks.push({ stage: "region-travel", passed: true, players: result.regionTravel.length });
    }

    if (options.kind === "folia") {
      await command(bots.alice, "lab start 42 1", "Folia 实验已启动");
      await command(bots.alice, "lab status", "试验箱 RUNNING");
      await command(bots.alice, "lab stop", "实验将在下一个逻辑步安全停止");
      await commandUntil(bots.alice, "lab status", "试验箱 STOPPED");
      await command(bots.alice, "lab replay", "回放开始");
      await commandUntil(bots.alice, "lab status", "REPLAY_HASH_AND_EVENTS_VERIFIED");
      await command(bots.alice, "lab resume", "正在受控线程读取并验证最近检查点");
      await command(bots.alice, "lab status", "试验箱 RUNNING");
      await command(bots.alice, "lab stop", "实验将在下一个逻辑步安全停止");
      await commandUntil(bots.alice, "lab status", "试验箱 STOPPED");
      checks.push({ stage: "P1", passed: true, interface: "Folia command workflow", replayVerified: true, checkpointResumed: true });
    } else {
      const windowOpened = new Promise((resolve) => bots.alice.once("windowOpen", resolve));
      await command(bots.alice, "lab start 42 1", "实验开始");
      const labWindow = await Promise.race([
        windowOpened,
        pause(10000).then(() => { throw new Error("P1 lab inventory did not open for the client"); }),
      ]);
      if (!labWindow || labWindow.slots.length === 0) throw new Error("P1 lab inventory had no client-visible slots");
      await command(bots.alice, "lab stop", "实验将在下一个逻辑步安全停止");
      bots.alice.closeWindow(labWindow);
      checks.push({ stage: "P1", passed: true, openedSlots: labWindow.slots.length });
    }

    const blueprintId = `smoke:blueprint${suffix}`;
    await command(bots.alice, `blueprint create ${blueprintId}`, "已创建并打开");
    await command(bots.alice, "blueprint add solar genesis:solar_panel 1 0 0 core", "已加入 solar");
    await command(bots.alice, "blueprint preview", "质量");
    await command(bots.alice, "blueprint save", "蓝图已原子保存");
    await command(bots.alice, "blueprint close", "已关闭蓝图工作区");
    await command(bots.alice, `blueprint open ${blueprintId}`, "已打开");
    checks.push({ stage: "P2", passed: true, savedAndReopened: true });

    const programId = `smoke:program${suffix}`;
    await command(bots.alice, `program create ${programId}`, "已创建并打开");
    await command(bots.alice, "program replace start CONSTANT_INTEGER - value=5", "已替换为 CONSTANT_INTEGER");
    await command(bots.alice, "program add result COMPARE_THRESHOLD_INTEGER start operator=gt,value=4", "已加入 result");
    await command(bots.alice, "program simulate 42 3", "replay=verified");
    await command(bots.alice, "program save", "程序已原子保存");
    checks.push({ stage: "P3", passed: true, replayVerified: true });

    await command(bots.alice, "radio chip genesis:radio", "已生成绑定 genesis:radio");
    await command(bots.bob, "radio chip genesis:radio", "已生成绑定 genesis:radio");
    await command(bots.alice, "radio join sector_a 0 0 0 16", "已加入虚拟无线网络");
    await command(bots.bob, "radio join sector_a 1 0 0 16", "已加入虚拟无线网络");
    await command(bots.alice, `radio send ${bots.bob.player.uuid} 1 supply ore_offer`, "消息已排入");
    const firstPollStart = bots.bob.messages.length;
    const delivered = await commandUntil(bots.bob, "radio inbox", "from=");
    const messageIdentity = delivered.match(/from=([^\s]+).*?tick=(\d+) · seq=(\d+)/);
    if (!delivered.includes("ore_offer") || !messageIdentity)
      throw new Error(`P4 delivery identity could not be read from: ${delivered}`);
    const resend = `radio send ${bots.bob.player.uuid} 1 supply ore_offer`;
    const firstPollCount = bots.bob.messages.slice(firstPollStart).filter((message) => message.includes("ore_offer")).length;
    const duplicatePollStart = bots.bob.messages.length;
    await command(bots.bob, "radio inbox", "虚拟信箱 1 条");
    const duplicatePollLines = bots.bob.messages.slice(duplicatePollStart).filter((message) => message.includes("ore_offer"));
    if (firstPollCount !== 1 || duplicatePollLines.length !== 1)
      throw new Error("P4 repeated inbox poll did not return exactly one copy of the unacknowledged message");
    const recipientUuid = bots.bob.player.uuid;
    await dropConnection(bots.bob);
    bots.bob = await connect(mineflayer, options.host, Number(options.port), names.bob);
    if (bots.bob.player.uuid !== recipientUuid)
      throw new Error("P4 reconnect changed the recipient UUID");
    await command(bots.bob, "radio join sector_a 1 0 0 16", "已加入虚拟无线网络");
    const redelivered = await commandUntil(bots.bob, "radio inbox", "from=");
    const redeliveredIdentity = redelivered.match(/from=([^\s]+).*?tick=(\d+) · seq=(\d+)/);
    if (!redeliveredIdentity || redeliveredIdentity[1] !== messageIdentity[1]
        || redeliveredIdentity[2] !== messageIdentity[2] || redeliveredIdentity[3] !== messageIdentity[3]
        || !redelivered.includes("ore_offer"))
      throw new Error(`P4 reconnect did not redeliver the original pending envelope: ${redelivered}`);
    await command(bots.bob, `radio ack ${messageIdentity[1]} ${messageIdentity[2]} ${messageIdentity[3]}`, "消息已确认");
    const confirmedInboxStart = bots.bob.messages.length;
    await command(bots.bob, "radio inbox", "虚拟信箱 0 条");
    if (bots.bob.messages.slice(confirmedInboxStart).some((message) => message.includes("ore_offer")))
      throw new Error("P4 acknowledged message remained in the recipient mailbox");
    await command(bots.alice, resend, "重复消息已识别，未重复投递");
    const retryInboxStart = bots.bob.messages.length;
    await command(bots.bob, "radio inbox", "虚拟信箱 0 条");
    if (bots.bob.messages.slice(retryInboxStart).some((message) => message.includes("ore_offer")))
      throw new Error("P4 cross-tick retry created a duplicate after acknowledgement");
    checks.push({
      stage: "P4",
      passed: true,
      deliveredMessage: true,
      atLeastOncePolls: 2,
      recipientDisconnectPersisted: true,
      acknowledgementClearedMailbox: true,
      crossTickDuplicateNotRedelivered: true,
      messageTick: Number(messageIdentity[2]),
      sequence: Number(messageIdentity[3]),
    });

    await command(bots.alice, "ecology start 42", "生态舱");
    await command(bots.alice, "ecology step 2", "population=");
    await command(bots.alice, "ecology replay", "生态回放通过");
    checks.push({ stage: "P5", passed: true, replayVerified: true });
    const ecologyStudy = await runEcologyStudy(bots);
    checks.push({ stage: "P5-ecology-study", ...ecologyStudy });

    await command(bots.alice, `factory invite ${names.bob}`, "已邀请");
    await command(bots.bob, "factory accept", "已组成双人模拟组织");
    await command(bots.carol, `factory invite ${names.dave}`, "已邀请");
    await command(bots.dave, "factory accept", "已组成双人模拟组织");
    const alicePairFirst = organizationForPair(bots, "alice", "bob");
    const otherPairFirst = organizationForPair(bots, "carol", "dave");
    const aliceOrganization = alicePairFirst < otherPairFirst ? "org_cedar" : "org_ember";
    const faultSeed = faultSeedForFactoryOrder(aliceOrganization, "press_fault", 5, 7);
    await command(bots.alice, `factory start ${faultSeed}`, "工厂 ");
    await command(bots.alice, "factory bid 15", "工厂 ");
    await command(bots.alice, "factory step 4", "工厂 ");
    await command(bots.alice, "factory order press_fault 2", "工厂 ");
    const failureResolutionStart = bots.alice.messages.length;
    await command(bots.alice, "factory step 2", "工厂 ");
    const failedOrderLine = await waitForMessage(bots.alice, failureResolutionStart, "FAILED_RECOVERED");
    const recoveryLine = await waitForMessage(bots.alice, failureResolutionStart, "recovered=1");
    const refundedOrganization = bots.alice.messages.slice(failureResolutionStart)
      .find((message) => /org_(cedar|ember) members=.*credits=85 · ore=16 · parts=0/.test(message));
    if (!failedOrderLine.includes("DETERMINISTIC_FACTORY_FAULT") || !refundedOrganization)
      throw new Error("P6 production failure did not persist compensation, refund inputs, and release parts");
    await command(bots.alice, "factory replay", "四人工厂回放通过");
    checks.push({
      stage: "P6",
      passed: true,
      fourPlayers: true,
      replayVerified: true,
      injectedProductionFailure: true,
      compensationRecovered: true,
      refundedCreditsAndOre: true,
      releasedPartsEscrow: true,
      recoveredOrdersLine: recoveryLine,
      failureSeed: faultSeed,
      failedOrder: failedOrderLine,
      recoveredOrganization: refundedOrganization,
    });

    const rulesetId = `smoke:arena${suffix}`;
    await command(bots.alice, `arena create ${rulesetId}`, "已创建私有 ArenaRuleset 草稿");
    await command(bots.alice, "arena set width 24", "已修改 width");
    await command(bots.alice, "arena set height 20", "已修改 height");
    await command(bots.alice, "arena set units 6", "已修改 units");
    await command(bots.alice, "arena access public on", "已更新 public 许可");
    await command(bots.alice, "arena access clone on", "已更新 clone 许可");
    await command(bots.alice, "arena preview", "ArenaRuleset");
    await command(bots.alice, "arena save", "ArenaRuleset 草稿已原子保存");
    await command(bots.alice, "arena publish", "ArenaRuleset 已提交审核");
    await command(bots.alice, `arena review status ${rulesetId} 1.0.0`, "status=PENDING");
    const catalogStart = bots.alice.messages.length;
    await command(bots.alice, "arena catalog", "公开规则库");
    if (bots.alice.messages.slice(catalogStart).some((message) => message.includes(rulesetId)))
      throw new Error("P7 pending ArenaRuleset became publicly visible before review");
    await command(bots.alice, `arena review approve ${rulesetId} 1.0.0`, "permission denied");
    await command(bots.observer, "arena review pending", rulesetId);
    await command(bots.observer, `arena review approve ${rulesetId} 1.0.0`, "已批准并发布");
    await command(bots.alice, `arena review status ${rulesetId} 1.0.0`, "status=APPROVED");
    const approvedCatalogStart = bots.alice.messages.length;
    await command(bots.alice, "arena catalog", rulesetId);
    if (!bots.alice.messages.slice(approvedCatalogStart).some((message) => message.includes(rulesetId)))
      throw new Error("P7 approved ArenaRuleset is missing from the public catalog");
    const rejectedRulesetId = `smoke:rejected${suffix}`;
    await command(bots.bob, `arena create ${rejectedRulesetId}`, "已创建私有 ArenaRuleset 草稿");
    await command(bots.bob, "arena access public on", "已更新 public 许可");
    await command(bots.bob, "arena save", "ArenaRuleset 草稿已原子保存");
    await command(bots.bob, "arena publish", "ArenaRuleset 已提交审核");
    await command(bots.bob, `arena review status ${rejectedRulesetId} 1.0.0`, "status=PENDING");
    await command(bots.observer, `arena review reject ${rejectedRulesetId} 1.0.0 needs_balance_changes`, "已拒绝");
    await command(bots.bob, `arena review status ${rejectedRulesetId} 1.0.0`, "status=REJECTED");
    await waitForMessage(bots.bob, Math.max(0, bots.bob.messages.length - 4), "reason=needs_balance_changes");
    const rejectedCatalogStart = bots.bob.messages.length;
    await command(bots.bob, "arena catalog", "公开规则库");
    if (bots.bob.messages.slice(rejectedCatalogStart).some((message) => message.includes(rejectedRulesetId)))
      throw new Error("P7 rejected ArenaRuleset became publicly visible");
    checks.push({
      stage: "P7",
      passed: true,
      reviewRequired: true,
      authorCannotApprove: true,
      approvedRuleset: rulesetId,
      rejectedRuleset: rejectedRulesetId,
      rejectionReasonPersisted: true,
      rejectedRulesetHidden: true,
    });

    await command(bots.alice, `arena match invite ${names.bob} cedar`, "已邀请");
    await command(bots.bob, "arena match accept", "已加入队伍 cedar");
    await command(bots.carol, `arena match invite ${names.dave} moss`, "已邀请");
    await command(bots.dave, "arena match accept", "已加入队伍 moss");
    const started = await command(bots.alice, "arena match start genesis:arena_ruleset 1.0.0 42", "matchId=");
    const matchId = started.match(/matchId=([^\s]+)/)?.[1];
    if (!matchId) throw new Error(`P8 match ID was missing from: ${started}`);
    await command(bots.observer, `arena match watch ${matchId}`, "Observer mode is read-only");
    await command(bots.alice, "arena match pass", "动作已封存");
    const pendingBeforeDisconnect = await command(bots.observer, "arena match matches", matchId);
    if (!pendingBeforeDisconnect.includes("pending=1"))
      throw new Error(`P8 pending roster was not persisted before disconnect: ${pendingBeforeDisconnect}`);
    const bobUuid = bots.bob.player.uuid;
    await dropConnection(bots.bob);
    bots.bob = await connect(mineflayer, options.host, Number(options.port), names.bob);
    if (bots.bob.player.uuid !== bobUuid)
      throw new Error("P8 reconnect changed the participant UUID");
    const restoredBob = await waitForMessage(bots.bob, 0, "已恢复 Arena match", 15000);
    if (!restoredBob.includes(matchId) || !restoredBob.includes("pending=1"))
      throw new Error(`P8 reconnect did not restore its persisted match action: ${restoredBob}`);
    await command(bots.bob, "arena match pass", "动作已封存");
    await command(bots.carol, "arena match pass", "动作已封存");
    await command(bots.dave, "arena match pass", "Territory=");
    await command(bots.alice, "arena match replay", "Arena replay verified");
    checks.push({
      stage: "P8",
      passed: true,
      fourPlayerRound: true,
      observerReadOnly: true,
      replayVerified: true,
      abruptDisconnect: true,
      observerSawPendingActionBeforeDisconnect: true,
      participantUuidPreserved: bots.bob.player.uuid === bobUuid,
      pendingActionRestored: true,
      matchIdRestored: matchId,
    });

    await command(bots.alice, "status", "real-settlement=disabled");
    checks.push({ stage: "P9", passed: true, realSettlementRemainsDisabled: true });

    const signedDesign = stageSignedDesign(options["data-directory"], options["blueprint-source"], suffix);
    await command(bots.alice, `design publishers add ${signedDesign.keyId} ${signedDesign.fileAlias}`, "permission denied");
    await command(bots.observer, `design publishers add ${signedDesign.keyId} ${signedDesign.fileAlias}`, "Publisher trust root active");
    await command(bots.observer, "design publishers list", `TRUSTED ${signedDesign.keyId}`);
    await command(bots.alice, "design install offline_fixture", "签名设计已导入到个人库");
    await command(bots.alice, "design list", signedDesign.designId);
    await command(bots.alice, `design inspect ${signedDesign.designId}`, "author=");
    await command(bots.bob, `design inspect ${signedDesign.designId}`, "设计导入未完成");
    const rotatedDesign = stageSignedDesign(options["data-directory"], options["blueprint-source"], `${suffix}-rotated`);
    await command(bots.observer, `design publishers add ${rotatedDesign.keyId} ${rotatedDesign.fileAlias}`, "Publisher trust root active");
    await command(bots.alice, "design install offline_fixture", "签名设计已导入到个人库");
    await command(bots.alice, `design inspect ${rotatedDesign.designId}`, "author=");
    await command(bots.observer, `design publishers revoke ${signedDesign.keyId} scheduled-rotation`, "Publisher trust root revoked");
    await command(bots.alice, `design inspect ${signedDesign.designId}`, "设计导入未完成");
    await command(bots.alice, `design inspect ${rotatedDesign.designId}`, "author=");
    checks.push({
      stage: "P10",
      passed: true,
      importedDesign: signedDesign.designId,
      publisherKeyId: signedDesign.keyId,
      rotatedPublisherKeyId: rotatedDesign.keyId,
      oldKeyRevoked: true,
      oldSignatureRejected: true,
      ownerPrivate: true,
      trustRoot: "ephemeral test-only Ed25519 key",
    });

    if (Number(options["sustained-lab-minutes"] || 0) > 0) {
      const loadTest = await runSustainedLabProfile(bots, Number(options["sustained-lab-minutes"]));
      checks.push({ stage: "P11-sustained-load", ...loadTest });
    }

    if (options["test-shutdown-menu"] === "true") {
      const shutdownWindowOpened = new Promise((resolve) => bots.alice.once("windowOpen", resolve));
      await command(bots.alice, "lab start 99 1", "实验开始");
      const shutdownWindow = await Promise.race([
        shutdownWindowOpened,
        pause(10000).then(() => { throw new Error("P1 shutdown inventory did not open"); }),
      ]);
      if (!shutdownWindow || shutdownWindow.slots.length === 0)
        throw new Error("P1 shutdown inventory had no client-visible slots");
      process.stdout.write("READY_TO_STOP_WITH_MENU_OPEN\n");
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("Folia/Paper server did not disconnect the open menu client")), 30000);
        bots.alice.once("end", (reason) => {
          clearTimeout(timeout);
          resolve(reason);
        });
      });
      checks.push({ stage: "P1-shutdown", passed: true, inventoryWasOpen: true, serverDisconnectedClient: true });
    }
    if (options["disable-plugin-during-lab"] === "true") {
      if (options.kind !== "folia") throw new Error("The live-disable lab test is specific to Folia");
      await command(bots.alice, "lab start 100 1", "Folia 实验已启动");
      await command(bots.alice, "lab status", "试验箱 RUNNING");
      await pause(1500);
      process.stdout.write("READY_TO_DISABLE_PLUGIN_DURING_LAB\n");
      const disableMarker = path.join(options["data-directory"], "..", "LabDisableTrigger", "disable-completed.txt");
      const runtimeSamplePath = path.join(options["data-directory"], "..", "LabDisableTrigger", "runtime-sample.json");
      const disableDeadline = Date.now() + 20000;
      while (Date.now() < disableDeadline && !fs.existsSync(disableMarker)) await pause(100);
      if (!fs.existsSync(disableMarker)) throw new Error("Folia disable fixture did not return after disabling StarGenesis");
      const owner = bots.alice.player.uuid;
      const evidenceDirectory = path.join(options["data-directory"], "labs", "lab");
      const snapshotPath = path.join(evidenceDirectory, `${owner}.snapshot.json`);
      const replayPath = path.join(evidenceDirectory, `${owner}.replay.json`);
      if (!fs.existsSync(snapshotPath) || !fs.existsSync(replayPath))
        throw new Error("Folia lab checkpoint and replay were not persisted during live plugin disable");
      const snapshot = JSON.parse(fs.readFileSync(snapshotPath, "utf8"));
      const replay = JSON.parse(fs.readFileSync(replayPath, "utf8"));
      if (snapshot.seed !== 100 || replay.seed !== 100 || snapshot.worldId !== replay.worldId
          || snapshot.tick < 1 || replay.frames.length < 1 || replay.frames.length !== snapshot.tick) {
        throw new Error("Folia lab live-disable checkpoint/replay did not preserve the active seed and tick history");
      }
      const runtimeSample = JSON.parse(fs.readFileSync(runtimeSamplePath, "utf8"));
      const loadedSamples = runtimeSample.samples.filter((sample) => sample.onlinePlayers >= 5);
      const sustainedMinutes = Number(options["sustained-lab-minutes"] || 0);
      const minimumFivePlayerSamples = sustainedMinutes > 0 ? Math.max(10, Math.floor(sustainedMinutes * 6)) : 10;
      if (runtimeSample.sampleCount !== runtimeSample.samples.length || loadedSamples.length < minimumFivePlayerSamples
          || loadedSamples.some((sample) => !Array.isArray(sample.tps) || sample.tps.length !== 3
            || sample.tps.some((value) => !Number.isFinite(value)) || !Number.isFinite(sample.averageTickMillis))) {
        throw new Error(`Folia runtime time series did not capture at least ${minimumFivePlayerSamples} valid five-player samples`);
      }
      const sampleTimes = runtimeSample.samples.map((sample) => Date.parse(sample.sampledAt));
      if (sampleTimes.some((sampledAt) => !Number.isFinite(sampledAt)))
        throw new Error("Folia runtime sample timestamp is invalid");
      const runtimeSummary = {
        sampleCount: runtimeSample.sampleCount,
        fivePlayerSamples: loadedSamples.length,
        intervalSeconds: sampleTimes.length > 1
          ? Math.round((sampleTimes[sampleTimes.length - 1] - sampleTimes[0]) / 1000) : 0,
        minFivePlayerTps1m: Math.min(...loadedSamples.map((sample) => sample.tps[0])),
        maxFivePlayerAverageTickMillis: Math.max(...loadedSamples.map((sample) => sample.averageTickMillis)),
        maxOnlinePlayers: Math.max(...runtimeSample.samples.map((sample) => sample.onlinePlayers)),
      };
      checks.push({
        stage: "P1-live-disable",
        passed: true,
        interface: "Folia command workflow",
        simulationWasRunning: true,
        checkpointPersisted: true,
        replayPersisted: true,
        seed: snapshot.seed,
        savedTick: snapshot.tick,
        replayFrames: replay.frames.length,
        runtimeSample: runtimeSummary,
      });
    }

    result.passed = true;
  } catch (error) {
    result.error = error instanceof Error ? error.stack || error.message : String(error);
  } finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  }

  const exitCode = result.passed ? 0 : 1;
  fs.writeSync(1, `${JSON.stringify(result, null, 2)}\n`);
  process.exit(exitCode);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
