package com.grara.beat.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 맵 코드로부터 일렉트로 음악(PCM)과 채보(노트)를 결정적으로 생성한다.
 * 노트는 실제 음악 이벤트(킥, 스네어, 멜로디, 섹션 전환)에 맞춰 배치된다.
 */
public final class Song {
    public static final int SR = 24000;
    public static final double LEAD_IN = 2.0;

    public final MapCode map;
    public final short[] pcm;
    public final List<Note> notes;
    public final double beatSec;
    public final int bars;
    private final float[] barEnergy;

    private Song(MapCode map, short[] pcm, List<Note> notes, double beatSec, int bars, float[] barEnergy) {
        this.map = map;
        this.pcm = pcm;
        this.notes = notes;
        this.beatSec = beatSec;
        this.bars = bars;
        this.barEnergy = barEnergy;
    }

    public static Song generate(MapCode m) {
        return new Gen(m).run();
    }

    /** 해당 시점의 곡 세기 (0..1), 시각효과용. */
    public float energyAt(double t) {
        int bar = (int) Math.floor((t - LEAD_IN) / (beatSec * 4));
        if (bar < 0 || bar >= bars) return 0.3f;
        return barEnergy[bar];
    }

    public double musicEnd() {
        return LEAD_IN + bars * 4 * beatSec;
    }

    public double lastNoteTime() {
        double m = 0;
        for (Note n : notes) m = Math.max(m, n.end);
        return notes.isEmpty() ? musicEnd() : m;
    }

    public int count(int type) {
        int n = 0;
        for (Note x : notes) if (x.type == type) n++;
        return n;
    }

    // =====================================================================

    private static final int INTRO = 0, VERSE = 1, BUILD = 2, DROP = 3, BREAK = 4, OUTRO = 5;
    private static final float[] ENERGY = {0.45f, 0.65f, 0.85f, 1f, 0.35f, 0.4f};

    private static final class Cand {
        final double t;
        final int level;
        final boolean spin;

        Cand(double t, int level, boolean spin) {
            this.t = t;
            this.level = level;
            this.spin = spin;
        }
    }

    private static final class Gen {
        final MapCode m;
        final int style;
        final Random rnd;
        final double beat, step;
        final int bars, total;
        final float[] drums, music, lead, duck;
        final ArrayList<Cand> cands = new ArrayList<>();
        final ArrayList<Double> kickTimes = new ArrayList<>();
        final int[] scale, prog, arpPat;
        final boolean[] mOn = new boolean[32];
        final int[] mDeg = new int[32];
        final int[] mDur = new int[32];
        final int varShift;
        float[] kickS, snareS, clapS, hatC, hatO, crashS, stickS;
        final float[] duckCurve;
        final float[] barEnergy;

        Gen(MapCode m) {
            this.m = m;
            style = m.style;
            rnd = new Random(m.seed * 1000003L + m.style * 7919L + m.key * 31L + (m.minor ? 1 : 0) + m.bpm * 131L);
            beat = 60.0 / m.bpm;
            step = beat / 4;
            bars = MapCode.BARS[m.len];
            total = (int) ((LEAD_IN + bars * 4 * beat + 2.5) * SR);
            drums = new float[total];
            music = new float[total];
            lead = new float[total];
            duck = new float[total];
            Arrays.fill(duck, 1f);
            barEnergy = new float[bars];
            scale = m.minor ? new int[]{0, 2, 3, 5, 7, 8, 10} : new int[]{0, 2, 4, 5, 7, 9, 11};
            int[][] progs = m.minor
                    ? new int[][]{{0, 5, 2, 6}, {0, 6, 5, 4}, {0, 3, 5, 4}, {0, 5, 3, 6}}
                    : new int[][]{{0, 4, 5, 3}, {0, 5, 3, 4}, {5, 3, 0, 4}, {0, 3, 4, 3}};
            prog = progs[rnd.nextInt(progs.length)];
            int[][] arps = {{0, 2, 4, 7}, {0, 4, 2, 7}, {0, 2, 4, 2}, {7, 4, 2, 0}};
            arpPat = arps[rnd.nextInt(arps.length)];
            varShift = rnd.nextBoolean() ? 2 : -1;
            duckCurve = new float[(int) (0.3 * SR)];
            for (int i = 0; i < duckCurve.length; i++) {
                duckCurve[i] = 1f - 0.6f * (float) Math.exp(-i / (0.08 * SR));
            }
            makeDrums();
            makeMotif();
        }

        // ---------- 유틸 ----------

