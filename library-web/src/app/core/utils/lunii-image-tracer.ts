import ImageTracer, { type ImageTracerOptions } from 'imagetracerjs';

/** Device canvas size (Lunii screen). */
export const LUNII_WIDTH = 320;
export const LUNII_HEIGHT = 240;

/** Fixed grayscale palette matching Lunii readability (black → white). */
export const LUNII_PALETTE = [
  { r: 0, g: 0, b: 0, a: 255 },
  { r: 64, g: 64, b: 64, a: 255 },
  { r: 128, g: 128, b: 128, a: 255 },
  { r: 192, g: 192, b: 192, a: 255 },
  { r: 255, g: 255, b: 255, a: 255 },
] as const;

const MAX_TRACE_EDGE = 720;
const BG_TOLERANCE = 42;
const BG_BORDER_CONSENSUS = 0.55;

const TRACER_OPTIONS: ImageTracerOptions = {
  ltres: 1,
  qtres: 1,
  pathomit: 8,
  rightangleenhance: true,
  colorsampling: 0,
  numberofcolors: LUNII_PALETTE.length,
  mincolorratio: 0,
  colorquantcycles: 2,
  strokewidth: 0,
  linefilter: false,
  scale: 1,
  roundcoords: 1,
  viewbox: true,
  blurradius: 0,
  pal: [...LUNII_PALETTE],
};

/**
 * Converts any browser-decodable image into a clean 320×240 Lunii PNG:
 * grayscale → border BG to black → ImageTracer flat regions → letterbox on black.
 */
export async function prepareLuniiImageWithTracer(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  try {
    const { width, height } = fitWithin(bitmap.width, bitmap.height, MAX_TRACE_EDGE);
    const src = document.createElement('canvas');
    src.width = width;
    src.height = height;
    const ctx = src.getContext('2d', { willReadFrequently: true });
    if (!ctx) throw new Error('Could not create canvas context');
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(bitmap, 0, 0, width, height);

    let imageData = ctx.getImageData(0, 0, width, height);
    toGrayscaleInPlace(imageData);
    floodFillBorderToBlack(imageData);
    ctx.putImageData(imageData, 0, 0);
    imageData = ctx.getImageData(0, 0, width, height);

    const svg = ImageTracer.imagedataToSVG(
      { width: imageData.width, height: imageData.height, data: imageData.data },
      TRACER_OPTIONS,
    );
    return await rasterizeSvgToDevicePng(svg);
  } finally {
    bitmap.close();
  }
}

/** Trace ImageData → SVG string (for unit tests / Node smoke). */
export function traceImageDataToSvg(imageData: {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}): string {
  const copy = {
    width: imageData.width,
    height: imageData.height,
    data: new Uint8ClampedArray(imageData.data),
  };
  toGrayscaleInPlace(copy);
  floodFillBorderToBlack(copy);
  return ImageTracer.imagedataToSVG(
    { width: copy.width, height: copy.height, data: copy.data },
    TRACER_OPTIONS,
  );
}

function fitWithin(w: number, h: number, maxEdge: number): { width: number; height: number } {
  const edge = Math.max(w, h);
  if (edge <= maxEdge) return { width: w, height: h };
  const scale = maxEdge / edge;
  return {
    width: Math.max(1, Math.round(w * scale)),
    height: Math.max(1, Math.round(h * scale)),
  };
}

function toGrayscaleInPlace(imageData: { data: Uint8ClampedArray }): void {
  const d = imageData.data;
  for (let i = 0; i < d.length; i += 4) {
    const y = Math.round(0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]);
    d[i] = y;
    d[i + 1] = y;
    d[i + 2] = y;
  }
}

/**
 * Flood-fills a coherent border background to black so tracer flats sit on black
 * (same idea as backend LuniiImagePrepare.removeSolidBackground).
 */
