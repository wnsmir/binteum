import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { request as httpRequest } from 'node:http';
import { createNearbyService, validateRequest, assess } from '../src/nearby.mjs';
import { createLocalServer, createConfiguredService } from '../src/index.mjs';
const NOW = 1800000000000;
const input = (extra = {}) => ({ lat: 37.5, lon: 127, category: 'CAFE', originTimestampMillis: NOW, availableSeconds: 1500, ...extra });
const json = data => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const osmPlace = (id, extra = {}) => ({ type: 'node', id, lat: 37.5005, lon: 127.0005, tags: { amenity: 'cafe', name: `Fixture Cafe ${id}` }, ...extra });
const trip = (out = 120, back = 180) => ({ trip: { status: 0, legs: [{ summary: { time: out } }, { summary: { time: back } }] } });

test('strict request coordinates, categories, timestamp, and unknown time', () => {
  assert.equal(validateRequest(input({ availableSeconds: null }), NOW).availableSeconds, null);
  for (const patch of [{ lat: '37.5' }, { lon: 181 }, { category: '__proto__' }, { category: 'WALK' }, { availableSeconds: -1 }]) {
    assert.throws(() => validateRequest(input(patch), NOW), { code: 'INVALID_REQUEST' });
  }
  assert.equal(validateRequest(input({ originTimestampMillis: NOW + 10_000 }), NOW).originTimestampMillis, NOW + 10_000);
  for (const stamp of [NOW - 120001, NOW + 10_001]) assert.throws(() => validateRequest(input({ originTimestampMillis: stamp }), NOW), { code: 'LOCATION_STALE' });
});

test('outside budget requires both directions, reserves five minutes, keeps unknown distinct', () => {
  assert.deepEqual(assess(120, 180, 600, 1200), { totalSeconds: 1200, fit: 'FITS' });
  assert.equal(assess(120, 180, 600, 1199).fit, 'TOO_LONG');
  assert.equal(assess(120, 180, 600, null).fit, 'UNKNOWN');
  assert.deepEqual(assess(120, null, 600, 9000), { totalSeconds: null, fit: 'ROUTE_UNAVAILABLE' });
});

test('OSM search returns real provider names, one pedestrian round trip and two asymmetric legs', async () => {
  const calls = [];
  const service = createNearbyService({ now: () => NOW, routeSpacingMs: 0, fetchImpl: async (url, options) => {
    calls.push({ url: String(url), options });
    return String(url).includes('overpass') ? json({ elements: [osmPlace(1)] }) : json(trip(121.1, 200.2));
  } });
  const result = await service.search(input());
  assert.equal(result.provider, 'OPENSTREETMAP'); assert.equal(result.originTimestampMillis, NOW);
  assert.equal(result.places[0].name, 'Fixture Cafe 1');
  assert.equal(result.places[0].outboundSeconds, 122); assert.equal(result.places[0].returnSeconds, 201);
  assert.equal(result.places[0].totalSeconds, 1223); assert.equal(result.places[0].fit, 'FITS');
  assert.equal(result.places[0].url, 'https://www.openstreetmap.org/node/1');
  const route = JSON.parse(calls[1].options.body);
  assert.equal(route.costing, 'pedestrian'); assert.equal(route.locations.length, 3);
  assert.deepEqual(route.locations[0], route.locations[2]);
  assert.equal(calls[1].options.headers['X-Client-Id'], 'binteum-pleos-local-dev');
  assert.ok(calls[0].options.body.includes('around%3A1500%2C37.5%2C127'));
  assert.ok(new URLSearchParams(calls[0].options.body).get('data').endsWith('out center;'));
});

test('invalid, private, wrong-category, duplicate and distant map objects are excluded; max three routes', async () => {
  let routes = 0;
  const elements = [osmPlace(1), osmPlace(1), osmPlace(2), osmPlace(3), osmPlace(4), osmPlace(5, { lat: 1 }),
    osmPlace(6, { tags: { name: 'Private', amenity: 'cafe', access: 'private' } }), osmPlace(7, { tags: { name: 'Wrong', shop: 'convenience' } })];
  const service = createNearbyService({ now: () => NOW, routeSpacingMs: 0, fetchImpl: async url => {
    if (String(url).includes('overpass')) return json({ elements }); routes++; return json(trip());
  } });
  const result = await service.search(input());
  assert.equal(result.places.length, 3); assert.equal(routes, 3);
});

