# Procedural Catalog Algorithm — Authoritative Shared Definition (E8-03)

> **This is the single authoritative definition of the procedural star catalog.**
> The Java server (`backend/galaxy`) and the TypeScript client
> (`frontend/.../galaxy/catalog-generator.ts`) BOTH implement exactly this
> algorithm with exactly these constants, so they derive **the same star from the
> same `(gameSeed, cell)`** without shipping any data (architecture
> `02-galaxy-scale.md` section 2; `procedural-galaxy` skill: "generation constants
> live in shared config; never fork them between client and server").
>
> A Java source file cannot be imported by TypeScript and vice-versa, so this
> document is the shared source of truth. Any change here MUST be applied to both
> implementations and the cross-check golden fixture re-pinned (see section 8).
> E8-04 (tile service) and E8-05 (client viewport fetch) build on this contract.

## 1. Scope and the O(visible) guarantee

The catalog is a pure function `(gameSeed, cell) -> Star[]`. The galaxy is diced
into a fixed square grid of cells of side `CELL_SIZE` (galaxy units). Generation
is **per-cell and self-contained**: materialising one cell reads only that cell's
integer address and the seed; it never touches, iterates, or depends on any other
cell, and nothing is stored. Therefore the cost of rendering a viewport is
`O(cells overlapping the viewport)` = `O(visible)`, never `O(catalog)`. A galaxy
of billions of potential stars costs nothing until a cell is asked for.

A fully-derived catalog star carries: stable `id`, position `(x, y)`, spectral
class, relative brightness (luminosity), relative size (radius). Planet rosters
are derived from the same `id` by the same algorithm (section 6) but are
zoom-gated detail, not part of the star-list parity proof.

## 2. Hashing — SplitMix64 (64-bit, bit-exact on both sides)

All randomness is closed-form hashing of `(gameSeed, coords, salt)`. No RNG
object, no wall-clock, no `Math.random`. The mixer is the SplitMix64 finaliser.

Constants (64-bit, two's-complement):

```
GOLDEN = 0x9E3779B97F4A7C15
MIX_A  = 0xBF58476D1CE4E5B9
MIX_B  = 0x94D049BB133111EB
```

`mix(z)` — unsigned 64-bit arithmetic; `>>>` is logical (unsigned) shift:

```
mix(z):
  z = (z XOR (z >>> 30)) * MIX_A      // multiply truncated to 64 bits
  z = (z XOR (z >>> 27)) * MIX_B
  return z XOR (z >>> 31)
```

`combine(v0, v1, ...)` folds an ordered argument list. Argument order and
position both matter:

```
combine(values...):
  h = GOLDEN
  for each v in values:
    h = (h + GOLDEN)          mod 2^64
    h = mix(h XOR mix(v))
  return mix(h)
```

`toUnitDouble(hash) -> [0,1)` uses the top 53 bits (double mantissa width):

```
toUnitDouble(hash) = (hash >>> 11) * 2^-53
```

`unit(baseHash, salt) = toUnitDouble(combine(baseHash, salt))`.

> **Java vs TS bit-exactness.** Java `long` is signed 64-bit two's-complement
> with wrapping multiply. JS `number` is a float64 with only 53 safe integer
> bits, so the TS port performs ALL hashing in `BigInt` masked to 64 bits
> (`& 0xFFFFFFFFFFFFFFFFn`), reproducing Java's wrap-around exactly. Only the
> final `toUnitDouble` leaves BigInt for `number`. This is the crux of parity.

### 2.1 Signed coordinate inputs

Cell coordinates and salts are passed to `combine` as signed 64-bit values. In
TS, integer inputs are converted to their unsigned two's-complement 64-bit form
before masking (`BigInt.asUintN(64, BigInt(v))`), matching how Java sign-extends
an `int`/`long` into the 64-bit mix.

## 3. Local salts (append-only ordinals)

Each derived quantity draws an independent decorrelated stream via a salt. The
salt value mixed in is `0x51A1 + ordinal`. Ordinals are part of the determinism
contract — append only, never reorder.

```
0  CELL_COUNT       candidate count for a cell
1  STAR_X           candidate x-offset within cell
2  STAR_Y           candidate y-offset within cell
3  DENSITY_ACCEPT   density rejection roll
4  STAR_ID          accepted star's stable id seed
5  SPECTRAL_CLASS   spectral class roll
6  BRIGHTNESS       brightness roll within class band
7  STAR_SIZE        size roll within class band
8  PLANET_COUNT     planet count roll
9  BIOME            planet biome roll
10 PLANET_SIZE      planet size roll
11 SLOT_COUNT       planet slot-count roll
12 LANE_TIEBREAK    (E2-03, not catalog)
13 HOME_TIEBREAK    (E2-04, not catalog)
```

## 4. Galaxy shape — density field

`densityAt(x, y)` returns a non-negative relative weight; `0` outside the disc.

```
r = hypot(x, y)
if r > R_MAX: return 0
radialEnvelope = exp(-r / DISC_SCALE_LENGTH)
arm   = armStrength(x, y, r)
bulge = BULGE_WEIGHT * exp(-(r/BULGE_RADIUS)^2)
weight = HALO_FLOOR + radialEnvelope * ARM_WEIGHT * arm + bulge
```

`armStrength(x, y, r)`:

```
if r < 1e-6: return 1.0
phase   = WIND * log(max(r,1)/R_MAX * ARM_LOG_SCALE + ARM_LOG_BIAS)
theta   = atan2(y, x)
spacing = 2*PI / ARMS
delta   = theta - phase
delta   = delta - spacing * floor(delta/spacing + 0.5)   // wrap to nearest arm
return exp(-(delta*delta) / (2 * ARM_HALF_WIDTH^2))
```

> All trig/log/exp use IEEE-754 double `Math.*`. Java `Math` and JS `Math` agree
> to full double precision for `log/exp/hypot/atan2/floor` on these inputs, so the
> accept/reject decision is identical. `Math.hypot` is used identically on both
> sides.

## 5. Star placement — `generate(gameSeed, cell)`

```
cellHash   = combine(gameSeed, cell.x, cell.y)
candidates = floor(unit(cellHash, CELL_COUNT) * (MAX_CANDIDATES_PER_CELL + 1))
ox = cell.x * CELL_SIZE ;  oy = cell.y * CELL_SIZE
out = []
for i in 0 .. candidates-1:
  candHash = combine(cellHash, i)
  fx = unit(candHash, STAR_X) ;  fy = unit(candHash, STAR_Y)
  x  = ox + fx * CELL_SIZE   ;  y  = oy + fy * CELL_SIZE
  accept = densityAt(x, y) / DENSITY_PEAK
  roll   = unit(candHash, DENSITY_ACCEPT)
  if roll >= accept: continue            // rejected by density field
  id = combine(candHash, STAR_ID)
  out.push({ id, x, y })
return out      // ordering = candidate index order (stable)
```

`DENSITY_PEAK = HALO_FLOOR + ARM_WEIGHT + BULGE_WEIGHT + 0.05` (normaliser so
`accept` is in `[0,1)`).

## 6. Star attributes — `system(gameSeed, id)`

```
base = combine(gameSeed, id)
spectral   = spectralFromRoll(unit(base, SPECTRAL_CLASS))
brightness = lerp(spectral.minB, spectral.maxB, unit(base, BRIGHTNESS))
size       = lerp(spectral.minSize, spectral.maxSize, unit(base, STAR_SIZE))
```

`spectralFromRoll(roll)` — weighted CDF in declaration order O..M, total = 1000:

```
Class  weight  [minB,maxB]   [minSize,maxSize]
O        1     12.0,30.0     6.0,10.0
B       12      5.0,12.0     3.0, 6.0
A       30      2.5, 5.0     1.6, 3.0
F       60      1.3, 2.5     1.1, 1.6
G      100      0.7, 1.3     0.9, 1.1
K      220      0.3, 0.7     0.6, 0.9
M      577     0.05, 0.3     0.3, 0.6
```

`target = roll * 1000`; walk classes accumulating weight; first class where
`target < acc` wins; fallthrough yields M (float guard). `lerp(lo,hi,t) =
lo + (hi-lo)*t`.

Planet rosters (zoom-gated, not in the parity fixture): `planetCount` uniform in
`[MIN_PLANETS, MAX_PLANETS]`; per-planet biome (weighted `BIOME_WEIGHTS`), size
(uniform over 4 sizes), slots (uniform in the size band). See `SystemGenerator`.

## 7. Constants (verbatim, both sides)

```
R_MAX                   = 1000.0
ARMS                    = 4
WIND                    = 2.7
ARM_LOG_SCALE           = 4.0
ARM_LOG_BIAS            = 1.0
ARM_HALF_WIDTH          = 0.32
ARM_WEIGHT              = 1.0
BULGE_RADIUS            = 180.0
BULGE_WEIGHT            = 1.4
HALO_FLOOR              = 0.06
DISC_SCALE_LENGTH       = 420.0
CELL_SIZE               = 50.0
MAX_CANDIDATES_PER_CELL = 12
DENSITY_PEAK            = HALO_FLOOR + ARM_WEIGHT + BULGE_WEIGHT + 0.05 = 2.51
SALT_BASE               = 0x51A1
SPECTRAL_TOTAL_WEIGHT   = 1000
```

The Java side is `GalaxyConstants` / `SpectralClass`; the TS side is
`CATALOG_CONSTANTS` in `catalog-generator.ts`. They MUST stay equal.

## 8. Parity proof (cross-check)

A golden fixture `docs/specs/fixtures/catalog-parity.json` lists, for several
`(seed, cellX, cellY)` triples, the exact emitted stars
`{ id, x, y, spectral, brightness, size }`.

- **Java** `CatalogParityFixtureTest` regenerates each triple and asserts the
  emitted stars equal the fixture, and prints the fixture if it is missing/edited
  so it can be re-pinned.
- **TS** `catalog-generator.spec.ts` loads the same fixture and asserts the client
  generator produces byte-identical values (`id` compared as a decimal string
  since it is a 64-bit value beyond JS safe-integer range).

Identical output for identical `(seed, coords)` on both sides means the client can
render scenery stars it was never sent. Generating a cell touches no other cell,
which gives O(visible). Both properties are asserted by the respective test
suites.
