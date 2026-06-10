#!/usr/bin/env python3
"""Numerical verification of the Engine-B doc-audit findings.

Every input number below comes ONLY from the allowed docs:
  - LUT min/default/max: water 125/1000/1000, lava 400/3100/3100, air 1.0/1.2/1000,
    stone 2500/2500/2500, steam 0.6/0.6/0.6   (00-MASTER-RULES)
  - g=10, dx=V=A=1, dt in [0.25, 0.5]          (00-MASTER-RULES, spec §12)
  - probe data: H=8 locks t~1500 at 79953; H=16 locks t~6000 at 159999.7;
    B-level t~300; stair profile 0,20000,20000,40000,...; swap_kv=150, swap_kc=3;
    LEVEL_MOB=0.01, p_ac_scale=2000           (spec 2026-06-09 §12, §8, §9.1)
Real-world constants (CRC-handbook level) are used ONLY in the 'theory vs reality'
section and are marked REAL.
"""
import math

g, dx, V, A, dt = 10.0, 1.0, 1.0, 1.0, 0.5
LUT = {  # min, default(=rest), max  [kg per 1 m^3 cell]
    'water': (125, 1000, 1000), 'lava': (400, 3100, 3100),
    'air': (1.0, 1.2, 1000), 'stone': (2500, 2500, 2500), 'steam': (0.6, 0.6, 0.6),
}
P = lambda s: print(s)
HR = lambda t: print(f"\n{'='*78}\n{t}\n{'='*78}")

# ---------------------------------------------------------------- 1
HR("1. CONVERGENCE ORDER: doc claims 'O(H) ticks'; probe data H=8->t1500, H=16->t6000")
t8, t16 = 1500.0, 6000.0
exp = math.log(t16/t8)/math.log(2)
C = t8/8**exp
P(f"  measured exponent  log(t16/t8)/log(2)         = {exp:.3f}   (O(H) would be 1.0)")
P(f"  fit t = C*H^2 with C = {C:.2f} ticks  ->  t(8)={C*64:.0f}, t(16)={C*256:.0f}  (matches both probes)")
P(f"  O(H) prediction from H=8 point: t(16) = {t8*2:.0f}  vs recorded {t16:.0f}  -> off 2x")
P(f"  extrapolation H=32: {C*1024:.0f} ticks = {C*1024*dt/3600:.2f} sim-hours at dt={dt}")
P(f"  per-cell cost: {t8/8:.0f} -> {t16/16:.0f} ticks/cell (doubles; no O(H) constant absorbs that)")
P("  VERDICT: data are exactly O(H^2); 'O(H)/~H ticks' claim is numerically false. CONFIRMED")

# ---------------------------------------------------------------- 2
HR("2. A-TERM TARGET: Spec S3 says A = rho*g*k*dx ('k cells ABOVE'); what did probes lock at?")
rho_w = LUT['water'][1]/V
for H, locked in ((8, 79953.0), (16, 159999.73)):
    A_doc = rho_w*g*(H-1)*dx       # base cell has k = H-1 cells above it
    full  = rho_w*g*H*dx           # bottom-FACE pressure (k+1 cell-weights)
    P(f"  H={H}: doc formula A=rho*g*(H-1)*dx = {A_doc:8.0f} | probe locked {locked:9.1f} | rho*g*H = {full:8.0f}")
P("  -> probes lock at rho*g*H = rho*g*(k+1)*dx, one full cell-weight (10000 Pa) above the")
P("     documented 'weight above' formula; the 'exact discretization' label and the")
P("     verification target disagree by rho*g*dx. CONFIRMED (off-by-one-cell-weight)")

# ---------------------------------------------------------------- 3
HR("3. FACE-FORCE FACTOR 2: law literal (P_self-P_nbr per face) vs spec face-average")
# recorded converged H=16 stair profile (spec S12), index 0 = top cell
p = [0.0]
for k in range(1, 16):
    p.append(p[-1] + (20000.0 if k % 2 == 1 else 0.0))
