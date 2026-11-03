#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const SERVICE = 'https://api.osv.dev/v1/querybatch';
const LIMIT = 'OSV coverage is incomplete. No matches does not establish that a dependency is vulnerability-free.';
const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

class ScanError extends Error {
  constructor(code) { super(code); this.code = code; }
}
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function inventoryPurls(inventory) {
  if (!Array.isArray(inventory) || inventory.length < 1 || inventory.length > 1000)
    throw new ScanError('INVALID_INVENTORY_COUNT');
  const purls = inventory.map(item => {
    if (!record(item) || typeof item.purl !== 'string' || item.purl.length > 1024 ||
        !/^pkg:maven\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+@[A-Za-z0-9_.+%~-]+$/.test(item.purl))
      throw new ScanError('INVALID_MAVEN_PURL');
    return item.purl;
  });
  if (new Set(purls).size !== purls.length) throw new ScanError('DUPLICATE_PURL');
  return purls;
}

function validateBatch(body, purls) {
  if (!Array.isArray(purls) || purls.length === 0) throw new ScanError('INVALID_INVENTORY_COUNT');
  if (!record(body) || Object.keys(body).some(key => key !== 'results') ||
      !Array.isArray(body.results) || body.results.length !== purls.length)
    throw new ScanError('INCOMPLETE_BATCH_RESPONSE');
  const findings = [];
  body.results.forEach((result, index) => {
    if (!record(result) || Object.keys(result).some(key => !['vulns', 'next_page_token'].includes(key)))
      throw new ScanError('INVALID_RESULT');
    if ('next_page_token' in result &&
        (typeof result.next_page_token !== 'string' || result.next_page_token.length !== 0))
      throw new ScanError('INCOMPLETE_PAGINATED_RESULT');
    // OSV uses an empty object for a package with no matched advisories.
    if (!('vulns' in result)) return;
    if (!Array.isArray(result.vulns)) throw new ScanError('INVALID_ADVISORY_LIST');
    for (const advisory of result.vulns) {
      if (!record(advisory) || typeof advisory.id !== 'string' ||
          !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$/.test(advisory.id) ||
          ('modified' in advisory && typeof advisory.modified !== 'string'))
        throw new ScanError('INVALID_ADVISORY_ID');
      findings.push({ Component: purls[index], Advisory: advisory.id });
    }
  });
  return findings;
}

async function readJson(response) {
  if (!response || response.ok !== true) throw new ScanError('HTTP_ERROR');
  if (!response.body || typeof response.body.getReader !== 'function') throw new ScanError('EMPTY_RESPONSE');
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_RESPONSE_BYTES) throw new ScanError('RESPONSE_TOO_LARGE');
      chunks.push(Buffer.from(value));
    }
  } finally { await reader.cancel().catch(() => {}); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new ScanError('INVALID_JSON_RESPONSE'); }
}

async function scan(inventory, fetchImpl = globalThis.fetch) {
  const purls = inventoryPurls(inventory);
  let body;
  try {
    const response = await fetchImpl(SERVICE, {
      method: 'POST',
      redirect: 'error',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'StreamingRadio-dependency-review' },
      body: JSON.stringify({ queries: purls.map(purl => ({ package: { purl } })) }),
      signal: AbortSignal.timeout(40_000)
    });
    body = await readJson(response);
  } catch (error) {
    if (error instanceof ScanError) throw error;
    // Do not echo arbitrary network errors, response bodies, URLs or local paths.
    throw new ScanError('REQUEST_FAILED');
  }
  const findings = validateBatch(body, purls);
  return {
    Checked: new Date().toISOString(), Service: SERVICE,
    Status: findings.length ? 'ADVISORIES_FOUND' : 'NO_MATCHES',
    Components: purls.length, SubmittedPurls: purls, Findings: findings, Limit: LIMIT
  };
}

async function main(args, fetchImpl = globalThis.fetch) {
  let output;
  try {
    if (args.length !== 4 || args[0] !== '--inventory' || args[2] !== '--output' || !args[1] || !args[3])
      throw new ScanError('USAGE_REQUIRES_INVENTORY_AND_OUTPUT');
    output = path.resolve(args[3]);
    let inventory;
    try { inventory = JSON.parse(fs.readFileSync(args[1], 'utf8').replace(/^\uFEFF/, '')); }
    catch { throw new ScanError('INVENTORY_READ_FAILED'); }
    const result = await scan(inventory, fetchImpl);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    process.stdout.write(`OSV: ${result.Components} components; ${result.Findings.length} advisory matches.\n`);
    return result.Findings.length ? 1 : 0;
  } catch (error) {
    const code = error instanceof ScanError ? error.code : 'SCAN_OR_REPORT_FAILED';
    // Replace a stale clean report on any failed scan; never represent failure as zero findings.
    if (output) {
      try {
        fs.mkdirSync(path.dirname(output), { recursive: true });
        fs.writeFileSync(output, JSON.stringify({ Checked: new Date().toISOString(),
          Service: SERVICE, Status: 'ERROR', Error: code, Limit: LIMIT }, null, 2) + '\n');
      } catch { /* A nonzero exit still blocks CI if a report cannot be written. */ }
    }
    process.stderr.write(`Dependency scan failed (${code}); no clean result.\n`);
    return 2;
  }
}

module.exports = { ScanError, inventoryPurls, validateBatch, scan, main };
if (require.main === module) main(process.argv.slice(2)).then(code => { process.exitCode = code; });
