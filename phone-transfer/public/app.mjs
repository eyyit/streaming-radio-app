import rawConfig from './config.mjs';
import {pairing, payload, configuration, jsonRequest, databaseRequest, removePair, LIMIT} from './protocol.mjs';

const $ = id => document.getElementById(id);
const status = (text, failure = false) => {
  $('status').textContent = text;
  $('status').classList.toggle('failure', failure);
};
const STORAGE = 'streaming-radio-phone-transfer-v1';
const TAB_LOCK = 'streaming-radio-phone-transfer-tab-v1';
let state, config, auth, pair, transfer, selectedFile = null;
let busy = false, ended = false;
const fragment = location.hash;
history.replaceState(null, '', location.pathname + location.search);
const persist = () => localStorage.setItem(STORAGE, JSON.stringify(state));
const request = (path, method, value) => databaseRequest(config, auth.idToken, path, method, value);
const cleanup = async id => {
  await removePair(config, auth.idToken, id);
  state.pending = state.pending.filter(item => item.id !== id);
  persist();
};

function entry(file = selectedFile) {
  selectedFile = file;
  $('heading').textContent = file ? 'Send a file' : 'Send a playlist';
  $('form').hidden = false;
  $('url-fields').hidden = Boolean(file);
  $('url').disabled = Boolean(file);
  $('file-details').hidden = !file;
  $('file-note').hidden = !file;
  $('or').hidden = Boolean(file);
  $('choose').textContent = file ? 'Choose another file' : 'Choose M3U file';
  $('sent').hidden = true;
  $('error').hidden = true;
  $('cancel').hidden = true;
  $('result-note').hidden = true;
  $('privacy').hidden = Boolean(file);
  if (file) {
    $('filename').textContent = file.name;
    $('filesize').textContent = `${Math.max(1, Math.ceil(file.size / 1024))} KB · M3U playlist`;
  }
  status(file ? '' : 'Connected to your car');
}

function sent(name) {
  $('heading').textContent = 'Sent to your car';
  status('Confirm on the car screen');
  $('form').hidden = true;
  $('error').hidden = true;
  $('privacy').hidden = true;
  $('sent').hidden = false;
  $('sent-name').textContent = name || 'Your playlist';
  $('cancel').hidden = false;
  $('result-note').hidden = false;
  $('result-note').textContent = 'After adding, the car attempts to delete the temporary transfer.';
}

function invalidFile(error) {
  $('heading').textContent = 'Could not send';
  status('This file is not a station list.', true);
  $('error-detail').textContent = error.message.includes('HLS')
    ? 'It appears to be an HLS playback manifest. Use an M3U station list containing station stream URLs.'
    : error.message;
  $('form').hidden = true;
  $('error').hidden = false;
  $('privacy').hidden = true;
  $('result-note').hidden = false;
  $('result-note').textContent = 'Nothing was changed in your car.';
}

async function cancel() {
  if (busy || !pair || !auth) return;
  busy = true;
  ended = true;
  $('form').hidden = true;
  $('error').hidden = true;
  $('sent').hidden = true;
  $('cancel').hidden = false;
  $('cancel').disabled = true;
  try {
    await cleanup(pair.id);
    $('heading').textContent = 'Transfer closed';
    status('Temporary transfer deleted.');
    $('cancel').hidden = true;
  } catch {
    status('Cleanup is pending. Reconnect and retry.', true);
    $('cancel').textContent = 'Retry cleanup';
  } finally { busy = false; $('cancel').disabled = false; }
}

