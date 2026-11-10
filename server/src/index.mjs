import { createServer } from 'node:http';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { ApiError, createNearbyService } from './nearby.mjs';

export function createConfiguredService(env = process.env) {
  const key = env.KAKAO_REST_API_KEY || '';
  const indexPath = env.LOCAL_POI_FILE || fileURLToPath(new URL('../../.local/maps/south-korea-pois.json', import.meta.url));
  let localIndex = null;
  if (!key.trim() && existsSync(indexPath)) {
    if (statSync(indexPath).size > 64 * 1024 * 1024) throw new Error('LOCAL_POI_INDEX_TOO_LARGE');
    try { localIndex = JSON.parse(readFileSync(indexPath, 'utf8')); }
    catch { throw new Error('LOCAL_POI_INDEX_INVALID'); }
  }
  if (!key.trim() && !localIndex && env.NEARBY_USE_OVERPASS !== '1') {
    return { provider: 'OPENSTREETMAP', configured: false, dataSource: { kind: 'MISSING_LOCAL_DATA' },
      async search() { throw new ApiError('NOT_CONFIGURED', 503, false); } };
  }
  return createNearbyService({ key, localIndex });
}

/** Loopback-only local development BFF. No request/body/coordinate/key logging. */
export function createLocalServer(service = createConfiguredService()) {
  return createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    const send = (status, body) => { response.writeHead(status); response.end(JSON.stringify(body)); };
    const port = request.socket.localPort;
    if (![`127.0.0.1:${port}`, `localhost:${port}`, `[::1]:${port}`].includes(request.headers.host)) {
      send(403, { error: { code: 'INVALID_REQUEST', message: 'Loopback Host is required.', retryable: false } }); return;
    }
    if (request.headers.origin) { send(403, { error: { code: 'INVALID_REQUEST', message: 'Browser cross-origin requests are not supported.', retryable: false } }); return; }
    if (request.method === 'GET' && request.url === '/health') {
      send(200, { status: 'ok', provider: service.provider, configured: service.configured !== false, localDevelopmentOnly: true, dataSource: service.dataSource }); return;
    }
    if (request.method !== 'POST' || request.url !== '/v1/nearby') { send(404, { error: { code: 'NOT_FOUND', message: 'Not found.', retryable: false } }); return; }
    if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] || '')) { send(415, { error: { code: 'INVALID_REQUEST', message: 'JSON is required.', retryable: false } }); return; }
    const controller = new AbortController();
    const deadline = setTimeout(() => controller.abort(), 30_000);
    response.on('close', () => { if (!response.writableEnded) controller.abort(); });
    try {
      const chunks = []; let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        if (bytes > 2048) throw new ApiError('INVALID_REQUEST', 413, false);
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { throw new ApiError('INVALID_REQUEST', 400, false); }
      const result = await service.search(body, { signal: controller.signal });
      if (!response.destroyed) send(200, result);
    } catch (error) {
      const failure = error instanceof ApiError ? error : new ApiError('PROVIDER_UNAVAILABLE');
      if (!response.destroyed) {
        if (failure.code === 'RATE_LIMITED') response.setHeader('Retry-After', '30');
        send(failure.status, { error: { code: failure.code, message: failure.code, retryable: failure.retryable } });
      }
    } finally { clearTimeout(deadline); }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT || 3210);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('PORT must be 1024–65535');
  const server = createLocalServer();
  server.headersTimeout = 10_000;
  server.requestTimeout = 50_000;
  server.listen(port, '127.0.0.1', () => console.log(`Binteum nearby local server: http://127.0.0.1:${port} (loopback only)`));
}
