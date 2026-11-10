const KAKAO = 'https://dapi.kakao.com';
const OVERPASS = 'https://overpass.private.coffee/api/interpreter';
const VALHALLA = 'https://valhalla1.openstreetmap.de/route';
const CLIENT = 'binteum-pleos-local-dev';
const USER_AGENT = 'BinteumPleosPrototype/0.1 (local development; https://github.com/wnsmir/binteum)';
const RESERVE = 300;
const MAX_AGE = 120_000;
const MAX_PLACES = 3;
const RADIUS = 1500;
const CATEGORY = Object.freeze({
  CAFE: { kakao: 'CE7', stay: 600, osm: '["amenity"="cafe"]' },
  FOOD: { kakao: 'FD6', stay: 1200, osm: '["amenity"~"^(restaurant|fast_food)$"]' },
  CONVENIENCE: { kakao: 'CS2', stay: 300, osm: '["shop"="convenience"]' },
  PARK: { stay: 600, osm: '["leisure"="park"]' },
});

export class ApiError extends Error {
  constructor(code, status = 503, retryable = true) {
    super(code); this.code = code; this.status = status; this.retryable = retryable;
  }
}

export function validateRequest(value, now = Date.now()) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !finite(value.lat, -90, 90) || !finite(value.lon, -180, 180) ||
      !Object.hasOwn(CATEGORY, value.category) || !Number.isSafeInteger(value.originTimestampMillis) ||
      (value.availableSeconds != null && (!Number.isSafeInteger(value.availableSeconds) || value.availableSeconds < 0 || value.availableSeconds > 86400))) {
    throw new ApiError('INVALID_REQUEST', 400, false);
  }
  if (value.originTimestampMillis > now + 10_000 || now - value.originTimestampMillis > MAX_AGE) {
    throw new ApiError('LOCATION_STALE', 400, false);
  }
  return { lat: value.lat, lon: value.lon, category: value.category,
    availableSeconds: value.availableSeconds ?? null, originTimestampMillis: value.originTimestampMillis };
}

const finite = (n, min, max) => typeof n === 'number' && Number.isFinite(n) && n >= min && n <= max;
const clean = (s, max = 160) => typeof s === 'string' ? s.replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, max) : '';
const boundedSeconds = n => finite(n, 0, 21600) ? Math.ceil(n) : null;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export function assess(outboundSeconds, returnSeconds, staySeconds, availableSeconds) {
  if (outboundSeconds == null || returnSeconds == null) return { totalSeconds: null, fit: 'ROUTE_UNAVAILABLE' };
  const totalSeconds = outboundSeconds + returnSeconds + staySeconds + RESERVE;
  return { totalSeconds, fit: availableSeconds == null ? 'UNKNOWN' : totalSeconds <= availableSeconds ? 'FITS' : 'TOO_LONG' };
}

function distanceMeters(a, b) {
  const rad = n => n * Math.PI / 180;
  const deltaLat = rad(b.latitude - a.lat), deltaLon = rad(b.longitude - a.lon);
  const h = Math.sin(deltaLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.latitude)) * Math.sin(deltaLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}

function walkingLink(origin, destination, provider) {
  if (provider === 'KAKAO') return `https://map.kakao.com/link/by/walk/${encodeURIComponent(`출발,${origin.lat},${origin.lon}`)}/${encodeURIComponent(`도착,${destination.lat},${destination.lon}`)}`;
  const url = new URL('https://www.openstreetmap.org/directions');
  url.searchParams.set('engine', 'fossgis_valhalla_foot');
  url.searchParams.set('route', `${origin.lat},${origin.lon};${destination.lat},${destination.lon}`);
  return url.toString();
}

