import {
  SPECTRAL_ORDER,
  SPECTRAL_PALETTE,
  normalizeBrightness,
  normalizeSize,
  spectralIndex,
} from './render-model';

describe('spectral palette (mirrors SpectralClass.java order O..M)', () => {
  it('has 7 classes in hottest-to-coolest order', () => {
    expect(SPECTRAL_ORDER).toEqual(['O', 'B', 'A', 'F', 'G', 'K', 'M']);
    expect(SPECTRAL_PALETTE.length).toBe(7);
  });

  it('maps class names to palette indices', () => {
    expect(spectralIndex('O')).toBe(0);
    expect(spectralIndex('G')).toBe(4);
    expect(spectralIndex('M')).toBe(6);
  });

  it('defaults unknown classes to G (index 4)', () => {
    expect(spectralIndex('X')).toBe(4);
    expect(spectralIndex('')).toBe(4);
  });
});

describe('brightness / size normalisation', () => {
  it('normalizeBrightness is monotonic and bounded 0..1', () => {
    const dim = normalizeBrightness(0.1);
    const sol = normalizeBrightness(1);
    const bright = normalizeBrightness(30);
    expect(dim).toBeGreaterThanOrEqual(0);
    expect(bright).toBeLessThanOrEqual(1);
    expect(dim).toBeLessThan(sol);
    expect(sol).toBeLessThan(bright);
  });

  it('normalizeSize maps into the PoC ~0.3..1.4 band, monotonic', () => {
    const small = normalizeSize(0.3);
    const sol = normalizeSize(1);
    const big = normalizeSize(10);
    expect(small).toBeGreaterThanOrEqual(0.3);
    expect(small).toBeLessThan(sol);
    expect(sol).toBeLessThan(big);
    expect(big).toBeLessThanOrEqual(1.4);
  });
});
