'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { inventoryPurls, validateBatch, scan, main } = require('./check-dependencies.cjs');
const inventory = Array.from({ length: 80 }, (_, i) => ({ purl: `pkg:maven/example/library${i}@1.0.0` }));
const purls = inventoryPurls(inventory);
const response = body => new Response(JSON.stringify(body));

test('accepts all eighty ordered empty results and submits only purls', async () => {
  const result = await scan(inventory, async (url, options) => {
    assert.equal(url, 'https://api.osv.dev/v1/querybatch');
    assert.deepEqual(JSON.parse(options.body), { queries: purls.map(purl => ({ package: { purl } })) });
    return response({ results: purls.map(() => ({})) });
  });
  assert.equal(result.Status, 'NO_MATCHES');
  assert.equal(result.Components, 80);
  assert.deepEqual(result.Findings, []);
});

for (const [name, body] of Object.entries({
  empty: {}, emptyResults: { results: [] }, truncated: { results: [{}] }, extra: { results: Array(81).fill({}) },
  nonarray: { results: {} }, null: null, array: [],
  nullResult: { results: [null] }, arrayResult: { results: [[]] },
  error: { results: [{ error: 'upstream failure' }] },
  nullVulns: { results: [{ vulns: null }] }, objectVulns: { results: [{ vulns: {} }] },
  nullAdvisory: { results: [{ vulns: [null] }] }, missingId: { results: [{ vulns: [{}] }] },
  blankId: { results: [{ vulns: [{ id: '' }] }] }, numericId: { results: [{ vulns: [{ id: 1 }] }] },
  unsafeId: { results: [{ vulns: [{ id: 'https://private.example/token' }] }] },
  page: { results: [{ next_page_token: 'another-page' }] },
  malformedPage: { results: [{ next_page_token: 123 }] }
})) {
  test(`rejects ${name} response instead of returning zero findings`, () => {
    assert.throws(() => validateBatch(body, ['empty', 'truncated', 'extra', 'nonarray'].includes(name) ? purls : purls.slice(0, 1)));
  });
}

test('keeps advisory-to-package order and allows documented empty lists', () => {
  assert.deepEqual(validateBatch({ results: [{ vulns: [] }, { vulns: [{ id: 'GHSA-test-1234', modified: '2026-11-02T00:00:00Z' }] }] }, purls.slice(0, 2)),
    [{ Component: purls[1], Advisory: 'GHSA-test-1234' }]);
});

for (const bad of [[], {}, null, [{ purl: 'https://private.example/token' }], [inventory[0], inventory[0]], Array(1001).fill(inventory[0])]) {
  test(`rejects invalid inventory ${JSON.stringify(bad).slice(0, 50)}`, () => assert.throws(() => inventoryPurls(bad)));
}

test('rejects HTTP errors, invalid JSON and network errors without leaking response text', async () => {
  await assert.rejects(scan(inventory, async () => new Response('private response', { status: 503 })), /HTTP_ERROR/);
  await assert.rejects(scan(inventory, async () => new Response('private response')), /INVALID_JSON_RESPONSE/);
  await assert.rejects(scan(inventory, async () => { throw new Error('private response'); }), /REQUEST_FAILED/);
});

test('rejects response bodies beyond the resource bound', async () => {
  await assert.rejects(scan(inventory, async () => new Response(' '.repeat(8 * 1024 * 1024 + 1))), /RESPONSE_TOO_LARGE/);
});

test('failed CLI inventory replaces stale clean report with explicit error and exits nonzero', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-dependency-test-'));
  try {
    const input = path.join(dir, 'inventory.json'), output = path.join(dir, 'report.json');
    fs.writeFileSync(input, '[]');
    fs.writeFileSync(output, JSON.stringify({ Status: 'NO_MATCHES' }));
    assert.equal(await main(['--inventory', input, '--output', output]), 2);
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.Status, 'ERROR');
    assert.equal(report.Error, 'INVALID_INVENTORY_COUNT');
    assert.equal('Findings' in report, false);
  } finally {
    for (const name of ['inventory.json', 'report.json']) fs.unlinkSync(path.join(dir, name));
    fs.rmdirSync(dir);
  }
});

test('CLI exits one for real advisories and two for HTTP failure; failure replaces prior report', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'radio-dependency-status-'));
  try {
    const input = path.join(dir, 'inventory.json'), output = path.join(dir, 'report.json');
    fs.writeFileSync(input, JSON.stringify(inventory.slice(0, 1)));
    const args = ['--inventory', input, '--output', output];
    assert.equal(await main(args, async () => response({ results: [{ vulns: [{ id: 'GHSA-test-1234' }] }] })), 1);
    assert.equal(JSON.parse(fs.readFileSync(output, 'utf8')).Status, 'ADVISORIES_FOUND');
    assert.equal(await main(args, async () => new Response('private response', { status: 502 })), 2);
    const report = JSON.parse(fs.readFileSync(output, 'utf8'));
    assert.equal(report.Status, 'ERROR');
    assert.equal(report.Error, 'HTTP_ERROR');
    assert.equal(JSON.stringify(report).includes('private response'), false);
  } finally {
    for (const name of ['inventory.json', 'report.json']) fs.unlinkSync(path.join(dir, name));
    fs.rmdirSync(dir);
  }
});
