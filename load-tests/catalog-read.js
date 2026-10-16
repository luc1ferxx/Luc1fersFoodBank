import http from 'k6/http';
import { check } from 'k6';

// Anonymous catalog reads: GET /restaurants/menu and GET /restaurant/{id}/menu in a closed loop (no think
// time), to compare cache designs. With CONDITIONAL=true every request sends the ETag captured in setup()
// as If-None-Match, so a client that already has the catalog only gets 304s.
const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const VU_COUNT = parsePositiveInt(__ENV.VUS || '50', 'VUS');
const DURATION = __ENV.DURATION || '30s';
const CONDITIONAL = (__ENV.CONDITIONAL || 'false') === 'true';

export const options = {
  vus: VU_COUNT,
  duration: DURATION,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

function parsePositiveInt(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer, got: ${value}`);
  }
  return parsed;
}

export function setup() {
  const restaurants = http.get(`${BASE_URL}/restaurants/menu`);
  if (restaurants.status !== 200) {
    throw new Error(`GET /restaurants/menu returned ${restaurants.status}`);
  }
  const ids = restaurants.json().map((restaurant) => restaurant.id);
  const etags = { all: restaurants.headers.Etag || null };
  for (const id of ids) {
    etags[id] = http.get(`${BASE_URL}/restaurant/${id}/menu`).headers.Etag || null;
  }
  if (CONDITIONAL && !etags.all) {
    throw new Error('CONDITIONAL=true but the backend does not send an ETag');
  }
  return { ids, etags };
}

function get(path, etag) {
  const params = CONDITIONAL && etag ? { headers: { 'If-None-Match': etag } } : {};
  return http.get(`${BASE_URL}${path}`, params);
}

export default function (data) {
  const all = get('/restaurants/menu', data.etags.all);
  check(all, { 'restaurants/menu ok': (r) => r.status === 200 || r.status === 304 });

  const id = data.ids[Math.floor(Math.random() * data.ids.length)];
  const menu = get(`/restaurant/${id}/menu`, data.etags[id]);
  check(menu, { 'restaurant menu ok': (r) => r.status === 200 || r.status === 304 });
}
