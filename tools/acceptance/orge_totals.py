#!/usr/bin/env python3
"""Independent reader of ORGE region files (world/orge/r.<rx>.<rz>.orge), formats v5 and v6.

Issue #31 acceptance: a pre-change (v5) world must load on the multi-material build with grand mass
and energy unchanged. The server logs its own before/after books (RegionStore.MigrationAudit); this
script re-derives the same totals straight from the bytes with no ORGE code, so the two can be compared.

    python3 orge_totals.py <world/orge dir> [--columns cx,cz;cx,cz...] [--cell x,y,z ...]

Prints, per region file, the column count by format version, then the grand mass / E / per-species
mass over every column (or only the listed columns). --cell dumps one cell's composition (all K slots).
Sums run cell by cell, slot 0 then slots 1..3, in double — the same order as ColumnTotals.
"""
import struct
import sys
import zlib
from pathlib import Path

CELLS = 4096
SLOTS = 4
VACUUM = "orge:vacuum"


class Reader:
    def __init__(self, b):
        self.b, self.o = b, 0

    def take(self, n):
        v = self.b[self.o:self.o + n]
        self.o += n
        return v

    def u8(self):
        return self.take(1)[0]

    def i16(self):
        return struct.unpack(">H", self.take(2))[0]

    def i32(self):
        return struct.unpack(">i", self.take(4))[0]

    def f32(self):
        return struct.unpack(">f", self.take(4))[0]

    def utf(self):
        return self.take(self.i16()).decode("utf-8")

    def floats(self, n):
        raw = zlib.decompress(self.take(self.i32()))
        assert len(raw) == 4 * n
        return struct.unpack(">%df" % n, raw)

    def chars(self, n):
        raw = zlib.decompress(self.take(self.i32()))
        assert len(raw) == 2 * n
        return struct.unpack(">%dH" % n, raw)


def read_section(r, version):
    s = {}
    form = r.u8()
    if form == 0:
        e, m = r.f32(), r.f32()
        s["E"], s["m"] = [e] * CELLS, [m] * CELLS
    elif form == 1:
        s["E"], s["m"] = r.floats(CELLS), r.floats(CELLS)
    else:
        raise ValueError("unknown form %d" % form)
    s["palette"], s["ix"] = None, None
    if r.u8() == 1:
        s["palette"] = [r.utf() for _ in range(r.i16())]
        s["ix"] = r.chars(CELLS)
    if r.u8() == 1:
        r.floats(CELLS), r.floats(CELLS), r.floats(CELLS)   # momentum (not a book here)
    if r.u8() == 1:
        r.floats(CELLS)                                     # P
    s["slot_ix"], s["slot_m"] = None, None
    if version >= 6 and r.u8() == 1:
        s["slot_ix"] = r.chars((SLOTS - 1) * CELLS)
        s["slot_m"] = r.floats((SLOTS - 1) * CELLS)
    return s


def cell_slots(s, i):
    """[(species, kg)] for the K slots of cell i, slot 0 first."""
    pal = s["palette"]
    out = [(pal[s["ix"][i]] if pal else VACUUM, s["m"][i])]
    for k in range(1, SLOTS):
        q = (k - 1) * CELLS + i
        if s["slot_ix"] is None:
            out.append((VACUUM, 0.0))
        else:
            out.append((pal[s["slot_ix"][q]], s["slot_m"][q]))
    return out


def read_region(path):
    """{(cx, cz): (version, {sectionY: section})} for every column in one region file."""
    b = path.read_bytes()
    magic, ver = struct.unpack(">ii", b[0:8])
    assert magic == 0x4F524742 and ver == 2, path
    rx, rz = (int(v) for v in path.name.split(".")[1:3])
    cols = {}
    loc = struct.unpack(">1024i", b[4096:8192])
    for slot, off in enumerate(loc):
        if not off:
            continue
        n = struct.unpack(">i", b[off * 4096:off * 4096 + 4])[0]
        r = Reader(b[off * 4096 + 4:off * 4096 + 4 + n])
        version = r.u8()
        sections = {}
        for _ in range(r.i16()):
            sy = r.i32()
            sections[sy] = read_section(r, version)
        cols[(rx * 32 + slot % 32, rz * 32 + slot // 32)] = (version, sections)
    return cols


def main(argv):
    root = Path(argv[1])
    only = None
    cells = []
    for a in argv[2:]:
        if a.startswith("--columns="):
            only = {tuple(int(v) for v in c.split(",")) for c in a.split("=", 1)[1].split(";") if c}
        elif a.startswith("--cell="):
            cells.append(tuple(int(v) for v in a.split("=", 1)[1].split(",")))
    world = {}
    for f in sorted(root.glob("r.*.orge")):
        cols = read_region(f)
        by_ver = {}
        for v, _ in cols.values():
            by_ver[v] = by_ver.get(v, 0) + 1
        print("%s: %d columns, by format version %s" % (f.name, len(cols), dict(sorted(by_ver.items()))))
        world.update(cols)
    mass = energy = 0.0
    species = {}
    n = 0
    for key in sorted(world):
        if only is not None and key not in only:
            continue
        n += 1
        for sy in sorted(world[key][1]):
            s = world[key][1][sy]
            for i in range(CELLS):
                for sp, m in cell_slots(s, i):
                    mass += m
                    species[sp] = species.get(sp, 0.0) + m
                energy += s["E"][i]
    print("columns %d: mass %.6f kg, E %.6f J" % (n, mass, energy))
    for sp in sorted(species):
        if species[sp] != 0.0:
            print("  %s = %.6f kg" % (sp, species[sp]))
    for x, y, z in cells:
        col = world.get((x >> 4, z >> 4))
        s = col[1].get(y >> 4) if col else None
        if s is None:
            print("cell (%d,%d,%d): no stored section" % (x, y, z))
            continue
        i = (x & 15) | ((y & 15) << 4) | ((z & 15) << 8)
        comp = ", ".join("%s %.4f kg" % (sp, m) for sp, m in cell_slots(s, i) if m != 0.0)
        print("cell (%d,%d,%d) v%d: E %.1f J; %s" % (x, y, z, col[0], s["E"][i], comp or "empty"))


if __name__ == "__main__":
    main(sys.argv)