        int note(int base, int degree) {
            int o = Math.floorDiv(degree, 7);
            int d = Math.floorMod(degree, 7);
            return base + scale[d] + 12 * o;
        }

        static double hz(int midi) {
            return 440 * Math.pow(2, (midi - 69) / 12.0);
        }

        int idx(double t) {
            return (int) Math.round(t * SR);
        }

        double t(int bar, int st) {
            return LEAD_IN + (bar * 16 + st) * step;
        }

        int section(int bar) {
            if (bar < 4) return INTRO;
            if (bar >= bars - 2) return OUTRO;
            int block = bars <= 16 ? 4 : 8;
            int b = bar - 4;
            int[] cycle = {VERSE, DROP, BREAK, DROP};
            int s = cycle[(b / block) % 4];
            int in = b % block;
            if ((s == VERSE || s == BREAK) && in == block - 1) return BUILD;
            return s;
        }

        void hit(float[] sample, double t, float amp) {
            int s = idx(t);
            for (int i = 0; i < sample.length; i++) {
                int j = s + i;
                if (j < 0) continue;
                if (j >= total) break;
                drums[j] += sample[i] * amp;
            }
        }

        void cand(double t, int level, boolean spin) {
            cands.add(new Cand(t, level, spin));
        }

        static double wave(double p, int w) {
            switch (w) {
                case 0: return 2 * p - 1;
                case 1: return p < 0.5 ? 0.8 : -0.8;
                case 2: return Math.sin(2 * Math.PI * p);
                default: return p < 0.5 ? 4 * p - 1 : 3 - 4 * p;
            }
        }

        /** 감산 합성 음 하나. cutoff 는 c0→c1 로 변하고, lfo>0 이면 LFO 로 흔든다(워블). */
        void synth(float[] buf, double t, double dur, double f, int wave, float amp,
                   double c0, double c1, double decay, boolean detune, double lfo,
                   double attack, double release) {
            int s = idx(t);
            int nd = (int) (dur * SR);
            int n = nd + (int) (release * SR);
            int na = Math.max(1, (int) (attack * SR));
            double p1 = 0, p2 = 0.31, inc1 = f / SR, inc2 = f * 1.008 / SR;
            double y1 = 0, y2 = 0, env = 1, rel = 1, lp = 0;
            double dm = decay > 0 ? Math.exp(-decay / SR) : 1;
            double rm = Math.exp(-4.0 / Math.max(1, release * SR));
            double lfoInc = lfo / SR;
            for (int i = 0; i < n; i++) {
                int j = s + i;
                if (j < 0) continue;
                if (j >= total) break;
                double x = wave(p1, wave);
                if (detune) {
                    x = (x + wave(p2, wave)) * 0.6;
                    p2 += inc2;
                    if (p2 >= 1) p2 -= 1;
                }
                p1 += inc1;
                if (p1 >= 1) p1 -= 1;
                double fc;
                if (lfo > 0) {
                    lp += lfoInc;
                    fc = c0 + (c1 - c0) * (0.5 - 0.5 * Math.cos(2 * Math.PI * lp));
                } else {
                    fc = c0 + (c1 - c0) * ((double) i / n);
                }
                double a = Math.min(0.95, 2 * Math.PI * fc / SR);
                y1 += a * (x - y1);
                y2 += a * (y1 - y2);
                double ea = i < na ? (double) i / na : 1;
                env *= dm;
                if (i > nd) rel *= rm;
                buf[j] += (float) (y2 * ea * env * rel * amp);
            }
        }

        // ---------- 드럼 샘플 ----------

