const http = require('http');
let state = 'normal';
let unavailableUntil = 0;
let openedStreams = 0;
const streams = new Set();
const rate = 22050;
const pcm = Buffer.alloc(rate * 2);
for (let i = 0; i < rate; i++) pcm.writeInt16LE(Math.round(Math.sin(i * 2 * Math.PI * 220 / rate) * 1000), i * 2);
const header = Buffer.alloc(44);
header.write('RIFF'); header.writeUInt32LE(0x7fffffff, 4); header.write('WAVEfmt ', 8);
header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
header.writeUInt32LE(rate, 24); header.writeUInt32LE(rate * 2, 28); header.writeUInt16LE(2, 32);
header.writeUInt16LE(16, 34); header.write('data', 36); header.writeUInt32LE(0x7fffffdb, 40);
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/stats') { res.end(JSON.stringify({openedStreams})); return; }
  if (url.pathname === '/control') {
    const requested = url.searchParams.get('state');
    if (requested === 'drop-streams') {
      unavailableUntil = Date.now() + 5000;
      for (const response of streams) response.destroy();
    } else state = requested;
    res.end('ok'); return;
  }
  if (url.pathname === '/list.m3u') {
    if (state === 'bad-list') { res.writeHead(503); res.end('temporary failure'); return; }
    const entries = {
      a: '#EXTINF:-1 tvg-id="a",Fixture Jazz One\nhttp://10.0.2.2:18765/a.wav',
      b: '#EXTINF:-1 tvg-id="b",Fixture Jazz Two\nhttp://10.0.2.2:18765/b.wav',
      c: '#EXTINF:-1 tvg-id="c" group-title="World",Fixture World\nhttp://10.0.2.2:18765/c.wav'
    };
    const order = state === 'reordered' ? ['b','a','c'] : ['a','b','c'];
    res.writeHead(200, {'Content-Type':'audio/x-mpegurl; charset=utf-8'});
    res.end('#EXTM3U\n# SECTION: Jazz\n' + order.map(key => entries[key]).join('\n')); return;
  }
  if (url.pathname.endsWith('.wav')) {
    if (Date.now() < unavailableUntil) { res.writeHead(503); res.end(); return; }
    openedStreams++;
    const icy = req.headers['icy-metadata'] === '1';
    const interval = rate * 2;
    let remaining = interval;
    const text = Buffer.from("StreamTitle='Massive Attack - Teardrop';", 'utf8');
    const block = Buffer.alloc(1 + Math.ceil(text.length / 16) * 16);
    block[0] = (block.length - 1) / 16; text.copy(block, 1);
    const headers = {'Content-Type':'audio/wav', 'icy-br': '353'};
    if (icy) headers['icy-metaint'] = String(interval);
    streams.add(res); res.writeHead(200, headers);
    function audio(buffer) {
      if (!icy) { res.write(buffer); return; }
      while (buffer.length) {
        const count = Math.min(buffer.length, remaining);
        res.write(buffer.subarray(0, count)); buffer = buffer.subarray(count); remaining -= count;
        if (!remaining) { res.write(block); remaining = interval; }
      }
    }
    audio(header);
    const timer = setInterval(() => { if (!res.destroyed && res.writableLength < rate * 10) audio(pcm); }, 1000);
    res.on('close', () => {clearInterval(timer); streams.delete(res);}); return;
  }
  res.writeHead(404); res.end();
}).listen(18765, '127.0.0.1', () => process.stdout.write('Local AAOS fixtures listening on 127.0.0.1:18765\n'));
