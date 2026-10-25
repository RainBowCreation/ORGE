# Material data sources

Every `data/orge/orge/materials/<id>.json` is a vanilla block id, or a material that renders as an
existing vanilla block (`steam` and metal vapors → air, melts → lava). Guarded by
`AllMaterialsLoadTest.everyMaterialUsesAVanillaBlock`. Values are measured room-temperature data;
1 m³ cell ⇒ `default_mass` = bulk density. `thermal_expansion` is volumetric (3 × linear).
`yield_stress` = uniaxial compressive strength × 1 m².

## Solids

| Group | Blocks | Values / source |
|---|---|---|
| Stone | stone, cobblestone, stone bricks, smooth stone, stone ores | crustal granitic rock k 2.5, c 800, 2700; UCS 140 MPa; μ 1e21 (crust 1e19–1e24) |
| Granite | granite, polished | k 2.8, c 790, 2700; UCS 140 MPa |
| Diorite / andesite | + polished | k 2.3 / 2.1, c 800 / 840, 2850 / 2650; UCS 180 / 140 MPa |
| Slate | deepslate family + deepslate ores | k 2.0, c 760, 2750; UCS 140 MPa |
| Tuff | tuff family | welded tuff k 0.6, c 900, 1800; UCS 20 MPa |
| Calcite / limestone | calcite, dripstone_block | k 3.6 / 2.5, c 820 / 840, 2710 / 2600 |
| Basalt | basalt, blackstone families, magma_block (basalt crust at 1000 K, glowing) | k 1.7, c 840, 2900; UCS 200 MPa |
| Sandstone | all sandstone variants | k 2.4, c 920, 2300; UCS 60 MPa |
| Obsidian | obsidian, crying_obsidian | volcanic glass k 1.3, c 840, 2400 |
| Fired clay | bricks, terracotta (all) | clay brick k 0.7, c 840, 1900; UCS 20 MPa |
| Adobe | mud_bricks, packed_mud | k 0.6, c 900, 1600; UCS 2 MPa |
| Concrete | concrete ×16 / powder ×16 | k 1.7, c 880, 2400, UCS 30 MPa / dry cement 1500, k 0.3 |
| Soil | dirt, grass, podzol, mycelium, farmland, path | moist loam k 1.0, c 1480, 1500 |
| Mud / clay | mud, clay | wet k 1.4 / 1.3, c 2500 / 1380, 1700 / 1800 |
| Sand / gravel | sand, red_sand, gravel (+ suspicious) | dry k 0.27 / 0.7, c 830 / 840, 1600 / 1800 |
| Snow | snow_block 400, powder_snow 100 kg | k(ρ) Sturm et al. 1997; ice cp / latent heat |
| Ice | ice, packed_ice, blue_ice | k 2.2, c 2108, 917; β 1.53e-4; μ 1e13, UCS 2 MPa |
| Wood | logs, wood, stripped, planks (9 species + bamboo) | USDA Wood Handbook FPL-GTR-282 ch.4: k(G, 12 % MC), cp(T, MC); air-dry density per species |
| Metals | iron, gold, copper (all ages, waxed) | CRC: k 80 / 318 / 401, c 449 / 129 / 385, ρ 7874 / 19300 / 8960; ε oxidized copper 0.78 |
| Minerals | coal_block (anthracite), quartz & amethyst blocks | k 0.26 / 7.7, c 1260 / 740, 1500 / 2650 |
| Glass | glass, stained ×16 | soda-lime k 1.0, c 840, 2500, ε 0.92 |
| Wool | wool ×16 | felt k 0.05, c 1360, 300 |
| Partial blocks | every `_slab` (½) and `_stairs` (¾) of a block above | same substance × filled volume (a double slab still reads ½) |

## Soil water (DESIGN-LAW v5 #8 porous-medium columns)

