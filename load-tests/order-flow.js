import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const VU_COUNT = parsePositiveInt(__ENV.VUS || '50', 'VUS');
const DURATION = __ENV.DURATION || '30s';
const USER_COUNT = Math.max(parsePositiveInt(__ENV.USER_COUNT || '50', 'USER_COUNT'), 50);
const USER_PASSWORD = __ENV.USER_PASSWORD || 'LoadTest123!';
const USER_PREFIX = __ENV.USER_PREFIX || `loadtest-${Date.now()}`;

if (VU_COUNT > USER_COUNT) {
  throw new Error(`VUS (${VU_COUNT}) must be <= USER_COUNT (${USER_COUNT}) to avoid measuring same-user cart lock contention as system throughput.`);
}

export const options = {
  vus: VU_COUNT,
  duration: DURATION,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
    checkout_success_rate: ['rate>0.95'],
  },
};

export const checkoutSuccessRate = new Rate('checkout_success_rate');

function parsePositiveInt(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    throw new Error(`${name} must be a positive integer, got: ${value}`);
  }
  return parsed;
}

function buildUser(index) {
  const suffix = String(index + 1).padStart(3, '0');
  return {
    email: `${USER_PREFIX}-${suffix}@laifood.local`,
    password: USER_PASSWORD,
    first_name: `Load${suffix}`,
    last_name: 'Tester',
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

function randomIntInclusive(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function think() {
  sleep(0.1 + Math.random() * 0.4);
}

export function setup() {
  const users = [];
  const signupParams = {
    ...jsonHeaders(),
    tags: { name: 'POST /signup setup' },
    responseCallback: http.expectedStatuses({ min: 200, max: 399 }, 409),
  };

  for (let i = 0; i < USER_COUNT; i += 1) {
    const user = buildUser(i);
    users.push(user);

    const response = http.post(
      `${BASE_URL}/signup`,
      JSON.stringify(user),
      signupParams
    );

    check(response, {
      'setup signup created or already exists': (r) => r.status === 201 || r.status === 409,
    });
  }

  const health = http.get(`${BASE_URL}/actuator/health`, { tags: { name: 'GET /actuator/health setup' } });
  check(health, {
    'setup actuator health is reachable': (r) => r.status === 200,
    'setup actuator health reports UP': (r) => (parseJson(r) || {}).status === 'UP',
  });

  return { users };
}

export default function (data) {
  const user = data.users[(__VU - 1) % data.users.length];

  group('01 login', () => {
    const payload = `username=${encodeURIComponent(user.email)}&password=${encodeURIComponent(user.password)}`;
    const response = http.post(`${BASE_URL}/login`, payload, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      tags: { name: 'POST /login' },
    });
    check(response, {
      'login status 200': (r) => r.status === 200,
    });
  });

  think();

  let restaurants = [];
  group('02 list restaurants', () => {
    const response = http.get(`${BASE_URL}/restaurants/menu`, { tags: { name: 'GET /restaurants/menu' } });
    restaurants = parseJson(response) || [];
    check(response, {
      'restaurants status 200': (r) => r.status === 200,
      'restaurants list is not empty': () => Array.isArray(restaurants) && restaurants.length > 0,
    });
  });

  if (!Array.isArray(restaurants) || restaurants.length === 0) {
    checkoutSuccessRate.add(false);
    return;
  }

  think();

  const restaurant = restaurants[randomIntInclusive(0, restaurants.length - 1)];
  let menu = [];
  group('03 get restaurant menu', () => {
    const response = http.get(`${BASE_URL}/restaurant/${restaurant.id}/menu`, {
      tags: { name: 'GET /restaurant/{id}/menu' },
    });
    menu = parseJson(response) || [];
    check(response, {
      'menu status 200': (r) => r.status === 200,
      'menu list is not empty': () => Array.isArray(menu) && menu.length > 0,
    });
  });

  if (!Array.isArray(menu) || menu.length === 0) {
    checkoutSuccessRate.add(false);
    return;
  }

  think();

  group('04 add 1-3 items to cart', () => {
    const itemCount = Math.min(randomIntInclusive(1, 3), menu.length);
    const start = randomIntInclusive(0, menu.length - 1);

    for (let i = 0; i < itemCount; i += 1) {
      const menuItem = menu[(start + i) % menu.length];
      const response = http.post(
        `${BASE_URL}/cart`,
        JSON.stringify({ menu_id: menuItem.id }),
        { ...jsonHeaders(), tags: { name: 'POST /cart' } }
      );
      check(response, {
        'add cart item status 2xx': (r) => r.status >= 200 && r.status < 300,
      });
      think();
    }
  });

  let checkoutSucceeded = false;
  group('05 payment checkout', () => {
    const idempotencyKey = `k6-${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
    const payment = {
      cardholder_name: `${user.first_name} ${user.last_name}`,
      card_number: '4242424242424242',
      expiry: '12/30',
      cvv: '123',
    };

    const response = http.post(
      `${BASE_URL}/payments/checkout`,
      JSON.stringify(payment),
      {
        ...jsonHeaders({ 'Idempotency-Key': idempotencyKey }),
        tags: { name: 'POST /payments/checkout' },
      }
    );
    const order = parseJson(response) || {};
    checkoutSucceeded = response.status === 200 && !!order.id && order.status === 'PAID';

    check(response, {
      'checkout status 200': (r) => r.status === 200,
      'checkout returns PAID order': () => checkoutSucceeded,
    });
    checkoutSuccessRate.add(checkoutSucceeded);
  });

  think();

  group('06 list orders', () => {
    const response = http.get(`${BASE_URL}/orders`, { tags: { name: 'GET /orders' } });
    const orders = parseJson(response) || [];
    check(response, {
      'orders status 200': (r) => r.status === 200,
      'orders list contains at least one order': () => Array.isArray(orders) && orders.length > 0,
    });
  });

  sleep(0.2 + Math.random() * 0.8);
}
