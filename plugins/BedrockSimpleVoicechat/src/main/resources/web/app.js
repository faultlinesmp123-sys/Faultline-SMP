// BedrockSimpleVoicechat web client: login, audio setup, the WebSocket to the server, and the UI.
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const LS = (() => { try { localStorage.setItem('_t', '1'); localStorage.removeItem('_t'); return localStorage; } catch { return null; } })();
  const store = {
    get(k, d) { try { const v = LS && LS.getItem('bvc.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { LS && LS.setItem('bvc.' + k, JSON.stringify(v)); } catch {} },
    del(k) { try { LS && LS.removeItem('bvc.' + k); } catch {} },
  };

  const settings = Object.assign({
    ptt: false, threshold: -50, micGain: 100, outGain: 100, ns: true, ec: true, agc: true, rate: 24000,
    wake: true, dev: '', vols: {}, muted: false, deaf: false,
  }, store.get('settings', {}));
  const save = () => store.set('settings', settings);

  // ---------------------------------------------------------------- auth from the link (#t=...) or a remembered device
  let token = '';
  const hash = new URLSearchParams(location.hash.slice(1));
  if (hash.get('t')) { token = hash.get('t'); history.replaceState(null, '', location.pathname); }
  let deviceKey = store.get('device', '');

  let ws = null, wsOpen = false, authed = false, retry = 0, retryTimer = null;
  let ctx = null, node = null, stream = null, src = null;
  let me = { name: '', uuid: '' }, status = { online: false, attached: false };
  let rate = 24000, ulaw = true, talking = new Set(), meTalking = false;
  let lastPong = 0, ping = 0, wakeLock = null, pttHeld = false;
  let nearby = [], groups = [], myGroupId = '';

  const ua = (() => {
    const u = navigator.userAgent;
    const os = /iPhone|iPad|iPod/.test(u) ? 'iOS' : /Android/.test(u) ? 'Android' : /Windows/.test(u) ? 'Windows' : /Mac/.test(u) ? 'Mac' : /Linux/.test(u) ? 'Linux' : 'device';
    const br = /Edg\//.test(u) ? 'Edge' : /OPR\//.test(u) ? 'Opera' : /Firefox\//.test(u) ? 'Firefox' : /CriOS|Chrome\//.test(u) ? 'Chrome' : /Safari\//.test(u) ? 'Safari' : 'Browser';
    return br + ' on ' + os;
  })();

  function toast(msg, ms = 2600) {
    const t = $('toast'); t.textContent = msg; t.classList.remove('hidden'); t.style.opacity = 1;
    clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.opacity = 0; setTimeout(() => t.classList.add('hidden'), 300); }, ms);
  }

  function show(which) {
    for (const id of ['login', 'start', 'main']) $(id).classList.toggle('hidden', id !== which);
  }

  function net(state, text) {
    $('netdot').className = 'dot ' + state; $('net').textContent = text;
  }

  // ---------------------------------------------------------------- the code boxes
  function buildCode() {
    const box = $('codebox'); box.innerHTML = '';
    const ins = [];
    for (let i = 0; i < 6; i++) {
      if (i === 3) { const g = document.createElement('div'); g.className = 'gap'; box.appendChild(g); }
      const el = document.createElement('input');
      el.inputMode = 'numeric'; el.maxLength = 1; el.autocomplete = 'one-time-code'; el.pattern = '[0-9]*';
      el.addEventListener('input', () => {
        const v = el.value.replace(/\D/g, '');
        if (v.length > 1) { spread(v, i); return; }
        el.value = v;
        if (v && i < 5) ins[i + 1].focus();
        if (ins.every(x => x.value)) $('codego').click();
      });
      el.addEventListener('keydown', (e) => { if (e.key === 'Backspace' && !el.value && i > 0) ins[i - 1].focus(); });
      el.addEventListener('paste', (e) => { e.preventDefault(); spread((e.clipboardData.getData('text') || '').replace(/\D/g, ''), 0); });
      ins.push(el); box.appendChild(el);
    }
    function spread(v, from) { for (let k = 0; k < v.length && from + k < 6; k++) ins[from + k].value = v[k]; (ins[Math.min(5, from + v.length)] || ins[5]).focus(); if (ins.every(x => x.value)) $('codego').click(); }
    $('codego').onclick = () => {
      const code = ins.map(x => x.value).join('');
      if (code.length !== 6) { toast('Enter all 6 numbers'); return; }
      pendingAuth = { code }; showStart('');
    };
    setTimeout(() => ins[0].focus(), 100);
  }

  let pendingAuth = null;

  function showStart(name) {
    $('startname').textContent = name || 'Voice chat';
    show('start');
  }

  // ---------------------------------------------------------------- audio
  async function startAudio() {
    if (ctx && node) { // already set up: a second Join tap must not open a second engine
      if (!stream) await openMic();
      if (ctx.state !== 'running') await ctx.resume();
      return;
    }
    if (!window.isSecureContext) throw new Error('This page must be opened over https for the microphone to work.');
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('This browser has no microphone support. Try Chrome or Safari.');
    const AC = window.AudioContext || window.webkitAudioContext;
    try { ctx = new AC({ sampleRate: 48000, latencyHint: 'interactive' }); } catch { ctx = new AC({ latencyHint: 'interactive' }); }
    await ctx.audioWorklet.addModule('/worklet.js');
    node = new AudioWorkletNode(ctx, 'bvc-engine', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
    node.port.onmessage = (e) => onEngine(e.data);
    node.connect(ctx.destination);
    await openMic();
    if (ctx.state !== 'running') await ctx.resume();
    sendCfg();
  }

  async function openMic() {
    if (stream) stream.getTracks().forEach(t => t.stop());
    if (src) { try { src.disconnect(); } catch {} src = null; }
    stream = await navigator.mediaDevices.getUserMedia({ audio: {
      deviceId: settings.dev ? { exact: settings.dev } : undefined,
      echoCancellation: settings.ec, noiseSuppression: settings.ns, autoGainControl: settings.agc, channelCount: 1,
    }});
    src = ctx.createMediaStreamSource(stream);
    src.connect(node);
    listDevices();
  }

  async function listDevices() {
    try {
      const ds = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audioinput');
      const sel = $('s-dev'); const cur = settings.dev;
      sel.innerHTML = '<option value="">Default</option>' + ds.map(d => `<option value="${esc(d.deviceId)}">${esc(d.label || 'Microphone')}</option>`).join('');
      sel.value = ds.some(d => d.deviceId === cur) ? cur : '';
    } catch {}
  }

  function sendCfg() {
    if (!node) return;
    node.port.postMessage({ t: 'cfg', rate, ulaw, threshold: settings.threshold, micGain: settings.micGain / 100, outGain: settings.outGain / 100,
      ptt: settings.ptt, muted: settings.muted, deaf: settings.deaf, whisper: !!whisperOn });
  }

  function onEngine(m) {
    switch (m.t) {
      case 'mic': if (wsOpen && authed && status.attached) ws.send(m.buf); break;
      case 'end': if (wsOpen) ws.send(JSON.stringify({ t: 'end' })); setTalk(false); break;
      case 'start': setTalk(true); break;
      case 'meter': meter(m); break;
    }
  }

  function setTalk(on) { meTalking = on; $('mic').classList.toggle('live', on && !settings.muted); renderStatus(); }

  function meter(m) {
    const db = Math.max(-80, Math.min(0, m.db));
    $('meter').style.width = ((db + 80) / 80 * 100) + '%';
    const ring = $('ring');
    ring.style.transform = 'scale(' + (1 + Math.min(0.12, m.peak * 0.25)) + ')';
    if (diagOn) $('diag').textContent = `Link ${rate / 1000} kHz ${ulaw ? 'mu-law' : 'PCM'} | buffer ${m.buf} ms | ping ${ping} ms | gaps ${m.under} | catch-ups ${m.late} | ${ua}`;
  }
  let diagOn = false;

  // ---------------------------------------------------------------- the socket
  function connect() {
    clearTimeout(retryTimer);
    stopForGood = false;
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    net('warn', 'connecting');
    try { ws = new WebSocket(url); } catch { scheduleRetry(); return; }
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      wsOpen = true; retry = 0;
      const a = { t: 'auth', rate, codec: ulaw ? 'ulaw' : 'pcm16', ua, vols: settings.vols, muted: settings.muted, deafened: settings.deaf };
      if (pendingAuth && pendingAuth.code) a.code = pendingAuth.code;
      else if (token) a.token = token;
      else if (deviceKey) a.device = deviceKey;
      else { ws.close(); show('login'); buildCode(); return; }
      ws.send(JSON.stringify(a));
    };
    ws.onmessage = (e) => {
      if (typeof e.data !== 'string') {
        const b = new Uint8Array(e.data);
        if (b[0] === 1 && node && !settings.deaf) { const p = e.data.slice(1); node.port.postMessage({ t: 'pcm', buf: p }, [p]); }
        return;
      }
      let m; try { m = JSON.parse(e.data); } catch { return; }
      onMsg(m);
    };
    ws.onclose = () => {
      wsOpen = false; authed = false;
      net('bad', 'offline');
      if (stopForGood) return;
      scheduleRetry();
    };
    ws.onerror = () => {};
  }
  let stopForGood = false;

  function scheduleRetry() {
    if (!deviceKey && !token && !pendingAuth) { show('login'); buildCode(); return; }
    retry = Math.min(retry + 1, 6);
    const d = [500, 1000, 2000, 3000, 5000, 8000, 10000][retry];
    $('status').textContent = 'Reconnecting...'; $('status').className = 'status dim';
    retryTimer = setTimeout(connect, d);
  }

  function onMsg(m) {
    switch (m.t) {
      case 'welcome':
        authed = true; token = ''; pendingAuth = null;
        me = { name: m.name, uuid: m.uuid };
        if (m.deviceKey) { deviceKey = m.deviceKey; store.set('device', deviceKey); }
        rate = m.rate; ulaw = m.codec === 'ulaw';
        if (m.ptt && store.get('settings', null) == null) { settings.ptt = true; save(); }
        $('who').textContent = 'Signed in as ' + m.name;
        net('ok', 'connected');
        sendCfg();
        show('main');
        keepAwake();
        break;
      case 'authfail':
        if (m.forget) { deviceKey = ''; store.del('device'); }
        pendingAuth = null; token = '';
        stopForGood = !deviceKey;
        toast(m.msg, 5000);
        if (ws) ws.close();
        show('login'); buildCode();
        break;
      case 'status':
        status = m;
        if (m.muted !== undefined && m.muted !== settings.muted) { settings.muted = m.muted; save(); sendCfg(); }
        if (m.deafened !== undefined && m.deafened !== settings.deaf) { settings.deaf = m.deafened; save(); sendCfg(); }
        renderStatus();
        break;
      case 'talk': talking = new Set(m.ids); renderTalk(); break;
      case 'nearby': nearby = m.list; myGroupId = m.groupId || ''; renderPeople(m); renderMyGroup(m.group); break;
      case 'groups': groups = m.list; renderGroups(); break;
      case 'pong': ping = Date.now() - m.n; lastPong = Date.now(); break;
      case 'toast': toast(m.msg); break;
      case 'replaced': stopForGood = true; toast(m.msg, 6000); $('status').textContent = m.msg; $('status').className = 'status dim'; net('bad', 'elsewhere'); break;
      case 'bye':
        if (m.forget) { deviceKey = ''; store.del('device'); stopForGood = true; }
        toast(m.msg, 6000);
        break;
    }
  }

  setInterval(() => { if (wsOpen) ws.send(JSON.stringify({ t: 'ping', n: Date.now() })); }, 5000);

  // ---------------------------------------------------------------- UI
  function renderStatus() {
    const st = $('status'), mic = $('mic'), b = $('banner'), hint = $('hint');
    mic.classList.toggle('muted', settings.muted);
    mic.classList.toggle('ptt', settings.ptt);
    $('mute').classList.toggle('on', settings.muted); $('mute').textContent = settings.muted ? 'Unmute' : 'Mute';
    $('deaf').classList.toggle('on', settings.deaf); $('deaf').textContent = settings.deaf ? 'Undeafen' : 'Deafen';
    $('whisper').classList.toggle('on', whisperOn);
    $('thmark').style.left = ((settings.threshold + 80) / 80 * 100) + '%';
    $('thmark').classList.toggle('hidden', settings.ptt);
    if (!status.online) { b.textContent = status.msg || 'Join the server to talk.'; b.classList.remove('hidden'); }
    else if (!status.attached) { b.textContent = status.msg || 'Connecting to the voice server...'; b.classList.remove('hidden'); }
    else b.classList.add('hidden');
    if (!authed) return;
    if (settings.deaf) { st.textContent = 'Deafened'; st.className = 'status'; }
    else if (settings.muted) { st.textContent = 'Muted'; st.className = 'status'; }
    else if (meTalking) { st.textContent = whisperOn ? 'Whispering...' : 'Talking...'; st.className = 'status'; }
    else { st.textContent = settings.ptt ? 'Hold to talk' : 'Listening'; st.className = 'status dim'; }
    hint.textContent = settings.ptt ? (matchMedia('(pointer:fine)').matches ? 'Hold the button or Space to talk.' : 'Hold the big button to talk.') : 'Voice activation is on: just talk. Tap the mic to mute.';
  }

  function head(uuid, name) {
    const letter = (name || '?').replace(/[^a-zA-Z0-9]/g, '').charAt(0).toUpperCase() || '?';
    const svg = `data:image/svg+xml,${encodeURIComponent(`<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 8 8'><rect width='8' height='8' fill='#2a3a4c'/><text x='4' y='6.2' font-size='6' text-anchor='middle' fill='#cfe' font-family='sans-serif'>${letter}</text></svg>`)}`;
    return `<img src="https://mc-heads.net/avatar/${esc(uuid)}/36" data-f="${esc(svg)}" alt="">`;
  }

  function renderPeople(m) {
    const box = $('people');
    $('pcount').textContent = nearby.length;
    if (!nearby.length) { box.innerHTML = `<div class="empty">Nobody within ${Math.round(m.distance || 48)} blocks.</div>`; return; }
    const keep = new Map([...box.querySelectorAll('.person')].map(el => [el.dataset.id, el]));
    const frag = document.createDocumentFragment();
    for (const p of nearby) {
      let el = keep.get(p.id);
      if (!el) {
        el = document.createElement('div'); el.className = 'person'; el.dataset.id = p.id;
        el.innerHTML = `${head(p.id, p.name)}<div><div class="nm"></div><div class="sub"></div></div><input type="range" min="0" max="200" step="5" title="Their volume">`;
        const r = el.querySelector('input');
        r.value = Math.round((settings.vols[p.id] ?? 1) * 100);
        r.oninput = () => { const v = r.value / 100; settings.vols[p.id] = v; save(); if (wsOpen) ws.send(JSON.stringify({ t: 'vol', id: p.id, v })); };
      }
      el.querySelector('.nm').innerHTML = esc(p.name) + (p.bedrock ? '<span class="tag">Bedrock</span>' : '') + (!p.voice ? '<span class="tag nov">no voice</span>' : '');
      el.querySelector('.sub').textContent = p.group ? 'In your group' : p.d + ' blocks away';
      el.classList.toggle('talking', talking.has(p.id));
      frag.appendChild(el);
    }
    box.innerHTML = ''; box.appendChild(frag);
  }

  function renderTalk() {
    for (const el of document.querySelectorAll('.person')) el.classList.toggle('talking', talking.has(el.dataset.id));
  }

  function renderMyGroup(name) {
    $('mygroup').innerHTML = name ? `You're in <b>${esc(name)}</b>. <button id="gleave" style="margin-left:8px;padding:5px 10px">Leave</button>` : "You're not in a group (everyone near you hears you).";
    const l = $('gleave'); if (l) l.onclick = () => ws.send(JSON.stringify({ t: 'group', op: 'leave' }));
    renderGroups();
  }

  function renderGroups() {
    const box = $('groups');
    if (!groups.length) { box.innerHTML = '<div class="empty">No groups yet. Make one below.</div>'; return; }
    box.innerHTML = '';
    for (const g of groups) {
      const el = document.createElement('div');
      el.className = 'group' + (g.id === myGroupId ? ' mine' : '');
      el.innerHTML = `<div class="nm">${esc(g.name)} ${g.password ? '&#128274;' : ''}<div class="sub">${g.members} in it, ${g.type}</div></div>`;
      if (g.id !== myGroupId) {
        const b = document.createElement('button'); b.textContent = 'Join';
        b.onclick = () => { let pw = ''; if (g.password) { pw = prompt('Password for ' + g.name); if (pw == null) return; } ws.send(JSON.stringify({ t: 'group', op: 'join', id: g.id, pw })); };
        el.appendChild(b);
      }
      box.appendChild(el);
    }
  }

  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

  // ---------------------------------------------------------------- controls
  let whisperOn = false;
  function setMuted(on) { settings.muted = on; save(); sendCfg(); if (wsOpen) ws.send(JSON.stringify({ t: 'mute', on })); renderStatus(); }
  function setDeaf(on) { settings.deaf = on; save(); sendCfg(); if (on && node) node.port.postMessage({ t: 'flush' }); if (wsOpen) ws.send(JSON.stringify({ t: 'deafen', on })); renderStatus(); }

  $('mute').onclick = () => setMuted(!settings.muted);
  $('deaf').onclick = () => setDeaf(!settings.deaf);
  $('whisper').onclick = () => { whisperOn = !whisperOn; sendCfg(); renderStatus(); toast(whisperOn ? 'Whisper on: only people close by hear you' : 'Whisper off'); };

  const mic = $('mic');
  const pttDown = (e) => { if (!settings.ptt) return; e.preventDefault(); pttHeld = true; node && node.port.postMessage({ t: 'tx', on: true }); mic.classList.add('live'); };
  const pttUp = () => { if (!settings.ptt || !pttHeld) return; pttHeld = false; node && node.port.postMessage({ t: 'tx', on: false }); mic.classList.remove('live'); };
  mic.addEventListener('pointerdown', pttDown);
  mic.addEventListener('pointerup', pttUp);
  mic.addEventListener('pointercancel', pttUp);
  mic.addEventListener('pointerleave', pttUp);
  mic.addEventListener('contextmenu', (e) => e.preventDefault());
  mic.addEventListener('click', () => { if (!settings.ptt) setMuted(!settings.muted); });
  window.addEventListener('keydown', (e) => {
    if (e.code !== 'Space' || e.repeat || e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
    if (settings.ptt) pttDown(e); else { e.preventDefault(); setMuted(!settings.muted); }
  });
  window.addEventListener('keyup', (e) => { if (e.code === 'Space') pttUp(); });
  window.addEventListener('blur', pttUp);

  for (const b of document.querySelectorAll('.tabs button')) b.onclick = () => {
    for (const x of document.querySelectorAll('.tabs button')) x.classList.toggle('sel', x === b);
    for (const t of ['people', 'groups', 'settings']) $('tab-' + t).classList.toggle('hidden', t !== b.dataset.tab);
    diagOn = b.dataset.tab === 'settings';
  };

  $('gcreate').onclick = () => {
    const name = $('gname').value.trim(); if (!name) { toast('Give the group a name'); return; }
    ws.send(JSON.stringify({ t: 'group', op: 'create', name, pw: $('gpw').value, type: $('gtype').value }));
    $('gname').value = ''; $('gpw').value = '';
  };

  // settings
  const bind = (id, key, conv, after) => {
    const el = $(id);
    if (el.type === 'checkbox') el.checked = !!settings[key]; else el.value = settings[key];
    el.addEventListener(el.type === 'range' ? 'input' : 'change', () => {
      settings[key] = el.type === 'checkbox' ? el.checked : conv ? conv(el.value) : el.value; save(); sendCfg(); renderStatus(); after && after();
    });
  };
  bind('s-ptt', 'ptt', null, () => { if (!settings.ptt) node && node.port.postMessage({ t: 'tx', on: false }); });
  bind('s-th', 'threshold', Number);
  bind('s-mg', 'micGain', Number);
  bind('s-out', 'outGain', Number);
  bind('s-ns', 'ns', null, () => ctx && openMic().catch(err => toast(err.message)));
  bind('s-ec', 'ec', null, () => ctx && openMic().catch(err => toast(err.message)));
  bind('s-agc', 'agc', null, () => ctx && openMic().catch(err => toast(err.message)));
  bind('s-dev', 'dev', null, () => ctx && openMic().catch(err => toast(err.message)));
  bind('s-q', 'rate', Number, () => { rate = settings.rate; toast('Quality changes when you reconnect'); if (ws) ws.close(); });
  bind('s-wake', 'wake', null, keepAwake);
  rate = settings.rate;
  $('forget').onclick = () => {
    if (!confirm('Unlink this device? You will need /vc and a code again.')) return;
    if (wsOpen) ws.send(JSON.stringify({ t: 'forget', device: deviceKey }));
    deviceKey = ''; store.del('device'); stopForGood = true; if (ws) ws.close(); show('login'); buildCode();
  };

  async function keepAwake() {
    try {
      if (settings.wake && 'wakeLock' in navigator && document.visibilityState === 'visible') { if (!wakeLock) { wakeLock = await navigator.wakeLock.request('screen'); wakeLock.addEventListener('release', () => { wakeLock = null; }); } }
      else if (!settings.wake && wakeLock) { await wakeLock.release(); wakeLock = null; }
    } catch {}
  }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') { keepAwake(); if (ctx && ctx.state !== 'running') ctx.resume(); if (!wsOpen && !stopForGood && (deviceKey || token)) connect(); }
  });

  // ---------------------------------------------------------------- go
  $('startbtn').onclick = async () => {
    $('starterr').textContent = '';
    $('startbtn').disabled = true; $('startbtn').textContent = 'Starting...';
    try {
      await startAudio();
      connect();
      show('main');
      renderStatus();
    } catch (e) {
      $('starterr').textContent = (e && e.name === 'NotAllowedError') ? 'The microphone was blocked. Allow it in your browser settings (the lock icon next to the address) and try again.' : (e.message || String(e));
      $('startbtn').disabled = false; $('startbtn').textContent = 'Try again';
    }
  };

  if (token || deviceKey) showStart(''); else { show('login'); buildCode(); }
})();

// No inline handlers (the page's security policy blocks them): a head that fails to load falls back to its letter.
document.addEventListener('error', (e) => {
  const t = e.target;
  if (t && t.tagName === 'IMG' && t.dataset && t.dataset.f && !t.dataset.fb) { t.dataset.fb = '1'; t.src = t.dataset.f; }
}, true);
