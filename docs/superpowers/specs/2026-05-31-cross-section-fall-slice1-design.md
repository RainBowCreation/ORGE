# Cross-section flow — Slice 1: vertical FALL across seams (engine-reported flux ledger)

Architecture chosen by the user: **engine-reported seam-flux ledger + Java delivery** (not super-grid).
First slice: **vertical fall across stacked-section (Y) seams, fluid only.** Same-species + wetting
fresh air below; horizontal seams, the other 5 faces, and thermal seam-wake are LATER slices.

## Problem recap

The kernel steps each 16³ section against a READ-ONLY halo, so a section cannot write a neighbour.
Interior fall (kernel `(2a)`) is `for y=1..15` — the **y=0 bottom plane never falls**, so fluid piles
on each section's floor and never gravity-falls into the section below. The current cross-seam vertical
exchange `(2b-ii)` is only same-species *leveling* (`frac·0.5·diff`), which is far weaker than gravity
and does not wet fresh air below.

## Mechanism: a NEG_Y outflux ledger + a pending-influx buffer

1. **Kernel emits, per section, a NEG_Y face outflux** (256 cells × {mass, enthalpy}). The donor
   species is read by Java from `matOut` at the y=0 plane (no separate species output). The kernel
   computes the downward seam transfer for each y=0 fluid cell, **subtracts** it from `massOut[y=0]`,
   and **records** it into the NEG_Y outflux buffer. It never writes the halo.
2. **Java delivers** each section A's NEG_Y outflux into a **pending-influx buffer** keyed by the
   section directly below (same cx/cz, sectionY−1), face = its TOP plane (y=15). The receiving
   section picks the pending influx up at its **next snapshot** (added to its input top-plane mass,
   then cleared) and the receiver is **woken**. This decouples delivery from the same-cycle
   write-back ordering, and uniformly handles a receiver that is active / dormant / unloaded
   (unloaded → influx waits in the buffer until the chunk loads; mass is never lost).

This is the named deferred fix ("engine-reported seam flux") and keeps the stateless per-section
kernel + bit-identical `sim_engine` parity.

## Cross-seam fall semantics (the bit-identity-critical decision)

`sim_engine`'s interior fall cascades top-down within a section in one frame, but the per-section
kernel cannot cascade a delivered parcel further this cycle (it's delivered AFTER the step). To keep
the kernel and `sim_engine` **bit-identical**, the cross-seam vertical transfer is a **single,
non-cascading transfer per cycle**, computed on **pre-step snapshot masses** (exactly like the
existing `(2b-ii)` antisymmetric seam), but with **fall (gravity) magnitude** instead of leveling:

- Donor = the UPPER section's y=0 cell; receiver = the LOWER section's y=15 cell (read via the upper
  section's NEG_Y halo, which is the lower section's pre-step top plane).
- Transfer only when the receiver face is a **loaded, non-void** cell (`haloMat != 0`) that is a
  **fall destination**: real air (`lut.air[haloMat]`) OR same-species fluid with capacity.
  An unloaded neighbour is the void sentinel (`haloMat==0`) → **no transfer** (no-flow world-bottom
  wall — `isAir` must NOT treat the void sentinel as air at a seam; this is the same void-vs-air
  distinction the air-sink fix established, applied to the seam).
- Magnitude (gravity): `cap = donor.maxMass − haloMass_below; dm = min(massOut[y0]_snapshot − donor.minFlow?, cap)`.
  (Resolve in plan: fall ignores the minFlow floor for the donor — interior fall does — so
  `dm = min(massOut[y0], cap)`; confirm against interior `(2a)` which uses `dm=min(m,cap)` with no floor.)
- **This REPLACES `(2b-ii)`'s downward half.** Vertical cross-seam flow becomes gravity-only
  (downward); the old symmetric up/down leveling across a Y seam is removed (water does not flow UP
  across a seam — unphysical). `(2b-i)` horizontal and `(2c)` swap are untouched this slice.
- Cascade speed: one section-seam per cycle (interior fall fills each section's floor each cycle; the
  seam ledger moves the floor plane down one section per cycle). A pour cascades to the world floor in
  ≈(number of seams) cycles at 4 Hz — visibly "falling". Acceptable for slice 1.

## Bit-identical `sim_engine` mirror + parity

`sim_engine.advect_chunk` keeps interior fall purely interior (unchanged) and replaces its vertical
seam block with the SAME single-transfer gravity rule, computed on the pre-step snapshot and
accumulated (non-cascading), applied at end — so within one frame `sim_engine` and the kernel produce
identical donor `massOut`. The receiver side: `sim_engine` adds dm to the lower section's y=15 cell
directly (but non-cascading this frame); the kernel records it as NEG_Y outflux. The parity harness
(`advection_parity_test.cpp`) **applies the kernel's NEG_Y outflux to the lower section's y=15** before
comparing, and asserts: (a) donor `massOut` bit-identical; (b) `kernel_lower_massOut[y15] + delivered
outflux == simengine_lower_massOut[y15]`; (c) total mass across the seam exactly conserved. Update the
existing two-section parity tests to the new fall semantics (they currently assert leveling).

## §9 conservation (donor-side credit)

The donor section's `massOut` is LOWER than its input by the emitted NEG_Y outflux, so
`StepValidator.massConservedPerSpecies` would falsely reject it. The per-species gate must **credit the
NEG_Y outflux to `sumAfter`** for the donor species (the mass legitimately left across the seam):
`sumAfter[donorSpecies] += Σ negYOutflux`. The RECEIVING side needs no §9 change — the pending influx
is added to its INPUT at the next snapshot, so its own step conserves normally. Tolerance unchanged.

## JNI ABI growth (after `matOut`, mirror the existing pattern)

Two new per-section output arrays: `float[] negYFluxMass` (n×256), `float[] negYFluxEnth` (n×256),
appended after `matOut` in `orgeStep` (Java decl + `orge_jni.cpp` param list, position-matched).
`BatchMarshaller` allocates + slices them; `NativeEngine` returns them. `StepResult` (or a small
`SeamFlux` sidecar) carries the NEG_Y arrays. Stub engine returns zero-filled (no flux) → no delivery.

## Java delivery + scheduler wiring

- New pure unit `SeamFluxLedger` (testable headless): given a section key + its NEG_Y {mass,enth} +
  the y=0 donor species, produce the influx to add to the section-below's y=15 plane (mass add, T mix
  `(mOld·Told + enth)/(mOld+mAdd)`, species adopt when the receiver cell is air). And a pending store
  `PendingInflux` (in-memory, dim+key→top-plane {mass,enth,species}), with apply-at-snapshot + clear.
