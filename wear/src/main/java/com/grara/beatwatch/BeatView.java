package com.grara.beatwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import com.grara.beat.core.AudioPlayer;
import com.grara.beat.core.MapCode;
import com.grara.beat.core.Note;
import com.grara.beat.core.Song;
import com.grara.beat.core.Updater;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** 원형 워치용 리듬게임 화면 전체 (메뉴 / 코드 입력 / 플레이 / 결과). */
public class BeatView extends View {

    private static final int S_MENU = 0, S_DETAIL = 1, S_CODE = 2, S_LOADING = 3, S_PLAY = 4, S_RESULT = 5, S_SYNC = 6;
    private static final int R_PERFECT = 0, R_GREAT = 1, R_GOOD = 2, R_MISS = 3;
    // 판정 범위(초). 베젤 노트는 SPIN_MUL 배 더 넉넉하게.
    private static final double W_PERFECT = 0.075, W_GREAT = 0.135, W_GOOD = 0.200;
    private static final double SPIN_MUL = 1.5;
    private static final double[] APPROACH = {1.5, 1.25, 1.0, 0.8};
    private static final int[] STYLE_COLOR = {
            Color.rgb(0, 229, 255), Color.rgb(120, 255, 120), Color.rgb(255, 80, 200), Color.rgb(140, 120, 255),
            Color.rgb(255, 170, 40), Color.rgb(255, 230, 60), Color.rgb(170, 60, 255), Color.rgb(60, 200, 255)
    };
    private static final int C_CW = Color.rgb(255, 150, 40), C_CCW = Color.rgb(190, 90, 255), C_ANY = Color.rgb(120, 255, 140);

    private static final class Item {
        final String title, sub;
        final MapCode map;
        final int action; // 0 맵, 1 랜덤, 2 코드, 3 싱크

        Item(String title, String sub, MapCode map, int action) {
            this.title = title;
            this.sub = sub;
            this.map = map;
            this.action = action;
        }
    }

    private static final class Fx {
        float x, y, life, max;
        int color;
        boolean ring;
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix mtx = new Matrix();
    private final Random rnd = new Random();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private Shader glowShader;

    Updater updater;

    private float W, H, cx, cy, R;
    private int screen = S_MENU;
    private boolean running;

    // 메뉴
    private final ArrayList<Item> items = new ArrayList<>();
    private int menuSel;
    private float menuPos;

    // 선택된 맵
    private MapCode cur;
    private String curTitle = "";
    private Song song;
    private boolean loading;

    // 코드 입력
    private final int[] codeIdx = new int[9];
    private int codeSlot;
    private String codeMsg = "";
    private float codeShake;

    // 플레이
    private AudioPlayer player;
    private int nextIdx, combo, maxCombo, score, life;
    private final int[] counts = new int[4];
    private double sumOff;
    private int offN;
    private boolean failed;
    private final ArrayList<Fx> fx = new ArrayList<>();
    private String judgeText = "";
    private int judgeColor;
    private float judgeT, ringFlash;
    private double playT;
    private int syncMs;

    // 결과
    private float resultT;
    private boolean newBest;

    // 입력
    private float downX, downY;
    private long downTime;
    private boolean swiped, scrolled, touching;
    private Note activeHold;
    private boolean holdByBezel;
    private double lastBezelT;
    private float rotAcc;
    private long lastRot;

    public BeatView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = ctx.getSharedPreferences("beat", Context.MODE_PRIVATE);
        vibrator = ctx.getSystemService(Vibrator.class);
        syncMs = prefs.getInt("sync", 0);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mono.setTextAlign(Paint.Align.CENTER);
        mono.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        buildMenu();
    }

    private void buildMenu() {
        items.clear();
        addPreset("네온 러시", new MapCode(0, 124, 0, 1, 9, true, 11));
        addPreset("선셋 드라이브", new MapCode(2, 100, 0, 1, 6, true, 202));
        addPreset("픽셀 하트", new MapCode(5, 150, 1, 1, 0, false, 55));
        addPreset("사이버 펄스", new MapCode(1, 132, 1, 2, 2, true, 3303));
        addPreset("스타게이트", new MapCode(3, 138, 1, 2, 7, true, 404));
        addPreset("썬더 브레이크", new MapCode(4, 172, 2, 2, 4, true, 6006));
        addPreset("글리치 스톰", new MapCode(7, 150, 2, 2, 11, false, 707));
        addPreset("지옥의 문", new MapCode(6, 140, 3, 3, 5, true, 8080));
        items.add(new Item("랜덤 맵", "새 음악 + 새 코드 만들기", null, 1));
        items.add(new Item("코드 입력", "폰/친구가 만든 맵 플레이", null, 2));
        items.add(new Item("싱크 조절", "현재 " + fmtMs(syncMs), null, 3));
    }

    private void addPreset(String name, MapCode m) {
        items.add(new Item(name, m.summary(), m, 0));
    }