function floodFillBorderToBlack(imageData: {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}): void {
  const { width: w, height: h, data } = imageData;
  if (w < 2 || h < 2) return;

  const samples: number[] = [];
  for (let x = 0; x < w; x++) {
    samples.push(rgbAt(data, x, 0, w));
    samples.push(rgbAt(data, x, h - 1, w));
  }
  for (let y = 1; y < h - 1; y++) {
    samples.push(rgbAt(data, 0, y, w));
    samples.push(rgbAt(data, w - 1, y, w));
  }

  const buckets = new Map<number, number>();
  for (const c of samples) {
    const key = quantizeRgb(c, 16);
    buckets.set(key, (buckets.get(key) ?? 0) + 1);
  }
  let bestKey = -1;
  let bestCount = 0;
  for (const [k, n] of buckets) {
    if (n > bestCount) {
      bestCount = n;
      bestKey = k;
    }
  }
  if (bestCount < samples.length * BG_BORDER_CONSENSUS) return;

  let sr = 0;
  let sg = 0;
  let sb = 0;
  let sn = 0;
  for (const c of samples) {
    if (quantizeRgb(c, 16) !== bestKey) continue;
    sr += (c >> 16) & 0xff;
    sg += (c >> 8) & 0xff;
    sb += c & 0xff;
    sn++;
  }
  if (sn === 0) return;
  const bgR = Math.round(sr / sn);
  const bgG = Math.round(sg / sn);
  const bgB = Math.round(sb / sn);

  // Don't treat near-black as background to remove.
  if (bgR < 24 && bgG < 24 && bgB < 24) return;

  const isBg = new Uint8Array(w * h);
  const queue: number[] = [];
  const enqueue = (x: number, y: number): void => {
    if (x < 0 || y < 0 || x >= w || y >= h) return;
    const i = y * w + x;
    if (isBg[i]) return;
    const c = rgbAt(data, x, y, w);
    const dr = Math.abs(((c >> 16) & 0xff) - bgR);
    const dg = Math.abs(((c >> 8) & 0xff) - bgG);
    const db = Math.abs((c & 0xff) - bgB);
    if (Math.max(dr, dg, db) > BG_TOLERANCE) return;
    isBg[i] = 1;
    queue.push(i);
  };

  for (let x = 0; x < w; x++) {
    enqueue(x, 0);
    enqueue(x, h - 1);
  }
  for (let y = 0; y < h; y++) {
    enqueue(0, y);
    enqueue(w - 1, y);
  }

  while (queue.length > 0) {
    const i = queue.pop()!;
    const x = i % w;
    const y = (i / w) | 0;
    enqueue(x - 1, y);
    enqueue(x + 1, y);
    enqueue(x, y - 1);
    enqueue(x, y + 1);
  }

  let bgCount = 0;
  for (let i = 0; i < isBg.length; i++) if (isBg[i]) bgCount++;
  if (bgCount > w * h * 0.92) return;

  for (let i = 0; i < isBg.length; i++) {
    if (!isBg[i]) continue;
    const p = i * 4;
    data[p] = 0;
    data[p + 1] = 0;
    data[p + 2] = 0;
    data[p + 3] = 255;
  }
}

function rgbAt(data: Uint8ClampedArray, x: number, y: number, w: number): number {
  const i = (y * w + x) * 4;
  return (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
}

function quantizeRgb(rgb: number, step: number): number {
  const r = (((rgb >> 16) & 0xff) / step) * step;
  const g = (((rgb >> 8) & 0xff) / step) * step;
  const b = ((rgb & 0xff) / step) * step;
  return (r << 16) | (g << 8) | b;
}

async function rasterizeSvgToDevicePng(svg: string): Promise<Blob> {
  const blob = new Blob([svg], { type: 'image/svg+xml' });
  const url = URL.createObjectURL(blob);
  try {
    const img = await loadImage(url);
    const canvas = document.createElement('canvas');
    canvas.width = LUNII_WIDTH;
    canvas.height = LUNII_HEIGHT;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Could not create canvas context');
    ctx.fillStyle = '#000000';
    ctx.fillRect(0, 0, LUNII_WIDTH, LUNII_HEIGHT);
    ctx.imageSmoothingEnabled = false;
    const scale = Math.min(LUNII_WIDTH / img.naturalWidth, LUNII_HEIGHT / img.naturalHeight);
    const dw = Math.max(1, Math.round(img.naturalWidth * scale));
    const dh = Math.max(1, Math.round(img.naturalHeight * scale));
    const dx = Math.floor((LUNII_WIDTH - dw) / 2);
    const dy = Math.floor((LUNII_HEIGHT - dh) / 2);
    ctx.drawImage(img, dx, dy, dw, dh);
    return await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('PNG encode failed'))), 'image/png');
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Failed to load traced SVG'));
    img.src = url;
  });
}
