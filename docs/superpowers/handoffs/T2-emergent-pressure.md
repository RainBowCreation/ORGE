# T2 — Make pressure EMERGE via reflection (THE CRUX, HIGH RISK) · read 00-MASTER-RULES.md first

**Goal:** in `core/engine_b.hpp` `resolve_world`, make depth-pressure emerge so the **hydrostatic-REST** and
**leveling** RED tests from T1 go GREEN — **without** widening the EOS, **without** a column sum, in the ONE
snapshot antisymmetric pass. This is the genuinely-hard fluid-sim core.

**Authoritative:** decomp **§8.1–§8.4** (one vector, emergent overburden, yield gate, horizontal); canonical
"pressure accumulates in RESOLVE" + reflection; unified-formula **§C.5** (absorb/reflect) + **§J.5**
(hydrostatic balance numbers) + §C.2 (pressure flux form).

## The mechanism (exactly what to build)
1. Gravity already adds `m·g·dt` down (in `u_g`). Keep it.
2. An incompressible receiver (`max==default`, capacity clamp) CANNOT accept mass → its blocked momentum
   must **reflect into pressure** (§C.5). The live wall-reaction branch (`engine_b.hpp:639–649`) does this
   only for **frozen-terrain walls** — extend the same reflection to **incompressible-FLUID-receiver faces**
   so a water column on a floor builds pressure from the bottom up.
3. Feed that **dynamic reflected pressure** into `p_face` (replacing the dead local-EOS-only value at
   `:633`). `p` stays a scalar read per face; the resolved force is the vector from the 6-face netting.
4. It builds over **ticks** (temporal accumulation), one neighbour read per face — never a column scan.

## STRICT DO
- Keep RESOLVE **ONE pass, ONE pre-step snapshot, antisymmetric** — conservation must stay exact (the flux
  form is unchanged; you change what scalar feeds it).
- If convergence needs damping, add a single global `G.head_relax` (∈(0,1], default 1.0) — a bounded
  relaxation, gradual leveling is the accepted §6 tradeoff.
- Regenerate the determinism golden (`core/.../golden/resident-lut-step.bin` via the regen path) WITH
  sanity asserts, since resolver output legitimately changes.

## STRICT DON'T
- ❌ Do NOT widen `max_mass` / add an EOS compression band. `max==default` is law.
- ❌ Do NOT add a "Σ mass above" / overburden pre-pass / column sweep / second pass. One pass, emergent.
- ❌ Do NOT make any cell read beyond its 6 face-neighbours (GPU-locality).
- ❌ Do NOT reach for `force_advect.hpp` or copy its local-EOS ∇p approach — that's the drift you're undoing.
- ❌ Do NOT, if stuck, silently add a Poisson/implicit solve or scale gravity — **escalate to the user
  first** (both are off-spec; §G.1b banks the implicit solve).

**Acceptance:** hydrostatic-REST stays at rest (Δm≈0 to FP, conserved); leveling test converges (monotone,
conserved, no overshoot). Cheap tier green. **Adversarial conservation review required** before it counts.
Report the per-species conservation numbers across N steps + whether `head_relax` was needed and its value.

**If BLOCKED:** say so with the failing numbers; do not fake convergence. This task is allowed to be the one
that needs a second iteration or a more capable model.
