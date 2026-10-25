// usage: ORGE_RCON_PASSWORD=… node rcon.js <port> <command>... — runs each command over RCON, prints the replies.
const { Rcon } = require('rcon-client');
(async () => {
  const [port, ...cmds] = process.argv.slice(2);
  const secret = process.env.ORGE_RCON_PASSWORD;
  if (!secret) throw new Error("set ORGE_RCON_PASSWORD to the server's rcon.password");
  const r = await Rcon.connect({ host: '127.0.0.1', port: +port, password: secret, timeout: 300000 });
  for (const c of cmds) console.log(`> ${c}\n${(await r.send(c)).replace(/§./g, '')}`);
  await r.end();
})().catch(e => { console.error(e.message); process.exit(1); });
