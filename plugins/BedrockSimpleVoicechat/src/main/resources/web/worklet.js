// BedrockSimpleVoicechat audio engine (runs in the browser's audio thread).
// Input: the microphone at the device's rate -> filtered + resampled to the link rate -> 20 ms frames, voice activation
// or push-to-talk, mu-law/PCM, sent to the page. Output: 20 ms stereo frames from the server -> a jitter buffer ->
// resampled to the device's rate.

const ULAW_DEC = new Int16Array(256);
for (let i = 0; i < 256; i++) {
  const u = ~i & 0xff;
  let t = ((u & 0x0f) << 3) + 0x84;
  t <<= (u & 0x70) >> 4;
  ULAW_DEC[i] = (u & 0x80) ? 0x84 - t : t - 0x84;
}
function ulawEnc(s) {
  let sign = (s >> 8) & 0x80;
  if (sign) s = -s;
  if (s > 32635) s = 32635;
  s += 0x84;
  let exp = 7;
  for (let mask = 0x4000; (s & mask) === 0 && exp > 0; mask >>= 1) exp--;
  const mant = (s >> (exp + 3)) & 0x0f;
  return (~(sign | (exp << 4) | mant)) & 0xff;
}

class VoiceEngine extends AudioWorkletProcessor {
  constructor() {
    super();
    this.rate = 24000;          // link rate
    this.ulaw = true;
    // playback ring buffer (stereo, link rate), ~2 s
    this.cap = 48000 * 2;
    this.ring = new Float32Array(this.cap * 2);
    this.w = 0; this.r = 0;     // in frames, may be fractional for r
    this.playing = false;
    this.outGain = 1;
    this.deaf = false;
    // capture
    this.tx = false;            // push-to-talk held
    this.ptt = false;
    this.muted = false;
    this.whisper = false;
    this.threshold = -48;       // dB
    this.micGain = 1;
    this.hang = 0;
    this.open = false;
    this.capPos = 0;
    this.lp = 0;                // one-pole low-pass state
    this.prevIn = 0;
    this.frame = new Int16Array(480);
    this.fi = 0;
    this.pre = [];              // frames kept from just before voice starts (no clipped first word)
    this.meterT = 0;
    this.peak = 0;
    this.stats = { under: 0, late: 0 };
    this.port.onmessage = (e) => this.onMsg(e.data);
    this.setRate(24000);
  }

  setRate(r) {
    this.rate = r;
    this.frameLen = r / 50;
    this.frame = new Int16Array(this.frameLen);
    this.fi = 0;
    this.stepIn = r / sampleRate;   // link samples per device sample (capture)
    this.stepOut = r / sampleRate;  // playback
    const fc = Math.min(0.45 * r, 0.45 * sampleRate);
    this.lpA = 1 - Math.exp(-2 * Math.PI * fc / sampleRate);
  }

  onMsg(m) {
    switch (m.t) {
      case 'cfg':
        if (m.rate && m.rate !== this.rate) this.setRate(m.rate);
        if (m.ulaw !== undefined) this.ulaw = m.ulaw;
        if (m.threshold !== undefined) this.threshold = m.threshold;
        if (m.micGain !== undefined) this.micGain = m.micGain;
        if (m.outGain !== undefined) this.outGain = m.outGain;
        if (m.ptt !== undefined) this.ptt = m.ptt;
        if (m.muted !== undefined) { this.muted = m.muted; if (m.muted) this.close(); }
        if (m.deaf !== undefined) { this.deaf = m.deaf; if (m.deaf) this.flush(); }
        if (m.whisper !== undefined) this.whisper = m.whisper;
        break;
      case 'tx': this.tx = m.on; if (!m.on && this.ptt) this.close(); break;
      case 'pcm': this.push(new Uint8Array(m.buf)); break;
      case 'flush': this.flush(); break;
    }
  }

  flush() { this.w = 0; this.r = 0; this.playing = false; }

  buffered() { return this.w - this.r; }

  push(b) {
    if (this.deaf) return;
    const n = this.ulaw ? b.length / 2 : b.length / 4;
    if (this.buffered() > this.rate * 0.35) {       // way behind (a hiccup): jump to near-live
      this.r = this.w - this.rate * 0.08;
      this.stats.late++;
    }
    for (let i = 0; i < n; i++) {
      let l, r;
      if (this.ulaw) { l = ULAW_DEC[b[i * 2]]; r = ULAW_DEC[b[i * 2 + 1]]; }
      else { const o = i * 4; l = (b[o] | (b[o + 1] << 8)) << 16 >> 16; r = (b[o + 2] | (b[o + 3] << 8)) << 16 >> 16; }
      const idx = (this.w % this.cap) * 2;
      this.ring[idx] = l / 32768; this.ring[idx + 1] = r / 32768;
      this.w++;
    }
    if (!this.playing && this.buffered() >= this.rate * 0.06) this.playing = true; // 60 ms head start
  }

