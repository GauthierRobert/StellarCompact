package com.stellarcompact.galaxy.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Java half of the E8-03 server/client parity cross-check.
 *
 * <p>For a fixed set of {@code (seed, cellX, cellY)} triples this regenerates the
 * catalog via {@link CatalogGenerator} and asserts the emitted stars are
 * byte-identical to the shared golden fixture
 * {@code docs/specs/fixtures/catalog-parity.json}. The TypeScript test
 * {@code frontend/.../catalog-generator.spec.ts} loads the SAME fixture and
 * asserts the client generator produces identical values - so the two suites
 * together prove "identical output for identical (seed, coords)" across
 * languages.
 *
 * <p>The fixture is authored by this test: when it is missing (or has been
 * deliberately deleted to re-pin after an intentional algorithm change) the test
 * writes the regenerated fixture and fails with a message, so a human re-runs and
 * both sides pick up the new golden values. A normal run is read-only.
 *
 * <p>This test does file I/O (allowed in test code) to read/write the fixture; the
 * generator under test stays pure. Doubles are serialised with
 * {@link Double#toString} so the value round-trips exactly and the TS side can
 * compare the same decimal; ids are serialised as decimal strings because they
 * are full 64-bit values beyond JS safe-integer range.
 */
class CatalogParityFixtureTest {

    /** The (seed, cellX, cellY) triples sampled across bulge, arms and halo. */
    private static final long[][] SAMPLES = {
            {0xC0FFEEL, 0, 0},      // bulge core - dense
            {0xC0FFEEL, 3, -2},     // mid disc
            {0xC0FFEEL, -7, 5},     // off-centre
            {0x1234L, 9, 9},        // outer, different seed
            {0xABCDEF01L, -4, -11}, // negative coords, third seed
    };

    @Test
    @DisplayName("server output matches the shared client/server golden fixture")
    void matchesSharedFixture() throws IOException {
        String generated = buildFixtureJson();
        Path fixture = fixturePath();

        boolean wroteMissing = false;
        if (!Files.exists(fixture)) {
            Files.createDirectories(fixture.getParent());
            Files.writeString(fixture, generated);
            wroteMissing = true;
        }

        String onDisk = Files.readString(fixture).replace("\r\n", "\n").strip();
        String want = generated.replace("\r\n", "\n").strip();

        // Always (re)emit the TS-importable mirror so the frontend parity test
        // sees the same canonical values without Node FS / out-of-src imports.
        writeTsMirror(generated);

        if (wroteMissing) {
            fail("Fixture was missing; wrote " + fixture
                    + " (+ the frontend TS mirror) - re-run the build to verify both "
                    + "server and client against it.");
        }
        if (!onDisk.equals(want)) {
            // Overwrite so an intentional algorithm change is easy to re-pin, but
            // still fail loudly so the change is never silent.
            Files.writeString(fixture, generated);
            fail("Catalog output changed vs fixture " + fixture
                    + ". If intentional, the fixture has been rewritten - re-run to confirm "
                    + "and re-run the TS parity test. If not, revert the generator change.");
        }
    }

    @Test
    @DisplayName("generating a cell does not depend on any other cell (O(visible))")
    void generationIsPerCellAndBounded() {
        long seed = 0xC0FFEEL;
        Cell target = new Cell(2, 2);
        List<CatalogStar> clean = CatalogGenerator.generateCell(seed, target);

        // Materialise a swathe of unrelated cells in between; the target must be
        // unchanged - proving the function reads only its own (seed, cell).
        for (int cx = -20; cx <= 20; cx++) {
            for (int cy = -20; cy <= 20; cy++) {
                List<CatalogStar> other = CatalogGenerator.generateCell(seed, new Cell(cx, cy));
                assertNotNull(other);
                // Each cell is bounded regardless of galaxy size.
                assertTrue(other.size() <= GalaxyConstants.MAX_CANDIDATES_PER_CELL,
                        "per-cell output must be bounded by MAX_CANDIDATES_PER_CELL");
            }
        }
        List<CatalogStar> again = CatalogGenerator.generateCell(seed, target);
        assertEquals(clean, again,
                "a cell's output must not depend on other cells being generated");
    }

    // --- fixture serialisation (mirrors the JSON the TS test parses) ---

    private static String buildFixtureJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_note\": \"AUTHORED BY CatalogParityFixtureTest. ")
          .append("Shared by server (Java) and client (TS) per docs/specs/")
          .append("procedural-catalog-algorithm.md section 8. Do not hand-edit.\",\n");
        sb.append("  \"cells\": [\n");
        for (int i = 0; i < SAMPLES.length; i++) {
            long seed = SAMPLES[i][0];
            int cx = (int) SAMPLES[i][1];
            int cy = (int) SAMPLES[i][2];
            List<CatalogStar> stars = CatalogGenerator.generateCell(seed, new Cell(cx, cy));
            sb.append("    {\n");
            sb.append("      \"seed\": ").append(seed).append(",\n");
            sb.append("      \"cellX\": ").append(cx).append(",\n");
            sb.append("      \"cellY\": ").append(cy).append(",\n");
            sb.append("      \"stars\": [");
            List<String> entries = new ArrayList<>(stars.size());
            for (CatalogStar s : stars) {
                entries.add(starJson(s));
            }
            if (entries.isEmpty()) {
                sb.append("]\n");
            } else {
                sb.append("\n");
                for (int j = 0; j < entries.size(); j++) {
                    sb.append("        ").append(entries.get(j));
                    sb.append(j < entries.size() - 1 ? ",\n" : "\n");
                }
                sb.append("      ]\n");
            }
            sb.append(i < SAMPLES.length - 1 ? "    },\n" : "    }\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String starJson(CatalogStar s) {
        // id as a decimal string (64-bit, beyond JS safe ints); doubles via
        // Double.toString so they round-trip exactly and TS can compare equal.
        return "{ \"id\": \"" + Long.toUnsignedString(s.id())
                + "\", \"x\": " + Double.toString(s.x())
                + ", \"y\": " + Double.toString(s.y())
                + ", \"spectral\": \"" + s.spectral().name()
                + "\", \"brightness\": " + Double.toString(s.brightness())
                + ", \"size\": " + Double.toString(s.size())
                + " }";
    }

    /**
     * Emits the TypeScript-importable mirror of the fixture next to the client
     * generator. The frontend test build forbids Node FS and out-of-{@code src}
     * imports, so the canonical JSON ({@code docs/specs/fixtures}) is mirrored as
     * a typed {@code const} module the spec imports directly. Single source of
     * truth: this Java test generates both, so they cannot drift.
     */
    private static void writeTsMirror(String json) throws IOException {
        Path ts = repoRoot().resolve(
                "frontend/src/app/features/galaxy/catalog-parity.fixture.ts");
        if (!Files.isDirectory(ts.getParent())) {
            return; // frontend not present (e.g. backend-only build) - skip.
        }
        String header = """
                /**
                 * GENERATED by backend CatalogParityFixtureTest - do NOT hand-edit.
                 * Mirror of docs/specs/fixtures/catalog-parity.json so the client parity
                 * test (catalog-generator.spec.ts) can import the same canonical values
                 * without Node FS or out-of-src imports (E8-03).
                 */
                export interface ParityStar {
                  readonly id: string;
                  readonly x: number;
                  readonly y: number;
                  readonly spectral: string;
                  readonly brightness: number;
                  readonly size: number;
                }
                export interface ParityCell {
                  readonly seed: number;
                  readonly cellX: number;
                  readonly cellY: number;
                  readonly stars: readonly ParityStar[];
                }
                export interface ParityFixture {
                  readonly _note?: string;
                  readonly cells: readonly ParityCell[];
                }

                export const CATALOG_PARITY_FIXTURE: ParityFixture =
                """;
        Files.writeString(ts, header + json.strip() + " as const;\n");
    }

    /** Walks up from the working dir to the repo root (the dir holding docs/specs). */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++) {
            if (Files.isDirectory(dir.resolve("docs/specs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        // Fall back to module-relative ../../ (galaxy module is backend/galaxy).
        return Path.of("").toAbsolutePath().getParent().getParent();
    }

    private static Path fixturePath() {
        return repoRoot().resolve("docs/specs/fixtures/catalog-parity.json");
    }
}