async function startSession() {
  config = configuration(rawConfig);
  state = JSON.parse(localStorage.getItem(STORAGE) || '{"pending":[]}');
  if (!Array.isArray(state.pending)) throw new Error('Browser storage unavailable.');
  if (state.refreshToken) {
    const refreshed = await jsonRequest(`https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(config.apiKey)}`,
      {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: new URLSearchParams({grant_type: 'refresh_token', refresh_token: state.refreshToken}).toString()});
    auth = {idToken: refreshed.id_token, localId: refreshed.user_id, refreshToken: refreshed.refresh_token};
  } else {
    auth = await jsonRequest(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(config.apiKey)}`,
      {method: 'POST', body: JSON.stringify({returnSecureToken: true})});
  }
  state.refreshToken = auth.refreshToken;
  persist();
  for (const old of [...state.pending]) {
    if (old.expiresAt <= Date.now()) {
      try { await cleanup(old.id); } catch { /* Retain for a later cleanup attempt. */ }
    }
  }
  pair = pairing(fragment);
  if (state.pending.length >= 16 && !state.pending.some(item => item.id === pair.id)) {
    throw new Error('Previous transfers need cleanup. Reconnect and try again.');
  }
  // Record before the write: a lost response can still create a claim.
  if (!state.pending.some(item => item.id === pair.id)) {
    state.pending.push({id: pair.id, expiresAt: Date.now() + 300000});
    persist();
  }
  await request(`claims/${pair.id}`, 'PUT', {phoneUid: auth.localId, secret: pair.secret});
  transfer = await request(`transfers/${pair.id}`);
  if (transfer.status !== 'waiting') throw new Error('This transfer was already sent. Open a new QR code.');
  state.pending.find(item => item.id === pair.id).expiresAt = transfer.expiresAt;
  persist();
  entry(null);
  const expire = async () => {
    if (ended) return;
    // Let an in-flight bounded request settle before closing the session.
    if (busy) { setTimeout(expire, 250); return; }
    await cancel();
    if ($('cancel').hidden) status('Transfer expired. Open a new QR code.');
  };
  setTimeout(expire, Math.max(0, transfer.expiresAt - Date.now()));
}

const choose = () => { if (!busy && !ended) $('file').click(); };
$('choose').addEventListener('click', choose);
$('recover-file').addEventListener('click', choose);
$('recover-url').addEventListener('click', () => { if (!busy && !ended) entry(null); });
$('file').addEventListener('change', () => {
  if (!busy && !ended && $('file').files[0]) entry($('file').files[0]);
  // Reset native selection so choosing the same corrected file raises change.
  $('file').value = '';
});

$('form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || ended) return;
  busy = true;
  $('send').disabled = true;
  let content;
  try {
    if (selectedFile && selectedFile.size > LIMIT) throw new Error('Choose a file up to 1 MiB.');
    const value = selectedFile ? new TextDecoder('utf-8', {fatal: true}).decode(await selectedFile.arrayBuffer()) : $('url').value.trim();
    content = payload(selectedFile ? 'file' : 'url', value, $('name').value.trim());
  } catch (error) {
    const detail = error instanceof TypeError ? new Error('Choose a UTF-8 M3U station list.') : error;
    if (selectedFile) invalidFile(detail); else status(detail.message, true);
  }
  if (content) {
    try {
      if (ended || Date.now() >= transfer.expiresAt) throw new Error('Transfer expired. Open a new QR code.');
      await request(`transfers/${pair.id}`, 'PUT', {...transfer, status: 'submitted', payload: content});
      sent(content.name || selectedFile?.name);
    } catch {
      // A response may be lost after the one-use submission succeeds.
      try {
        const current = await request(`transfers/${pair.id}`);
        if (current?.status === 'submitted') sent(current.payload?.name || selectedFile?.name);
        else status('Could not send. Reconnect and try Send to car again.', true);
      } catch {
        status('Transfer unavailable or expired. Reconnect to retry or cancel.', true);
      }
      $('cancel').hidden = false;
    }
  }
  busy = false;
  $('send').disabled = false;
});

$('cancel').addEventListener('click', cancel);
function showStartupError(error) {
  $('heading').textContent = rawConfig.enabled ? 'Could not connect' : 'Phone transfer is not active yet';
  status(rawConfig.enabled ? error.message : 'Use Add playlist in the car to enter a playlist URL.', Boolean(rawConfig.enabled));
  if (pair && auth) { $('cancel').hidden = false; $('cancel').textContent = 'Retry cleanup'; }
}

async function start() {
  if (!navigator?.locks?.request) {
    $('heading').textContent = 'Could not connect';
    status('This browser cannot safely manage a transfer. Use a supported browser.', true);
    $('form').hidden = true;
    return;
  }
  navigator.locks.request(TAB_LOCK, {mode: 'exclusive', ifAvailable: true}, async lock => {
    if (!lock) {
      $('heading').textContent = 'Transfer already open';
      status('Close the other transfer tab, then reload this page.', true);
      $('form').hidden = true;
      return;
    }
    try { await startSession(); } catch (error) { showStartupError(error); }
    // Keep the exclusive lock through startup errors and cleanup retries,
    // until the browser destroys this tab.
    await new Promise(() => {});
  }).catch(error => {
    showStartupError(error);
  });
}

start().catch(showStartupError);
