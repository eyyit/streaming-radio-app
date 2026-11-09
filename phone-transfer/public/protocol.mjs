export const LIMIT = 1048576;
export const HEX = /^[a-f0-9]{64}$/;

export function pairing(fragment) {
  const params = new URLSearchParams(fragment.replace(/^#/, ''));
  const id = params.get('s'), secret = params.get('k');
  if (!HEX.test(id || '') || !HEX.test(secret || '')) {
    throw new Error('Open a new QR code from the parked car.');
  }
  return {id, secret};
}

export function payload(kind, value, name = '') {
  if (!['url', 'file'].includes(kind) || typeof value !== 'string' ||
      typeof name !== 'string' || name.length > 512 || !value.length ||
      new TextEncoder().encode(value).length > LIMIT) {
    throw new Error('Use a playlist up to 1 MiB and a name up to 512 characters.');
  }
  if (kind === 'url') {
    let url;
    try { url = new URL(value); } catch { throw new Error('Enter a valid playlist URL.'); }
    if (url.protocol !== 'https:' || url.username || url.password ||
        value.length > 4096 || url.href.length > 4096) {
      throw new Error('Enter an HTTPS playlist URL without credentials, up to 4096 characters.');
    }
    value = url.href;
  } else if (/^#EXT-X-/mi.test(value)) {
    throw new Error('Choose a station playlist, not an HLS media manifest.');
  } else if (!value.split(/\r?\n/).some(line => {
    if (!/^https?:\/\//i.test(line.trim())) return false;
    try {
      const station = new URL(line.trim());
      return ['http:', 'https:'].includes(station.protocol);
    } catch { return false; }
  })) {
    throw new Error('Choose an M3U station list containing absolute HTTP or HTTPS stream URLs.');
  }
  return {kind, value, name};
}

export function configuration(config) {
  if (config.enabled !== true || !/^[A-Za-z0-9_-]{20,}$/.test(config.apiKey || '')) {
    throw new Error('Phone transfer is not configured yet.');
  }
  const url = new URL(config.databaseUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      url.pathname !== '/' || url.search || url.hash ||
      !/^[a-z0-9-]+(?:\.[a-z0-9-]+)?\.(?:firebaseio\.com|firebasedatabase\.app)$/.test(url.hostname)) {
    throw new Error('Invalid Firebase database configuration.');
  }
  return {...config, databaseUrl: url.origin};
}

export async function jsonRequest(url, options = {}, fetcher = fetch) {
  const response = await fetcher(url, {...options, redirect: 'error',
    credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
    signal: AbortSignal.timeout(15000),
    headers: {'Content-Type': 'application/json', ...options.headers}});
  if (!response.ok) throw new Error('Transfer unavailable or expired. Try a new QR code.');
  return response.json();
}

export function databaseRequest(config, token, path, method = 'GET', value) {
  return jsonRequest(`${config.databaseUrl}/${path}.json?auth=${encodeURIComponent(token)}`,
    {method, ...(value === undefined ? {} : {body: JSON.stringify(value)})});
}

export function removePair(config, token, id) {
  if (!HEX.test(id)) throw new Error('Invalid session.');
  // Atomic deletion: phone authorization still sees the existing claim.
  return databaseRequest(config, token, '', 'PATCH',
    {[`claims/${id}`]: null, [`transfers/${id}`]: null});
}
