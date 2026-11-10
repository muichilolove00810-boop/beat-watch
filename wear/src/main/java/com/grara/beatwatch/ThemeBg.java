package com.grara.beatwatch;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import com.grara.beat.core.MapCode;

/**
 * 공식 맵 13개 전용 + 장르별 기본 플레이 배경 (절차적 그리기).
 * 프레임마다 객체를 만들지 않는다: Path·셰이더는 크기가 정해질 때 한 번만 만들고 재사용.
 * 입자는 상태 없이 (번호 해시, 시간)으로 위치를 계산 → 배열 상태도 필요 없음. 입자 수 상한 40.
 * 배경은 어둡고 흐리게, 판정 링 주변은 마지막에 다시 어둡게 덮어 노트가 항상 앞에 보이게 한다.
 */
final class ThemeBg {

    static final String[] PRESET_NAMES = {"네온 러시", "선셋 드라이브", "픽셀 하트", "사이버 펄스", "스타게이트",
            "썬더 브레이크", "글리치 스톰", "지옥의 문", "레이저 하우스", "밤의 고속도로", "얼음 궁전", "8비트 모험", "폭풍 베이스"};
    static final MapCode[] PRESETS = {
            new MapCode(0, 124, 0, 1, 9, true, 11),
            new MapCode(2, 100, 0, 1, 6, true, 202),
            new MapCode(5, 150, 1, 1, 0, false, 55),
            new MapCode(1, 132, 1, 2, 2, true, 3303),
            new MapCode(3, 138, 1, 2, 7, true, 404),
            new MapCode(4, 172, 2, 2, 4, true, 6006),
            new MapCode(7, 150, 2, 2, 11, false, 707),
            new MapCode(6, 140, 3, 3, 5, true, 8080),
            new MapCode(0, 126, 1, 1, 4, false, 1212),
            new MapCode(2, 108, 1, 2, 9, true, 2424),
            new MapCode(3, 140, 2, 2, 1, true, 3636),
            new MapCode(5, 160, 2, 1, 7, false, 4848),
            new MapCode(6, 144, 2, 2, 10, true, 5050),
    };
    static final int NEON = 0, SUNSET = 1, PIXEL = 2, CYBER = 3, STAR = 4, THUNDER = 5, GLITCH = 6, HELL = 7,
            LASER = 8, HIGHWAY = 9, ICE = 10, BIT8 = 11, STORM = 12;
    /** 장르(하우스·테크노·신스웨이브·트랜스·DnB·칩튠·덥스텝·퓨처베이스) → 기본 배경 */
    private static final int[] STYLE_BG = {NEON, CYBER, SUNSET, STAR, THUNDER, BIT8, STORM, GLITCH};

    static final String[] FX_NAMES = {"켜기", "약하게", "끄기"};
    static final float[] FX_AMT = {1f, 0.55f, 0f};

    private static String[] presetCodes;