P(f"  stair profile (top->bottom): {[int(x) for x in p]}")
m_g = rho_w*g  # weight of one full cell = 10000 N
ok_avg, ok_law = True, True
for k in range(1, 15):
    F_avg = 0.5*(p[k+1]-p[k-1])*A          # spec/engine: face-average operator
    F_law = (p[k+1]-p[k-1])*A              # law literal: sum of per-face differences
    if abs(F_avg - m_g) > 1e-9: ok_avg = False
    if abs(F_law - m_g) > 1e-9: ok_law = False
P(f"  weight of one cell m*g = {m_g:.0f} N")
P(f"  face-average operator: net upward force = {0.5*(p[2]-p[0]):.0f} N on every interior cell -> {'BALANCES' if ok_avg else 'NO'}")
P(f"  law-literal operator:  net upward force = {(p[2]-p[0]):.0f} N = 2*m*g            -> {'balances' if ok_law else 'DOES NOT BALANCE (2x overshoot, column accelerates)'}")
P("  -> the converged profile that satisfies the engine's face-average operator violates the")
P("     law's literal per-face sum by exactly 2x. The law's rest state would need a half-slope")
P("     profile (rho*g*d/2). The two statements are different operators. CONFIRMED")

# ---------------------------------------------------------------- 4
HR("4. CHECKERBOARD CEILING LABEL: doc says amplitude saturates at 'rho*g*dx ~ 20000'")
P(f"  rho*g*dx = {rho_w*g*dx:.0f} Pa;  recorded ceiling = 20000 Pa = 2*rho*g*dx")
P("  -> the recorded number is right, its LABEL is off by 2x. CONFIRMED (mislabel)")

# ---------------------------------------------------------------- 5
HR("5. CONDUCTION STABILITY (explicit Euler, 6-face stencil): dt_max = m*cp*dx/(6*k_face*A)")
# REAL cp/k since the docs' LUT thermal numbers are not in the allowed excerpts:
mats = {  # REAL: cp [J/kgK], k [W/mK]
    'water': (4186, 0.6), 'air': (1005, 0.026), 'stone(granite)': (790, 2.5), 'lava(basalt melt)': (1200, 1.5),
}
for name, (cp, k) in mats.items():
    mfull = {'water':1000,'air':1.2,'stone(granite)':2500,'lava(basalt melt)':3100}[name]
    P(f"  {name:18s} full cell m={mfull:6.1f} kg: dt_max = {mfull*cp*dx/(6*k*A):12.1f} s  (dt=0.5 SAFE)")
m_thin, cp_w, k_w = 1e-6, 4186, 0.6
dtmax_thin = m_thin*cp_w*dx/(6*k_w*A)
amp = 1 - 6*k_w*A*dt/(m_thin*cp_w*dx)
P(f"  advection-thinned water cell m=1e-6 kg: dt_max = {dtmax_thin:.2e} s  << dt=0.5")
P(f"    explicit-update amplification factor 1 - 6k*dt/(m*cp) = {amp:.3e}  (|.|>1 -> divergent oscillation)")
P("  -> a ~1e-6 kg cell diverges with amplification ~ -4e5 per tick: EXACTLY the historical")
P("     temp-ghost bug class. No allowed doc states any bound or clamp. CONFIRMED (critical)")

# ---------------------------------------------------------------- 6
HR("6. EOS / CHI SINGULARITIES at the shipped LUT (chi = (max-default)/(max-min))")
for n, (mn, df, mx) in LUT.items():
    chi = "0/0 UNDEFINED" if mx == mn else f"{(mx-df)/(mx-mn):.5f}"
    comp = "DIV-BY-ZERO (max==default)" if mx == df else "finite"
    P(f"  {n:6s} chi = {chi:>14s} | compression branch /(max-default): {comp}")
P("  -> compression branch divides by zero for water/lava/stone/steam; chi is 0/0 for")
P("     stone & steam. Law #9's 'compresses via EOS' is non-evaluable for exactly the")
P("     incompressible materials it is meant to cover. CONFIRMED")