  close() {
    if (this.open) { this.open = false; this.port.postMessage({ t: 'end' }); }
    this.hang = 0;
  }

  emit(frame) {
    const out = new Uint8Array(2 + (this.ulaw ? frame.length : frame.length * 2));
    out[0] = 1; out[1] = this.whisper ? 1 : 0;
    if (this.ulaw) for (let i = 0; i < frame.length; i++) out[2 + i] = ulawEnc(frame[i]);
    else for (let i = 0; i < frame.length; i++) { out[2 + i * 2] = frame[i] & 0xff; out[3 + i * 2] = (frame[i] >> 8) & 0xff; }
    this.port.postMessage({ t: 'mic', buf: out.buffer }, [out.buffer]);
  }

  frameDone(f) {
    let sum = 0;
    for (let i = 0; i < f.length; i++) sum += f[i] * f[i];
    const rms = Math.sqrt(sum / f.length) / 32768;
    const db = rms > 0 ? 20 * Math.log10(rms) : -100;
    this.lastDb = db;
    let want;
    if (this.muted) want = false;
    else if (this.ptt) want = this.tx;
    else {
      if (db > this.threshold) this.hang = 20;       // 400 ms hangover
      else if (this.hang > 0) this.hang--;
      want = this.hang > 0;
    }
    if (want) {
      if (!this.open) {
        this.open = true;
        this.port.postMessage({ t: 'start' });
        for (const p of this.pre) this.emit(p);
        this.pre = [];
      }
      this.emit(f);
    } else {
      if (this.open) this.close();
      this.pre.push(f.slice());
      if (this.pre.length > 2) this.pre.shift();
    }
  }

  process(inputs, outputs) {
    // ---- capture
    const input = inputs[0];
    if (input && input.length > 0) {
      const ch = input[0];
      for (let i = 0; i < ch.length; i++) {
        const x = ch[i] * this.micGain;
        this.lp += this.lpA * (x - this.lp);
        const ax = Math.abs(x);
        if (ax > this.peak) this.peak = ax;
        this.capPos += this.stepIn;
        while (this.capPos >= 1) {
          this.capPos -= 1;
          const t = 1 - this.capPos / this.stepIn;    // where the link sample falls between the last two device samples
          let v = this.prevIn + (this.lp - this.prevIn) * Math.max(0, Math.min(1, t));
          if (v > 1) v = 1; else if (v < -1) v = -1;
          this.frame[this.fi++] = Math.round(v * 32767);
          if (this.fi === this.frameLen) { this.frameDone(this.frame); this.frame = new Int16Array(this.frameLen); this.fi = 0; }
        }
        this.prevIn = this.lp;
      }
    }
    // ---- playback
    const out = outputs[0];
    const L = out[0], R = out[1] || out[0];
    const g = this.outGain;
    for (let i = 0; i < L.length; i++) {
      if (!this.playing) { L[i] = 0; R[i] = 0; continue; }
      const avail = this.w - this.r;
      if (avail < 2) { this.playing = false; this.stats.under++; L[i] = 0; R[i] = 0; continue; }
      const i0 = Math.floor(this.r), f = this.r - i0;
      const a = (i0 % this.cap) * 2, b = ((i0 + 1) % this.cap) * 2;
      L[i] = (this.ring[a] + (this.ring[b] - this.ring[a]) * f) * g;
      R[i] = (this.ring[a + 1] + (this.ring[b + 1] - this.ring[a + 1]) * f) * g;
      // gently speed up / slow down to keep ~80 ms buffered (clock drift between server and device)
      const target = this.rate * 0.08;
      const drift = avail > target * 2 ? 1.02 : avail < target * 0.5 ? 0.99 : 1;
      this.r += this.stepOut * drift;
    }
    // ---- meter
    this.meterT += L.length;
    if (this.meterT > sampleRate / 20) {
      this.meterT = 0;
      this.port.postMessage({ t: 'meter', db: this.lastDb ?? -100, peak: this.peak, open: this.open, buf: Math.round((this.w - this.r) / this.rate * 1000), under: this.stats.under, late: this.stats.late });
      this.peak = 0;
    }
    return true;
  }
}

registerProcessor('bvc-engine', VoiceEngine);