- `MinecraftThermalWorld.snapshot()`: before building a section's task, drain any pending influx for it
  into the top-plane input mass/temp (after `MassSnapshot`/`MaterialChangeReseed`), so the receiver
  steps WITH the delivered mass. Wake already handled by the ledger enqueue.
- `Scheduler.writeBackResults`: after a conserved advection writeBack, hand the entry's NEG_Y flux to
  the ledger → enqueue pending influx for the section below + `wakeNeighbourFlow(below)`.
- Reconciler: the receiver renders its delivered water on its next step (one-cycle lag) — no change.

## Dormancy / load boundaries

- Receiver dormant-but-loaded → enqueued + woken → rejoins next snapshot, drains pending. ✓
- Receiver unloaded → influx waits in `PendingInflux` until the chunk loads (the snapshot drains it
  when the section first appears). Mass conserved. **Volatile** (in-memory; lost on server stop) —
  flagged as a slice-1 limitation (persisting pending influx is a later slice, like the §5 store).
- World floor (no section below, sectionY below min) → halo is void → no transfer → fluid rests on the
  bottom-most loaded section's floor. ✓ (correct; that IS the floor.)

## Out of scope (later slices, do NOT build now)

Horizontal cross-seam fall/wetting/leveling (the other 5 faces), cross-seam wetting that isn't
straight-down, thermal seam-wake at dormant borders, persisting `PendingInflux`, and the
horizontal-seam donor-only leak in `(2b-i)`. The ABI grows to all 6 faces in the full-fluid slice.

## Verification

- ENGINE: new/updated `advection_parity_test` cases (donor bit-identity + delivered-outflux receiver
  parity + seam conservation under gravity fall into air AND into same-species-with-capacity); kernel
  vs `sim_engine` green; a `test_seam_fall` gtest (water on an upper section's floor with air below in
  the lower section → emits NEG_Y outflux = min(m,cap), `massOut[y0]` drops).
- MAIN: `SeamFluxLedger`/`PendingInflux` unit tests; a `MinecraftThermalWorld` test that a pending
  influx is drained into the receiver's top-plane input next snapshot + the receiver is woken;
  `StepValidator` credits NEG_Y outflux (donor passes; a genuine fabrication still rejected);
  end-to-end native-backed test: two stacked sections, water on the upper floor + air in the lower
  top → after K cycles the water has moved down across the seam, total mass conserved.
- In-game gate (user): pour water onto a floating block spanning section seams → it cascades down
  THROUGH the seams to the floor (not piling on each section's floor); combined mass conserved.
