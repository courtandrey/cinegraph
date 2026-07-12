import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { check } from 'k6';

if (!__ENV.CINEGRAPH_HOST) {
  throw new Error('CINEGRAPH_HOST is required');
}

const BASE = __ENV.CINEGRAPH_HOST.replace(/\/+$/, '') + '/api';
const MODE = __ENV.MODE || 'burst';
if (MODE !== 'burst' && MODE !== 'stress') {
  throw new Error(`MODE must be 'burst' or 'stress', got '${MODE}'`);
}

const PARALLEL = parseInt(__ENV.PARALLEL || '100', 10);
const LB_PARALLEL = parseInt(__ENV.LB_PARALLEL || '50', 10);
const SET_SIZE = parseInt(__ENV.SET_SIZE || '1000', 10);
const SLO_MS = parseInt(__ENV.SLO_MS || '5000', 10);
const SLO_UPLOAD_MS = parseInt(__ENV.SLO_UPLOAD_MS || '60000', 10);
const RUN_ID = __ENV.RUN_ID || 'adhoc';

const RPS = parseInt(__ENV.RPS || '300', 10);
const RAMP = __ENV.RAMP || '2m';
const HOLD = __ENV.HOLD || '3m';
const PRE_VUS = parseInt(__ENV.PRE_VUS || `${RPS}`, 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || `${RPS * 4}`, 10);

const SEARCH_TERMS = [
  'love', 'night', 'man', 'war', 'star', 'dark', 'city', 'king', 'girl', 'dead',
  'house', 'time', 'day', 'black', 'blood', 'last', 'world', 'moon', 'fire', 'dream',
  'game', 'story', 'summer', 'winter', 'ghost', 'blue', 'red', 'american', 'paris', 'tokyo',
];

const UPLOAD_START_S = 120;
const RECS_START_S = UPLOAD_START_S + Math.ceil(SLO_UPLOAD_MS / 1000) + 10;

const readScenarios = MODE === 'stress'
  ? {
      traffic: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: PRE_VUS,
        maxVUs: MAX_VUS,
        stages: [
          { target: RPS, duration: RAMP },
          { target: RPS, duration: HOLD },
          { target: 0, duration: '30s' },
        ],
        exec: 'trafficTest',
      },
    }
  : {
      search: burst('searchTest', PARALLEL, '0s'),
      film_detail: burst('filmDetailTest', PARALLEL, '300s'),
      film_graph: burst('filmGraphTest', PARALLEL, '600s'),
      shortest_path: burst('pathTest', PARALLEL, '900s'),
    };

export const options = {
  batch: SEARCH_TERMS.length,
  batchPerHost: SEARCH_TERMS.length,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    ...readScenarios,
    upload: {
      ...burst('uploadTest', LB_PARALLEL, `${UPLOAD_START_S}s`),
      maxDuration: `${Math.ceil((SLO_UPLOAD_MS * 2) / 1000)}s`,
    },
    recommendations: burst('recsTest', LB_PARALLEL, `${RECS_START_S}s`),
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    'http_req_duration{endpoint:upload}': [`max<${SLO_UPLOAD_MS}`],
    'http_req_duration{endpoint:search}': [`max<${SLO_MS}`],
    'http_req_duration{endpoint:film_detail}': [`max<${SLO_MS}`],
    'http_req_duration{endpoint:film_graph}': [`max<${SLO_MS}`],
    'http_req_duration{endpoint:shortest_path}': [`max<${SLO_MS}`],
    'http_req_duration{endpoint:recommendations}': [`max<${SLO_MS}`],
  },
};

function burst(fn, vus, startTime) {
  return {
    executor: 'per-vu-iterations',
    vus,
    iterations: 1,
    startTime,
    maxDuration: '20m',
    exec: fn,
  };
}

export function setup() {
  const pool = seedFilmPool();
  console.log(`PERF_POOL ${pool.length} unique (title, year) films seeded`);
  if (pool.length < 50) {
    throw new Error(`film pool too small (${pool.length}); is the database loaded?`);
  }

  const hashes = [];
  for (let s = 0; s < LB_PARALLEL; s++) {
    const hash = crypto.sha256(buildCsv(pool, s), 'hex');
    console.log(`PERF_HASH ${hash}`);
    hashes.push(hash);
  }
  return { pool, hashes };
}

function seedFilmPool() {
  const requests = SEARCH_TERMS.map(t =>
    ['GET', `${BASE}/movies/search?q=${encodeURIComponent(t)}&limit=50`]);
  const responses = http.batch(requests);

  const countByKey = {};
  const filmByKey = {};
  responses.forEach((r, i) => {
    if (r.status !== 200) {
      throw new Error(`seed search '${SEARCH_TERMS[i]}' failed: ${r.status}`);
    }
    for (const film of r.json()) {
      if (film.year == null || !film.title) continue;
      const key = `${film.title.toLowerCase()}|${film.year}`;
      countByKey[key] = (countByKey[key] || 0) + 1;
      filmByKey[key] = { id: film.id, title: film.title, year: film.year };
    }
  });

  return Object.keys(countByKey)
    .filter(k => countByKey[k] === 1)
    .map(k => filmByKey[k]);
}