        void makeDrums() {
            Random nr = new Random(1234);
            kickS = new float[(int) (0.38 * SR)];
            double ph = 0;
            for (int i = 0; i < kickS.length; i++) {
                double tt = (double) i / SR;
                double f = 48 + 120 * Math.exp(-tt * 28);
                ph += 2 * Math.PI * f / SR;
                double env = Math.exp(-tt * 7.5);
                double click = tt < 0.003 ? (nr.nextFloat() * 2 - 1) * 0.5 * (1 - tt / 0.003) : 0;
                kickS[i] = (float) (Math.sin(ph) * env + click);
            }
            snareS = new float[(int) (0.25 * SR)];
            double lp = 0;
            for (int i = 0; i < snareS.length; i++) {
                double tt = (double) i / SR;
                double x = nr.nextFloat() * 2 - 1;
                lp += (x - lp) * 0.25;
                snareS[i] = (float) (Math.sin(2 * Math.PI * 185 * tt) * Math.exp(-tt * 25) * 0.6
                        + (x - lp) * Math.exp(-tt * 16) * 0.8);
            }
            clapS = new float[(int) (0.3 * SR)];
            double l1 = 0, l2 = 0;
            for (int i = 0; i < clapS.length; i++) {
                double tt = (double) i / SR;
                double x = nr.nextFloat() * 2 - 1;
                l1 += (x - l1) * 0.45;
                l2 += (l1 - l2) * 0.08;
                double env = tt < 0.03 ? Math.exp(-(tt % 0.01) * 300) : Math.exp(-(tt - 0.03) * 16);
                clapS[i] = (float) ((l1 - l2) * env * 1.6);
            }
            hatC = noiseHit(nr, 0.06, 70);
            hatO = noiseHit(nr, 0.3, 11);
            crashS = noiseHit(nr, 1.4, 2.6);
            stickS = new float[(int) (0.05 * SR)];
            for (int i = 0; i < stickS.length; i++) {
                double tt = (double) i / SR;
                stickS[i] = (float) (Math.sin(2 * Math.PI * 1800 * tt) * Math.exp(-tt * 80));
            }
        }

        static float[] noiseHit(Random nr, double len, double decay) {
            float[] o = new float[(int) (len * SR)];
            double lp = 0;
            for (int i = 0; i < o.length; i++) {
                double tt = (double) i / SR;
                double x = nr.nextFloat() * 2 - 1;
                lp += (x - lp) * 0.3;
                o[i] = (float) ((x - lp) * Math.exp(-tt * decay));
            }
            return o;
        }

        // ---------- 멜로디 모티프 (2마디) ----------

        void makeMotif() {
            float dens = (style == 5 || style == 4) ? 0.55f : style == 2 ? 0.35f : 0.45f;
            for (int s = 0; s < 32; s++) {
                double pr = (s % 8 == 0) ? 0.9 : (s % 4 == 0) ? 0.65 : (s % 2 == 0) ? dens : dens * 0.45;
                mOn[s] = rnd.nextDouble() < pr;
            }
            mOn[0] = true;
            int[] steps = {-2, -1, -1, 1, 1, 2, 0, 3, -3};
            int cur = 7 + new int[]{0, 2, 4}[rnd.nextInt(3)];
            for (int s = 0; s < 32; s++) {
                if (!mOn[s]) continue;
                if (s > 0) cur += steps[rnd.nextInt(steps.length)];
                cur = Math.max(3, Math.min(13, cur));
                if (s % 16 == 0) {
                    while (Math.floorMod(cur, 7) != 0 && Math.floorMod(cur, 7) != 2 && Math.floorMod(cur, 7) != 4) cur--;
                }
                mDeg[s] = cur;
            }
            int maxDur = style == 2 ? 6 : 4;
            for (int s = 0; s < 32; s++) {
                if (!mOn[s]) continue;
                int d = 1;
                while (s + d < 32 && !mOn[s + d] && d < maxDur) d++;
                mDur[s] = d;
            }
        }

        // ---------- 마디별 생성 ----------

        Song run() {
            // 카운트인 스틱
            for (int k = 1; k <= 4; k++) {
                double tt = LEAD_IN - k * beat;
                if (tt >= 0.05) hit(stickS, tt, 0.45f);
            }
            int prev = -1;
            for (int bar = 0; bar < bars; bar++) {
                int sec = section(bar);
                barEnergy[bar] = ENERGY[sec];
                boolean secStart = sec != prev;
                prev = sec;
                int cd = prog[bar % 4];
                drumsBar(bar, sec);
                bassBar(bar, sec, cd);
                padBar(bar, sec, cd);
                leadBar(bar, sec, cd);
                if (secStart && (sec == DROP || sec == BREAK)) {
                    hit(crashS, t(bar, 0), 0.35f);
                    cand(t(bar, 0), 0, true);
                } else if (sec == DROP && bar % 2 == 0) {
                    cand(t(bar, 0), 1, true);
                } else if (sec == DROP) {
                    cand(t(bar, 8), 3, true);
                }
                if (sec == BUILD) cand(t(bar, 12), 1, true);
                if (sec == VERSE && bar % 4 == 0) cand(t(bar, 0), 0, true);
                if (sec == BREAK && !secStart && bar % 2 == 0) cand(t(bar, 8), 2, true);
                if (bar == bars - 1) hit(crashS, t(bar, 0), 0.3f);
            }
            short[] pcm = mix();
            List<Note> notes = chart();
            return new Song(m, pcm, notes, beat, bars, barEnergy);
        }

