package net.faultlinesmp.bedrockvc;

/**
 * Audio helpers. Simple Voice Chat works in 20 ms frames of 48 kHz mono 16-bit (960 samples). The browser link uses a
 * lower rate (24 kHz by default: voice sounds the same, half the data) and either 16-bit PCM or 8-bit mu-law (half
 * again: ~48 KB/s down for stereo, ~24 KB/s up), so it works on mobile data and slow Wi-Fi.
 */
final class Audio {
    private Audio() {}

    static final int SVC_RATE = 48000;
    static final int SVC_FRAME = 960;

    // ---- mu-law (G.711) ----
    private static final short[] ULAW_DECODE = new short[256];
    static {
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xFF;
            int t = ((u & 0x0F) << 3) + 0x84;
            t <<= (u & 0x70) >> 4;
            ULAW_DECODE[i] = (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
        }
    }

    static byte ulaw(int sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > 32635) sample = 32635;
        sample += 0x84;
        int exp = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exp > 0; mask >>= 1) exp--;
        int mant = (sample >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mant);
    }

    static short unulaw(byte b) { return ULAW_DECODE[b & 0xFF]; }

    /** Decodes a mic frame from the browser into samples at its rate. */
    static short[] decode(byte[] data, int off, boolean ulaw) {
        if (ulaw) {
            short[] s = new short[data.length - off];
            for (int i = 0; i < s.length; i++) s[i] = unulaw(data[off + i]);
            return s;
        }
        short[] s = new short[(data.length - off) / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((data[off + 2 * i] & 0xFF) | (data[off + 2 * i + 1] << 8));
        return s;
    }

    /**
     * Up-samples a frame to 48 kHz by linear interpolation, carrying the previous frame's last sample in last[0] so
     * frame edges join smoothly.
     */
    static short[] upTo48k(short[] in, int rate, short[] last) {
        int f = SVC_RATE / rate;
        short[] out = new short[in.length * f];
        int prev = last[0];
        for (int i = 0; i < in.length; i++) {
            int cur = in[i];
            for (int k = 1; k <= f; k++) out[i * f + k - 1] = (short) (prev + (cur - prev) * k / f);
            prev = cur;
        }
        last[0] = (short) prev;
        return out;
    }

    /** A soft limiter: transparent below ~0.7, then rounds off instead of clipping when several people shout at once. */
    static float limit(float x) {
        float a = Math.abs(x);
        if (a <= 0.7f) return x;
        float over = (a - 0.7f) / 0.3f;
        float y = 0.7f + 0.3f * (over / (1 + over));
        return Math.copySign(Math.min(y, 0.999f), x);
    }

    /**
     * The 48 kHz stereo mix (interleaved floats, -1..1) down to the link rate and format.
     * Down-sampling averages each group of samples (a simple low-pass, plenty for voice).
     */
    static byte[] encodeStereo(float[] mix48, int rate, boolean ulaw, byte header) {
        int f = SVC_RATE / rate;
        int n = SVC_FRAME / f;
        byte[] out = new byte[1 + (ulaw ? n * 2 : n * 4)];
        out[0] = header;
        int o = 1;
        for (int i = 0; i < n; i++) {
            for (int ch = 0; ch < 2; ch++) {
                float sum = 0;
                for (int k = 0; k < f; k++) sum += mix48[(i * f + k) * 2 + ch];
                int s = Math.round(limit(sum / f) * 32767f);
                if (ulaw) out[o++] = ulaw(s);
                else { out[o++] = (byte) s; out[o++] = (byte) (s >> 8); }
            }
        }
        return out;
    }

    static int validRate(int r) { return r == 16000 || r == 24000 || r == 48000 ? r : 24000; }
}