    private static String fmtMs(int ms) {
        return (ms > 0 ? "+" : "") + ms + "ms";
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        cx = w / 2f;
        cy = h / 2f;
        R = w * 0.43f;
        glowShader = new RadialGradient(0, 0, 1, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    }

    void resume() {
        running = true;
        invalidate();
    }

    void pause() {
        running = false;
        if (screen == S_PLAY) {
            stopPlayer();
            screen = S_DETAIL;
        }
    }

    /** 뒤로 가기. 처리했으면 true. */
    boolean back() {
        switch (screen) {
            case S_DETAIL:
            case S_CODE:
            case S_SYNC:
                screen = S_MENU;
                return true;
            case S_PLAY:
                stopPlayer();
                screen = S_DETAIL;
                return true;
            case S_RESULT:
                screen = S_DETAIL;
                return true;
            case S_LOADING:
                return true;
            default:
                return false;
        }
    }

    // =================== 입력 ===================

    private long lastSpinInput;

    void onRotary(float delta, long eventTime) {
        long now = SystemClock.uptimeMillis();
        if (screen == S_PLAY) {
            // 플레이 중에는 베젤 한 칸(딸깍)만 돌려도 바로 입력으로 인정
            if (delta == 0 || eventTime - lastSpinInput < 45) return;
            lastSpinInput = eventTime;
            inputSpin(delta > 0 ? 1 : -1, eventTime);
            return;
        }
        if (now - lastRot > 300) rotAcc = 0;
        lastRot = now;
        rotAcc += delta;
        if (Math.abs(rotAcc) < 0.9f) return;
        int dir = rotAcc > 0 ? 1 : -1;
        rotAcc = 0;
        switch (screen) {
            case S_MENU:
                menuSel = Math.max(0, Math.min(items.size() - 1, menuSel + dir));
                tick();
                break;
            case S_CODE:
                codeIdx[codeSlot] = Math.floorMod(codeIdx[codeSlot] + dir, 32);
                codeMsg = "";
                break;
            case S_SYNC:
                syncMs = Math.max(-300, Math.min(300, syncMs + dir * 5));
                break;
            case S_PLAY:
                inputSpin(dir, eventTime);
                break;
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                swiped = false;
                scrolled = false;
                touching = true;
                if (screen == S_PLAY) inputTap(e.getEventTime());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (screen == S_PLAY) inputTap(e.getEventTime());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (screen == S_PLAY || swiped) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!scrolled && Math.abs(dx) > W * 0.18f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiped = true;
                    if (dx > 0) {
                        back();
                    } else if (screen == S_CODE) {
                        codeSlot = Math.max(0, codeSlot - 1);
                    }
                } else if (screen == S_MENU && Math.abs(dy) > H * 0.12f && Math.abs(dy) > Math.abs(dx)) {
                    scrolled = true;
                    menuSel = Math.max(0, Math.min(items.size() - 1, menuSel + (dy < 0 ? 1 : -1)));
                    downY = e.getY();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                return true;
            case MotionEvent.ACTION_UP: {
                touching = false;
                if (screen == S_PLAY || swiped || scrolled) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) < W * 0.08f && Math.abs(dy) < W * 0.08f) {
                    boolean longPress = e.getEventTime() - downTime > 600;
                    onTap(e.getX(), e.getY(), longPress);
                }
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void onTap(float x, float y, boolean longPress) {
        switch (screen) {
            case S_MENU: {
                float sp = H * 0.24f;
                int hitIdx = Math.round(menuPos + (y - cy) / sp);
                if (hitIdx != menuSel) {
                    menuSel = Math.max(0, Math.min(items.size() - 1, hitIdx));
                    return;
                }
                Item it = items.get(menuSel);
                if (it.action == 0) {
                    openMap(it.map, it.title);
                } else if (it.action == 1) {
                    openMap(MapCode.random(new Random(), rnd.nextInt(3)), "랜덤 맵");
                } else if (it.action == 2) {
                    if (cur != null) setCode(cur.code());
                    codeSlot = 0;
                    codeMsg = "";
                    screen = S_CODE;
                } else {
                    screen = S_SYNC;
                }
                break;
            }
            case S_DETAIL:
                startLoading();
                break;
            case S_CODE:
                if (codeSlot < 8) {
                    codeSlot++;
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i : codeIdx) sb.append(MapCode.ALPHA.charAt(i));
                    MapCode m = MapCode.parse(sb.toString());
                    if (m == null) {
                        codeMsg = "코드가 올바르지 않아요";
                        codeShake = 1f;
                        buzz(80);
                    } else {
                        openMap(m, "코드 맵");
                    }
                }
                break;
            case S_SYNC:
                prefs.edit().putInt("sync", syncMs).apply();
                buildMenu();
                screen = S_MENU;
                break;
            case S_RESULT:
                if (resultT < 0.8f) return;
                if (longPress && offN > 3) {
                    int avg = (int) Math.round(sumOff / offN * 1000);
                    syncMs = Math.max(-300, Math.min(300, syncMs + avg));
                    prefs.edit().putInt("sync", syncMs).apply();
                    buildMenu();
                    buzz(40);
                }
                startPlay();
                break;
        }
    }

    private void setCode(String code) {
        for (int i = 0; i < 9; i++) codeIdx[i] = Math.max(0, MapCode.ALPHA.indexOf(code.charAt(i)));
    }

    private void openMap(MapCode m, String title) {
        if (cur == null || !cur.code().equals(m.code())) song = null;
        cur = m;
        curTitle = title;
        screen = S_DETAIL;
    }

    // =================== 로딩 / 플레이 ===================

    private void startLoading() {
        if (song != null && song.map.code().equals(cur.code())) {
            startPlay();
            return;
        }
        screen = S_LOADING;
        loading = true;
        final MapCode m = cur;
        new Thread(() -> {
            Song s = Song.generate(m);
            main.post(() -> {
                loading = false;
                if (screen != S_LOADING || cur != m) return;
                song = s;
                startPlay();
            });
        }, "song-gen").start();
    }

    private void startPlay() {
        stopPlayer();
        for (Note n : song.notes) {
            n.result = -1;
            n.tailResult = -1;
        }
        activeHold = null;
        nextIdx = 0;
        combo = maxCombo = score = 0;
        life = 100;
        for (int i = 0; i < 4; i++) counts[i] = 0;
        sumOff = 0;
        offN = 0;
        failed = false;
        fx.clear();
        judgeT = 0;
        player = new AudioPlayer(song.pcm);
        player.start();
        screen = S_PLAY;
    }

    private void stopPlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private double songTime() {
        return player == null ? 0 : player.time() - syncMs / 1000.0;
    }

    /** 이벤트 시각(uptime ms)을 곡 시간으로 변환. */
    private double songTimeAt(long eventTime) {
        return songTime() - (SystemClock.uptimeMillis() - eventTime) / 1000.0;
    }

    private void inputTap(long eventTime) {
        if (player == null) return;
        double t = songTimeAt(eventTime);
        Note best = findNote(t, Note.TAP, 0);
        if (best != null) {
            if (best.type == Note.HOLD) {
                activeHold = best;
                holdByBezel = false;
            }
            judge(best, t - best.time, false);
        }
    }

    private void inputSpin(int dir, long eventTime) {
        if (player == null) return;
        double t = songTimeAt(eventTime);
        if (activeHold != null && holdByBezel) {
            lastBezelT = t; // 베젤로 줄 유지 중
            return;
        }
        Note best = findNote(t, Note.SPIN, dir);
        if (best != null) {
            judge(best, t - best.time, best.dir != 0 && best.dir != dir);
            return;
        }
        // 베젤로도 줄 노트를 시작할 수 있음
        best = findNote(t, Note.HOLD, 0);
        if (best != null) {
            activeHold = best;
            holdByBezel = true;
            lastBezelT = t;
            judge(best, t - best.time, false);
        }
    }

    private static double win(int type) {
        return type == Note.TAP ? W_GOOD : W_GOOD * SPIN_MUL;
    }

    private Note findNote(double t, int type, int dir) {
        List<Note> ns = song.notes;
        Note best = null;
        double w = win(type);
        double bd = w;
        for (int i = nextIdx; i < ns.size(); i++) {
            Note n = ns.get(i);
            if (n.time > t + w) break;
            if (n.result >= 0) continue;
            boolean ok = n.type == type || (type == Note.TAP && n.type == Note.HOLD);
            if (!ok) continue;
            double d = Math.abs(n.time - t);
            if (d <= bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    private void judge(Note n, double off, boolean wrongDir) {
        double a = Math.abs(off) / (n.type == Note.SPIN || (n.type == Note.HOLD && holdByBezel) ? SPIN_MUL : 1);
        int res = a <= W_PERFECT ? R_PERFECT : a <= W_GREAT ? R_GREAT : R_GOOD;
        if (wrongDir) res = R_GOOD; // 방향이 틀려도 실패는 아님 (GOOD)
        n.result = res;
        counts[res]++;
        combo++;
        maxCombo = Math.max(maxCombo, combo);
        int base = res == R_PERFECT ? 300 : res == R_GREAT ? 200 : 100;
        score += (int) (base * (1 + Math.min(combo, 100) / 100f));
        life = Math.min(100, life + (res == R_PERFECT ? 2 : res == R_GREAT ? 1 : 0));
        sumOff += off;
        offN++;
        String[] names = {"PERFECT", "GREAT", "GOOD"};
        int[] cols = {Color.rgb(120, 240, 255), Color.rgb(140, 255, 140), Color.rgb(255, 220, 90)};
        judgeText = names[res];
        judgeColor = cols[res];
        judgeT = 0.6f;
        if (n.type != Note.SPIN) {
            double ang = Math.toRadians(n.angle);
            burst((float) (cx + Math.cos(ang) * R), (float) (cy + Math.sin(ang) * R), cols[res]);
        } else {
            ringFlash = 1f;
            Fx f = new Fx();
            f.ring = true;
            f.life = f.max = 0.4f;
            f.color = spinColor(n.dir);
            fx.add(f);
        }
    }

    private void miss(Note n) {
        n.result = R_MISS;
        counts[R_MISS]++;
        if (n.type == Note.HOLD && n.tailResult < 0) {
            n.tailResult = R_MISS;
            counts[R_MISS]++;
        }
        combo = 0;
        life -= cur.diff == 3 ? 14 : 10;
        judgeText = "MISS";
        judgeColor = Color.rgb(255, 70, 90);
        judgeT = 0.6f;
        if (life <= 0) {
            life = 0;
            failed = true;
            buzz(200);
            finishPlay();
        }
    }

    private void finishPlay() {
        stopPlayer();
        // 남은 노트는 MISS 처리
        for (Note n : song.notes) {
            if (n.result < 0) {
                n.result = R_MISS;
                counts[R_MISS]++;
            }
            if (n.type == Note.HOLD && n.tailResult < 0) {
                n.tailResult = R_MISS;
                counts[R_MISS]++;
            }
        }
        activeHold = null;
        float acc = accuracy();
        String key = "best_" + cur.code();
        float prev = prefs.getFloat(key, -1);
        newBest = !failed && acc > prev;
        if (newBest) prefs.edit().putFloat(key, acc).apply();
        resultT = 0;
        screen = S_RESULT;
        buildMenu();
    }

    private float accuracy() {
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        if (total == 0) return 0;
        return (counts[0] + counts[1] * 0.7f + counts[2] * 0.4f) / total * 100f;
    }

    private static String rank(float acc, boolean failed) {
        if (failed) return "F";
        if (acc >= 95) return "S";
        if (acc >= 90) return "A";
        if (acc >= 80) return "B";
        if (acc >= 70) return "C";
        return "D";
    }

    private static int rankColor(String rk) {
        switch (rk) {
            case "S": return Color.rgb(255, 215, 60);
            case "A": return Color.rgb(120, 255, 160);
            case "B": return Color.rgb(100, 200, 255);
            case "C": return Color.rgb(200, 160, 255);
            case "D": return Color.rgb(200, 200, 200);
            default: return Color.rgb(255, 80, 90);
        }
    }

    private void burst(float x, float y, int color) {
        for (int i = 0; i < 8; i++) {
            Fx f = new Fx();
            double a = rnd.nextDouble() * Math.PI * 2;
            float d = W * (0.02f + rnd.nextFloat() * 0.05f);
            f.x = x + (float) Math.cos(a) * d;
            f.y = y + (float) Math.sin(a) * d;
            f.life = f.max = 0.3f + rnd.nextFloat() * 0.2f;
            f.color = color;
            fx.add(f);
        }
    }

    private void tick() {
        buzz(6);
    }

    private void buzz(int ms) {
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private static int spinColor(int dir) {
        return dir > 0 ? C_CW : dir < 0 ? C_CCW : C_ANY;
    }

    // =================== 그리기 ===================

    private long lastFrame;

    @Override
    protected void onDraw(Canvas c) {
        long nowN = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (nowN - lastFrame) / 1e9f);
        lastFrame = nowN;

        menuPos += (menuSel - menuPos) * Math.min(1f, dt * 12f);
        codeShake = Math.max(0, codeShake - dt * 3);
        judgeT = Math.max(0, judgeT - dt);
        ringFlash = Math.max(0, ringFlash - dt * 4);
        resultT += dt;
        for (Iterator<Fx> it = fx.iterator(); it.hasNext(); ) {
            Fx f = it.next();
            f.life -= dt;
            if (f.life <= 0) it.remove();
        }
        if (updater != null && screen == S_MENU) {
            updater.checkIfDue();
            if (updater.isReady()) updater.installIfReady();
        }

        c.drawColor(Color.rgb(8, 6, 16));
        switch (screen) {
            case S_MENU: drawMenu(c); break;
            case S_DETAIL: drawDetail(c); break;
            case S_CODE: drawCode(c); break;
            case S_LOADING: drawLoading(c); break;
            case S_PLAY: updatePlay(); if (screen == S_PLAY) drawPlay(c); else invalidate(); break;
            case S_RESULT: drawResult(c); break;
            case S_SYNC: drawSync(c); break;
        }
        if (running) postInvalidateOnAnimation();
    }

    private void glow(Canvas c, float x, float y, float rad, int color) {
        mtx.setScale(rad, rad);
        mtx.postTranslate(x, y);
        glowShader.setLocalMatrix(mtx);
        p.setShader(glowShader);
        p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        c.drawCircle(x, y, rad, p);
        p.setShader(null);
        p.setColorFilter(null);
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private void drawBackdrop(Canvas c, int color) {
        float t = SystemClock.uptimeMillis() / 1000f;
        glow(c, cx, cy, W * (0.55f + 0.03f * (float) Math.sin(t * 2)), alpha(color, 60));
        stroke.setStrokeWidth(W * 0.006f);
        stroke.setColor(alpha(color, 70));
        c.drawCircle(cx, cy, W * 0.47f, stroke);
    }

    private void drawMenu(Canvas c) {
        Item sel = items.get(menuSel);
        int col = sel.map != null ? STYLE_COLOR[sel.map.style] : Color.rgb(0, 229, 255);
        drawBackdrop(c, col);
        float sp = H * 0.24f;
        for (int i = 0; i < items.size(); i++) {
            float y = cy + (i - menuPos) * sp;
            if (y < -sp || y > H + sp) continue;
            float d = Math.abs(i - menuPos);
            float k = Math.max(0, 1 - d * 0.45f);
            Item it = items.get(i);
            int ic = it.map != null ? STYLE_COLOR[it.map.style] : Color.rgb(200, 200, 220);
            if (i == menuSel) {
                p.setColor(alpha(ic, 40));
                r.set(cx - W * 0.4f, y - sp * 0.42f, cx + W * 0.4f, y + sp * 0.42f);
                c.drawRoundRect(r, sp * 0.42f, sp * 0.42f, p);
                stroke.setColor(alpha(ic, 220));
                stroke.setStrokeWidth(W * 0.008f);
                c.drawRoundRect(r, sp * 0.42f, sp * 0.42f, stroke);
            }
            text.setTextSize(W * (0.058f + 0.022f * k));
            text.setColor(alpha(Color.WHITE, (int) (90 + 165 * k)));
            c.drawText(it.title, cx, y - W * 0.005f, text);
            text.setTextSize(W * 0.04f);
            text.setColor(alpha(ic, (int) (70 + 160 * k)));
            String sub = it.sub;
            if (it.map != null) {
                float b = prefs.getFloat("best_" + it.map.code(), -1);
                if (b >= 0) sub = sub + "  " + rank(b, false);
            }
            c.drawText(sub, cx, y + W * 0.05f, text);
        }
        // 상단 타이틀 + 하단 상태
        p.setColor(Color.rgb(8, 6, 16));
        c.drawRect(0, 0, W, H * 0.13f, p);
        text.setTextSize(W * 0.05f);
        text.setColor(Color.rgb(0, 229, 255));
        c.drawText("BEAT WATCH", cx, H * 0.1f, text);
        String st = updater != null ? updater.status() : "";
        if (!st.isEmpty()) {
            p.setColor(Color.rgb(8, 6, 16));
            c.drawRect(0, H * 0.88f, W, H, p);
            text.setTextSize(W * 0.04f);
            text.setColor(Color.rgb(120, 220, 255));
            c.drawText(st, cx, H * 0.94f, text);
        }
    }

    private void drawDetail(Canvas c) {
        int col = STYLE_COLOR[cur.style];
        drawBackdrop(c, col);
        text.setColor(Color.WHITE);
        text.setTextSize(W * 0.09f);
        c.drawText(curTitle, cx, H * 0.24f, text);
        text.setTextSize(W * 0.05f);
        text.setColor(col);
        c.drawText(cur.summary(), cx, H * 0.33f, text);
        text.setColor(Color.LTGRAY);
        c.drawText(cur.keyName() + " · " + cur.lengthText(), cx, H * 0.40f, text);
        mono.setTextSize(W * 0.085f);
        mono.setColor(Color.WHITE);
        c.drawText(cur.pretty(), cx, H * 0.54f, mono);
        text.setTextSize(W * 0.038f);
        text.setColor(Color.GRAY);
        c.drawText("맵 코드 (친구와 공유)", cx, H * 0.6f, text);
        float b = prefs.getFloat("best_" + cur.code(), -1);
        if (b >= 0) {
            text.setTextSize(W * 0.048f);
            text.setColor(rankColor(rank(b, false)));
            c.drawText("최고 " + rank(b, false) + "  " + String.format("%.1f%%", b), cx, H * 0.68f, text);
        }
        boolean on = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        text.setTextSize(W * 0.065f);
        text.setColor(on ? Color.rgb(255, 230, 120) : Color.rgb(200, 170, 80));
        c.drawText("탭해서 시작", cx, H * 0.79f, text);
        text.setTextSize(W * 0.036f);
        text.setColor(Color.GRAY);
        c.drawText("오른쪽 스와이프: 뒤로", cx, H * 0.87f, text);
    }

    private void drawCode(Canvas c) {
        drawBackdrop(c, Color.rgb(0, 229, 255));
        text.setColor(Color.WHITE);
        text.setTextSize(W * 0.065f);
        c.drawText("코드 입력", cx, H * 0.2f, text);
        float bw = W * 0.12f, bh = W * 0.15f, gap = W * 0.02f;
        float shake = codeShake > 0 ? (float) Math.sin(codeShake * 40) * W * 0.02f * codeShake : 0;
        for (int i = 0; i < 9; i++) {
            int row = i < 4 ? 0 : i < 8 ? 1 : 2;
            int colI = i < 4 ? i : i < 8 ? i - 4 : 0;
            int nInRow = row == 2 ? 1 : 4;
            float rowW = nInRow * bw + (nInRow - 1) * gap;
            float x0 = cx - rowW / 2 + colI * (bw + gap) + shake;
            float y0 = H * 0.27f + row * (bh + gap);
            r.set(x0, y0, x0 + bw, y0 + bh);
            boolean selected = i == codeSlot;
            p.setColor(selected ? Color.argb(70, 0, 229, 255) : Color.argb(40, 255, 255, 255));
            c.drawRoundRect(r, bw * 0.2f, bw * 0.2f, p);
            if (selected) {
                stroke.setColor(Color.rgb(0, 229, 255));
                stroke.setStrokeWidth(W * 0.008f);
                c.drawRoundRect(r, bw * 0.2f, bw * 0.2f, stroke);
            }
            mono.setTextSize(bh * 0.62f);
            mono.setColor(i == 8 ? Color.rgb(255, 200, 120) : Color.WHITE);
            c.drawText(String.valueOf(MapCode.ALPHA.charAt(codeIdx[i])), x0 + bw / 2, y0 + bh * 0.72f, mono);
        }
        text.setTextSize(W * 0.04f);
        if (!codeMsg.isEmpty()) {
            text.setColor(Color.rgb(255, 90, 100));
            c.drawText(codeMsg, cx, H * 0.84f, text);
        } else {
            text.setColor(Color.LTGRAY);
            c.drawText("베젤: 글자 · 탭: 다음", cx, H * 0.84f, text);
            c.drawText(codeSlot == 8 ? "마지막 칸에서 탭하면 확인" : "← 스와이프: 이전 칸", cx, H * 0.9f, text);
        }
    }

    private void drawLoading(Canvas c) {
        int col = STYLE_COLOR[cur.style];
        drawBackdrop(c, col);
        float t = SystemClock.uptimeMillis() / 1000f;
        stroke.setColor(col);
        stroke.setStrokeWidth(W * 0.02f);
        r.set(cx - W * 0.15f, cy - W * 0.15f, cx + W * 0.15f, cy + W * 0.15f);
        c.drawArc(r, (t * 360) % 360, 100, false, stroke);
        c.drawArc(r, (t * 360 + 180) % 360, 100, false, stroke);
        text.setTextSize(W * 0.055f);
        text.setColor(Color.WHITE);
        c.drawText("음악 만드는 중…", cx, H * 0.75f, text);
    }

    private void drawSync(Canvas c) {
        drawBackdrop(c, Color.rgb(255, 200, 60));
        text.setColor(Color.WHITE);
        text.setTextSize(W * 0.065f);
        c.drawText("싱크 조절", cx, H * 0.25f, text);
        text.setTextSize(W * 0.16f);
        text.setColor(Color.rgb(255, 220, 90));
        c.drawText(fmtMs(syncMs), cx, H * 0.53f, text);
        text.setTextSize(W * 0.042f);
        text.setColor(Color.LTGRAY);
        c.drawText("소리가 늦게 들리면 + 로", cx, H * 0.65f, text);
        c.drawText("베젤: 조절 · 탭: 저장", cx, H * 0.72f, text);
        text.setColor(Color.GRAY);
        c.drawText("결과 화면을 길게 누르면 자동 보정", cx, H * 0.8f, text);
    }

    // ---------- 플레이 ----------

    private void updatePlay() {
        if (player == null) return;
        playT = songTime();
        // 줄 노트 유지 판정
        if (activeHold != null) {
            Note h = activeHold;
            if (playT >= h.end - 0.06) {
                h.tailResult = R_PERFECT;
                counts[R_PERFECT]++;
                combo++;
                maxCombo = Math.max(maxCombo, combo);
                score += (int) (300 * (1 + Math.min(combo, 100) / 100f));
                judgeText = "PERFECT";
                judgeColor = Color.rgb(120, 240, 255);
                judgeT = 0.6f;
                double ang = Math.toRadians(h.angle);
                burst((float) (cx + Math.cos(ang) * R), (float) (cy + Math.sin(ang) * R), Color.WHITE);
                activeHold = null;
            } else if (holdByBezel ? playT - lastBezelT > 0.4 : !touching) {
                activeHold = null;
                h.tailResult = R_MISS;
                counts[R_MISS]++;
                combo = 0;
                life -= 6;
                judgeText = "끊김";
                judgeColor = Color.rgb(255, 70, 90);
                judgeT = 0.6f;
                if (life <= 0) {
                    life = 0;
                    failed = true;
                    buzz(200);
                    finishPlay();
                    return;
                }
            } else if (((int) (playT * 12)) != ((int) ((playT - 0.016) * 12))) {
                double ang = Math.toRadians(h.angle);
                burst((float) (cx + Math.cos(ang) * R), (float) (cy + Math.sin(ang) * R), STYLE_COLOR[cur.style]);
            }
        }
        List<Note> ns = song.notes;
        while (nextIdx < ns.size()) {
            Note n = ns.get(nextIdx);
            if (n.result >= 0) {
                nextIdx++;
                continue;
            }
            if (playT > n.time + win(n.type)) {
                miss(n);
                if (screen != S_PLAY) return;
                nextIdx++;
                continue;
            }
            break;
        }
        if (playT > song.lastNoteTime() + 1.8 || playT > player.length()) finishPlay();
    }

    private void drawPlay(Canvas c) {
        double t = playT;
        int col = STYLE_COLOR[cur.style];
        float energy = song.energyAt(t);
        double beats = (t - Song.LEAD_IN) / song.beatSec;
        float frac = beats >= 0 ? (float) (beats - Math.floor(beats)) : 0.5f;
        float pulse = (float) Math.exp(-frac * 5) * energy;

        // 배경 펄스
        glow(c, cx, cy, W * (0.35f + 0.2f * pulse), alpha(col, (int) (40 + 110 * pulse)));
        // 비주얼라이저 링
        int bars = 48;
        float r0 = W * 0.12f;
        stroke.setStrokeWidth(W * 0.012f);
        long bi = (long) Math.floor(beats * 2);
        for (int i = 0; i < bars; i++) {
            float h = W * 0.015f + W * 0.06f * pulse * hash(i, bi);
            double a = Math.toRadians(i * 360.0 / bars);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            stroke.setColor(alpha(col, 90 + (int) (120 * pulse)));
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + h), cy + sa * (r0 + h), stroke);
        }
        // 판정 링
        stroke.setStrokeWidth(W * (0.012f + 0.01f * ringFlash));
        stroke.setColor(alpha(Color.WHITE, 150 + (int) (100 * Math.max(ringFlash, pulse * 0.5f))));
        c.drawCircle(cx, cy, R, stroke);
        if (ringFlash > 0) glow(c, cx, cy, R * 1.12f, alpha(Color.WHITE, (int) (60 * ringFlash)));

        // 노트
        double ap = APPROACH[cur.diff];
        List<Note> ns = song.notes;
        for (int i = Math.max(0, nextIdx - 4); i < ns.size(); i++) {
            Note n = ns.get(i);
            if (n.time > t + ap) break;
            if (n.type == Note.HOLD) {
                boolean active = n == activeHold;
                if (n.result >= 0 && !active) continue;
                drawHold(c, n, t, ap, col, active);
                continue;
            }
            if (n.result >= 0) continue;
            float prog = (float) (1 - (n.time - t) / ap);
            if (prog < 0) continue;
            float rr = R * prog;
            if (n.type == Note.TAP) {
                double a = Math.toRadians(n.angle);
                float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                float size = W * 0.045f * (0.45f + 0.55f * Math.min(1f, prog));
                glow(c, x, y, size * 2.2f, alpha(col, 120));
                p.setColor(col);
                c.drawCircle(x, y, size, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x, y, size * 0.55f, p);
            } else {
                int sc = spinColor(n.dir);
                stroke.setColor(alpha(sc, (int) (120 + 135 * Math.min(1f, prog))));
                stroke.setStrokeWidth(W * 0.022f);
                c.drawCircle(cx, cy, rr, stroke);
                drawArrows(c, rr, n.dir, sc, t);
            }
        }

        // 이펙트
        for (Fx f : fx) {
            float k = f.life / f.max;
            if (f.ring) {
                stroke.setColor(alpha(f.color, (int) (255 * k)));
                stroke.setStrokeWidth(W * 0.03f * k);
                c.drawCircle(cx, cy, R * (1 + (1 - k) * 0.15f), stroke);
            } else {
                p.setColor(alpha(f.color, (int) (255 * k)));
                c.drawCircle(f.x, f.y, W * 0.012f * (0.5f + k), p);
            }
        }

        // HUD: 판정 / 콤보 / 점수
        if (judgeT > 0) {
            float k = Math.min(1f, judgeT / 0.6f);
            text.setTextSize(W * (0.075f + 0.02f * k));
            text.setColor(alpha(judgeColor, (int) (255 * Math.min(1f, judgeT * 3))));
            c.drawText(judgeText, cx, cy - W * 0.02f, text);
        }
        if (combo >= 3) {
            text.setTextSize(W * 0.06f);
            text.setColor(Color.WHITE);
            c.drawText(combo + " COMBO", cx, cy + W * 0.08f, text);
        }
        text.setTextSize(W * 0.045f);
        text.setColor(Color.LTGRAY);
        c.drawText(String.valueOf(score), cx, H * 0.11f, text);

        // 생명(아래 호) / 진행(위 호)
        float ro = W * 0.485f;
        r.set(cx - ro, cy - ro, cx + ro, cy + ro);
        stroke.setStrokeWidth(W * 0.014f);
        stroke.setColor(Color.argb(60, 255, 255, 255));
        c.drawArc(r, 50, 80, false, stroke);
        c.drawArc(r, 230, 80, false, stroke);
        int lc = life > 50 ? Color.rgb(100, 255, 140) : life > 25 ? Color.rgb(255, 210, 80) : Color.rgb(255, 70, 90);
        stroke.setColor(lc);
        c.drawArc(r, 130, -80 * life / 100f, false, stroke);
        float prog = (float) Math.max(0, Math.min(1, (t - Song.LEAD_IN) / (song.lastNoteTime() - Song.LEAD_IN)));
        stroke.setColor(col);
        c.drawArc(r, 230, 80 * prog, false, stroke);

        if (t < Song.LEAD_IN - 0.2) {
            text.setTextSize(W * 0.045f);
            text.setColor(Color.LTGRAY);
            c.drawText("점: 탭 · 링: 베젤", cx, cy + W * 0.17f, text);
            c.drawText("줄: 누르고 있기 / 베젤 계속 돌리기", cx, cy + W * 0.23f, text);
        }
    }

    /** 줄(롱노트): 머리부터 꼬리까지 이어진 굵은 선. 누르고 있으면 머리가 링에 고정. */
    private void drawHold(Canvas c, Note n, double t, double ap, int col, boolean active) {
        float pHead = active ? 1f : (float) (1 - (n.time - t) / ap);
        float pTail = (float) (1 - (n.end - t) / ap);
        if (pHead < 0) return;
        pHead = Math.min(pHead, 1.05f);
        pTail = Math.max(0f, Math.min(pTail, pHead));
        double a = Math.toRadians(n.angle);
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float hx = cx + ca * R * pHead, hy = cy + sa * R * pHead;
        float tx = cx + ca * R * pTail, ty = cy + sa * R * pTail;
        float width = W * 0.07f * (0.5f + 0.5f * Math.min(1f, pHead));
        stroke.setStrokeWidth(width * 1.5f);
        stroke.setColor(alpha(col, active ? 110 : 60));
        c.drawLine(hx, hy, tx, ty, stroke);
        stroke.setStrokeWidth(width);
        stroke.setColor(alpha(col, active ? 255 : 190));
        c.drawLine(hx, hy, tx, ty, stroke);
        stroke.setStrokeWidth(width * 0.35f);
        stroke.setColor(alpha(Color.WHITE, active ? 230 : 150));
        c.drawLine(hx, hy, tx, ty, stroke);
        float size = W * 0.05f * (0.5f + 0.5f * Math.min(1f, pHead));
        glow(c, hx, hy, size * (active ? 3f : 2.2f), alpha(col, active ? 200 : 120));
        p.setColor(Color.WHITE);
        c.drawCircle(hx, hy, size * 0.75f, p);
        p.setColor(col);
        c.drawCircle(tx, ty, size * 0.45f, p);
        if (active && holdByBezel) {
            text.setTextSize(W * 0.04f);
            text.setColor(Color.WHITE);
            c.drawText("계속 돌리기!", cx, cy + W * 0.17f, text);
        }
    }

    private void drawArrows(Canvas c, float rr, int dir, int color, double t) {
        if (rr < W * 0.05f) return;
        p.setColor(color);
        float rot = (float) (t * 120 * (dir == 0 ? 1 : dir));
        int n = 4;
        for (int k = 0; k < n; k++) {
            double a = Math.toRadians(k * 360.0 / n + rot);
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            // 접선 방향
            float tx = (float) -Math.sin(a), ty = (float) Math.cos(a);
            int d = dir == 0 ? 1 : dir;
            float s = W * 0.03f;
            path.reset();
            path.moveTo(x + tx * s * d, y + ty * s * d);
            path.lineTo(x - tx * s * 0.5f * d + (float) Math.cos(a) * s * 0.7f, y - ty * s * 0.5f * d + (float) Math.sin(a) * s * 0.7f);
            path.lineTo(x - tx * s * 0.5f * d - (float) Math.cos(a) * s * 0.7f, y - ty * s * 0.5f * d - (float) Math.sin(a) * s * 0.7f);
            path.close();
            c.drawPath(path, p);
            if (dir == 0) {
                path.reset();
                path.moveTo(x - tx * s, y - ty * s);
                path.lineTo(x + tx * s * 0.5f + (float) Math.cos(a) * s * 0.7f, y + ty * s * 0.5f + (float) Math.sin(a) * s * 0.7f);
                path.lineTo(x + tx * s * 0.5f - (float) Math.cos(a) * s * 0.7f, y + ty * s * 0.5f - (float) Math.sin(a) * s * 0.7f);
                path.close();
                c.drawPath(path, p);
            }
        }
    }

    private static float hash(int i, long b) {
        long h = i * 374761393L + b * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h >>> 8) & 0xFFFF) / 65535f;
    }

    // ---------- 결과 ----------

    private void drawResult(Canvas c) {
        float acc = accuracy();
        String rk = rank(acc, failed);
        int rc = rankColor(rk);
        drawBackdrop(c, rc);
        text.setTextSize(W * 0.06f);
        text.setColor(failed ? Color.rgb(255, 80, 90) : Color.rgb(120, 255, 160));
        c.drawText(failed ? "FAILED" : (newBest ? "CLEAR! 신기록" : "CLEAR!"), cx, H * 0.17f, text);
        float k = Math.min(1f, resultT / 0.4f);
        text.setTextSize(W * 0.24f * (0.6f + 0.4f * k));
        text.setColor(rc);
        c.drawText(rk, cx - W * 0.17f, H * 0.42f, text);
        text.setTextSize(W * 0.075f);
        text.setColor(Color.WHITE);
        c.drawText(String.format("%.1f%%", acc), cx + W * 0.16f, H * 0.33f, text);
        text.setTextSize(W * 0.045f);
        text.setColor(Color.LTGRAY);
        c.drawText(score + "점", cx + W * 0.16f, H * 0.4f, text);

        text.setTextSize(W * 0.042f);
        float y = H * 0.53f;
        drawCount(c, "PERFECT", counts[0], Color.rgb(120, 240, 255), cx - W * 0.2f, y);
        drawCount(c, "GREAT", counts[1], Color.rgb(140, 255, 140), cx + W * 0.2f, y);
        drawCount(c, "GOOD", counts[2], Color.rgb(255, 220, 90), cx - W * 0.2f, y + H * 0.09f);
        drawCount(c, "MISS", counts[3], Color.rgb(255, 70, 90), cx + W * 0.2f, y + H * 0.09f);

        text.setTextSize(W * 0.042f);
        text.setColor(Color.WHITE);
        c.drawText("최대 콤보 " + maxCombo, cx, H * 0.74f, text);
        if (offN > 0) {
            int avg = (int) Math.round(sumOff / offN * 1000);
            String tend = Math.abs(avg) <= 8 ? "타이밍 정확!" : (avg < 0 ? "평균 " + (-avg) + "ms 빠름" : "평균 " + avg + "ms 늦음");
            text.setColor(Color.rgb(255, 210, 120));
            c.drawText(tend, cx, H * 0.8f, text);
        }
        text.setTextSize(W * 0.036f);
        text.setColor(Color.GRAY);
        c.drawText("탭: 다시 · 길게: 싱크 보정 후 다시", cx, H * 0.87f, text);
        c.drawText("오른쪽 스와이프: 뒤로", cx, H * 0.92f, text);
    }

    private void drawCount(Canvas c, String label, int n, int color, float x, float y) {
        text.setTextSize(W * 0.035f);
        text.setColor(color);
        c.drawText(label, x, y, text);
        text.setTextSize(W * 0.05f);
        text.setColor(Color.WHITE);
        c.drawText(String.valueOf(n), x, y + W * 0.05f, text);
    }
}
