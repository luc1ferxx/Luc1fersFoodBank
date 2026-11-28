// Stock race: BUYERS customers each try to buy one unit of a menu item limited to STOCK units, all at once. It checks
// that checkout never oversells (exactly STOCK orders are paid, every other buyer gets 409 OUT_OF_STOCK, nothing else
// fails, and the item ends at 0) and measures the checkout latency under that contention (stock_checkout_duration).
// Every buyer signs in and fills its cart first, then waits for one start time, PREP_MS after setup(), so that the
// checkouts race instead of trickling in behind the logins; stock_late_start counts the buyers not ready by then
// (raise PREP_MS when it is not 0). stock_race_window is the time from the start to each checkout's answer: its max
// is how long the race took, so BUYERS / max is the checkouts per second.
// The design is in docs/correctness.md "Stock".
//
// Prerequisites: a backend with the demo seed (FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo: the admin
// login and the menu items) and SECURITY_RATE_LIMIT_ENABLED=false (the signup and login buckets per IP refuse the
// buyers otherwise). Run it against the throwaway stack of load-tests/catalog-bench.compose.yml, started as its header
// shows (backend on port 18080, Kafka off): nothing else touches its data, and with Kafka on the accept timeout would
// cancel the paid orders 10 minutes later, giving their stock back if the item has a limit again by then.
//
//   BASE_URL=http://localhost:18080 STOCK=100 BUYERS=400 k6 run load-tests/stock-race.js
//
// Options (defaults): BASE_URL (http://localhost:8080), STOCK (100), BUYERS (400, more than STOCK), ITEM_ID (1, the
// menu item to limit), PREP_MS (15000), ADMIN_EMAIL and ADMIN_PASSWORD (demo@example.com, demo123), and for the
// buyers it signs up USER_PASSWORD (LoadTest123!) and USER_PREFIX (stockrace-<start time>).
//
// setup() sets the limit and teardown() removes it, but k6 skips teardown when setup fails or the run is killed.
// Remove a limit left behind as the admin (with the defaults above; substitute your BASE_URL, ADMIN_EMAIL,
// ADMIN_PASSWORD and ITEM_ID):
//
//   curl -s -c /tmp/stock-race.cookies -d 'username=demo@example.com&password=demo123' http://localhost:18080/login
//   curl -s -b /tmp/stock-race.cookies -X DELETE http://localhost:18080/admin/menu-items/1/stock
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'demo@example.com';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'demo123';
const ITEM_ID = parsePositiveInt(__ENV.ITEM_ID || '1', 'ITEM_ID');
const STOCK = parsePositiveInt(__ENV.STOCK || '100', 'STOCK');
const BUYERS = parsePositiveInt(__ENV.BUYERS || '400', 'BUYERS');
// Time for every buyer to sign in (a BCrypt check each) and fill its cart before the common start.
const PREP_MS = parsePositiveInt(__ENV.PREP_MS || '15000', 'PREP_MS');
// Below the backend's keep-alive timeout, so no buyer has to reconnect at the start.
const KEEP_ALIVE_MS = 5000;
const USER_PASSWORD = __ENV.USER_PASSWORD || 'LoadTest123!';
// Init code runs in every VU, each with its own timestamp: only setup() builds users, and the VUs get them from it.
const USER_PREFIX = __ENV.USER_PREFIX || `stockrace-${Date.now()}`;
const STOCK_URL = `${BASE_URL}/admin/menu-items/${ITEM_ID}/stock`;
const STOCK_TAGS = { tags: { name: '/admin/menu-items/{id}/stock' } };
const SIGNUP_BATCH = 50;

if (BUYERS <= STOCK) {
  throw new Error(`BUYERS (${BUYERS}) must be greater than STOCK (${STOCK}), or nobody races for the last unit.`);
}

export const options = {
  setupTimeout: '5m',
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: BUYERS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    stock_paid: [`count==${STOCK}`],
    stock_sold_out: [`count==${BUYERS - STOCK}`],
    stock_unexpected: ['count==0'],
    stock_late_start: ['count==0'],
    http_req_failed: ['rate==0'],
    // Also fails the run when teardown finds stock left.
    checks: ['rate==1'],
  },
};

const stockPaid = new Counter('stock_paid');
const stockSoldOut = new Counter('stock_sold_out');
const stockUnexpected = new Counter('stock_unexpected');
const stockLateStart = new Counter('stock_late_start');
const stockCheckoutDuration = new Trend('stock_checkout_duration', true);
const stockRaceWindow = new Trend('stock_race_window', true);