        void drumsBar(int bar, int sec) {
            boolean four = !(style == 4 || style == 6);
            boolean clap = style == 0 || style == 3 || style == 7;
            if (sec == BREAK) {
                if (bar % 2 == 0) hit(hatO, t(bar, 0), 0.1f);
                return;
            }
            if (sec == BUILD) {
                for (int st = 0; st < 16; st++) {
                    boolean on = st % 4 == 0 || (st >= 8 && st % 2 == 0) || st >= 12;
                    if (!on) continue;
                    hit(snareS, t(bar, st), 0.22f + 0.4f * st / 15f);
                    cand(t(bar, st), st % 4 == 0 ? 0 : st % 2 == 0 ? 2 : 3, false);
                }
                if (four) for (int st = 0; st < 16; st += 4) kick(t(bar, st));
                return;
            }
            boolean lastOutro = sec == OUTRO && bar == bars - 1;
            int[] kicks, snares;
            if (four) {
                kicks = lastOutro ? new int[]{0} : new int[]{0, 4, 8, 12};
                snares = new int[]{4, 12};
            } else if (style == 4) {
                kicks = lastOutro ? new int[]{0} : (bar % 2 == 0 ? new int[]{0, 10} : new int[]{0, 6, 10});
                snares = new int[]{4, 12};
            } else {
                kicks = lastOutro ? new int[]{0} : new int[]{0, (bar % 2 == 0) ? 10 : 6};
                snares = new int[]{8};
            }
            boolean snareOn = !(sec == INTRO && bar < 2) && !lastOutro;
            for (int k : kicks) {
                kick(t(bar, k));
                int lv;
                if (sec == INTRO) lv = k == 0 ? 0 : 2;
                else if (four) lv = k % 8 == 0 ? 0 : 1;
                else lv = k == 0 ? 0 : 1;
                cand(t(bar, k), lv, false);
            }
            if (snareOn) {
                for (int s : snares) {
                    hit(clap ? clapS : snareS, t(bar, s), clap ? 0.45f : 0.5f);
                    cand(t(bar, s), four ? 1 : 0, false);
                }
            }
            if (sec == INTRO && bar == 0) return;
            float hv = sec == DROP ? 1f : 0.7f;
            if (style == 0 || style == 3) {
                for (int st = 2; st < 16; st += 4) {
                    hit(hatO, t(bar, st), 0.13f * hv);
                    if (sec == DROP) cand(t(bar, st), 3, false);
                }
                if (style == 3) for (int st = 0; st < 16; st++) if (st % 4 != 2) hit(hatC, t(bar, st), 0.07f * hv);
            } else if (style == 1 || style == 4) {
                for (int st = 0; st < 16; st++) {
                    hit(hatC, t(bar, st), (st % 2 == 1 ? 0.14f : 0.08f) * hv);
                    if (sec == DROP && st % 4 == 2) cand(t(bar, st), 3, false);
                }
            } else {
                for (int st = 0; st < 16; st += 2) {
                    hit(hatC, t(bar, st), (st % 4 == 2 ? 0.15f : 0.09f) * hv);
                    if (sec == DROP && st % 4 == 2) cand(t(bar, st), 3, false);
                }
            }
        }

        void kick(double tt) {
            hit(kickS, tt, 0.9f);
            kickTimes.add(tt);
        }

