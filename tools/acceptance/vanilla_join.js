// Issue #31 acceptance — a VANILLA 1.21.11 client joins a dedicated server running the ORGE build.
//
// The client is mineflayer: it speaks only the vanilla protocol and knows only vanilla registries and
// block states (minecraft-data 1.21.11), so it is an unmodded client. Over RCON the script builds a
// scene (waterlogged blocks, sand and dirt under ponded water, a heated pool that boils off), the bot
// walks and swims through it, and the script records:
//   * every custom_payload channel and every registry_data registry/entry the server sent, in the
//     configuration and play phases — a vanilla client needs none of them to be non-vanilla;
//   * every block state the client received around the scene — each must decode to a vanilla state;
//   * kicks, disconnects and protocol errors (any one fails the run);
//   * /orge get readouts of the scene cells while it runs.
// Exit 0 = joined, walked the scene, stayed connected, and saw only vanilla content.
//
// usage: ORGE_RCON_PASSWORD=… node vanilla_join.js <host> <port> <rconPort> [seconds-per-phase] [tick-rate]
// (the server's rcon.password comes from the environment; it is never written into this file)
// tick-rate (vanilla /tick rate, default 20): on a host where one native step takes longer than the
// scheduler's 40-tick grace window, every step is cancelled at 20 TPS and the scene never evolves; a
// lower game tick rate gives the engine the wall time it needs (the client is unaffected).
// SKIP_BUILD=1 re-walks the scene a previous run built (the world keeps it).
const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const { Vec3 } = require('vec3');

const [host = '127.0.0.1', port = '25570', rconPort = '25577', phaseSec = '40', tickRate = '20'] =
    process.argv.slice(2);
const PHASE = +phaseSec * 1000;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

const failures = [];
const payloads = {};          // "state channel" -> count
const registries = {};        // registry id -> entry ids
const tagNamespaces = new Set();
let rcon;
const cmd = async c => (await rcon.send(c)).replace(/§./g, '');

// Scene: dug into the superflat top inside migrated old columns (chunks 0..1 × −1..0, whose stored cells
// match their blocks). The bot stands on y = Y; the ponds sit one block down (y = Y−1), so it walks and
// swims through them.
const Y = -60, D = Y - 1;
const OX = -2, OZ = -8;                              // scene origin (x 12..28, z -8..6)
const P = (x, y, z) => [x + OX, y, z + OZ];
const S = {
  stairs: [P(18, D, 4), P(19, D, 4), P(20, D, 4)],   // waterlogged oak stairs in a pond
  slab: P(21, D, 4),                                 // waterlogged oak slab
  sand: [P(18, D - 1, 8), P(22, D - 1, 8)],          // sand bed x 18..22, water ponded on it
  dirt: [P(24, D - 1, 8), P(26, D - 1, 8)],          // dirt bed x 24..26, water ponded on it
  pool: [P(18, D, 11), P(21, D, 11)],                // heated pool: boils off (evaporates)
};
const at = (p, dy = 0) => `${p[0]} ${p[1] + dy} ${p[2]}`;
// A build command that did not apply (e.g. "That position is not loaded") fails the run.
async function build(c) {
  const r = await cmd(c);
  if (!/^(Changed the block|Successfully filled)/.test(r.trim())) failures.push(`scene build: "${c}" -> "${r.trim()}"`);
}
const box = (a, b, block) => build(`fill ${at(a)} ${at(b)} ${block}`);

async function buildScene() {
  // ONE edit per cell, on untouched superflat ground (grass at D, dirt below): every block below
  // replaces what worldgen put there, so the engine sees each placement exactly once.
  // pond with waterlogged stairs + slab in it
  await build(`setblock ${at(P(21, D, 3))} minecraft:water`);   // one plain water cell beside them
  for (const p of S.stairs) await build(`setblock ${at(p)} minecraft:oak_stairs[facing=north,waterlogged=true]`);
  await build(`setblock ${at(S.slab)} minecraft:oak_slab[type=bottom,waterlogged=true]`);
  // sand and dirt beds with water ponded on top (infiltration: wet sand / wet dirt)
  await box(S.sand[0], S.sand[1], 'minecraft:sand');
  await box(P(18, D, 8), P(22, D, 8), 'minecraft:water');
  await box(P(24, D, 8), P(26, D, 8), 'minecraft:water');       // the dirt below is worldgen dirt
  // pool, heated once the engine has stepped it
  await box(S.pool[0], S.pool[1], 'minecraft:water');
}