/** Validate a locally generated regional extract; no fixture or arbitrary provider URL path. */
export function validateLocalIndex(value) {
  const stamp = typeof value?.datasetTimestamp === 'string' ? Date.parse(value.datasetTimestamp) : NaN;
  const bounds = value?.coverage?.bounds;
  if (value?.formatVersion !== 1 || value?.coverage?.countryCode !== 'KR' ||
      !/^https:\/\/download\.geofabrik\.de\/asia\/south-korea-(latest|[0-9]{6})\.osm\.pbf$/.test(value?.sourceUrl || '') ||
      !Number.isFinite(stamp) || !bounds || !finite(bounds.south, 30, 39) || !finite(bounds.north, 32, 40) ||
      !finite(bounds.west, 120, 132) || !finite(bounds.east, 123, 135) || bounds.south >= bounds.north || bounds.west >= bounds.east ||
      !Array.isArray(value.places) || value.places.length === 0 || value.places.length > 500000) {
    throw new Error('LOCAL_POI_INDEX_INVALID');
  }
  const seen = new Set();
  const places = value.places.flatMap(place => {
    const id = typeof place?.id === 'string' ? place.id : '';
    const name = clean(place?.name);
    if (!/^(node|way|relation)\/[1-9][0-9]{0,18}$/.test(id) || seen.has(id) || !name || !Object.hasOwn(CATEGORY, place?.category) ||
        !finite(place?.latitude, bounds.south, bounds.north) || !finite(place?.longitude, bounds.west, bounds.east) ||
        !['node', 'representative_point'].includes(place?.positionType)) return [];
    seen.add(id);
    return [{ id, category: place.category, name, address: clean(place.address, 300), latitude: place.latitude, longitude: place.longitude,
      positionType: place.positionType, url: `https://www.openstreetmap.org/${id}` }];
  });
  if (!places.length) throw new Error('LOCAL_POI_INDEX_INVALID');
  return { places, bounds: { ...bounds }, datasetTimestamp: new Date(stamp).toISOString(), sourceUrl: value.sourceUrl };
}