    /** 맵 코드가 공식 맵과 같으면 그 맵 전용 배경, 아니면 장르 배경. (코드 문자열을 만들므로 맵이 바뀔 때만 호출) */
    static int idFor(MapCode m) {
        if (m == null) return NEON;
        if (presetCodes == null) {
            String[] a = new String[PRESETS.length];
            for (int i = 0; i < a.length; i++) a[i] = PRESETS[i].code();
            presetCodes = a;
        }
        String code = m.code();
        for (int i = 0; i < presetCodes.length; i++) if (presetCodes[i].equals(code)) return i;
        return STYLE_BG[m.style];
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path work = new Path();
    private final Path heart = new Path(), circuit = new Path(), flake = new Path(), ice = new Path(),
            mountFar = new Path(), mountNear = new Path(), cloud = new Path(), arch = new Path(), scan = new Path(),
            iceTop = new Path();
    private final float[] trX = new float[32], trY = new float[32], trLen = new float[8];
    private Shader sky, hell;
    private float W, H, cx, cy, R, u, amt;

    ThemeBg() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---------------- 크기 정해질 때 한 번 ----------------

    void resize(float w, float h, float ringR) {
        W = w;
        H = h;
        cx = w / 2f;
        cy = h / 2f;
        R = ringR;
        u = w;
        sky = new LinearGradient(0, 0, 0, cy + u * 0.06f, Color.rgb(20, 6, 40), Color.rgb(80, 28, 58), Shader.TileMode.CLAMP);
        hell = new RadialGradient(cx, H * 0.85f, u * 0.85f, Color.rgb(150, 22, 10), Color.TRANSPARENT, Shader.TileMode.CLAMP);

        // 도트 하트 (7x6 픽셀, 가운데 기준, 픽셀 1 단위)
        heart.reset();
        for (int row = 0; row < HEART.length; row++) addRuns(heart, HEART[row], row, 3.5f, 3f);
        cloud.reset();
        for (int row = 0; row < CLOUD.length; row++) addRuns(cloud, CLOUD[row], row, 4f, 1.5f);

        // 회로 선: 바깥에서 안쪽으로 직각으로 꺾이는 8줄
        circuit.reset();
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + 20 + hf(i) * 10);
            float x0 = cx + (float) Math.cos(a) * u * 0.52f, y0 = cy + (float) Math.sin(a) * u * 0.52f;
            float y1 = cy + (y0 - cy) * 0.62f;
            float x2 = cx + (x0 - cx) * 0.4f;
            float y3 = cy + (y1 - cy) * 0.45f;
            float[] px = {x0, x0, x2, x2}, py = {y0, y1, y1, y3};
            float len = 0;
            for (int j = 0; j < 4; j++) {
                trX[i * 4 + j] = px[j];
                trY[i * 4 + j] = py[j];
                if (j > 0) len += Math.abs(px[j] - px[j - 1]) + Math.abs(py[j] - py[j - 1]);
            }
            trLen[i] = len;
            circuit.moveTo(px[0], py[0]);
            for (int j = 1; j < 4; j++) circuit.lineTo(px[j], py[j]);
        }

