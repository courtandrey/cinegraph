import { GenreShare, MapCluster } from '../../../models/movie.model';

export const GENRE_COLORS: Readonly<Record<number, string>> = {
  27: '#e5484d',
  53: '#f07a3a',
  9648: '#c2417a',
  80: '#b8864e',
  28: '#f2a93b',
  12: '#a8d65a',
  37: '#d4a259',
  10752: '#7d9a4e',
  36: '#c9b48a',
  878: '#40bcf4',
  14: '#9d7cf0',
  16: '#5fd4c4',
  10751: '#7ee0a0',
  35: '#f6d55c',
  10749: '#ff8fb0',
  10402: '#e77ad6',
  18: '#5b8def',
  99: '#9aa9b8',
  10770: '#8796a8'
};

export const NEUTRAL_COLOR = '#6b7a8a';

const FALLBACK_GENRE_COLOR = '#9aa9b8';
const LIGHTNESS = { min: 0.66, max: 0.8 };
const MIN_CHROMA = 0.13;
const MIN_DISTANCE = 0.09;
const HUE_STEP = 14;
const LIGHTNESS_STEP = 0.07;

interface Lab { l: number; a: number; b: number; }

export function clusterColors(clusters: MapCluster[]): Map<number, string> {
  const colored = clusters
    .filter(c => c.kind === 'CLUSTER')
    .sort((x, y) => y.size - x.size || x.id - y.id);
  const baseline = genreShares(colored.flatMap(c => c.genres));
  const assigned: Lab[] = [];
  const out = new Map<number, string>();
  for (const c of colored) {
    const lab = distinctFrom(liftMix(genreShares(c.genres), baseline), assigned);
    assigned.push(lab);
    out.set(c.id, labToHex(lab));
  }
  for (const c of clusters) if (!out.has(c.id)) out.set(c.id, NEUTRAL_COLOR);
  return out;
}

export function genreColor(genreId: number): string {
  return GENRE_COLORS[genreId] ?? FALLBACK_GENRE_COLOR;
}

export function genreShares(genres: GenreShare[]): Map<number, number> {
  const counts = new Map<number, number>();
  for (const g of genres) counts.set(g.genreId, (counts.get(g.genreId) ?? 0) + g.films);
  const total = [...counts.values()].reduce((a, b) => a + b, 0);
  const out = new Map<number, number>();
  if (total === 0) return out;
  counts.forEach((v, k) => out.set(k, v / total));
  return out;
}

export function liftMix(shares: Map<number, number>, baseline: Map<number, number>): Lab {
  const lift = new Map<number, number>();
  shares.forEach((p, g) => lift.set(g, Math.max(0, p - (baseline.get(g) ?? 0))));
  const liftTotal = [...lift.values()].reduce((a, b) => a + b, 0);
  const weights = liftTotal > 0 ? lift : shares;
  const total = [...weights.values()].reduce((a, b) => a + b, 0);
  if (total === 0) return hexToLab(FALLBACK_GENRE_COLOR);
  let l = 0, a = 0, b = 0;
  weights.forEach((w, g) => {
    const lab = hexToLab(genreColor(g));
    l += lab.l * w / total;
    a += lab.a * w / total;
    b += lab.b * w / total;
  });
  return vivid({ l, a, b });
}

export function distance(x: Lab, y: Lab): number {
  return Math.hypot(x.l - y.l, x.a - y.a, x.b - y.b);
}

function distinctFrom(lab: Lab, taken: Lab[]): Lab {
  const nearest = (c: Lab) => taken.reduce((m, t) => Math.min(m, distance(c, t)), Infinity);
  let best = lab;
  let bestGap = nearest(lab);
  if (bestGap >= MIN_DISTANCE) return lab;
  for (let step = 1; step * HUE_STEP <= 180; step++) {
    for (const sign of [1, -1]) {
      for (const shade of [0, LIGHTNESS_STEP, -LIGHTNESS_STEP]) {
        const candidate = rotate(lab, sign * step * HUE_STEP, shade);
        const gap = nearest(candidate);
        if (gap >= MIN_DISTANCE) return candidate;
        if (gap > bestGap) {
          best = candidate;
          bestGap = gap;
        }
      }
    }
  }
  return best;
}

function vivid(lab: Lab): Lab {
  const chroma = Math.hypot(lab.a, lab.b);
  const hue = Math.atan2(lab.b, lab.a);
  const c = Math.max(chroma, MIN_CHROMA);
  return {
    l: Math.min(LIGHTNESS.max, Math.max(LIGHTNESS.min, lab.l)),
    a: c * Math.cos(hue),
    b: c * Math.sin(hue)
  };
}

function rotate(lab: Lab, degrees: number, shade: number): Lab {
  const chroma = Math.hypot(lab.a, lab.b);
  const hue = Math.atan2(lab.b, lab.a) + degrees * Math.PI / 180;
  return {
    l: Math.min(LIGHTNESS.max + LIGHTNESS_STEP, Math.max(LIGHTNESS.min - LIGHTNESS_STEP, lab.l + shade)),
    a: chroma * Math.cos(hue),
    b: chroma * Math.sin(hue)
  };
}

export function hexToLab(hex: string): Lab {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map(v => toLinear(v / 255));
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return {
    l: 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    a: 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    b: 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s
  };
}

export function labToHex(lab: Lab): string {
  let chroma = Math.hypot(lab.a, lab.b);
  const hue = Math.atan2(lab.b, lab.a);
  for (let i = 0; i < 40; i++) {
    const rgb = labToLinearRgb({ l: lab.l, a: chroma * Math.cos(hue), b: chroma * Math.sin(hue) });
    if (rgb.every(v => v >= -1e-4 && v <= 1 + 1e-4) || chroma < 1e-3) {
      return '#' + rgb.map(v => Math.round(255 * toGamma(Math.min(1, Math.max(0, v))))
        .toString(16).padStart(2, '0')).join('');
    }
    chroma *= 0.95;
  }
  return FALLBACK_GENRE_COLOR;
}

function labToLinearRgb(lab: Lab): number[] {
  const l = (lab.l + 0.3963377774 * lab.a + 0.2158037573 * lab.b) ** 3;
  const m = (lab.l - 0.1055613458 * lab.a - 0.0638541728 * lab.b) ** 3;
  const s = (lab.l - 0.0894841775 * lab.a - 1.291485548 * lab.b) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s
  ];
}

function toLinear(c: number): number {
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function toGamma(c: number): number {
  return c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055;
}