function parsePositiveInt(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer, got: ${value}`);
  }
  return parsed;
}

function buildUser(index) {
  const suffix = String(index + 1).padStart(4, '0');
  return {
    email: `${USER_PREFIX}-${suffix}@example.test`,
    password: USER_PASSWORD,
    first_name: `Stock${suffix}`,
    last_name: 'Racer',
  };
}

function jsonHeaders(extra = {}) {
  return {
    headers: {
      'Content-Type': 'application/json',
      ...extra,
    },
  };
}

function parseJson(response) {
  try {
    return response.json();
  } catch (e) {
    return null;
  }
}

function signIn(email, password) {
  const payload = `username=${encodeURIComponent(email)}&password=${encodeURIComponent(password)}`;
  return http.post(`${BASE_URL}/login`, payload, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    tags: { name: 'POST /login' },
  });
}

function require200(response, what) {
  if (response.status !== 200) {
    throw new Error(`${what} answered ${response.status}: ${response.body}`);
  }
  return response;
}

export function setup() {
  const users = [];
  for (let i = 0; i < BUYERS; i += 1) {
    users.push(buildUser(i));
  }
  const signupParams = {
    ...jsonHeaders(),
    tags: { name: 'POST /signup setup' },
    responseCallback: http.expectedStatuses(201, 409),
  };
  for (let start = 0; start < users.length; start += SIGNUP_BATCH) {
    const requests = users.slice(start, start + SIGNUP_BATCH).map((user) => ({
      method: 'POST',
      url: `${BASE_URL}/signup`,
      body: JSON.stringify(user),
      params: signupParams,
    }));
    for (const response of http.batch(requests)) {
      if (response.status !== 201 && response.status !== 409) {
        throw new Error(`POST /signup answered ${response.status}: ${response.body}`);
      }
    }
  }

  require200(signIn(ADMIN_EMAIL, ADMIN_PASSWORD), `POST /login as ${ADMIN_EMAIL}`);
  require200(http.del(STOCK_URL, null, STOCK_TAGS), `DELETE ${STOCK_URL}`);
  const limit = JSON.stringify({ remaining: STOCK });
  require200(http.put(STOCK_URL, limit, { ...jsonHeaders(), ...STOCK_TAGS }), `PUT ${STOCK_URL}`);
  const stock = require200(http.get(STOCK_URL, STOCK_TAGS), `GET ${STOCK_URL}`);
  if ((parseJson(stock) || {}).remaining !== STOCK) {
    throw new Error(`The limit of item ${ITEM_ID} is not ${STOCK}: ${stock.body}; remove it as the header shows.`);
  }
  return { users, startAt: Date.now() + PREP_MS };
}

export default function (data) {
  const user = data.users[__VU - 1];
  signIn(user.email, user.password);
  http.del(`${BASE_URL}/cart`, null, { tags: { name: 'DELETE /cart' } });
  http.post(`${BASE_URL}/cart`, JSON.stringify({ menu_id: ITEM_ID }), { ...jsonHeaders(), tags: { name: 'POST /cart' } });

  // Every VU adds a sample, so the threshold is evaluated even when nobody is late.
  stockLateStart.add(Date.now() < data.startAt ? 0 : 1);
  // Tomcat closes a connection idle for 10 s (server.tomcat.connection-timeout), and hundreds of buyers reconnecting at
  // the start overflow the listen backlog ("connection reset by peer"), so each buyer keeps its connection in use.
  while (data.startAt - Date.now() > KEEP_ALIVE_MS) {
    sleep(KEEP_ALIVE_MS / 1000);
    http.get(`${BASE_URL}/cart`, { tags: { name: 'GET /cart while waiting' } });
  }
  const wait = data.startAt - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }

  const payment = {
    cardholder_name: `${user.first_name} ${user.last_name}`,
    card_number: '4242424242424242',
    expiry: '12/30',
    cvv: '123',
  };
  const response = http.post(`${BASE_URL}/payments/checkout`, JSON.stringify(payment), {
    ...jsonHeaders({ 'Idempotency-Key': `stockrace-${__VU}-${Date.now()}-${Math.random().toString(36).slice(2)}` }),
    tags: { name: 'POST /payments/checkout' },
    responseCallback: http.expectedStatuses(200, 409),
  });
  stockCheckoutDuration.add(response.timings.duration);
  stockRaceWindow.add(Date.now() - data.startAt);

  const body = parseJson(response) || {};
  const paid = response.status === 200 && body.status === 'PAID';
  const soldOut = response.status === 409 && body.code === 'OUT_OF_STOCK';
  // Every VU adds to all three counters, so each has samples and its threshold is evaluated.
  stockPaid.add(paid ? 1 : 0);
  stockSoldOut.add(soldOut ? 1 : 0);
  stockUnexpected.add(paid || soldOut ? 0 : 1);
  if (!paid && !soldOut) {
    console.error(`VU ${__VU} (${user.email}): checkout answered ${response.status}: ${response.body}`);
  }
}

export function teardown() {
  signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
  const stock = http.get(STOCK_URL, STOCK_TAGS);
  const soldOut = check(stock, {
    'teardown: no stock left': (r) => r.status === 200 && (parseJson(r) || {}).remaining === 0,
  });
  if (!soldOut) {
    console.error(`Item ${ITEM_ID} after the race: ${stock.status} ${stock.body}`);
  }
  check(http.del(STOCK_URL, null, STOCK_TAGS), {
    'teardown: limit removed': (r) => r.status === 200,
  });
}