/**
 * Deterministic per (RUN_ID, setIndex, pool): setup() hashes the CSV without
 * storing it, and upload VUs regenerate the identical bytes. This keeps the
 * per-VU copy of setup data tiny, which is what makes large-VU runs feasible.
 */
function buildCsv(pool, setIndex) {
  const rng = mulberry32(seedHash(`${RUN_ID}:${setIndex}`));
  const films = shuffled(pool, rng).slice(0, Math.min(SET_SIZE, pool.length));
  const lines = ['Name,Year,Letterboxd URI,Rating'];
  films.forEach((f, i) => {
    const rating = (Math.floor(rng() * 10) + 1) / 2;
    lines.push(`${csvEscape(f.title)},${f.year},https://boxd.it/perf/${RUN_ID}/${setIndex}/${i},${rating}`);
  });
  return lines.join('\n') + '\n';
}

function seedHash(text) {
  let h = 5381;
  for (let i = 0; i < text.length; i++) h = ((h << 5) + h + text.charCodeAt(i)) >>> 0;
  return h;
}

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function csvEscape(value) {
  return /[",\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

function shuffled(items, rng) {
  const copy = items.slice();
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

function randomFrom(items) {
  return items[Math.floor(Math.random() * items.length)];
}

const TRAFFIC_MIX = [
  [0.35, searchTest],
  [0.25, filmDetailTest],
  [0.30, filmGraphTest],
  [0.10, pathTest],
];

export function trafficTest(data) {
  let roll = Math.random();
  for (const [weight, fn] of TRAFFIC_MIX) {
    roll -= weight;
    if (roll < 0) return fn(data);
  }
  return searchTest(data);
}

export function searchTest(data) {
  const q = Math.random() < 0.5
    ? randomFrom(SEARCH_TERMS)
    : randomFrom(data.pool).title.split(' ')[0];
  const r = http.get(`${BASE}/movies/search?q=${encodeURIComponent(q)}&limit=10`,
    { tags: { endpoint: 'search' } });
  check(r, {
    'search: status 200': res => res.status === 200,
    'search: array body': res => Array.isArray(res.json()),
  });
}

export function filmDetailTest(data) {
  const id = randomFrom(data.pool).id;
  const r = http.get(`${BASE}/movies/${id}`, { tags: { endpoint: 'film_detail' } });
  check(r, {
    'detail: status 200': res => res.status === 200,
    'detail: has id': res => res.json('id') === id,
  });
}

export function filmGraphTest(data) {
  const id = randomFrom(data.pool).id;
  const r = http.get(`${BASE}/movies/${id}/graph?minScore=0&limit=25`,
    { tags: { endpoint: 'film_graph' } });
  check(r, {
    'graph: status 200': res => res.status === 200,
    'graph: has center': res => res.json('center.id') === id,
  });
}

export function pathTest(data) {
  const from = randomFrom(data.pool);
  let to = randomFrom(data.pool);
  while (to.id === from.id) to = randomFrom(data.pool);
  const r = http.get(`${BASE}/movies/${from.id}/path/${to.id}`,
    { timeout: '90s', tags: { endpoint: 'shortest_path' } });
  check(r, {
    'path: status 200': res => res.status === 200,
    'path: has found flag': res => typeof res.json('found') === 'boolean',
  });
}

export function uploadTest(data) {
  const idx = exec.scenario.iterationInTest % data.hashes.length;
  const csv = buildCsv(data.pool, idx);
  const r = http.post(`${BASE}/letterboxd/graphs`,
    { file: http.file(csv, `perf-${RUN_ID}-${idx}.csv`, 'text/csv') },
    { timeout: `${Math.ceil((SLO_UPLOAD_MS * 2) / 1000)}s`, tags: { endpoint: 'upload' } });
  check(r, {
    'upload: status 200': res => res.status === 200,
    'upload: hash matches': res => res.json('hash') === data.hashes[idx],
    'upload: graphs built': res => (res.json('graphs') || []).length > 0,
  });
}

export function recsTest(data) {
  const hash = data.hashes[exec.scenario.iterationInTest % data.hashes.length];
  const r = http.get(`${BASE}/letterboxd/${hash}/recommendations?limit=25`,
    { tags: { endpoint: 'recommendations' } });
  check(r, {
    'recs: status 200': res => res.status === 200,
    'recs: array body': res => Array.isArray(res.json()),
    'recs: non-empty': res => res.json().length > 0,
  });
}