        void bassBar(int bar, int sec, int cd) {
            if (sec == BREAK || sec == BUILD) return;
            if (sec == INTRO && bar < 2) return;
            if (sec == OUTRO && bar == bars - 1) return;
            int b = note(36 + m.key, cd);
            if (b > 47) b -= 12;
            double f = hz(b), f2 = hz(b + 12);
            float a = sec == INTRO ? 0.22f : 0.32f;
            double cut = sec == INTRO ? 400 : 1;
            switch (style) {
                case 0:
                    for (int st = 2; st < 16; st += 4)
                        synth(music, t(bar, st), 2 * step, f, 0, a, cut > 1 ? cut : 900, 400, 6, false, 0, 0.004, 0.03);
                    break;
                case 1:
                    for (int st : new int[]{0, 3, 6, 8, 11, 14})
                        synth(music, t(bar, st), 1.5 * step, f, 1, a * 0.8f, cut > 1 ? cut : 1100, 300, 10, false, 0, 0.003, 0.03);
                    break;
                case 2:
                case 5:
                    for (int st = 0; st < 16; st += 2)
                        synth(music, t(bar, st), 1.8 * step, (st / 2) % 2 == 0 ? f : f2, style == 5 ? 1 : 0, a * 0.8f,
                                style == 5 ? 6000 : (cut > 1 ? cut : 1300), style == 5 ? 6000 : 500, 3, style == 2, 0, 0.003, 0.03);
                    break;
                case 3:
                    for (int st = 0; st < 16; st++) {
                        if (st % 4 == 0) continue;
                        synth(music, t(bar, st), 0.9 * step, f, 0, a * 0.8f, cut > 1 ? cut : 1600, 500, 9, false, 0, 0.002, 0.02);
                    }
                    break;
                case 4:
                    synth(music, t(bar, 0), 8 * step, f, 0, a, cut > 1 ? cut : 500, 350, 0, true, 0, 0.01, 0.05);
                    synth(music, t(bar, 8), 8 * step, f, 0, a, cut > 1 ? cut : 450, 300, 0, true, 0, 0.01, 0.05);
                    break;
                case 6: {
                    double lfo = new double[]{2, 4, 3, 6}[(bar + m.seed) % 4] / beat;
                    synth(music, t(bar, 0), 6 * step, f, 0, a * 1.1f, 150, 2200, 0, true, lfo, 0.01, 0.05);
                    synth(music, t(bar, 8), 8 * step, f, 0, a * 1.1f, 150, 2200, 0, true, lfo * 1.5, 0.01, 0.05);
                    break;
                }
                default:
                    for (int st : new int[]{0, 3, 6, 10, 14})
                        synth(music, t(bar, st), 2.5 * step, f, 0, a, cut > 1 ? cut : 1800, 400, 4, true, 0, 0.003, 0.04);
            }
        }

        void padBar(int bar, int sec, int cd) {
            boolean padStyle = style == 0 || style == 2 || style == 3 || style == 7;
            boolean on = sec == BREAK || sec == OUTRO || (padStyle && (sec == VERSE || sec == DROP || sec == BUILD));
            if (!on) return;
            float a = sec == BREAK ? 0.075f : 0.05f;
            for (int k = 0; k < 3; k++) {
                int mi = note(60 + m.key, cd + k * 2);
                synth(music, t(bar, 0), 4 * beat, hz(mi), 0, a, 500, sec == BREAK ? 1600 : 1100, 0, true, 0, 0.12, 0.35);
            }
            if (sec == BREAK) cand(t(bar, 0), 0, false);
        }

        void leadBar(int bar, int sec, int cd) {
            int base = 60 + m.key;
            if (sec == DROP) {
                int w;
                double c0, c1, dec;
                boolean det;
                switch (style) {
                    case 0: w = 0; c0 = 2600; c1 = 900; dec = 6; det = false; break;
                    case 1: w = 1; c0 = 1800; c1 = 700; dec = 10; det = false; break;
                    case 2: w = 0; c0 = 2400; c1 = 2000; dec = 0; det = true; break;
                    case 3: w = 0; c0 = 3600; c1 = 1500; dec = 3; det = true; break;
                    case 4: w = 1; c0 = 2600; c1 = 1200; dec = 5; det = false; break;
                    case 5: w = 1; c0 = 9000; c1 = 9000; dec = 2; det = false; break;
                    case 6: w = 0; c0 = 3000; c1 = 800; dec = 4; det = true; break;
                    default: w = 0; c0 = 4200; c1 = 1200; dec = 5; det = true;
                }
                for (int s = 0; s < 16; s++) {
                    int ms = (bar % 2) * 16 + s;
                    if (!mOn[ms]) continue;
                    int deg = mDeg[ms] + (((bar / 2) % 2 == 1 && ms >= 24) ? varShift : 0);
                    double tt = t(bar, s);
                    synth(lead, tt, mDur[ms] * step * 0.9, hz(note(base, cd + deg)), w, 0.16f, c0, c1, dec, det, 0, 0.004, 0.06);
                    cand(tt, s % 2 == 0 ? 2 : 3, false);
                }
            } else if (sec == BREAK) {
                for (int s = 0; s < 16; s += 2) {
                    int ms = (bar % 2) * 16 + s;
                    if (!mOn[ms]) continue;
                    double tt = t(bar, s);
                    synth(lead, tt, Math.max(2, mDur[ms]) * step, hz(note(base, cd + mDeg[ms])), 3, 0.13f, 3000, 2000, 3, false, 0, 0.01, 0.1);
                    cand(tt, s % 4 == 0 ? 1 : 2, false);
                }
            } else if (sec == VERSE || sec == BUILD) {
                int every = (m.bpm < 110 || m.bpm >= 150) ? 2 : 1;
                for (int s = 0, i = 0; s < 16; s += every, i++) {
                    int deg = cd + arpPat[i % 4] + 7;
                    double tt = t(bar, s);
                    synth(lead, tt, every * step * 0.8, hz(note(base, deg)), 3, 0.08f, 5000, 2000, 12, false, 0, 0.002, 0.03);
                    if (sec == VERSE) cand(tt, s % 4 == 0 ? 2 : 3, false);
                }
            }
        }