# ---------------------------------------------------------------- 7
HR("7. SWAP BARRIER vs REAL VISCOSITIES: R = swap_kv*sqrt(mu_i+mu_j) + swap_kc*min(minMass)*g")
swap_kv, swap_kc = 150.0, 3.0
mu_real = {'water': 1e-3, 'lava_low': 1e2, 'lava_mid': 1e3, 'lava_high': 1e5}  # REAL Pa*s ranges
fd = (LUT['lava'][1]-LUT['water'][1])*g
P(f"  lava-over-water buoyant driver forceDiff = (3100-1000)*10 = {fd:.0f} N")
for tag in ('lava_low','lava_mid','lava_high'):
    mu = mu_real[tag] + mu_real['water']
    R = swap_kv*math.sqrt(mu) + swap_kc*min(LUT['lava'][0],LUT['water'][0])*g
    P(f"  mu_lava={mu_real[tag]:8.0e} Pa*s: R_pair = {R:9.0f} N -> {'SWAPS (driver wins)' if fd > R else 'BLOCKED  <-- physically wrong permanent freeze'}")
P("  -> with REAL lava viscosity 1e2..1e3 Pa*s the pair swaps; at the real upper range")
P("     (rhyolitic ~1e5 Pa*s) the proxy PERMANENTLY BLOCKS a genuinely buoyant overturn.")
P("     The docs' 'buoyancy >> viscosity term for every shipped pair' is true only for an")
P("     undocumented viscosity range — unverifiable from the docs, falsified at real upper-range values.")

# ---------------------------------------------------------------- 8
HR("8. B (own_weight_head) PARTIAL-FILL FACTOR: [1000|500] walled pair")
m1, m2 = 1000.0, 500.0
true_dP = g*(m1-m2)/A           # true settled floor-pressure difference
doc_dP  = (m1-m2)/V*g*0.5*dx    # doc's homogenized rho*g*dx/2 difference
P(f"  true floor-pressure difference  g*dm/A      = {true_dP:.0f} Pa")
P(f"  doc  own_weight_head difference dm*g*dx/2V  = {doc_dP:.0f} Pa  (= half)")
P("  -> driving force is 2x low (rate error only); fixed point m1==m2 identical: equilibrium")
P("     [750|750] correct. CONFIRMED as labeled (minor, rate-scale only)")

# ---------------------------------------------------------------- 9
HR("9. MULTI-FACE DONOR BUDGET: per-face clamps compose to negative donor mass")
D, mn = 300.0, 125.0   # water donor with 4 eligible lower neighbours
per_face_max = D - mn  # each face ALONE satisfies D-f >= min
P(f"  donor D={D:.0f}, min={mn:.0f}: single-face rule allows f <= {per_face_max:.0f} per face")
tot = 4*150.0
P(f"  4 faces each taking f=150 (each individually legal): total outflow {tot:.0f} > D={D:.0f}")
P(f"  donor ends at {D-tot:.0f} kg  -> negative; a >=0 clamp/void-guard then FABRICATES {tot-D:.0f} kg")
P("  -> no allowed doc defines the multi-face composition rule. CONFIRMED (critical)")

# ---------------------------------------------------------------- 10
HR("10. PHASE RELABEL 'KEEPING E' with cp_old != cp_new (REAL cp: water 4186, steam 2080, ice 2108)")
cp_w2, cp_s, cp_i = 4186.0, 2080.0, 2108.0
T0 = 372.0  # steam cell cooling through its condensation threshold ~373 K
T_after = T0*cp_s/cp_w2
P(f"  steam at T={T0:.0f} K relabels -> water keeping E: T' = T*cp_steam/cp_water = {T_after:.1f} K")
P(f"  -> condensing steam lands at {T_after:.0f} K (-{T0-T_after:.0f} K jump), far below water's")
P(f"     freeze threshold (273 K) -> immediately relabels again to ice: a 2-step cascade")
T1 = 374.0
P(f"  water at T={T1:.0f} K relabels -> steam keeping E: T' = T*cp_water/cp_steam = {T1*cp_w2/cp_s:.0f} K (+{T1*cp_w2/cp_s-T1:.0f} K)")
P("  -> boiling water instantly reads ~753 K. With real cp ratios the threshold jump is 2x in")
P("     either direction; whether it re-crosses a return threshold depends on LUT values the")
P("     docs never constrain. No doc states a hysteresis/latent-heat guard. CONFIRMED")