Water soaks into dirt and sand as pore water and drains back out; the soil block itself never moves
(engine seepage, issue #29). Only soils declare these columns; every other material keeps the law
defaults (`particle_density` = `default_mass`, `flow_resistance` 0 = no skeleton, Brooks–Corey 0 / 1 / 0).

| Blocks | `particle_density` ρ_p | porosity φ = 1 − ρ_b/ρ_p | `flow_resistance` r = 1/κ | `air_entry_pressure` p_b | `pore_size_index` λ | `residual_saturation` S_r |
|---|---|---|---|---|---|---|
| dirt, coarse/rooted dirt, grass_block, podzol, mycelium, farmland, dirt_path (loam) | 2650 kg/m³ | 0.434 (pore volume 0.434 m³ ⇒ 434 kg water saturated) | 2.66e12 m⁻² (κ 3.75e-13 m²) | 1092 Pa | 0.220 | 0.0583 |
| sand, red_sand, suspicious_sand | 2650 kg/m³ | 0.396 (396 kg water saturated) | 1.675e11 m⁻² (κ 5.97e-12 m²) | 711 Pa | 0.592 | 0.0458 |

- **ρ_p:** mineral (quartz) grain density 2.65 g/cm³ — Blake & Hartge 1986, *Particle density*, Methods of
  Soil Analysis Pt 1, ch. 14. Porosity is derived, not a column: loam 0.434 (real 0.40–0.50), sand 0.396
  (real 0.35–0.45); Rawls et al. give total porosity 0.463 / 0.437.
- **r:** intrinsic permeability κ = K_s·μ_w/(ρ_w·g) from the saturated hydraulic conductivity K_s of
  Rawls, Brakensiek & Saxton 1982, *Estimation of soil water properties*, Trans. ASAE 25(5):1316, Table 2
  (loam 1.32 cm/h, sand 21.0 cm/h), with water at 20 °C (μ_w 1.002e-3 Pa·s, ρ_w 998 kg/m³, g 9.81).
- **p_b, λ:** Brooks & Corey 1964 (Hydrology Paper 3, Colorado State Univ.) bubbling pressure h_b and
  pore-size index, geometric means from Rawls et al. 1982 Table 2: loam h_b 11.15 cm, λ 0.220; sand
  h_b 7.26 cm, λ 0.592; p_b = ρ_w·g·h_b.
- **S_r:** residual water content over total porosity, Rawls et al. 1982 Table 2: loam 0.027/0.463, sand
  0.020/0.437.
- **Engine knobs** (proposal §12 Q6, not material data): r_ε = 1e-6 m⁻², film volume 1e-3 m³; the
  Brooks–Corey suction is capped at the permanent wilting point, 1500 kPa (FAO-56, Allen et al. 1998).

Real rates this gives (Darcy, g = 10): a ponded loam cell takes water at 2·K_s ≈ 7.3e-6 m/s through its
first half-cell path, sand ≈ 1.2e-4 m/s; dry soil sucks faster (Brooks–Corey suction) until it nears
saturation. Saturated soil over a cave drips only while its suction is below the half-cell head
½·ρ_w·g·Δx (a seepage face).

## Partial, flowable and waterlogged blocks (DESIGN-LAW v5 #8 open-volume columns)

Water and air pass through stairs, slabs, rails, plants and coral without deleting them, and a waterlogged
one holds its water as a second component of the cell (engine seepage, issue #30). The block never moves
(its yield is not exceeded, or its μ is +∞). The open fraction is derived, not a column: φ = 1 − ρ_b/ρ_p.

| Blocks | `particle_density` ρ_p | open fraction φ | `flow_resistance` r = 12/(φ·h²) | water when waterlogged ρ_w·φ |
|---|---|---|---|---|
| every `_stairs` (¾ of the cell) | its substance (`default_mass`/0.75: stone 2700, oak 750, copper 8960…) | 0.25 | 192 m⁻² (h = ½ m) | 250 kg |
| every `_slab` (½) | its substance (`default_mass`/0.5) | 0.5 | 96 m⁻² (h = ½ m) | 500 kg |
| rail, powered/detector/activator rail | iron 7874 kg/m³ (CRC) | 0.98 | 12.245 m⁻² (h = 1 m) | — |
| grass, flowers, crops, saplings, vines, small mushrooms (the ~1 kg herbaceous rows; not leaves or carpets) | fresh herbaceous tissue ≈ water, 1000 kg/m³ (80–90 % water) | 0.999 | 12.012 m⁻² (h = 1 m) | — |
| coral, coral fan, coral wall fan (5 colours, live and dead) | aragonite 2930 kg/m³ (CRC) | 0.98 | 12.245 m⁻² (h = 1 m) | 980 kg |

- **r:** the Hagen–Poiseuille slit of open gap h with open fraction φ, κ = φ·h²/12 (Bear 1972, *Dynamics of
  Fluids in Porous Media*, §5.10), as proposal §13. Derived from the geometry, not measured (flagged):
  the Darcy terminal speed through these gaps is ~10⁴ m/s, so the flux is bounded by the donor's mass,
  the receiver's room and, for a gas, its EOS — not by r.
- **Coral:** aragonite skeleton (CRC: ρ 2.93 g/cm³; k 2.24 W/(m·K), Horai 1971 *J. Geophys. Res.* 76:1278;
  cp 820 J/(kg·K) as calcite). The block's skeleton fraction 0.02 (58.6 kg) is an **estimate** (a fan or
  branch is a few thin plates; the vanilla model has zero-thickness planes), like a rail's model volume.
- **Bamboo slab / stairs** are half / three quarters of a bamboo plank (they had carried a plant row).
- **Waterlogged blocks:** the first time a chunk is simulated (and once for a section saved before
  multi-component cells), a `waterlogged=true` block gets ρ_w·V_pore of water as a second component; a
  drained one is never refilled from nothing. The reconciler keeps the block while it is among the
  cell's components and draws `waterlogged` from the water it holds (on at ≥ ½ of the open volume, off
  below ¼). A block with no open volume (fences, signs, lanterns… on their current rows) keeps its
  waterlogged property as vanilla set it.

