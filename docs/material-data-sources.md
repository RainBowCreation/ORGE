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

## Heat sources (pinned = burning fuel holds the flame temperature)

Wood/pitch flame 1300 K. A torch releases ~2 kW, ~25 % radiant, so its cell's effective emissivity is
ε = 500 W / (6 m² · σ · 1300⁴) = 5.1e-4 (torch, wall/soul torches, lantern, soul lantern). A fire block is
a ~1 m luminous flame, ε = 1 − exp(−κL), κ ≈ 1 m⁻¹ ⇒ 0.63; its gas is air at 1300 K (0.272 kg). Torch =
0.2 kg wood; lantern = ~1 kg sheet iron.

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

## Not modeled

- **Chemistry, not phase change:** calcite/dripstone (calcination → CaO + CO2), clay/brick/terracotta/
  concrete (dehydration, vitrification), wood/coal/wool (pyrolysis, combustion), soils and mud (drying).
- **Rock-melt boiling:** basaltic/andesitic/felsic melts vaporize incongruently (no single boiling point).
- **Liquid air** (condenses 79 K): would shift air's enthalpy zero, breaking saved worlds; not added.
- **Cooling-rate products:** quenched felsic melt is obsidian, fast-cooled silica is glass; a phase
  pair freezes to one canonical solid.
- **Fictional blocks:** `glowstone`, `redstone_block`, `end_rod`, `nether_portal` keep placeholder data;
  netherrack, end stone, soul sand/soil and nether stems fall back to `generic_solid`.
- **Deliberate:** `bedrock`, `tinted_glass` (k = 0 insulators).
- **Blockstate-dependent:** campfire (lit/unlit), double slabs, walls, fences, leaves, plants.
- **Engine:** sand/gravel/mud stay rigid (no friction-angle law); different melts don't mix.