        // ---------- 믹스 ----------

        short[] mix() {
            for (double kt : kickTimes) {
                int s = idx(kt);
                for (int i = 0; i < duckCurve.length; i++) {
                    int j = s + i;
                    if (j < 0) continue;
                    if (j >= total) break;
                    if (duckCurve[i] < duck[j]) duck[j] = duckCurve[i];
                }
            }
            int d = idx(3 * step);
            float[] out = new float[total];
            float peak = 1e-6f;
            for (int i = 0; i < total; i++) {
                float l = lead[i];
                if (i >= d) l += lead[i - d] * 0.32f;
                if (i >= 2 * d) l += lead[i - 2 * d] * 0.14f;
                float v = drums[i] + (music[i] + l) * duck[i];
                v = (float) Math.tanh(v * 1.2);
                out[i] = v;
                float av = Math.abs(v);
                if (av > peak) peak = av;
            }
            float g = 0.95f / peak;
            short[] pcm = new short[total];
            for (int i = 0; i < total; i++) pcm[i] = (short) (out[i] * g * 32767);
            return pcm;
        }

        // ---------- 채보 ----------

        List<Note> chart() {
            double gap = new double[]{0.42, 0.26, 0.17, 0.105}[m.diff];
            ArrayList<Cand> list = new ArrayList<>();
            for (Cand c : cands) if (c.level <= m.diff) list.add(c);
            // 베젤 노트를 먼저 배치 (같은 시점의 탭보다 우선)
            list.sort((a, b) -> {
                if (a.spin != b.spin) return a.spin ? -1 : 1;
                if (a.level != b.level) return a.level - b.level;
                return Double.compare(a.t, b.t);
            });
            TreeMap<Double, Cand> chosen = new TreeMap<>();
            for (Cand c : list) {
                Map.Entry<Double, Cand> lo = chosen.floorEntry(c.t);
                Map.Entry<Double, Cand> hi = chosen.ceilingEntry(c.t);
                if (lo != null && c.t - lo.getKey() < ((lo.getValue().spin || c.spin) ? gap * 1.6 : gap)) continue;
                if (hi != null && hi.getKey() - c.t < ((hi.getValue().spin || c.spin) ? gap * 1.6 : gap)) continue;
                chosen.put(c.t, c);
            }
            ArrayList<Note> notes = new ArrayList<>();
            Random dr = new Random(m.seed + 99);
            int dir = 1;
            for (Cand c : chosen.values()) {
                float ang = (float) ((((c.t - LEAD_IN) / beat) * 45.0 + 270) % 360);
                if (c.spin) {
                    int d = 0;
                    if (m.diff >= 2) {
                        d = dir;
                        if (dr.nextFloat() < 0.7f) dir = -dir;
                    }
                    notes.add(new Note(c.t, Note.SPIN, d, ang));
                } else {
                    notes.add(new Note(c.t, Note.TAP, 0, ang));
                }
            }
            return mergeHolds(notes);
        }

        /** 짧은 간격으로 3개 이상 이어지는 탭 노트를 하나의 줄(HOLD) 노트로 합친다. */
        List<Note> mergeHolds(ArrayList<Note> in) {
            double maxGap = Math.max(0.17, beat * 0.5 + 0.01);
            ArrayList<Note> out = new ArrayList<>();
            int i = 0;
            while (i < in.size()) {
                Note a = in.get(i);
                int j = i;
                if (a.type == Note.TAP) {
                    while (j + 1 < in.size() && in.get(j + 1).type == Note.TAP
                            && in.get(j + 1).time - in.get(j).time <= maxGap) j++;
                }
                if (j - i >= 2 && in.get(j).time - a.time >= 0.3) {
                    out.add(new Note(a.time, in.get(j).time, Note.HOLD, 0, a.angle));
                    i = j + 1;
                } else {
                    out.add(a);
                    i++;
                }
            }
            return out;
        }
    }
}