/** Engine steps completed so far (from /orge perf). */
async function stepsDone() {
  const m = (await cmd('orge perf')).match(/steps (\d+) completed/);
  return m ? +m[1] : 0;
}

/** Waits until the engine has completed n more steps (or the timeout passes). */
async function awaitSteps(n, timeoutMs) {
  const s0 = await stepsDone(), t0 = Date.now();
  let s = s0;
  while (s - s0 < n && Date.now() - t0 < timeoutMs) { await sleep(2000); s = await stepsDone(); }
  log(`  engine: ${s - s0} steps completed in ${((Date.now() - t0) / 1000).toFixed(0)} s (${(await cmd('orge perf')).trim()})`);
}

const SAND = P(20, D - 1, 8), OVER_SAND = P(20, D, 8), DIRT = P(25, D - 1, 8), OVER_DIRT = P(25, D, 8);
const POOL = P(19, D, 11), OVER_POOL = P(19, Y, 11);

async function readCells(label) {
  const pts = [S.stairs[1], S.slab, SAND, OVER_SAND, DIRT, OVER_DIRT, POOL, OVER_POOL];
  for (const p of pts) log(`  ${label} orge get ${at(p)} -> ${(await cmd(`orge get ${at(p)}`)).trim()}`);
}

function clientView(bot, label) {
  // What the CLIENT has for the scene: every state must be a vanilla state minecraft-data knows.
  let unknown = 0, n = 0;
  const seen = {};
  for (let x = OX + 14; x <= OX + 30; x++) for (let y = D - 2; y <= Y + 3; y++) for (let z = OZ; z <= OZ + 14; z++) {
    const b = bot.blockAt(new Vec3(x, y, z));
    n++;
    if (!b || !b.name || b.name === 'unknown') { unknown++; continue; }
    const props = b.getProperties();
    const key = b.name + (props.waterlogged !== undefined ? `[waterlogged=${props.waterlogged}]` : '')
        + (props.level !== undefined ? `[level=${props.level}]` : '');
    seen[key] = (seen[key] || 0) + 1;
  }
  log(`  ${label} client view: ${n} blocks, ${unknown} unknown; ${JSON.stringify(seen)}`);
  if (unknown) failures.push(`${label}: ${unknown} block states the vanilla client could not decode`);
  const probe = p => { const b = bot.blockAt(new Vec3(...p)); return b ? `${b.name}${JSON.stringify(b.getProperties())}` : 'null'; };
  log(`  ${label} stairs ${probe(S.stairs[1])} slab ${probe(S.slab)} sand ${probe(SAND)} over-sand ${probe(OVER_SAND)} dirt ${probe(DIRT)} over-dirt ${probe(OVER_DIRT)} pool ${probe(POOL)}`);
}

async function walk(bot, path, ms) {
  // Walk (and swim) through each waypoint with plain movement controls, like a player would.
  const t0 = Date.now();
  for (let i = 0; Date.now() - t0 < ms; i = (i + 1) % path.length) {
    const target = new Vec3(path[i][0] + OX + 0.5, Y, path[i][1] + OZ + 0.5);
    await bot.lookAt(target.offset(0, 1.6, 0), true);
    bot.setControlState('forward', true);
    bot.setControlState('jump', bot.entity.isInWater);
    const tw = Date.now();
    while (bot.entity.position.xzDistanceTo(target) > 0.8 && Date.now() - tw < 6000) {
      await bot.lookAt(target.offset(0, 1.6, 0), true);
      bot.setControlState('jump', bot.entity.isInWater || bot.entity.isCollidedHorizontally);
      await sleep(100);
    }
    bot.clearControlStates();
    const p = bot.entity.position;
    log(`  walk -> (${path[i]}) at ${p.x.toFixed(1)} ${p.y.toFixed(1)} ${p.z.toFixed(1)} inWater=${bot.entity.isInWater}`);
  }
}

