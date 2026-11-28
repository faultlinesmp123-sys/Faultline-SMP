package net.faultlinesmp.bedrockvc;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One player talking through the browser. The page's WebSocket can come and go (a phone locking, Wi-Fi to mobile data);
 * the Simple Voice Chat side is "attached" only while the player is online AND a page is connected:
 *  - their voice: browser frames -> 48 kHz -> Opus -> an AudioSender = exactly what the mod's client would send, so the
 *    server treats it like any other player's mic (proximity, whisper, groups, the speaking icon over their head);
 *  - what they hear: a PlayerAudioListener gets every packet the server would send to their client; each stream is
 *    decoded, placed in 3D (distance fade + left/right from where they look) and mixed every 20 ms by the Mixer.
 */
final class Session {
    final BedrockVoice pl;
    final UUID id;
    volatile String name;
    volatile WebServer.Ws ws;
    volatile int rate = 24000;
    volatile boolean ulaw = true;
    volatile boolean muted, deafened, whisper;
    volatile String lastStatus;
    /** Wrong group passwords in the current minute (brute-force guard). */
    long pwWindow; int pwFails;
    long groupMadeAt;
    volatile float master = 1f;
    volatile String device = "";
    final Map<UUID, Float> vols = new ConcurrentHashMap<>();

    // the Simple Voice Chat side
    private volatile boolean attached;
    private VoicechatConnection conn;
    private AudioSender sender;
    private PlayerAudioListener listener;
    private OpusEncoder encoder;
    private final short[] upLast = new short[1];
    volatile long selfTalkAt;
    volatile long lastMicFrame;

    final Map<UUID, Stream> streams = new ConcurrentHashMap<>();
    /** sender -> last time we heard them (for "who's talking"). */
    final Map<UUID, Long> talking = new ConcurrentHashMap<>();
    long stats_framesIn, stats_framesOut, stats_dropped;

    Session(BedrockVoice pl, UUID id, String name) { this.pl = pl; this.id = id; this.name = name; }

    boolean attached() { return attached; }
    boolean live() { WebServer.Ws w = ws; return w != null && !w.closed(); }

    // ---------------------------------------------------------------- attach / detach (main thread)

    /** Hooks the player into Simple Voice Chat. Returns null when done, else why not (shown on the page). */
    synchronized String attach(VoicechatServerApi api) {
        if (attached) return null;
        if (api == null) return "Simple Voice Chat isn't running on the server yet.";
        VoicechatConnection c = api.getConnectionOf(id);
        if (c == null) return "The voice server hasn't seen you yet: give it a second.";
        if (c.isInstalled()) return "You already have the Simple Voice Chat mod installed: you don't need this page.";
        AudioSender s = api.createAudioSender(c);
        if (!api.registerAudioSender(s)) return "Couldn't register your microphone with Simple Voice Chat.";
        PlayerAudioListener l = api.playerAudioListenerBuilder().setPlayer(id).setPacketListener(this::onPacket).build();
        api.registerAudioListener(l);
        conn = c; sender = s; listener = l;
        encoder = api.createEncoder(OpusEncoderMode.VOIP);
        c.setConnected(true);
        c.setDisabled(deafened);
        attached = true;
        return null;
    }

    synchronized void detach(VoicechatServerApi api) {
        if (!attached) return;
        attached = false;
        try { if (api != null && sender != null) api.unregisterAudioSender(sender); } catch (Throwable ignored) { }
        try { if (api != null && listener != null) api.unregisterAudioListener(listener); } catch (Throwable ignored) { }
        try { if (conn != null) { conn.setDisabled(false); conn.setConnected(false); } } catch (Throwable ignored) { }
        try { if (encoder != null) encoder.close(); } catch (Throwable ignored) { }
        for (Stream s : streams.values()) s.close();
        streams.clear();
        talking.clear();
        conn = null; sender = null; listener = null; encoder = null;
    }

    VoicechatConnection connection() { return conn; }

    void setDeafened(boolean on) {
        deafened = on;
        VoicechatConnection c = conn;
        if (c != null) try { c.setDisabled(on); } catch (Throwable ignored) { }
        if (on) { for (Stream s : streams.values()) s.clear(); }
    }

    // ---------------------------------------------------------------- the mic (WebSocket thread)

    void mic(byte[] data) {
        if (!attached || muted || data.length < 3) return;
        AudioSender s = sender;
        OpusEncoder e = encoder;
        if (s == null || e == null) return;
        int expect = rate / 50 * (ulaw ? 1 : 2);
        if (data.length - 2 != expect) return;
        boolean w = (data[1] & 1) != 0;
        short[] pcm = Audio.decode(data, 2, ulaw);
        short[] full = rate == Audio.SVC_RATE ? pcm : Audio.upTo48k(pcm, rate, upLast);
        try {
            byte[] opus;
            synchronized (this) { opus = e.encode(full); }
            if (s.isWhispering() != w) s.whispering(w);
            s.send(opus);
            selfTalkAt = System.currentTimeMillis();
            lastMicFrame = selfTalkAt;
            stats_framesIn++;
        } catch (Throwable t) {
            pl.debug("mic frame: " + t);
        }
    }

    /** They stopped talking: tells the voice server so the speaking icon goes off at once. */
    void micEnd() {
        AudioSender s = sender;
        OpusEncoder e = encoder;
        try { if (s != null) s.reset(); } catch (Throwable ignored) { }
        try { if (e != null) synchronized (this) { e.resetState(); } } catch (Throwable ignored) { }
        upLast[0] = 0;
    }

    // ---------------------------------------------------------------- what they hear (Simple Voice Chat's thread)