# ---------------------------------------------------------------- 11
HR("11. LATENT HEAT MAGNITUDE (absent from the law's fixed LUT schema)")
P(f"  REAL water: sensible 0->100 C = cp*dT = {4186*100/1e3:.0f} kJ/kg;")
P(f"  latent fusion = 334 kJ/kg ({334/418.6:.1f}x the 0->80C sensible); vaporization = 2256 kJ/kg")
P(f"  = {2256/418.6:.1f}x the ENTIRE liquid sensible range. Phase change at constant E is")
P("  energy-free in the model -> boiling/freezing fronts propagate with zero thermal cost.")
P("  CONFIRMED physics gap (major): no latent-heat field exists in the frozen schema.")

# ---------------------------------------------------------------- 12
HR("12. PRESSURELESS COMPRESSED GAS (in-game air over-accumulation, known bug #2)")
chi_air = (LUT['air'][2]-LUT['air'][1])/(LUT['air'][2]-LUT['air'][0])
m_obs = 835.0  # documented in-game observation, sealed wells
ratio = m_obs/LUT['air'][1]
p_real = ratio*101325.0  # REAL: isothermal ideal gas at 696x rest density
P(f"  chi_air = {chi_air:.5f} -> (1-chi) = {1-chi_air:.1e}: A-relaxation suppressed ~{1/(1-chi_air):.0f}x")
P(f"  B = 0 for gas (chi>0.999); p_eos declared inert -> compressed air pushback ~ 0")
P(f"  observed in-game packing {m_obs:.0f} kg/cell = {ratio:.0f}x rest density")
P(f"  REAL ideal-gas pressure at {ratio:.0f}x, isothermal: {p_real/1e6:.1f} MPa (~{p_real/101325:.0f} atm)")
P("  -> the model gives a ~700x-compressed gas essentially ZERO pushback where reality gives")
P("     ~70 MPa. This is the documented mechanism behind the open air-over-accumulation bug.")

# ---------------------------------------------------------------- 13
HR("13. THEORY vs REALITY: magnitudes & timescales the model gets right/wrong")
P(f"  hydrostatic 8 m water: model rho*g*H = {rho_w*g*8/1e3:.0f} kPa vs REAL 9.81*1000*8 = {9810*8/1e3:.1f} kPa  -> realistic (g=10 rounding)")
P(f"  LUT densities vs REAL: water 1000 (real 998), air 1.2 (real 1.204), stone 2500 (granite 2650-2750),")
P(f"    lava 3100 (basalt melt 2600-2800; 3100 is dense), steam 0.6 (real 0.59 at 100C) -> all realistic")
c_real = 1480.0
t_model = t8*dt
P(f"  pressure-equilibration 8 m: REAL acoustic H/c = {8/c_real*1e3:.1f} ms; model {t8:.0f} ticks*{dt}s = {t_model:.0f} sim-s")
P(f"    -> model is ~{t_model/(8/c_real):.0e}x slower than reality (gameplay-visible: deep pools read wrong pressure for minutes)")
t_level = 300*dt
P(f"  leveling [1000|500]: model ~{t_level:.0f} sim-s to 1e-3 precision; REAL adjacent 1 m columns slosh-level in ~1-2 s")
P(f"    (shallow-water timescale ~ sqrt(L/g) with damping) -> model ~100x slower, monotonic (no slosh)")
v_swap = dx/dt
P(f"  swap sink rate: 1 cell/tick = {v_swap:.0f} m/s at dt=0.5 but {dx/0.25:.0f} m/s at dt=0.25 -> dt-DEPENDENT")
P(f"    (REAL dense-blob sinking terminal velocity ~ O(1) m/s: right order, wrong dt-scaling)")
P(f"  conduction across 1 m water: REAL diffusion time L^2/alpha = {1.0/ (0.6/(1000*4186)):.2e} s (~80 days)")
P(f"    -> per-second conduction steps are far inside reality's regime; conduction fidelity is fine")
P(f"  air-launch hazard arithmetic (S3.1): impulse 5000*A*dt = {5000*dt:.0f} N*s on 1.2 kg -> dv = {5000*dt/1.2:.0f} m/s (~Mach 3)")
P("    -> the docs' own guard-rail number is correct; any one-sided vertical head on light gas is catastrophic")

print("\nDONE — all quantitative checks executed.")
