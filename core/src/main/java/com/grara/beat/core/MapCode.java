package com.grara.beat.core;

import java.util.Random;

/**
 * 맵 코드: 장르·BPM·난이도·길이·키·장/단조·시드를 40비트에 담고 체크 문자 1개를 붙인 9글자.
 * 같은 코드 → 항상 같은 음악과 같은 박자(노트).
 */
public final class MapCode {
    public static final String ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final String[] STYLES = {"하우스", "테크노", "신스웨이브", "트랜스", "드럼앤베이스", "칩튠", "덥스텝", "퓨처베이스"};
    public static final int[] STYLE_BPM = {124, 132, 100, 138, 172, 150, 140, 150};
    public static final String[] DIFFS = {"쉬움", "보통", "어려움", "지옥"};
    public static final int[] BARS = {16, 24, 32, 48};
    public static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    public static final int BPM_MIN = 80, BPM_MAX = 206;

    public final int style, bpm, diff, len, key, seed;
    public final boolean minor;

    public MapCode(int style, int bpm, int diff, int len, int key, boolean minor, int seed) {
        this.style = clamp(style, 0, 7);
        int b = clamp(bpm, BPM_MIN, BPM_MAX);
        this.bpm = BPM_MIN + ((b - BPM_MIN) / 2) * 2;
        this.diff = clamp(diff, 0, 3);
        this.len = clamp(len, 0, 3);
        this.key = clamp(key, 0, 11);
        this.minor = minor;
        this.seed = seed & 0x3FFFFF;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private long bits() {
        long v = style;
        v = (v << 6) | (((bpm - BPM_MIN) / 2) & 63);
        v = (v << 2) | diff;
        v = (v << 2) | len;
        v = (v << 4) | key;
        v = (v << 1) | (minor ? 1 : 0);
        v = (v << 22) | seed;
        return v;
    }

    /** 9글자 코드 (구분자 없음). */
    public String code() {
        char[] c = new char[9];
        long v = bits();
        for (int i = 7; i >= 0; i--) {
            c[i] = ALPHA.charAt((int) (v & 31));
            v >>= 5;
        }
        c[8] = ALPHA.charAt(check(c));
        return new String(c);
    }

    /** 보기 좋은 형태: ABCD-EFGH-J */
    public String pretty() {
        String s = code();
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.charAt(8);
    }

    private static int check(char[] c) {
        int s = 0;
        for (int i = 0; i < 8; i++) s += ALPHA.indexOf(c[i]) * (2 * i + 1);
        return s % 32;
    }

    /** 사용자가 입력한 코드를 해석. 틀리면 null. */
    public static MapCode parse(String in) {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char ch : in.toUpperCase().toCharArray()) {
            if (ch == 'O') ch = '0';
            if (ch == 'I' || ch == 'L') ch = '1';
            if (ch == 'U') ch = 'V';
            if (ALPHA.indexOf(ch) >= 0) sb.append(ch);
        }
        if (sb.length() != 9) return null;
        char[] c = sb.toString().toCharArray();
        if (ALPHA.charAt(check(c)) != c[8]) return null;
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 5) | ALPHA.indexOf(c[i]);
        int seed = (int) (v & 0x3FFFFF); v >>= 22;
        boolean minor = (v & 1) == 1; v >>= 1;
        int key = (int) (v & 15); v >>= 4;
        int len = (int) (v & 3); v >>= 2;
        int diff = (int) (v & 3); v >>= 2;
        int bpm = BPM_MIN + (int) (v & 63) * 2; v >>= 6;
        int style = (int) (v & 7);
        if (key > 11 || bpm > BPM_MAX) return null;
        return new MapCode(style, bpm, diff, len, key, minor, seed);
    }

    public static MapCode random(Random r, int diff) {
        int style = r.nextInt(8);
        int bpm = STYLE_BPM[style] + (r.nextInt(5) - 2) * 2;
        return new MapCode(style, bpm, diff, 1 + r.nextInt(2), r.nextInt(12), r.nextInt(3) > 0, r.nextInt(0x3FFFFF));
    }

    public String keyName() {
        return KEYS[key] + (minor ? " 단조" : " 장조");
    }

    public String summary() {
        return STYLES[style] + " · " + bpm + " BPM · " + DIFFS[diff];
    }

    /** 곡 길이(초, 리드인 제외). */
    public double lengthSec() {
        return BARS[len] * 4 * 60.0 / bpm;
    }

    public String lengthText() {
        int s = (int) Math.round(lengthSec());
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
