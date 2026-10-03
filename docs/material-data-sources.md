# Material data sources

Every `data/orge/orge/materials/<id>.json` is named after an existing vanilla block id (guarded by
`AllMaterialsLoadTest.everyMaterialUsesAVanillaBlock`; `steam` is the one exception, rendered as air).
Values are measured room-temperature data, 1 m³ cell ⇒ `default_mass` = bulk density.
`thermal_expansion` is volumetric (3 × linear). `yield_stress` = uniaxial compressive strength × 1 m².

| Group | Blocks | Values / source |
|---|---|---|
| Stone-made | cobblestone, stone bricks, smooth stone, stone ores | `stone.json` verbatim (granite, melts → lava) |
| Granite | granite, polished | k 2.8, c 790, 2700; UCS 140 MPa; μ 1e21 (crust 1e19–1e24) |
| Diorite / andesite | + polished | k 2.3 / 2.1, c 800 / 840, 2850 / 2650; UCS 180 / 140 MPa |
| Slate | deepslate family + deepslate ores | k 2.0, c 760, 2750; UCS 140 MPa |
| Tuff | tuff family | welded tuff k 0.6, c 900, 1800; UCS 20 MPa |
| Calcite / limestone | calcite, dripstone_block | k 3.6 / 2.5, c 820 / 840, 2710 / 2600 |
| Basalt | basalt, smooth/polished, blackstone family, magma_block | k 1.7, c 840, 2900; UCS 200 MPa |
| Sandstone | all sandstone variants | k 2.4, c 920, 2300; UCS 60 MPa |
| Obsidian | obsidian, crying_obsidian | volcanic glass k 1.3, c 840, 2400 |
| Fired clay | bricks, terracotta (all) | clay brick k 0.7, c 840, 1900; UCS 20 MPa |
| Adobe | mud_bricks, packed_mud | k 0.6, c 900, 1600; UCS 2 MPa |
| Concrete | concrete ×16 / powder ×16 | k 1.7, c 880, 2400, UCS 30 MPa / dry cement 1500, k 0.3 |
| Soil | dirt, grass, podzol, mycelium, farmland, path | moist loam k 1.0, c 1480, 1500 |
| Mud / clay | mud, clay | wet k 1.4 / 1.3, c 2500 / 1380, 1700 / 1800 |
| Sand / gravel | sand, red_sand, gravel (+ suspicious) | dry k 0.27 / 0.7, c 830 / 840, 1600 / 1800 |
| Snow | snow_block 400, powder_snow 100 kg | k from Sturm et al. 1997 k(ρ); ice cp/latent; melts → water |
| Ice | packed_ice, blue_ice | `ice.json` verbatim (blue_ice keeps game pin at 250 K) |
| Wood | logs, wood, stripped, planks (9 species + bamboo) | USDA Wood Handbook FPL-GTR-282 ch.4: k(G, 12 % MC), cp(T, MC); air-dry density per species |
| Metals | iron, gold, copper (all ages, waxed) | CRC Handbook: k 80 / 318 / 401, c 449 / 129 / 385, ρ 7874 / 19300 / 8960; ε oxidized copper 0.78 |
| Minerals | coal_block (anthracite), quartz & amethyst blocks | k 0.26 / 7.7, c 1260 / 740, 1500 / 2650 |
| Glass | glass, stained ×16 | soda-lime k 1.0, c 840, 2500, ε 0.92 |
| Wool | wool ×16 | felt k 0.05, c 1360, 300 |
| Pinned sources | torch, lantern, fire, soul_fire | object mass/cp/k (wood stick 0.2 kg; ~1 kg iron lantern; flame = air at its T, ideal gas). Pinned T and ε are game rules, unchanged |

Not real-data (deliberate): `bedrock`, `tinted_glass` (k = 0 insulators); fictional blocks
(`glowstone`, `redstone_block`, `end_rod`, `nether_portal`, netherrack, end stone, soul sand…).
Not covered: partial blocks (slabs, stairs, walls, fences), leaves, plants; they fall back to `generic_solid`.

## Phase changes

Each melting solid has its own molten partner (`orge:molten_<solid>`, rendered as `minecraft:lava`; no
new block). One threshold T* both ways, so the chain-anchored relabel is E-exact and T-continuous
(law §6; guarded by `phaseSourcesShareTheirCanonicalPartnersCurve`). Liquid window min = ρ/8.

| Solid | T* (K) | L (J/kg) | Melt ρ / cp / μ (Pa·s) |
|---|---|---|---|
| granite | 1488 | 2.7e5 | 2300 / 1400 / 1e5 |
| diorite, andesite, deepslate | 1473 | 3.5e5 | 2450 / 1400 / 3.5e4 (andesitic) |
| tuff | 1173 | 2.7e5 | 2300 / 1400 / 1e8 (rhyolitic) |
| basalt, blackstone, magma_block | 1473 | 4.0e5 | 2700 / 1480 / 1e3 |
| sandstone, sand, quartz/amethyst (each its own melt) | 1986 | 1.6e5 (SiO2) | 2200 / 1430 / 1e7 |
| obsidian (glass, L = 0) | 1173 | 0 | 2300 / 1400 / 1e9 |
| glass (soda-lime softening point, L = 0) | 993 | 0 | 2400 / 1400 / 4e6 |
| iron | 1811 | 2.47e5 | 7030 / 824 / 5.5e-3 |
| gold | 1337 | 6.37e4 | 17360 / 149 / 5.1e-3 |
| copper (all ages) | 1358 | 2.09e5 | 8000 / 517 / 4.0e-3 |

Stone-made blocks melt into `orge:lava` (stone's chain); snow, packed and blue ice melt into water (ice's chain).
Sources: Lesher & Spera, *Thermodynamic and transport properties of silicate melts and magma*
(Encyclopedia of Volcanoes); melt viscosity tables (UMass Lowell petrology notes, Dingwell); CRC Handbook
and Assael et al. reference data for liquid Fe/Cu/Au.

No phase change (real process is chemistry, not melting): calcite/dripstone (calcination → CaO + CO2),
clay/brick/terracotta/concrete (dehydration, vitrification), wood/coal/wool (pyrolysis, combustion),
soils and mud (drying). Metals don't boil (no gas species).

Known gaps: sand/gravel/mud stay rigid (cohesionless granular flow needs a friction-angle law, not τ_y);
different molten species don't mix (immiscible, sorted by density).
