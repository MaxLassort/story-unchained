import { describe, it, expect } from 'vitest';
import { LUNII_PALETTE, traceImageDataToSvg } from './lunii-image-tracer';

type RgbaImage = { width: number; height: number; data: Uint8ClampedArray };

function solidRect(w: number, h: number, fill: [number, number, number]): RgbaImage {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const p = i * 4;
    data[p] = fill[0];
    data[p + 1] = fill[1];
    data[p + 2] = fill[2];
    data[p + 3] = 255;
  }
  return { width: w, height: h, data };
}

/** White square on light-grey border (BG will flood to black). */
function whiteOnGreyBorder(size = 64): RgbaImage {
  const data = new Uint8ClampedArray(size * size * 4);
  const border = 8;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const p = (y * size + x) * 4;
      const onBorder = x < border || y < border || x >= size - border || y >= size - border;
      const v = onBorder ? 200 : 255;
      data[p] = v;
      data[p + 1] = v;
      data[p + 2] = v;
      data[p + 3] = 255;
    }
  }
  return { width: size, height: size, data };
}

describe('lunii-image-tracer', () => {
  it('traces solid regions into filled SVG paths (not stroke-only)', () => {
    const svg = traceImageDataToSvg(solidRect(48, 48, [255, 255, 255]));
    expect(svg).toContain('<svg');
    expect(svg).toMatch(/fill\s*=\s*["']rgb\(/i);
    expect(svg).not.toMatch(/stroke-width\s*=\s*["'][1-9]/i);
  });

  it('uses the Lunii greyscale palette colors in path fills', () => {
    const svg = traceImageDataToSvg(whiteOnGreyBorder(80));
    const fills = [...svg.matchAll(/fill\s*=\s*["']rgb\((\d+),(\d+),(\d+)\)["']/gi)].map((m) =>
      [Number(m[1]), Number(m[2]), Number(m[3])] as const,
    );
    expect(fills.length).toBeGreaterThan(0);
    const allowed = new Set(LUNII_PALETTE.map((c) => `${c.r},${c.g},${c.b}`));
    for (const [r, g, b] of fills) {
      expect(allowed.has(`${r},${g},${b}`), `unexpected fill rgb(${r},${g},${b})`).toBe(true);
    }
  });

  it('flood-fills light border background toward black flats', () => {
    const svg = traceImageDataToSvg(whiteOnGreyBorder(64));
    expect(svg).toMatch(/fill\s*=\s*["']rgb\(0,0,0\)["']/i);
    expect(svg).toMatch(/fill\s*=\s*["']rgb\(255,255,255\)["']/i);
  });
});