    private void onPacket(SoundPacket p) {
        if (!attached || deafened) return;
        UUID from = p.getSender();
        if (id.equals(from)) return;
        byte[] opus = p.getOpusEncodedData();
        UUID key = p.getChannelId() != null ? p.getChannelId() : from;
        Stream st = streams.computeIfAbsent(key, k -> new Stream(pl.api() == null ? null : pl.api().createDecoder(), from));
        if (st.dec == null) return;
        if (opus == null || opus.length == 0) { st.end(); return; }
        short[] pcm;
        try { pcm = st.decode(opus); } catch (Throwable t) { return; }
        if (pcm == null || pcm.length != Audio.SVC_FRAME) return;
        float[] g = gains(p);
        if (g[0] <= 0.0005f && g[1] <= 0.0005f) { st.lastAt = System.currentTimeMillis(); return; }
        st.push(pcm, g[0], g[1]);
        if (from != null) talking.put(from, System.currentTimeMillis());
    }

    /** Left/right gain for a packet: distance fade like the mod's, panned by where the listener looks. */
    private float[] gains(SoundPacket p) {
        if (p instanceof StaticSoundPacket) return new float[]{1f, 1f};
        BedrockVoice.Pos me = pl.pos(id);
        BedrockVoice.Pos src = null;
        float max;
        if (p instanceof EntitySoundPacket e) {
            src = pl.pos(e.getEntityUuid());
            if (src == null) src = pl.entityPos(e.getEntityUuid());
            max = e.getDistance();
        } else if (p instanceof LocationalSoundPacket l) {
            Position q = l.getPosition();
            if (me != null) src = new BedrockVoice.Pos(me.world(), q.getX(), q.getY(), q.getZ(), 0);
            max = l.getDistance();
        } else return new float[]{1f, 1f};
        if (me == null || src == null || !me.world().equals(src.world())) return new float[]{0.8f, 0.8f};
        if (max <= 0) max = 48;
        double dx = src.x() - me.x(), dy = src.y() - me.y(), dz = src.z() - me.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double vol = Math.max(0, 1 - d / max);
        vol = Math.pow(vol, pl.falloff());
        double hd = Math.sqrt(dx * dx + dz * dz);
        double pan = 0;
        if (hd > 0.3) {
            double yaw = Math.toRadians(me.yaw());
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);   // the listener's right
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);    // and forward
            pan = (dx * rx + dz * rz) / hd;
            double front = (dx * fx + dz * fz) / hd;
            if (front < 0) vol *= 1 - 0.18 * -front;           // a little quieter behind you
            pan *= Math.min(1, hd / 2.5) * pl.stereo();
        }
        float l = (float) (vol * Math.min(1, 1 - pan));
        float r = (float) (vol * Math.min(1, 1 + pan));
        return new float[]{l, r};
    }

    // ---------------------------------------------------------------- mixing (the Mixer thread, every 20 ms)

    private final float[] mix = new float[Audio.SVC_FRAME * 2];
    private int tail;

    /** Mixes one 20 ms frame and sends it. */
    void mixTick(long now) {
        WebServer.Ws w = ws;
        if (!attached || w == null || w.closed()) return;
        Arrays.fill(mix, 0);
        boolean any = false;
        for (Iterator<Map.Entry<UUID, Stream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
            Stream s = it.next().getValue();
            if (now - s.lastAt > 15000) { s.close(); it.remove(); continue; }
            Frame f = s.next();
            if (f == null) continue;
            float v = master * (s.sender == null ? 1f : vols.getOrDefault(s.sender, 1f));
            float gl0 = s.gl, gr0 = s.gr, gl1 = f.l * v, gr1 = f.r * v;
            for (int i = 0; i < Audio.SVC_FRAME; i++) {
                float t = i / (float) Audio.SVC_FRAME;
                float x = f.pcm[i] / 32768f;
                mix[i * 2] += x * (gl0 + (gl1 - gl0) * t);
                mix[i * 2 + 1] += x * (gr0 + (gr1 - gr0) * t);
            }
            s.gl = gl1; s.gr = gr1;
            any = true;
        }
        if (any) tail = 3; else if (tail > 0) tail--; else return;
        if (w.pending() > pl.maxPending()) { stats_dropped++; return; }
        w.binary(Audio.encodeStereo(mix, rate, ulaw, (byte) 1));
        stats_framesOut++;
    }

    record Frame(short[] pcm, float l, float r) { }

    /** One sound stream (a speaker, or a plugin's audio channel): its own decoder and a small jitter buffer. */
    static final class Stream {
        final OpusDecoder dec;
        final UUID sender;
        private final ArrayDeque<Frame> q = new ArrayDeque<>();
        private boolean primed;
        volatile long lastAt = System.currentTimeMillis();
        float gl, gr;

        Stream(OpusDecoder dec, UUID sender) { this.dec = dec; this.sender = sender; }

        synchronized short[] decode(byte[] opus) { return dec.decode(opus); }

        synchronized void push(short[] pcm, float l, float r) {
            q.addLast(new Frame(pcm, l, r));
            while (q.size() > 8) q.pollFirst(); // over 160 ms behind: catch up
            lastAt = System.currentTimeMillis();
        }

        /** Next frame, after a 40 ms head start so small network hiccups don't cut words. */
        synchronized Frame next() {
            if (!primed) { if (q.size() < 2) return null; primed = true; }
            Frame f = q.pollFirst();
            if (f == null) primed = false;
            else if (q.size() > 4) q.pollFirst(); // drifting behind: skip a frame
            return f;
        }

        synchronized void end() {
            try { dec.resetState(); } catch (Throwable ignored) { }
            lastAt = System.currentTimeMillis();
        }

        synchronized void clear() { q.clear(); primed = false; }

        synchronized void close() {
            q.clear();
            try { if (dec != null) dec.close(); } catch (Throwable ignored) { }
        }
    }
}