## Block aliases (other vanilla blocks that ARE an existing material)

A material JSON may list `"blocks"`: their first touch resolves to that material (one species, so a
cave's air mixes with the sky's). `air`: cave_air, void_air, light. `water`: bubble_column, kelp,
kelp_plant, seagrass, tall_seagrass. `stone`/`cobblestone`/stone bricks/`deepslate`: their infested_*
twins. `oak_planks`: crafting/cartography/fletching/smithing tables, note block, jukebox, bookshelves,
barrel, beehive, bee nest, composter. `cobblestone`: furnace, smoker, dispenser, dropper, observer,
pistons. `smooth_stone`: blast furnace. Guarded by `blockAliasesAreVanillaAndUnique`.

## More real substances

| Block | Values / source |
|---|---|
| tnt (cast TNT) | ρ 1654, k 0.226, cp 1117 (0.254 + 7.5e-4·T cal/g·K); melts 353.5 K, ΔH_fus 21.4 kJ/mol (9.4e4 J/kg) into molten_tnt (water-rendered): ρ 1465, μ 9.5 mPa·s at 85 °C. Detonation not modeled |
| honey_block | a real liquid (FAO): ρ 1420, cp 2700, k 0.44, μ 19 Pa·s (clover honey, 16 % water, 20 °C) |
| raw_gold_block / raw_copper_block | native gold / copper (melt with the metal) |
| raw_iron_block | magnetite Fe3O4: ρ 5180, k 5.1, cp 651 (JANAF) |
| bone_block | cortical bone (IT'IS tissue DB): ρ 1908, cp 1313, k 0.32 |
| hay_block, target | field straw bale: ρ 110, k 0.052, cp 1540 |
| leaves | dense-crown canopy bulk density 0.4 kg/m³ dry, ×2 fresh = 0.8 kg; cp 3000; k of air; ε 0.97 |
| grass, flowers, crops, saplings, vines, small mushrooms | ~1 kg fresh herbaceous biomass per cell; k of air; ε 0.97 |
| anvils, cauldron, hopper, iron bars, chain, rails | iron × the vanilla model's filled volume (0.40 / 0.50 / 0.35 / 0.06 / 0.02) |
| copper bars, chains, lightning rods, bulbs | copper (each age) × model volume (0.06 / 0.02 / 0.02 / 1) |
| copper torch / lantern | the torch / lantern flame |
| pointed dripstone, amethyst cluster & buds, carpets | parent material × model volume |

## Heat sources (pinned = burning fuel holds the flame temperature)

Wood/pitch flame 1300 K. A torch releases ~2 kW, ~25 % radiant, so its cell's effective emissivity is
ε = 500 W / (6 m² · σ · 1300⁴) = 5.1e-4 (torch, wall/soul torches, lantern, soul lantern). A fire block is
a ~1 m luminous flame, ε = 1 − exp(−κL), κ ≈ 1 m⁻¹ ⇒ 0.63; its gas is air at 1300 K (0.272 kg). Torch =
0.2 kg wood; lantern = ~1 kg sheet iron.

## Sky forcing (solar / radiational, issue #32)

Not material data: the climate the once-per-second sky pass forces exposed cells with
(`SurfaceForcing`, spec `superpowers/specs/2026-10-25-solar-radiational-forcing.md`). A cell takes
`τ·ε` of the sky (grey body: it absorbs what it emits, the `ε` column above), so these numbers only set
the sky; each block's response comes from its own `ε`, `m`, `cp`.

| Quantity | Value | Source |
|---|---|---|
| Overworld absorbed sunlight, daily mean | 161 W/m² (`S = π·161·max(0, sin(2π·dayTime/24000))`, noon 506 W/m²); × (1 − rain level) | Trenberth, Fasullo & Kiehl 2009, *Earth's global energy budget*, BAMS 90(3):311, Fig. 1 — solar absorbed by the surface |
| Overworld sky temperature | 276.8 K = (333 W/m² / σ)^¼ | same budget: back radiation 333 W/m² |
| End sky temperature | 2.725 K | Fixsen 2009, *The temperature of the cosmic microwave background*, ApJ 707:916 (2.72548 K) |
| Stefan–Boltzmann σ | 5.670374419e-8 W/(m²·K⁴) | CODATA 2018 (exact) |
| Nether | no forcing | bedrock ceiling |

Balance: the loss `ε·σ·(T⁴ − T_sky⁴)` grows with `T`, so a surface settles where a day's sun equals a
day's loss — `(494 W/m² / σ)^¼ ≈ 305 K` for an isolated Overworld surface, lower once conduction into
air and ground shares the sun. The End relaxes toward 2.725 K and the per-pass floor never takes a cell
below it.

## Phase changes

Each composition class melts into ONE real melt (rendered as `minecraft:lava`) and refreezes into its
canonical solid. Other members melt into it too, anchored from above (`h(T*) + L ≡ h_melt(T*)`), so every
relabel is E-exact and T-continuous (law §6; guarded by `everyPhaseRelabelIsTemperatureContinuous` and
native `engine_b_enthalpy_curve_test (6)`). Rocks melt over a solidus–liquidus range: T* is the midpoint.
Liquid window min = ρ/8.

| Melt | Melts from (→ refreezes as first) | T* (K) | L (J/kg) | Melt ρ / cp / μ (Pa·s) |
|---|---|---|---|---|
| lava (basaltic) | basalt, blackstone, magma_block | 1398 (1323–1473) | 4.0e5 | 2700 / 1480 / 150 |
| molten_andesite | andesite, diorite, deepslate | 1373 (~1273–1473) | 3.5e5 | 2450 / 1400 / 1e5 |
| molten_granite (felsic) | granite, stone, tuff, obsidian, gravel | 1328 (~1233–1423) | 2.7e5 (obsidian 0: glass) | 2300 / 1400 / 1e7 |
| molten_quartz | quartz/amethyst, sand, sandstone | 1986 | 1.6e5 | 2200 / 1430 / 1e7 |
| molten_glass | glass (softening point, no latent heat) | 993 | 0 | 2400 / 1400 / 4e6 |
| molten_iron | iron_block | 1811 | 2.47e5 | 7030 / 824 / 5.5e-3 |
| molten_gold | gold_block | 1337 | 6.37e4 | 17360 / 149 / 5.1e-3 |
| molten_copper | copper (all ages) | 1358 | 2.09e5 | 8000 / 517 / 4.0e-3 |
| water | ice, packed/blue ice, snow, powder snow | 273 | 3.34e5 | |

Boiling / sublimation (vapor rendered as air, like steam): water → steam 373 K; molten iron → iron_vapor
3134 K (6.09e6 J/kg), copper → copper_vapor 2835 K (4.73e6), gold → gold_vapor 3129 K (1.65e6) — monatomic,
cp = 2.5R/M, k Eucken; molten quartz → silica_vapor 3220 K (1.17e7, dissociates to SiO + ½O2, mean
M 0.040; DTIC AD0606246 total heat 6650 Btu/lb minus sensible + fusion); coal_block sublimes → carbon_vapor
3915 K (2.28e7, mostly C3: JANAF ΔfH 820 kJ/mol). All vapors: ideal gas at 1 atm, μ Chapman–Enskog.
Ice also covers `frosted_ice`; the `snow` layer is ⅛ of a 400 kg/m³ block (50 kg).

Sources: Lesher & Spera, *Thermodynamic and transport properties of silicate melts and magma*
(Encyclopedia of Volcanoes); melt viscosity tables (UMass Lowell petrology notes; Dingwell); Hawaiian
basalt lava rheology (150 Pa·s at 1125 °C); CRC Handbook and Assael et al. liquid-metal reference data.

## Evaporation and condensation (DESIGN-LAW v5 #6/#9, no new column)

Water evaporates from a liquid surface into the air of the neighbouring cell (the air cell carries the
vapor as a `steam` component) and vapor condenses back: onto colder water across a face, or in place when
humid air cools to its dew point (engine vapor exchange + dew-point phase transfer, issue #28). Nothing new
is declared per material: the saturation curve of a phase pair comes from the pair's existing columns.

- **Saturation pressure:** integrated Clausius–Clapeyron with Kirchhoff's L(T) = L + Δcp·(T_b − T), from
  the cold phase's `max_temp` T_b and `latent_heat_max` L, Δcp = cp_liquid − cp_vapor and the vapor's
  `molar_mass`. For water this gives 1.31 / 2.48 / 3.73 kPa at 283.15 / 293.15 / 300 K against IAPWS-95
  1.23 / 2.34 / 3.54 kPa (Wagner & Pruß 2002, *J. Phys. Chem. Ref. Data* 31:387): +5 to +7 %, exact at
  373 K. A pair's threshold is (1 − χ_pair)·T_data + χ_pair·T_sat(p): water boils at T_sat(P_atm + P), its
  vapor condenses at the dew point T_sat(p_v); ice↔water (χ_pair 0) stays at 273 K.
- **Exchange conductance** (engine knob `g_vap`, proposal §12 Q6, not material data): Penman's still-air
  wind function f(u = 0) = 2.6 mm·day⁻¹·kPa⁻¹ (Penman 1948, *Proc. R. Soc. A* 193:120; Shuttleworth 1993,
  *Handbook of Hydrology* ch. 4) = 3.0e-8 kg·m⁻²·s⁻¹·Pa⁻¹. A 300 K lake under dry air loses 1.1e-4
  kg·m⁻²·s⁻¹ (≈ 270 W/m² of latent heat), a real warm-water evaporation rate. The engine ships the knob at
  0 until humid air can flow with dry air (#41); the face exchange is then switched on at this value.
- The other vapor pairs (iron, copper, gold, silica, carbon) use the same curve; their p_sat at ordinary
  temperatures is negligible, so they only exchange when hot.

## Not modeled

- **Chemistry, not phase change:** calcite/dripstone (calcination → CaO + CO2), clay/brick/terracotta/
  concrete (dehydration, vitrification), wood/coal/wool (pyrolysis, combustion), soils and mud (drying).
- **Rock-melt boiling:** basaltic/andesitic/felsic melts vaporize incongruently (no single boiling point).
- **Liquid air** (condenses 79 K): would shift air's enthalpy zero, breaking saved worlds; not added.
- **Cooling-rate products:** quenched felsic melt is obsidian, fast-cooled silica is glass; a phase
  pair freezes to one canonical solid.
- **No source found (left on the fallback):** beeswax cp (honeycomb_block, candles), diamond's total
  emissivity (IR-transparent), watermelon/pumpkin bulk density and conductivity (their ASHRAE freezing data
  exists), molten magnetite density/viscosity (raw iron doesn't melt), coral blocks, sponge, moss,
  mushroom blocks, cactus, dried kelp, bell (bronze), chests.
- **Waterlogged blocks with no open volume** (fences, walls, panes, chains, lanterns, signs, trapdoors,
  ladders, leaves, sea pickles…) still ignore their water: their rows carry no ρ_p yet. Stairs, slabs,
  rails, plants and coral hold theirs (above).
- **A flooded non-waterloggable host** (rail or flower in a river) keeps the block and hides the water
  (vanilla cannot draw it); it is never broken.
- **Fictional blocks:** `glowstone`, `redstone_block`, `end_rod`, `nether_portal` keep placeholder data;
  netherrack, end stone, soul sand/soil and nether stems fall back to `generic_solid`.
- **Deliberate:** `bedrock`, `tinted_glass` (k = 0 insulators).
- **Blockstate-dependent:** campfire (lit/unlit), double slabs, walls, fences, leaves, plants.
- **Engine:** sand/gravel/mud stay rigid (no friction-angle law); different melts don't mix.
