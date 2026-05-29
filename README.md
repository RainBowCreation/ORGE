# ORGE

**Overhauled Realistic General Elements** — a Minecraft base mod that gives every block
(including air and liquids) a **temperature** and a **mass**, and simulates heat
conduction with a fast native C++ engine ([ORGE-ENGINE](https://github.com/RainBowCreation/ORGE-ENGINE)).

This branch (`rebuild`) is a from-scratch v2. The previous Fabric 1.20.1 implementation
lives under [`/old`](./old) as reference only.

## What it does

- Per-cell **temperature + mass** metadata for every block.
- Heat conduction simulated by ORGE-ENGINE, called **in-process via Panama FFI**.
- **Distributed compute:** the server orchestrates connected clients as a worker pool —
  each client computes the subchunks near it and returns results; the server only
  schedules and saves. This keeps server CPU flat as players spread across regions.
- **Phase change:** blocks turn into a target material (conserving mass + temperature)
  when they cross a boiling/freezing point.
- **Data-driven materials:** register materials and block bindings via datapack JSON
  (or a thin Java API). It's a base mod — addons supply their own materials.

Heat-capacity / phase data from [nist.gov]; thermal conductivity from [TPRC].

## Status

- **v1 (in progress):** thermal core — metadata, distributed simulation, phase change.
- **Phase 2 (planned):** fluid dynamics — finite water with no source blocks, gas
  buoyancy — added inside ORGE-ENGINE.

See [DESIGN.md](./DESIGN.md) for the full architecture.

## Building

Requires JDK 21. Clone with submodules:

```bash
git clone --recurse-submodules <repo>
# or, if already cloned:
git submodule update --init --recursive
```

Architectury multiloader build (`core`, `fabric-1.21`, `neoforge-1.21`). Native engine
libraries are fetched from ORGE-ENGINE releases and bundled automatically.

## License

MIT — see [LICENSE](./old/LICENSE).