/** Coordinates/route results live only in this small TTL cache; never written to disk or logs. */
export function createNearbyService({ key = '', fetchImpl = fetch, now = Date.now, pause = sleep,
  requestTimeoutMs = 10_000, routeSpacingMs = 1200, cooldownMs = 15_000, localIndex = null } = {}) {
  const apiKey = key.trim();
  const provider = apiKey ? 'KAKAO' : 'OPENSTREETMAP';
  const local = !apiKey && localIndex ? validateLocalIndex(localIndex) : null;
  const dataSource = local ? { kind: 'LOCAL_OSM_EXTRACT', datasetTimestamp: local.datasetTimestamp, sourceUrl: local.sourceUrl, coverage: 'SOUTH_KOREA' } :
    { kind: provider === 'KAKAO' ? 'KAKAO_API' : 'OVERPASS_API' };
  const inCoverage = input => !local || (input.lat >= local.bounds.south && input.lat <= local.bounds.north && input.lon >= local.bounds.west && input.lon <= local.bounds.east);
  let busy = false;
  let lastRequestAt = -Infinity;
  let lastRouteAt = -Infinity;
  let providerBlockedUntil = -Infinity;
  const cache = new Map();

  async function json(url, options = {}, signal) {
    const timeoutSignal = AbortSignal.timeout(requestTimeoutMs);
    try {
      const response = await fetchImpl(url, { ...options, redirect: 'error',
        signal: signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal,
        headers: { 'User-Agent': USER_AGENT, Accept: 'application/json', ...options.headers } });
      if (response.status === 429 || response.status === 406) {
        providerBlockedUntil = now() + 30_000;
        throw new ApiError('RATE_LIMITED', 429);
      }
      if (response.status === 401 || response.status === 403) throw new ApiError('PROVIDER_AUTH', 503, false);
      if (!response.ok) throw new ApiError('PROVIDER_UNAVAILABLE');
      // A bounded small-area query should never require an unbounded download.
      if (Number(response.headers.get('content-length')) > 1_048_576) throw new ApiError('PROVIDER_UNAVAILABLE');
      const reader = response.body?.getReader();
      if (!reader) throw new ApiError('PROVIDER_UNAVAILABLE');
      const chunks = []; let count = 0;
      while (true) {
        const part = await reader.read(); if (part.done) break;
        count += part.value.byteLength;
        if (count > 1_048_576) { await reader.cancel(); throw new ApiError('PROVIDER_UNAVAILABLE'); }
        chunks.push(part.value);
      }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(timeoutSignal.aborted || signal?.aborted ? 'PROVIDER_TIMEOUT' : 'PROVIDER_UNAVAILABLE');
    }
  }

  async function findKakao(input, signal) {
    const category = CATEGORY[input.category];
    const url = new URL(category.kakao ? '/v2/local/search/category.json' : '/v2/local/search/keyword.json', KAKAO);
    const params = { x: input.lon, y: input.lat, radius: RADIUS, size: 15, sort: 'distance' };
    if (category.kakao) params.category_group_code = category.kakao; else params.query = '공원';
    for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
    const data = await json(url, { headers: { Authorization: `KakaoAK ${apiKey}` } }, signal);
    if (!Array.isArray(data.documents)) throw new ApiError('PROVIDER_UNAVAILABLE');
    return data.documents.flatMap(item => {
      const id = String(item.id ?? ''); const latitude = Number(item.y), longitude = Number(item.x);
      const name = clean(item.place_name);
      if (!/^\d{1,32}$/.test(id) || !name || !finite(latitude, -90, 90) || !finite(longitude, -180, 180)) return [];
      if (category.kakao && item.category_group_code !== category.kakao) return [];
      if (!category.kakao && !clean(item.category_name).split('>').some(part => part.trim().endsWith('공원'))) return [];
      return [{ id, name, latitude, longitude, address: clean(item.road_address_name || item.address_name),
        url: `https://map.kakao.com/link/map/${id}` }];
    });
  }

  async function findOsm(input, signal) {
    const query = `[out:json][timeout:8][maxsize:8388608];nwr(around:${RADIUS},${input.lat},${input.lon})${CATEGORY[input.category].osm};out center;`;
    const data = await json(OVERPASS, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Client-Id': CLIENT },
      body: new URLSearchParams({ data: query }).toString() }, signal);
    if (!Array.isArray(data.elements) || data.remark) throw new ApiError('PROVIDER_UNAVAILABLE');
    return data.elements.flatMap(item => {
      if (!['node', 'way', 'relation'].includes(item.type) || !Number.isSafeInteger(item.id) || item.id <= 0) return [];
      const tags = item.tags || {};
      const latitude = item.lat ?? item.center?.lat, longitude = item.lon ?? item.center?.lon;
      const name = clean(tags['name:ko'] || tags.name || tags.brand);
      if (!name || !finite(latitude, -90, 90) || !finite(longitude, -180, 180)) return [];
      if (['private', 'no'].includes(tags.access) || ['private', 'no'].includes(tags.foot) || tags.disused === 'yes') return [];
      const matches = input.category === 'CAFE' ? tags.amenity === 'cafe' : input.category === 'FOOD' ? ['restaurant', 'fast_food'].includes(tags.amenity) :
        input.category === 'CONVENIENCE' ? tags.shop === 'convenience' : tags.leisure === 'park';
      if (!matches) return [];
      const address = clean(tags['addr:full']) || ['addr:city', 'addr:street', 'addr:housenumber'].map(key => clean(tags[key])).filter(Boolean).join(' ');
      return [{ id: `${item.type}/${item.id}`, name, latitude, longitude, address,
        url: `https://www.openstreetmap.org/${item.type}/${item.id}` }];
    });
  }

  async function kakaoLeg(from, to, signal) {
    const url = new URL('/v2/routing/walk', KAKAO);
    for (const [key, value] of Object.entries({ start_x: from.lon, start_y: from.lat, end_x: to.lon, end_y: to.lat, route_mode: 'BROAD_FIRST' })) {
      url.searchParams.set(key, String(value));
    }
    const data = await json(url, { headers: { Authorization: `KakaoAK ${apiKey}` } }, signal);
    if (data.status !== 'OK') return null;
    return boundedSeconds(data.route?.properties?.totalTime);
  }

  async function route(input, place, signal) {
    const destination = { lat: place.latitude, lon: place.longitude };
    if (provider === 'KAKAO') {
      // Deliberately separate directions: return access/duration may differ.
      const outbound = await kakaoLeg(input, destination, signal);
      const back = await kakaoLeg(destination, input, signal);
      return [outbound, back];
    }
    const delay = routeSpacingMs - (now() - lastRouteAt);
    if (delay > 0) await pause(delay);
    if (signal?.aborted) throw new ApiError('PROVIDER_TIMEOUT');
    lastRouteAt = now();
    const data = await json(VALHALLA, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Client-Id': CLIENT },
      body: JSON.stringify({ locations: [input, destination, input].map(point => ({ lat: point.lat, lon: point.lon, type: 'break' })),
        costing: 'pedestrian', units: 'kilometers', directions_options: { directions_type: 'none' } }) }, signal);
    if (data.trip?.status !== 0 || !Array.isArray(data.trip.legs) || data.trip.legs.length !== 2) return [null, null];
    return data.trip.legs.map(leg => boundedSeconds(leg.summary?.time));
  }

  function response(input, cached) {
    // Never re-date cached map/route data; client can reject a result after its freshness window.
    const order = { FITS: 0, UNKNOWN: 1, TOO_LONG: 2, ROUTE_UNAVAILABLE: 3 };
    return { provider, dataSource, coverageStatus: inCoverage(input) ? 'WITHIN_COVERAGE' : 'OUTSIDE_COVERAGE', generatedAtMillis: cached.fetchedAt, originTimestampMillis: input.originTimestampMillis,
      returnReserveSeconds: RESERVE, attribution: provider === 'KAKAO' ? 'Kakao Map' : local ? `© OpenStreetMap contributors · Geofabrik ${local.datasetTimestamp.slice(0, 10)} · Valhalla` : '© OpenStreetMap contributors · Valhalla',
      places: cached.places.map(place => ({ ...place, ...assess(place.outboundSeconds, place.returnSeconds, place.staySeconds, input.availableSeconds) }))
        .sort((a, b) => order[a.fit] - order[b.fit] || (a.totalSeconds ?? Infinity) - (b.totalSeconds ?? Infinity)) };
  }

  return {
    provider, dataSource, configured: true,
    async search(raw, { signal } = {}) {
      const input = validateRequest(raw, now());
      const cacheKey = `${input.category}/${input.lat}/${input.lon}`;
      for (const [key, value] of cache) if (now() - value.fetchedAt > 60_000) cache.delete(key);
      const existing = cache.get(cacheKey);
      if (existing) return response(input, existing);
      if (busy || now() < providerBlockedUntil || now() - lastRequestAt < cooldownMs) throw new ApiError('RATE_LIMITED', 429);
      busy = true; lastRequestAt = now();
      try {
        const found = local ? (inCoverage(input) ? local.places.filter(place => place.category === input.category) : []) :
          await (provider === 'KAKAO' ? findKakao(input, signal) : findOsm(input, signal));
        const seen = new Set();
        const candidates = found.filter(place => {
          const key = place.id;
          if (seen.has(key) || distanceMeters(input, place) > RADIUS + 100) return false;
          seen.add(key); return true;
        }).sort((a, b) => distanceMeters(input, a) - distanceMeters(input, b)).slice(0, MAX_PLACES);
        const places = [];
        for (const place of candidates) {
          let times = [null, null], routeErrorCode = null;
          try { times = await route(input, place, signal); }
          catch (error) { routeErrorCode = error instanceof ApiError ? error.code : 'PROVIDER_UNAVAILABLE'; }
          const both = times.every(value => value != null);
          places.push({ ...place, outboundSeconds: times[0], returnSeconds: times[1], staySeconds: CATEGORY[input.category].stay,
            outboundUrl: both ? walkingLink(input, { lat: place.latitude, lon: place.longitude }, provider) : null,
            returnUrl: both ? walkingLink({ lat: place.latitude, lon: place.longitude }, input, provider) : null,
            routeErrorCode });
          if (signal?.aborted) throw new ApiError('PROVIDER_TIMEOUT');
          // Do not repeatedly call a public provider after a rate/auth/server failure.
          if (routeErrorCode) {
            for (const pending of candidates.slice(places.length)) places.push({ ...pending, outboundSeconds: null, returnSeconds: null,
              staySeconds: CATEGORY[input.category].stay, outboundUrl: null, returnUrl: null, routeErrorCode });
            break;
          }
        }
        validateRequest(input, now());
        const result = { fetchedAt: now(), places };
        cache.set(cacheKey, result);
        while (cache.size > 8) cache.delete(cache.keys().next().value);
        return response(input, result);
      } finally { busy = false; }
    },
  };
}