(async () => {
  const secret = process.env.ORGE_RCON_PASSWORD;
  if (!secret) throw new Error('set ORGE_RCON_PASSWORD to the server\'s rcon.password');
  rcon = await Rcon.connect({ host, port: +rconPort, password: secret, timeout: 300000 });
  log('rcon connected;', (await cmd('list')).trim());

  // The bot spawns (and the engine's active range starts) at the scene, not at the old world's spawn.
  log((await cmd(`setworldspawn ${at(P(16, Y, 4))}`)).trim());
  const bot = mineflayer.createBot({ host, port: +port, username: 'orgebot', version: '1.21.11', auth: 'offline' });
  bot._client.on('packet', (data, meta) => {
    if (meta.name === 'custom_payload') {
      const k = `${meta.state} ${data.channel}`;
      payloads[k] = (payloads[k] || 0) + 1;
    } else if (meta.name === 'registry_data') {
      registries[data.id] = (data.entries || []).map(e => e.key);
    } else if (meta.name === 'tags') {
      for (const t of data.tags || []) for (const tag of t.tags || []) tagNamespaces.add(String(tag.tagName).split(':')[0]);
    }
  });
  bot.on('kicked', r => failures.push(`kicked: ${JSON.stringify(r)}`));
  bot.on('error', e => failures.push(`client error: ${e.message}`));
  let ended = false;
  bot.on('end', r => { ended = true; log('connection ended:', r); });

  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('no spawn in 120 s')), 120000); });
  log(`spawned as ${bot.username} (protocol ${bot.protocolVersion}, game ${bot.version}) at ${bot.entity.position}`);
  await cmd('gamemode creative orgebot');
  await cmd('time set noon');
  await cmd(`tick rate ${tickRate}`);

  await cmd(`tp orgebot ${at(P(16, Y, 4))}`);
  if (process.env.SKIP_BUILD !== '1') await buildScene();   // SKIP_BUILD=1: re-walk an existing scene
  await sleep(3000);
  await awaitSteps(4, 300000);   // the engine has taken every placement
  clientView(bot, 't0');
  await readCells('t0');
  // the pool, heated to 420 K, boils to steam (evaporation by phase change)
  for (let x = S.pool[0][0]; x <= S.pool[1][0]; x++) log('  heat', (await cmd(`orge set ${x} ${D} ${S.pool[0][2]} 420`)).trim());

  const route = [[16, 4], [19, 4], [21, 4], [23, 6], [20, 8], [25, 8], [27, 6], [23, 11], [19, 11], [16, 11], [16, 6]];
  await walk(bot, route, PHASE);
  clientView(bot, 't1');
  await readCells('t1');
  await walk(bot, route.slice().reverse(), PHASE);
  clientView(bot, 't2');
  await readCells('t2');

  await cmd('save-all flush');
  if (ended) failures.push('the client was disconnected before the run ended');

  log('custom_payload channels received:', JSON.stringify(payloads));
  const nonVanillaPayload = Object.keys(payloads).filter(k => !k.split(' ')[1].startsWith('minecraft:'));
  log('registries received:', Object.keys(registries).length);
  const nonVanillaReg = [];
  for (const [id, entries] of Object.entries(registries)) {
    if (!String(id).startsWith('minecraft:')) nonVanillaReg.push(id);
    for (const e of entries) if (!String(e).startsWith('minecraft:')) nonVanillaReg.push(`${id} ${e}`);
  }
  log('non-vanilla registry ids/entries:', JSON.stringify(nonVanillaReg));
  log('tag namespaces received (tag NAMES; a vanilla client ignores unknown ones):', JSON.stringify([...tagNamespaces]));
  log('non-minecraft custom_payload channels:', JSON.stringify(nonVanillaPayload));
  if (nonVanillaReg.length) failures.push(`non-vanilla registry content: ${nonVanillaReg.join(', ')}`);
  for (const k of nonVanillaPayload) failures.push(`non-vanilla custom payload sent to the client: ${k}`);

  bot.quit('acceptance run complete');
  await sleep(1000);
  await rcon.end();
  if (failures.length) { log('FAIL'); for (const f of failures) log('  ' + f); process.exit(1); }
  log('PASS: vanilla client joined, walked the scene, stayed connected, saw only vanilla content');
  process.exit(0);
})().catch(e => { log('FAIL', e.stack || e); process.exit(1); });