        // 눈 결정 (반지름 1, 팔 6개 + 곁가지)
        flake.reset();
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(k * 60);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            flake.moveTo(0, 0);
            flake.lineTo(ca, sa);
            for (int s = -1; s <= 1; s += 2) {
                double b = a + Math.toRadians(40 * s);
                flake.moveTo(ca * 0.55f, sa * 0.55f);
                flake.lineTo(ca * 0.55f + (float) Math.cos(b) * 0.3f, sa * 0.55f + (float) Math.sin(b) * 0.3f);
            }
        }

        // 얼음 결정: 화면 원을 따라 휘어짐 (아래는 솟은 결정, 위는 고드름)
        buildIce(ice, 40, 140, 12, 0.03f, 0.07f, 300);
        buildIce(iceTop, 215, 325, 14, 0.02f, 0.05f, 400);

        // 8비트 산 (가로 2W 타일, 계단 모양)
        buildMount(mountFar, u * 0.04f, 0.45f, 0.9f, 500);
        buildMount(mountNear, u * 0.025f, 0.3f, 0.7f, 600);

        // 지옥의 문 아치
        arch.reset();
        float aw = u * 0.22f;
        arch.moveTo(cx - aw, H);
        arch.lineTo(cx - aw, cy - u * 0.02f);
        arch.quadTo(cx - aw, cy - u * 0.24f, cx, cy - u * 0.3f);
        arch.quadTo(cx + aw, cy - u * 0.24f, cx + aw, cy - u * 0.02f);
        arch.lineTo(cx + aw, H);

        // 주사선
        scan.reset();
        for (float y = 0; y < H; y += u * 0.022f) {
            scan.moveTo(0, y);
            scan.lineTo(W, y);
        }
    }

    private static final String[] HEART = {".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."};
    private static final String[] CLOUD = {"..XXX...", ".XXXXXX.", "XXXXXXXX"};

    private static void addRuns(Path p, String row, int y, float ox, float oy) {
        int s = -1;
        for (int x = 0; x <= row.length(); x++) {
            boolean on = x < row.length() && row.charAt(x) == 'X';
            if (on && s < 0) s = x;
            if (!on && s >= 0) {
                p.addRect(s - ox, y - oy, x - ox, y + 1 - oy, Path.Direction.CW);
                s = -1;
            }
        }
    }

    /** 원 가장자리(각도 a0~a1)를 따라 안쪽으로 솟은 결정. 골은 가장자리에서 0.04u 안쪽. */
    private void buildIce(Path p, float a0, float a1, int n, float hMin, float hVar, int seed) {
        p.reset();
        float ro = u * 0.53f, rv = u * 0.46f;
        p.moveTo(cx + cosd(a0) * ro, cy + sind(a0) * ro);
        for (int i = 0; i <= n; i++) {
            float a = a0 + (a1 - a0) * i / n;
            p.lineTo(cx + cosd(a) * rv, cy + sind(a) * rv);
            if (i < n) {
                float am = a + (a1 - a0) / n * 0.5f, rt = rv - u * (hMin + hVar * hf(seed + i));
                p.lineTo(cx + cosd(am) * rt, cy + sind(am) * rt);
            }
        }
        for (int i = 8; i >= 0; i--) {
            float a = a0 + (a1 - a0) * i / 8;
            p.lineTo(cx + cosd(a) * ro, cy + sind(a) * ro);
        }
        p.close();
    }

    private static float cosd(float d) {
        return (float) Math.cos(Math.toRadians(d));
    }

    private static float sind(float d) {
        return (float) Math.sin(Math.toRadians(d));
    }

    private void buildMount(Path p, float b, float peakH, float slope, int seed) {
        int n = Math.round(2 * W / b);
        p.reset();
        p.moveTo(0, H);
        for (int j = 0; j < n; j++) {
            float h = 0;
            for (int k = 0; k < 4; k++) {
                float pc = hf(seed + k) * n, ph = peakH * (0.6f + 0.4f * hf(seed + 10 + k));
                float d = Math.abs(j - pc);
                d = Math.min(d, n - d); // 타일이 이어지도록
                h = Math.max(h, ph - d * slope * b / u);
            }
            float hh = Math.round(Math.max(0.05f, h) * u / b) * b;
            p.lineTo(j * b, H - hh);
            p.lineTo((j + 1) * b, H - hh);
        }
        p.lineTo(n * b, H);
        p.close();
    }

    // ---------------- 프레임마다 ----------------

    /**
     * @param tm    시간(초) — 플레이 중엔 곡 시간
     * @param beats 박자 위치(소수부 = 박자 위상)
     * @param en    곡 세기 0..1
     * @param a     강도 (켜기 1, 약하게 0.55, 상세 화면은 더 낮게). 0이면 아무것도 안 그림
     */
    void draw(Canvas c, int id, double tm, double beats, float en, float a) {
        if (a <= 0 || u <= 0) return;
        amt = a;
        float fr = fr(beats);
        float pulse = (float) Math.exp(-fr * 5) * en;
        float t = (float) tm;
        switch (id) {
            case NEON: neon(c, beats, fr, pulse, en, t); break;
            case SUNSET: sunset(c, fr, pulse); break;
            case PIXEL: pixel(c, pulse, t); break;
            case CYBER: cyber(c, beats, pulse); break;
            case STAR: star(c, beats, pulse, t); break;
            case THUNDER: thunder(c, beats, en, t); break;
            case GLITCH: glitch(c, beats, en); break;
            case HELL: hell(c, pulse, t); break;
            case LASER: laser(c, pulse, t); break;
            case HIGHWAY: highway(c, beats); break;
            case ICE: ice(c, pulse, t); break;
            case BIT8: bit8(c, beats, t); break;
            case STORM: storm(c, pulse, t); break;
        }
        // 판정 링 주변은 다시 어둡게 비워 둔다
        line.setStrokeWidth(u * 0.06f);
        line.setColor(Color.argb((int) (150 * Math.min(1f, a * 1.5f)), 8, 6, 16));
        c.drawCircle(cx, cy, R, line);
    }

    private int col(int r, int g, int b, float a) {
        return Color.argb(Math.max(0, Math.min(255, (int) (a * amt))), r, g, b);
    }

    static float hf(int n) {
        n ^= n >>> 16;
        n *= 0x7feb352d;
        n ^= n >>> 15;
        n *= 0x846ca68b;
        n ^= n >>> 16;
        return (n & 0xFFFFFF) / 16777216f;
    }

    private static float fr(double v) {
        return (float) (v - Math.floor(v));
    }

    // 0 네온 러시: 박자마다 가운데서 퍼지는 네온 링 + 테두리 네온 점
    private void neon(Canvas c, double beats, float fr, float pulse, float en, float t) {
        long b0 = (long) Math.floor(beats);
        for (int k = 0; k < 4; k++) {
            float s = (k + fr) / 4f;
            float rr = u * (0.08f + 0.3f * s);
            float al = 80 * (1 - s) * (0.5f + 0.5f * en) * Math.min(1f, (u * 0.38f - rr) / (u * 0.08f));
            line.setStrokeWidth(u * (0.004f + 0.007f * (1 - s)));
            line.setColor(((b0 - k) & 1) == 0 ? col(60, 200, 255, al) : col(255, 70, 200, al));
            c.drawCircle(cx, cy, rr, line);
        }
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 + t * 12);
            fill.setColor((i & 1) == 0 ? col(60, 200, 255, 50 + 90 * pulse) : col(255, 70, 200, 50 + 90 * pulse));
            c.drawCircle(cx + (float) Math.cos(a) * u * 0.465f, cy + (float) Math.sin(a) * u * 0.465f, u * 0.007f, fill);
        }
    }

    // 1 선셋 드라이브: 노을 하늘 + 줄무늬 태양 + 원근 격자 도로
    private void sunset(Canvas c, float fr, float pulse) {
        float hy = cy + u * 0.06f;
        fill.setShader(sky);
        fill.setColor(Color.BLACK);
        fill.setAlpha((int) (255 * amt));
        c.drawRect(0, 0, W, hy, fill);
        fill.setShader(null);
        float sy = hy - u * 0.08f, sr = u * 0.16f;
        fill.setColor(col(170, 80, 60, 90 + 40 * pulse));
        c.drawCircle(cx, sy, sr, fill);
        fill.setColor(col(71, 25, 55, 255));
        for (int k = 0; k < 4; k++) {
            float y = sy + u * 0.01f + (k + fr) * u * 0.035f;
            if (y > hy) continue;
            c.drawRect(cx - sr, y, cx + sr, y + u * (0.004f + 0.003f * k), fill);
        }
        fill.setColor(col(14, 4, 24, 255));
        c.drawRect(0, hy, W, H, fill);
        line.setStrokeWidth(u * 0.003f);
        line.setColor(col(200, 60, 170, 55 + 40 * pulse));
        work.reset();
        for (int i = -6; i <= 6; i++) {
            work.moveTo(cx + i * u * 0.02f, hy);
            work.lineTo(cx + i * u * 0.2f, H);
        }
        c.drawPath(work, line);
        for (int k = 0; k < 6; k++) {
            float s = (k + fr) / 6f;
            float y = hy + (H - hy) * s * s;
            line.setColor(col(200, 60, 170, (25 + 60 * s) * (0.8f + 0.5f * pulse)));
            c.drawLine(0, y, W, y, line);
        }
        line.setStrokeWidth(u * 0.004f);
        line.setColor(col(255, 120, 90, 70));
        c.drawLine(0, hy, W, hy, line);
    }

    // 2 픽셀 하트: 가운데 큰 도트 하트가 박자에 뛰고, 작은 하트들이 떠오름
    private void pixel(Canvas c, float pulse, float t) {
        drawPix(c, heart, cx, cy - u * 0.01f, u * 0.065f * (1 + 0.08f * pulse), col(200, 50, 110, 50 + 50 * pulse));
        for (int i = 0; i < 9; i++) {
            float s = fr(hf(i * 7 + 2) + t * (0.035f + 0.03f * hf(i * 7 + 3)));
            float x = cx + (hf(i * 7 + 1) - 0.5f) * u * 0.85f + (float) Math.sin(t * 0.9f + i) * u * 0.03f;
            float y = H * 1.08f - s * H * 1.16f;
            float px = u * (0.012f + 0.008f * hf(i * 7 + 4)) * (1 + 0.18f * pulse);
            float al = 120 * Math.min(1f, Math.min(s * 5, (1 - s) * 5));
            drawPix(c, heart, x, y, px, col(255, 110, 160, al));
        }
    }

    private void drawPix(Canvas c, Path shape, float x, float y, float px, int color) {
        fill.setColor(color);
        c.save();
        c.translate(x, y);
        c.scale(px, px);
        c.drawPath(shape, fill);
        c.restore();
    }

    // 3 사이버 펄스: 회로 선 + 박자에 맞춰 안쪽으로 흐르는 신호
    private void cyber(Canvas c, double beats, float pulse) {
        line.setStrokeWidth(u * 0.004f);
        line.setColor(col(40, 160, 110, 45 + 60 * pulse));
        c.drawPath(circuit, line);
        for (int i = 0; i < 8; i++) {
            fill.setColor(col(40, 160, 110, 90));
            c.drawCircle(trX[i * 4 + 3], trY[i * 4 + 3], u * 0.009f, fill);
            float d = fr(beats * 0.25 + i / 8.0) * trLen[i];
            float x = trX[i * 4], y = trY[i * 4];
            for (int j = 1; j < 4; j++) {
                float x1 = trX[i * 4 + j], y1 = trY[i * 4 + j];
                float sl = Math.abs(x1 - x) + Math.abs(y1 - y);
                if (d <= sl || j == 3) {
                    float k = sl > 0 ? Math.min(1f, d / sl) : 0;
                    x += (x1 - x) * k;
                    y += (y1 - y) * k;
                    break;
                }
                d -= sl;
                x = x1;
                y = y1;
            }
            fill.setColor(col(180, 255, 240, 60 + 90 * pulse));
            c.drawCircle(x, y, u * 0.008f, fill);
        }
    }

    // 4 스타게이트: 워프하는 별 흐름 + 서로 반대로 도는 관문 고리
    private void star(Canvas c, double beats, float pulse, float t) {
        for (int i = 0; i < 40; i++) {
            double ang = hf(i * 3 + 1) * Math.PI * 2;
            float s = fr(hf(i * 3 + 2) + beats * 0.05 * (1 + hf(i * 3 + 3)));
            float r0 = u * (0.03f + 0.5f * s * s), len = u * (0.006f + 0.05f * s * s) * (1 + 0.8f * pulse);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            line.setStrokeWidth(u * (0.002f + 0.004f * s));
            line.setColor(col(170, 190, 255, 25 + 100 * s));
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len), line);
        }
        line.setStrokeWidth(u * 0.006f);
        line.setColor(col(100, 190, 255, 40 + 60 * pulse));
        float g = u * 0.24f;
        rect.set(cx - g, cy - g, cx + g, cy + g);
        for (int k = 0; k < 6; k++) c.drawArc(rect, t * 20 + k * 60, 38, false, line);
        g = u * 0.16f;
        rect.set(cx - g, cy - g, cx + g, cy + g);
        for (int k = 0; k < 6; k++) c.drawArc(rect, -t * 30 + k * 60 + 30, 30, false, line);
    }

    // 5 썬더 브레이크: 먹구름 + 2박자마다 옆에서 내리치는 번개
    private void thunder(Canvas c, double beats, float en, float t) {
        double b2 = beats / 2;
        int bi = (int) Math.floor(b2);
        float flash = (float) Math.exp(-(b2 - bi) * 2 * 4) * (0.5f + 0.5f * en);
        work.reset(); // 구름을 한 Path로 모아 한 번에 칠함 (겹친 이음매 없음)
        for (int i = 0; i < 7; i++) {
            float x = cx + (i - 3) * u * 0.14f + (float) Math.sin(t * 0.3f + i) * u * 0.02f;
            float y = u * 0.03f + hf(i) * u * 0.05f;
            work.addCircle(x, y, u * 0.12f + hf(i + 9) * u * 0.05f, Path.Direction.CW);
        }
        int kf = (int) (40 * flash);
        fill.setColor(col(40 + kf, 40 + kf, 60 + kf, 90));
        c.drawPath(work, fill);
        if (flash < 0.02f) return;
        fill.setColor(col(140, 140, 220, 22 * flash));
        c.drawCircle(cx, cy, u * 0.5f, fill);
        float x = cx + (hf(bi * 5 + 1) < 0.5f ? -1 : 1) * u * (0.15f + 0.15f * hf(bi * 5 + 2));
        work.reset();
        work.moveTo(x, u * 0.05f);
        float bx = x, by = 0;
        for (int k = 1; k <= 10; k++) {
            x += (hf(bi * 5 + 10 + k) - 0.5f) * u * 0.14f;
            work.lineTo(x, u * 0.05f + k * u * 0.075f);
            if (k == 4) {
                bx = x;
                by = u * 0.05f + k * u * 0.075f;
            }
        }
        float dir = hf(bi * 5 + 30) < 0.5f ? -1 : 1; // 곁가지
        work.moveTo(bx, by);
        work.lineTo(bx + dir * u * 0.07f, by + u * 0.06f);
        work.lineTo(bx + dir * u * 0.1f, by + u * 0.13f);
        line.setStrokeWidth(u * 0.02f);
        line.setColor(col(150, 150, 255, 50 * flash));
        c.drawPath(work, line);
        line.setStrokeWidth(u * 0.01f);
        line.setColor(col(230, 230, 255, 170 * flash));
        c.drawPath(work, line);
    }

    // 6 글리치 스톰: 주사선 + 1박자마다 빨강·흰색으로 어긋나는 노이즈 띠
    private void glitch(Canvas c, double beats, float en) {
        line.setStrokeWidth(u * 0.003f);
        line.setColor(col(100, 80, 160, 22));
        c.drawPath(scan, line);
        int tick = (int) Math.floor(beats); // 1박자마다만 바뀜 (광과민 배려)
        float g = 0.35f + 0.65f * (float) Math.exp(-fr(beats) * 3);
        for (int k = 0; k < 4; k++) {
            int s = tick * 13 + k * 7;
            float y = hf(s) * H, h = u * (0.01f + 0.04f * hf(s + 50));
            float x0 = hf(s + 150) * W * 0.5f, x1 = x0 + W * (0.3f + 0.7f * hf(s + 200));
            float d = (hf(s + 100) - 0.5f) * u * 0.1f * g + u * 0.008f;
            float al = 30 * g * (0.5f + en);
            fill.setColor(col(200, 30, 70, al));
            c.drawRect(x0 + d, y, x1 + d, y + h, fill);
            fill.setColor(col(200, 200, 230, al));
            c.drawRect(x0 - d, y, x1 - d, y + h, fill);
        }
        fill.setColor(col(220, 220, 255, 45 * g));
        float sz = u * 0.008f;
        for (int k = 0; k < 14; k++) {
            int s = tick * 31 + k * 3 + 1000;
            float x = hf(s) * W, y = hf(s + 1) * H;
            c.drawRect(x, y, x + sz, y + sz, fill);
        }
    }

    // 7 지옥의 문: 아래에서 붉게 맥동하는 빛 + 아치 문 + 솟아오르는 불씨
    private void hell(Canvas c, float pulse, float t) {
        fill.setShader(hell);
        fill.setColor(Color.BLACK);
        fill.setAlpha(Math.min(255, (int) ((150 + 90 * pulse) * amt)));
        c.drawCircle(cx, H * 0.85f, u * 0.85f, fill);
        fill.setShader(null);
        line.setStrokeWidth(u * 0.012f);
        line.setColor(col(200, 50, 30, 90 + 50 * pulse));
        c.drawPath(arch, line);
        for (int i = 0; i < 36; i++) {
            float s = fr(hf(i * 4 + 2) + t * (0.15f + 0.15f * hf(i * 4 + 3)));
            float x = cx + (hf(i * 4 + 1) - 0.5f) * u * 0.8f + (float) Math.sin(t * 2 + i) * u * 0.02f;
            float y = H * (1.02f - s * 0.95f);
            float r = u * (0.004f + 0.006f * hf(i * 4 + 4)) * (1 - s * 0.6f);
            fill.setColor((i & 1) == 0 ? col(255, 90, 30, 130 * (1 - s)) : col(255, 200, 80, 130 * (1 - s)));
            c.drawCircle(x, y, r, fill);
        }
    }

    // 8 레이저 하우스: 위아래 4곳에서 좌우로 휘젓는 레이저 빔
    private static final int[] LASER_COL = {Color.rgb(60, 255, 120), Color.rgb(255, 60, 200),
            Color.rgb(255, 60, 80), Color.rgb(230, 230, 255)};

    private void laser(Canvas c, float pulse, float t) {
        for (int s = 0; s < 4; s++) {
            boolean bottom = s < 2;
            float sx = cx + (s % 2 == 0 ? -1 : 1) * u * (bottom ? 0.25f : 0.3f), sy = bottom ? H : 0;
            int lc = LASER_COL[s];
            for (int j = 0; j < 2; j++) {
                double ang = Math.toRadians((bottom ? -90 : 90) + (j == 0 ? -15 : 15)
                        + 35 * Math.sin(t * 1.1 + j * 1.7 + s * 0.8));
                float ex = sx + (float) Math.cos(ang) * u * 1.1f, ey = sy + (float) Math.sin(ang) * u * 1.1f;
                line.setStrokeWidth(u * 0.016f);
                line.setColor(col(Color.red(lc), Color.green(lc), Color.blue(lc), 16 + 25 * pulse));
                c.drawLine(sx, sy, ex, ey, line);
                line.setStrokeWidth(u * 0.004f);
                line.setColor(col(Color.red(lc), Color.green(lc), Color.blue(lc), 30 + 50 * pulse));
                c.drawLine(sx, sy, ex, ey, line);
            }
        }
    }

    // 9 밤의 고속도로: 원근 도로 + 지나가는 차선 + 양옆 가로등 불빛
    private void highway(Canvas c, double beats) {
        float vy = cy - u * 0.12f;
        float sc = fr(beats * 0.5);
        work.reset();
        work.moveTo(cx, vy);
        work.lineTo(cx - u * 0.6f, H);
        work.lineTo(cx + u * 0.6f, H);
        work.close();
        fill.setColor(col(16, 16, 40, 200));
        c.drawPath(work, fill);
        line.setStrokeWidth(u * 0.006f);
        line.setColor(col(90, 90, 130, 100));
        c.drawLine(cx, vy, cx - u * 0.6f, H, line);
        c.drawLine(cx, vy, cx + u * 0.6f, H, line);
        line.setColor(col(255, 150, 80, 45));
        line.setStrokeWidth(u * 0.004f);
        c.drawLine(cx - u * 0.35f, vy, cx + u * 0.35f, vy, line);
        for (int k = 0; k < 6; k++) {
            float s = (k + sc) / 6f, s2 = Math.min(1f, s + 0.06f);
            line.setStrokeWidth(u * (0.004f + 0.02f * s));
            line.setColor(col(200, 200, 160, (30 + 50 * s) * (s < 0.45f ? 0.5f : 1f))); // 가운데(콤보 글자) 쪽은 흐리게
            c.drawLine(cx, vy + (H - vy) * s * s, cx, vy + (H - vy) * s2 * s2, line);
        }
        for (int k = 0; k < 5; k++) {
            float s = (k + sc) / 5f, p = s * s;
            for (int side = -1; side <= 1; side += 2) {
                float x = cx + side * (u * 0.03f + u * 0.75f * p), y = vy - (u * 0.02f + u * 0.3f * p);
                line.setStrokeWidth(u * (0.002f + 0.006f * p));
                line.setColor(col(60, 60, 90, 60 * p));
                c.drawLine(x, y, x, vy + (H - vy) * p, line);
                float lr = u * (0.004f + 0.018f * p);
                fill.setColor(col(255, 170, 60, 15 + 35 * p));
                c.drawCircle(x, y, lr * 2, fill);
                fill.setColor(col(255, 190, 90, 40 + 90 * p));
                c.drawCircle(x, y, lr, fill);
            }
        }
    }

    // 10 얼음 궁전: 내리는 눈 + 천천히 도는 눈 결정 + 위아래 얼음 결정
    private void ice(Canvas c, float pulse, float t) {
        fill.setColor(col(60, 120, 170, 45));
        c.drawPath(ice, fill);
        fill.setColor(col(60, 120, 170, 30));
        c.drawPath(iceTop, fill);
        for (int i = 0; i < 30; i++) {
            float x = hf(i * 5 + 1) * W + (float) Math.sin(t * 0.7f + i * 1.3f) * u * 0.03f;
            float y = fr(hf(i * 5 + 2) + t * (0.03f + 0.04f * hf(i * 5 + 3))) * H * 1.1f - u * 0.05f;
            fill.setColor(col(200, 230, 255, 70 + 40 * hf(i * 5 + 5)));
            c.drawCircle(x, y, u * (0.003f + 0.005f * hf(i * 5 + 4)), fill);
        }
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(i * 72 + 15 + t * 3);
            float d = u * (0.2f + 0.13f * hf(i + 700));
            float rad = u * (0.035f + 0.02f * hf(i + 710)) * (1 + 0.12f * pulse);
            line.setStrokeWidth(u * 0.003f / rad);
            line.setColor(col(150, 210, 255, 60 + 50 * pulse));
            c.save();
            c.translate(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d);
            c.rotate(t * 10 + i * 30);
            c.scale(rad, rad);
            c.drawPath(flake, line);
            c.restore();
        }
    }

    // 11 8비트 모험: 도트 별 + 흘러가는 도트 구름 + 두 겹의 계단 산
    private void bit8(Canvas c, double beats, float t) {
        long b0 = (long) Math.floor(beats);
        float sz = u * 0.016f;
        for (int i = 0; i < 8; i++) {
            boolean on = ((b0 + i) & 1) == 0;
            fill.setColor(col(200, 200, 255, on ? 90 : 35));
            float x = hf(i + 800) * W, y = u * (0.08f + 0.3f * hf(i + 810));
            c.drawRect(x, y, x + sz, y + sz, fill);
        }
        for (int i = 0; i < 3; i++) {
            float s = fr(hf(i + 820) + t * 0.02f * (1 + i * 0.3f));
            float x = W + u * 0.15f - s * (W + u * 0.3f), y = u * (0.17f + i * 0.09f);
            drawPix(c, cloud, x, y, u * 0.016f, col(90, 90, 140, 110));
        }
        float off = fr(t * 0.015f) * 2 * W;
        fill.setColor(col(60, 60, 140, 170));
        drawTile(c, mountFar, off);
        off = fr(t * 0.035f) * 2 * W;
        fill.setColor(col(40, 120, 70, 200));
        drawTile(c, mountNear, off);
    }

    private void drawTile(Canvas c, Path p, float off) {
        c.save();
        c.translate(-off, 0);
        c.drawPath(p, fill);
        c.translate(2 * W, 0);
        c.drawPath(p, fill);
        c.restore();
    }

    // 12 폭풍 베이스: 비스듬히 내리는 비 + 베이스에 맞춰 출렁이는 파동
    private void storm(Canvas c, float pulse, float t) {
        work.reset();
        for (int i = 0; i < 40; i++) {
            float y = fr(hf(i * 3 + 2) + t * (0.6f + 0.3f * hf(i * 3 + 3))) * (H + u * 0.1f) - u * 0.05f;
            float x = hf(i * 3 + 1) * W * 1.2f - u * 0.2f + y * 0.3f;
            work.moveTo(x, y);
            work.lineTo(x + u * 0.015f, y + u * 0.05f);
        }
        line.setStrokeWidth(u * 0.0045f);
        line.setColor(col(100, 160, 255, 90));
        c.drawPath(work, line);
        for (int w = 0; w < 2; w++) {
            float y0 = cy + (w == 0 ? -1 : 1) * u * 0.27f;
            float amp = u * 0.03f * (0.3f + 1.5f * pulse);
            work.reset();
            for (int k = 0; k <= 48; k++) {
                float x = W * k / 48f;
                float env = (float) Math.sin(Math.PI * k / 48.0);
                float y = y0 + (float) Math.sin(k * 0.4f + t * 3 + w * 2) * amp * env;
                if (k == 0) work.moveTo(x, y);
                else work.lineTo(x, y);
            }
            line.setStrokeWidth(u * 0.005f);
            line.setColor(col(70, 140, 255, 60 + 60 * pulse));
            c.drawPath(work, line);
        }
    }
}