test('empty OSM response stays empty; errors are not disguised as no places', async () => {
  const empty = createNearbyService({ now: () => NOW, fetchImpl: async () => json({ elements: [] }) });
  assert.deepEqual((await empty.search(input())).places, []);
  const broken = createNearbyService({ now: () => NOW, fetchImpl: async () => json({ remark: 'runtime error', elements: [] }) });
  await assert.rejects(broken.search(input()), { code: 'PROVIDER_UNAVAILABLE' });
});

test('routing outage retains genuine places without fake durations or additional provider calls', async () => {
  let calls = 0;
  const service = createNearbyService({ now: () => NOW, routeSpacingMs: 0, fetchImpl: async url => {
    calls++;
    return String(url).includes('overpass') ? json({ elements: [osmPlace(1), osmPlace(2), osmPlace(3)] }) : new Response('', { status: 429 });
  } });
  const result = await service.search(input());
  assert.equal(calls, 2); assert.equal(result.places.length, 3);
  for (const place of result.places) { assert.equal(place.fit, 'ROUTE_UNAVAILABLE'); assert.equal(place.totalSeconds, null); assert.equal(place.routeErrorCode, 'RATE_LIMITED'); }
});

test('cache does not re-date source data; recalculates budget and binds new request timestamp', async () => {
  let now = NOW, calls = 0;
  const service = createNearbyService({ now: () => now, routeSpacingMs: 0, fetchImpl: async url => {
    calls++; return String(url).includes('overpass') ? json({ elements: [osmPlace(1)] }) : json(trip());
  } });
  await service.search(input()); now += 20_000;
  const result = await service.search(input({ originTimestampMillis: now, availableSeconds: 600 }));
  assert.equal(calls, 2); assert.equal(result.generatedAtMillis, NOW); assert.equal(result.originTimestampMillis, now);
  assert.equal(result.places[0].fit, 'TOO_LONG');
});

test('global cooldown suppresses repeated different-category requests without retries', async () => {
  const service = createNearbyService({ now: () => NOW, fetchImpl: async () => json({ elements: [] }) });
  await service.search(input());
  await assert.rejects(service.search(input({ category: 'FOOD' })), { code: 'RATE_LIMITED' });
});

test('Kakao has isolated key headers, fixed category, correct coordinate order, separate return call', async () => {
  const calls = [];
  const service = createNearbyService({ key: 'test-not-a-real-key', now: () => NOW, fetchImpl: async (url, options) => {
    calls.push({ url: new URL(url), options });
    if (calls.length === 1) return json({ documents: [{ id: '123', place_name: 'Fixture Cafe', category_group_code: 'CE7', x: '127.0005', y: '37.5005', address_name: 'Fixture Address' }] });
    return json({ status: 'OK', route: { properties: { totalTime: calls.length === 2 ? 120 : 300 } } });
  } });
  const result = await service.search(input());
  assert.equal(result.provider, 'KAKAO'); assert.equal(calls[0].url.searchParams.get('x'), '127');
  assert.equal(calls[0].url.searchParams.get('category_group_code'), 'CE7');
  assert.equal(calls[2].url.searchParams.get('start_x'), '127.0005');
  assert.equal(calls[2].url.searchParams.get('end_x'), '127');
  assert.equal(result.places[0].returnSeconds, 300);
  assert.equal(JSON.stringify(result).includes('test-not-a-real-key'), false);
});

test('Kakao provider authorization error does not leak body/key and does not fall back silently', async () => {
  const service = createNearbyService({ key: 'test-not-a-real-key', now: () => NOW, fetchImpl: async () => new Response('secret diagnostic', { status: 401 }) });
  await assert.rejects(service.search(input()), error => error.code === 'PROVIDER_AUTH' && !error.message.includes('secret'));
});

test('HTTP boundary rejects browser cross-origin and malformed JSON; health has no secrets', async t => {
  const server = createLocalServer({ provider: 'OPENSTREETMAP', search: async body => body });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const health = await (await fetch(`${base}/health`)).json();
  assert.deepEqual(health, { status: 'ok', provider: 'OPENSTREETMAP', configured: true, localDevelopmentOnly: true });
  const hostileHostStatus = await new Promise((resolve, reject) => {
    const request = httpRequest(`${base}/health`, { headers: { Host: 'attacker.example' } }, reply => { reply.resume(); resolve(reply.statusCode); });
    request.on('error', reject); request.end();
  });
  assert.equal(hostileHostStatus, 403);
  assert.equal((await fetch(`${base}/v1/nearby`, { method: 'POST', headers: { Origin: 'https://example.com', 'Content-Type': 'application/json' }, body: '{}' })).status, 403);
  assert.equal((await fetch(`${base}/v1/nearby`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: 'bad' })).status, 400);
  assert.equal((await fetch(`${base}/v1/nearby`, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '{}' })).status, 415);
});


