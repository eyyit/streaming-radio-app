// Actual RTDB engine tests, never a JavaScript approximation of rules.
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
if (!host || !/^(127\.0\.0\.1|localhost):\d+$/.test(host)) throw new Error('Local Firebase emulator required.');
const base = `http://${host}`;
const jwt = uid => {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  return `${encode({alg:'none',typ:'JWT'})}.${encode({sub:uid,user_id:uid,iat:now,exp:now+3600,aud:'demo-phone-transfer',iss:'https://securetoken.google.com/demo-phone-transfer',firebase:{sign_in_provider:'anonymous'}})}.`;
};
async function call(path, uid, method = 'GET', data) {
  return fetch(`${base}/${path}.json?ns=demo-phone-transfer-default-rtdb${uid === 'admin' ? '' : '&auth=' + jwt(uid)}`, {method,
    headers: {...(uid === 'admin' ? {Authorization: 'Bearer owner'} : {}), 'Content-Type':'application/json'},
    ...(data === undefined ? {} : {body:JSON.stringify(data)})});
}
const ok = async promise => {const response = await promise; assert.equal(response.ok, true, await response.text());};
const denied = async promise => {const response = await promise; assert.equal(response.status, 401, await response.text());};
await ok(call('.settings/rules', 'admin', 'PUT', JSON.parse(await readFile(new URL('../database.rules.json', import.meta.url)))));
await ok(call('', 'admin', 'PUT', null));
let count = 0;
const run = async (name, task) => {await task(); count++; console.log(`PASS ${name}`);};
const id = 'a'.repeat(64), secret = 'b'.repeat(64), other = 'c'.repeat(64);
const record = () => ({ownerUid:'car',claimSecret:secret,createdAt:Date.now(),expiresAt:Date.now()+290000,status:'waiting'});
let transfer = record();
await run('deny root/list/unpaired and wrong identity creation', async () => {
  await denied(call('', 'car')); await denied(call('transfers','car'));
  await denied(call(`transfers/${id}`,'attacker','PUT',transfer));
});
await run('timestamps, lifetime, unknown fields and missing schema', async () => {
  for (const bad of [{...transfer,createdAt:Date.now()+60000}, {...transfer,expiresAt:Date.now()+400000}, {...transfer,extra:1}, {ownerUid:'car'}]) {
    await denied(call(`transfers/${id}`,'car','PUT',bad));
  }
});
await ok(call(`transfers/${id}`,'car','PUT',transfer));
await run('secret capability required and cross-session access denied', async () => {
  await denied(call(`transfers/${id}`,'phone'));
  await denied(call(`claims/${id}`,'phone','PUT',{phoneUid:'phone',secret:other}));
  await denied(call(`claims/${id}`,'phone','PUT',{phoneUid:'attacker',secret}));
  await ok(call(`claims/${id}`,'phone','PUT',{phoneUid:'phone',secret}));
  await ok(call(`transfers/${id}`,'phone'));
  await denied(call(`claims/${id}`,'attacker','PUT',{phoneUid:'attacker',secret}));
  await denied(call(`transfers/${other}`,'phone'));
});
await run('immutable metadata, schema and oversize payload fail', async () => {
  const submitted = {...transfer,status:'submitted',payload:{kind:'file',value:'x',name:''}};
  const userInfo = new URL('https://example.org/a.m3u');
  userInfo.username = 'fixture';
  for (const value of ['http:' + '//example.org/a.m3u', userInfo.href]) {
    await denied(call(`transfers/${id}`,'phone','PUT',{...submitted,payload:{kind:'url',value,name:''}}));
  }
  for (const bad of [{...submitted,expiresAt:transfer.expiresAt+1}, {...submitted,payload:{kind:'file',value:'x'.repeat(1048577),name:''}}, {...submitted,payload:{kind:'url',value:'https://x/'+ 'x'.repeat(4096),name:''}}, {...submitted,payload:{kind:'file',value:'x',name:'x'.repeat(513)}}, {...submitted,payload:{kind:'file',value:'x',name:'',extra:1}}]) {
    await denied(call(`transfers/${id}`,'phone','PUT',bad));
  }
  await denied(call(`transfers/${id}`,'car','PUT',submitted));
});
await run('maximum file, Unicode and one-use submit', async () => {
  const submitted = {...transfer,status:'submitted',payload:{kind:'file',value:'x'.repeat(1048576),name:'日本語'}};
  await ok(call(`transfers/${id}`,'phone','PUT',submitted));
  await denied(call(`transfers/${id}`,'phone','PUT',submitted));
});
await run('unrelated deletion denied; pair atomic delete succeeds', async () => {
  await denied(call(`transfers/${id}`,'attacker','DELETE'));
  await denied(call(`claims/${id}`,'attacker','DELETE'));
  await ok(call('','phone','PATCH',{[`transfers/${id}`]:null,[`claims/${id}`]:null}));
});
await run('expired read/claim/submit denied; paired cleanup permitted', async () => {
  transfer = {...record(),createdAt:Date.now()-400000,expiresAt:Date.now()-100000};
  await ok(call(`transfers/${id}`,'admin','PUT',transfer));
  await ok(call(`claims/${id}`,'admin','PUT',{phoneUid:'phone',secret}));
  await denied(call(`transfers/${id}`,'car')); await denied(call(`claims/${id}`,'phone'));
  await denied(call(`claims/${id}`,'phone','PUT',{phoneUid:'phone',secret}));
  await denied(call(`transfers/${id}`,'phone','PUT',{...transfer,status:'submitted',payload:{kind:'file',value:'x',name:''}}));
  await ok(call('','phone','PATCH',{[`transfers/${id}`]:null,[`claims/${id}`]:null}));
});
await run('stale claim blocks ID reuse', async () => {
  await ok(call(`claims/${id}`,'admin','PUT',{phoneUid:'phone',secret}));
  await denied(call(`transfers/${id}`,'car','PUT',record()));
  await ok(call(`claims/${id}`,'phone','DELETE'));
});
await run('canonical HTTPS URL and query at sign are accepted', async () => {
  transfer = record();
  await ok(call(`transfers/${id}`,'car','PUT',transfer));
  await ok(call(`claims/${id}`,'phone','PUT',{phoneUid:'phone',secret}));
  await ok(call(`transfers/${id}`,'phone','PUT',{...transfer,status:'submitted',payload:{kind:'url',value:'https://xn--mnich-kva.example/a%20playlist.m3u?label=@radio',name:''}}));
  await ok(call('','car','PATCH',{[`transfers/${id}`]:null,[`claims/${id}`]:null}));
});
console.log(`${count} actual RTDB rules scenarios passed`);