test('provider429 creates30second backoff without endpoint switching', async () => {
  let now = NOW, calls = 0;
  const service = createNearbyService({ now: () => now, fetchImpl: async () => { calls++; return new Response('', { status: 429 }); } });
  await assert.rejects(service.search(input()), { code: 'RATE_LIMITED' });
  now += 16000;
  await assert.rejects(service.search(input({ originTimestampMillis: now, category: 'FOOD' })), { code: 'RATE_LIMITED' });
  assert.equal(calls, 1);
});

const localFixture = (extra = {}) => ({ formatVersion: 1, source: 'Geofabrik / OpenStreetMap',
  sourceUrl: 'https://download.geofabrik.de/asia/south-korea-latest.osm.pbf', datasetTimestamp: '2026-11-08T21:21:00Z',
  coverage: { countryCode: 'KR', bounds: { south: 32, north: 39, west: 123, east: 132 } },
  places: [{ id: 'node/100', category: 'CAFE', name: 'Test extract cafe', address: 'Test address', latitude: 37.5005, longitude: 127.0005, positionType: 'node' },
    { id: 'way/200', category: 'PARK', name: 'Test extract park', latitude: 37.5006, longitude: 127.0006, positionType: 'representative_point' }], ...extra });

test('local extract uses only real supplied index records and never sends an Overpass request', async () => {
  const calls = [];
  const service = createNearbyService({ localIndex: localFixture(), now: () => NOW, routeSpacingMs: 0,
    fetchImpl: async (url, options) => { calls.push(String(url)); return json(trip()); } });
  const result = await service.search(input());
  assert.equal(calls.length, 1); assert.ok(calls[0].includes('valhalla'));
  assert.equal(result.places.length, 1); assert.equal(result.places[0].name, 'Test extract cafe');
  assert.equal(result.places[0].url, 'https://www.openstreetmap.org/node/100');
  assert.equal(result.dataSource.kind, 'LOCAL_OSM_EXTRACT'); assert.equal(result.coverageStatus, 'WITHIN_COVERAGE');
  assert.ok(result.attribution.includes('2026-11-08'));
});

test('local extract outside coverage returns an explicit empty result without network calls', async () => {
  const service = createNearbyService({ localIndex: localFixture(), now: () => NOW,
    fetchImpl: async () => { throw new Error('must not request'); } });
  const result = await service.search(input({ lat: 51.5, lon: -0.12 }));
  assert.deepEqual(result.places, []); assert.equal(result.coverageStatus, 'OUTSIDE_COVERAGE');
});

test('local extract trust boundary rejects invalid metadata and filters invalid or wrong-category points', async () => {
  for (const patch of [{ formatVersion: 2 }, { sourceUrl: 'https://evil.example/data' }, { datasetTimestamp: 'bad' }, { places: [] }]) {
    assert.throws(() => createNearbyService({ localIndex: localFixture(patch) }), /LOCAL_POI_INDEX_INVALID/);
  }
  const data = localFixture();
  data.places.push({ ...data.places[0], id: 'node/999', latitude: 90 });
  data.places.push({ ...data.places[0], id: 'node/998', category: 'CONVENIENCE' });
  data.places.push({ ...data.places[0], id: 'node/997', url: 'https://evil.example' });
  const service = createNearbyService({ localIndex: data, now: () => NOW, routeSpacingMs: 0, fetchImpl: async () => json(trip()) });
  const result = await service.search(input());
  assert.equal(result.places.length, 2); assert.ok(result.places.every(p => p.url.startsWith('https://www.openstreetmap.org/')));
});


test('missing local data requires setup; public Overpass is only an explicit opt-in', async () => {
  const absent = { LOCAL_POI_FILE: '/does-not-exist/binteum-test-pois.json' };
  const missing = createConfiguredService(absent);
  assert.equal(missing.configured, false); assert.equal(missing.dataSource.kind, 'MISSING_LOCAL_DATA');
  await assert.rejects(missing.search(input()), { code: 'NOT_CONFIGURED' });
  const optIn = createConfiguredService({ ...absent, NEARBY_USE_OVERPASS: '1' });
  assert.equal(optIn.configured, true); assert.equal(optIn.dataSource.kind, 'OVERPASS_API');
});
