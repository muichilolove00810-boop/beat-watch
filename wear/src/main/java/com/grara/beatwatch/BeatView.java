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

    private static final int S_MENU = 0, S_DETAIL = 1, S_CODE = 2, S_LOADING = 3, S_PLAY = 4, S_RESULT = 5, S_SYNC = 6,
            S_REC = 7, S_EDIT = 8;
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

    private interface Adj {
        String value();

        void adjust(int d);
    }

    private static final class Item {
        final String title;
        String sub;
        final MapCode map;
        final int action; // 0 맵, 1 랜덤, 2 코드, 3 싱크, 9 실행, 10 값 조절
        Runnable act;
        Adj adj;

        Item(String title, String sub, MapCode map, int action) {
            this.title = title;
            this.sub = sub;
            this.map = map;
            this.action = action;
        }

        Item(String title, String sub, Runnable act) {
            this(title, sub, null, 9);
            this.act = act;
        }

        Item(String title, Adj adj) {
            this(title, null, null, 10);
            this.adj = adj;
        }
    }

    private static final class MenuState {
        final ArrayList<Item> items;
        final int sel;
        final String title;

        MenuState(ArrayList<Item> items, int sel, String title) {
            this.items = items;
            this.sel = sel;
            this.title = title;
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
    private ArrayList<Item> items = new ArrayList<>();
    private int menuSel;
    private float menuPos;
    private String menuTitle = "BEAT WATCH";
    private boolean menuEditing;
    private final ArrayList<MenuState> menuStack = new ArrayList<>();
    private String toast = "";
    private float toastT;

    // 내 맵
    private List<CustomMap> customs;
    private CustomMap curCustom;
    private String songKey = "";

    // 녹음
    private double recDownT = -1, lastRecSpinT = -9;
    private int recCount;
    private String recLast = "";
    private float recFlash;

    // 노트 편집
    private double edT = Song.LEAD_IN;
    private AudioPlayer edPlayer;
    private String edInfo = "";

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
    private boolean hasBombs;

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
        if (!menuStack.isEmpty()) return; // 하위 메뉴에 있을 때는 유지
        items = new ArrayList<>();
        menuTitle = "BEAT WATCH";
        addPreset("네온 러시", new MapCode(0, 124, 0, 1, 9, true, 11));
        addPreset("선셋 드라이브", new MapCode(2, 100, 0, 1, 6, true, 202));
        addPreset("픽셀 하트", new MapCode(5, 150, 1, 1, 0, false, 55));
        addPreset("사이버 펄스", new MapCode(1, 132, 1, 2, 2, true, 3303));
        addPreset("스타게이트", new MapCode(3, 138, 1, 2, 7, true, 404));
        addPreset("썬더 브레이크", new MapCode(4, 172, 2, 2, 4, true, 6006));
        addPreset("글리치 스톰", new MapCode(7, 150, 2, 2, 11, false, 707));
        addPreset("지옥의 문", new MapCode(6, 140, 3, 3, 5, true, 8080));
        addPreset("레이저 하우스", new MapCode(0, 126, 1, 1, 4, false, 1212));
        addPreset("밤의 고속도로", new MapCode(2, 108, 1, 2, 9, true, 2424));
        addPreset("얼음 궁전", new MapCode(3, 140, 2, 2, 1, true, 3636));
        addPreset("8비트 모험", new MapCode(5, 160, 2, 1, 7, false, 4848));
        addPreset("폭풍 베이스", new MapCode(6, 144, 2, 2, 10, true, 5050));
        items.add(new Item("내 맵", "직접 만들기 · 녹음 · 편집", this::openMyMaps));
        items.add(new Item("저장한 맵", "불러온 맵 모음 · " + savedMaps().size() + "개", this::openSaved));
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
            screen = curCustom != null ? S_MENU : S_DETAIL;
        }
        if (screen == S_REC) finishRec();
        if (screen == S_EDIT) {
            stopEdPlayer();
            saveCustoms();
        }
    }

    /** 뒤로 가기. 처리했으면 true. */
    boolean back() {
        switch (screen) {
            case S_MENU:
                if (menuEditing) {
                    menuEditing = false;
                    return true;
                }
                if (!menuStack.isEmpty()) {
                    popMenu();
                    return true;
                }
                return false;
            case S_REC:
                finishRec();
                return true;
            case S_EDIT:
                if (edPicker || edPatterns) {
                    edPicker = edPatterns = false;
                    return true;
                }
                stopEdPlayer();
                saveCustoms();
                screen = S_MENU;
                return true;
            case S_DETAIL:
            case S_CODE:
            case S_SYNC:
                screen = S_MENU;
                return true;
            case S_PLAY:
                stopPlayer();
                screen = curCustom != null ? S_MENU : S_DETAIL;
                return true;
            case S_RESULT:
                screen = curCustom != null ? S_MENU : S_DETAIL;
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
        if (screen == S_REC) {
            if (delta != 0) recSpin(delta > 0 ? 1 : -1, eventTime);
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
                if (menuEditing && items.get(menuSel).adj != null) {
                    items.get(menuSel).adj.adjust(dir);
                } else {
                    menuSel = Math.max(0, Math.min(items.size() - 1, menuSel + dir));
                }
                tick();
                break;
            case S_EDIT:
                edRotary(dir);
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
                if (screen == S_REC) recDown(e.getEventTime());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (screen == S_PLAY) inputTap(e.getEventTime());
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (screen == S_PLAY || screen == S_REC || swiped) return true;
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
                if (screen == S_REC) {
                    recUp(e.getEventTime());
                    return true;
                }
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
                if (it.action == 10) {
                    menuEditing = !menuEditing;
                    return;
                }
                if (menuEditing) {
                    menuEditing = false;
                    return;
                }
                if (it.action == 9) {
                    if (it.act != null) it.act.run();
                    return;
                }
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
                if (Math.abs(y - H * 0.705f) < H * 0.045f && Math.abs(x - cx) < W * 0.3f) {
                    toggleSaved(cur, curTitle);
                    break;
                }
                startLoading();
                break;
            case S_EDIT:
                edTap(x, y, longPress);
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
        curCustom = null;
        if (cur == null || !cur.code().equals(m.code())) song = null;
        cur = m;
        curTitle = title;
        screen = S_DETAIL;
    }

    // =================== 로딩 / 플레이 ===================

    private void startLoading() {
        if (curCustom != null) {
            final CustomMap cm = curCustom;
            loadCustomSong(cm, () -> {
                song = song.withNotes(cm.toNotes());
                startPlay();
            });
            return;
        }
        if (song != null && song.map.code().equals(cur.code()) && songKey.equals(cur.code())) {
            startPlay();
            return;
        }
        songKey = "";
        screen = S_LOADING;
        loading = true;
        final MapCode m = cur;
        new Thread(() -> {
            Song s = Song.generate(m);
            main.post(() -> {
                loading = false;
                if (screen != S_LOADING || cur != m) return;
                song = s;
                songKey = m.code();
                startPlay();
            });
        }, "song-gen").start();
    }

    /** 내 맵의 음악(레이어 포함)을 만들고 then 실행. 같은 음악이면 재사용. */
    private void loadCustomSong(final CustomMap cm, final Runnable then) {
        final MapCode m = cm.code();
        cur = m;
        final String key = m.code() + "/" + cm.layers;
        if (song != null && key.equals(songKey)) {
            then.run();
            return;
        }
        screen = S_LOADING;
        loading = true;
        new Thread(() -> {
            Song s = Song.generate(m, cm.layers);
            main.post(() -> {
                loading = false;
                if (screen != S_LOADING) return;
                song = s;
                songKey = key;
                then.run();
            });
        }, "song-gen").start();
    }

    // =================== 내 맵 메뉴 ===================

    // =================== 저장한 맵 ===================

    private ArrayList<String[]> savedMaps() {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("saved_maps", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                out.add(new String[]{e.getString(0), e.getString(1)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeSavedMaps(ArrayList<String[]> list) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String[] e : list) a.put(new org.json.JSONArray().put(e[0]).put(e[1]));
        prefs.edit().putString("saved_maps", a.toString()).apply();
    }

    private boolean isSaved(String code) {
        for (String[] e : savedMaps()) if (e[0].equals(code)) return true;
        return false;
    }

    private void toggleSaved(MapCode m, String title) {
        ArrayList<String[]> list = savedMaps();
        String code = m.code();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0].equals(code)) {
                list.remove(i);
                writeSavedMaps(list);
                buzz(20);
                if ("저장한 맵".equals(menuTitle)) items = savedItems();
                return;
            }
        }
        String name = (title.equals("랜덤 맵") || title.equals("코드 맵")) ? title + " " + code.substring(0, 4) : title;
        list.add(0, new String[]{code, name});
        writeSavedMaps(list);
        buzz(30);
        if ("저장한 맵".equals(menuTitle)) items = savedItems();
    }

    private ArrayList<Item> savedItems() {
        ArrayList<Item> its = new ArrayList<>();
        for (String[] e : savedMaps()) {
            final MapCode m = MapCode.parse(e[0]);
            if (m == null) continue;
            final String name = e[1];
            float b = prefs.getFloat("best_" + m.code(), -1);
            its.add(new Item(name, m.summary() + (b >= 0 ? "  " + rank(b, false) : ""), () -> openMap(m, name)));
        }
        if (its.isEmpty()) its.add(new Item("비어 있어요", "맵 화면에서 ☆ 저장하기를 눌러요", null));
        return its;
    }

    private void openSaved() {
        pushMenu("저장한 맵", savedItems());
    }

    private void pushMenu(String title, ArrayList<Item> its) {
        menuStack.add(new MenuState(items, menuSel, menuTitle));
        items = its;
        menuTitle = title;
        menuSel = 0;
        menuPos = 0;
        menuEditing = false;
        screen = S_MENU;
    }

    private void popMenu() {
        MenuState st = menuStack.remove(menuStack.size() - 1);
        items = st.items;
        menuSel = Math.min(st.sel, items.size() - 1);
        menuPos = menuSel;
        menuTitle = st.title;
        menuEditing = false;
        screen = S_MENU;
    }

    private void replaceMenu(String title, ArrayList<Item> its) {
        int sel = menuSel;
        items = its;
        menuTitle = title;
        menuSel = Math.min(sel, its.size() - 1);
        screen = S_MENU;
    }

    private void showToast(String t) {
        toast = t;
        toastT = 2.5f;
    }

    private List<CustomMap> customs() {
        if (customs == null) customs = CustomMap.loadAll(prefs);
        return customs;
    }

    private void saveCustoms() {
        if (customs != null) CustomMap.saveAll(prefs, customs);
    }

    private ArrayList<Item> myMapsItems() {
        ArrayList<Item> its = new ArrayList<>();
        its.add(new Item("+ 새 맵", "자동 채보로 시작해서 고치기", () -> newCustom(true)));
        its.add(new Item("+ 빈 맵", "음악만 · 직접 녹음해서 만들기", () -> newCustom(false)));
        for (CustomMap cm : customs()) {
            final CustomMap c = cm;
            its.add(new Item(cm.name, MapCode.STYLES[cm.style] + " · " + cm.bpm + " · 노트 " + cm.notes.size(), () -> openCustom(c)));
        }
        return its;
    }

    private void openMyMaps() {
        pushMenu("내 맵", myMapsItems());
    }

    private void newCustom(boolean autoChart) {
        final CustomMap cm = new CustomMap();
        cm.id = "c" + System.currentTimeMillis();
        cm.name = "내 맵 " + (customs().size() + 1);
        cm.setFrom(MapCode.random(new Random(), 1));
        customs().add(cm);
        if (autoChart) {
            loadCustomSong(cm, () -> {
                cm.setFromNotes(song.notes);
                saveCustoms();
                replaceMenu("내 맵", myMapsItems());
                openCustom(cm);
            });
        } else {
            saveCustoms();
            replaceMenu("내 맵", myMapsItems());
            openCustom(cm);
        }
    }

    private ArrayList<Item> customItems(final CustomMap cm) {
        ArrayList<Item> its = new ArrayList<>();
        its.add(new Item("플레이", "노트 " + cm.notes.size() + "개", () -> {
            if (cm.notes.isEmpty()) {
                showToast("노트가 없어요. 녹음이나 편집을 먼저!");
                return;
            }
            curCustom = cm;
            curTitle = cm.name;
            startLoading();
        }));
        its.add(new Item("녹음 (새로)", "음악 들으며 탭·누르기·베젤", () -> startRec(cm, false)));
        its.add(new Item("녹음 (덧입히기)", "기존 노트 위에 더 찍기", () -> startRec(cm, true)));
        its.add(new Item("노트 편집", "베젤로 이동하며 하나씩", () -> startEdit(cm)));
        its.add(new Item("곡 설정", "장르 · BPM · 키 · 악기", () -> openCustomSettings(cm)));
        its.add(new Item("트릭 랜덤으로 넣기", "숨김·깜짝·회오리·폭탄 등을 섞어요", () -> {
            Random rr = new Random();
            int n = 0;
            for (double[] x : cm.notes) {
                if (x[5] != 0 || rr.nextFloat() > 0.22f) continue;
                int tr = 1 + rr.nextInt(Note.TR_COUNT - 1);
                if (tr == Note.TR_BOMB && rr.nextFloat() < 0.6f) tr = Note.TR_SPIRAL;
                if (!Note.trickAllowed((int) x[2], tr)) tr = Note.TR_SUDDEN;
                x[5] = tr;
                n++;
            }
            saveCustoms();
            showToast("트릭 " + n + "개를 넣었어요");
        }));
        its.add(new Item("트릭 모두 지우기", "노트는 그대로, 트릭만 없애요", () -> {
            for (double[] x : cm.notes) x[5] = 0;
            saveCustoms();
            showToast("트릭을 모두 지웠어요");
        }));
        its.add(new Item("자동 채보로 채우기", "지금 음악에 맞춰 다시 만들기", () -> {
            final MapCode m = cm.code();
            screen = S_LOADING;
            loading = true;
            new Thread(() -> {
                Song s = Song.generate(m, cm.layers);
                main.post(() -> {
                    loading = false;
                    cm.setFromNotes(s.notes);
                    saveCustoms();
                    replaceMenu(cm.name, customItems(cm));
                    showToast("노트 " + cm.notes.size() + "개 생성");
                });
            }, "song-gen").start();
        }));
        its.add(new Item("복제", "같은 맵 하나 더", () -> {
            CustomMap c = new CustomMap();
            c.id = "c" + System.currentTimeMillis();
            c.name = cm.name + " 복사";
            c.setFrom(cm.code());
            c.layers = cm.layers;
            c.quant = cm.quant;
            for (double[] n : cm.notes) c.notes.add(n.clone());
            customs().add(c);
            saveCustoms();
            popMenu();
            replaceMenu("내 맵", myMapsItems());
            showToast("복제했어요");
        }));
        its.add(new Item("삭제", "탭하면 바로 삭제", () -> {
            customs().remove(cm);
            saveCustoms();
            popMenu();
            replaceMenu("내 맵", myMapsItems());
            showToast("삭제했어요");
        }));
        return its;
    }

    private void openCustom(CustomMap cm) {
        curCustom = cm;
        pushMenu(cm.name, customItems(cm));
    }

    private void openCustomSettings(final CustomMap cm) {
        ArrayList<Item> its = new ArrayList<>();
        its.add(new Item("장르", new Adj() {
            public String value() { return MapCode.STYLES[cm.style]; }
            public void adjust(int d) { cm.style = Math.floorMod(cm.style + d, 8); cm.bpm = MapCode.STYLE_BPM[cm.style]; saveCustoms(); }
        }));
        its.add(new Item("BPM", new Adj() {
            public String value() { return String.valueOf(cm.bpm); }
            public void adjust(int d) { cm.bpm = Math.max(MapCode.BPM_MIN, Math.min(MapCode.BPM_MAX, cm.bpm + d * 2)); saveCustoms(); }
        }));
        its.add(new Item("키", new Adj() {
            public String value() { return MapCode.KEYS[cm.key] + (cm.minor ? " 단조" : " 장조"); }
            public void adjust(int d) { cm.key = Math.floorMod(cm.key + d, 12); saveCustoms(); }
        }));
        its.add(new Item("장조/단조", new Adj() {
            public String value() { return cm.minor ? "단조 (어두움)" : "장조 (밝음)"; }
            public void adjust(int d) { cm.minor = !cm.minor; saveCustoms(); }
        }));
        its.add(new Item("멜로디 번호", new Adj() {
            public String value() { return String.valueOf(cm.seed); }
            public void adjust(int d) { cm.seed = Math.max(0, Math.min(0x3FFFFF, cm.seed + d)); saveCustoms(); }
        }));
        its.add(new Item("길이", new Adj() {
            public String value() { return MapCode.BARS[cm.len] + "마디 · " + cm.code().lengthText(); }
            public void adjust(int d) { cm.len = Math.max(0, Math.min(3, cm.len + d)); saveCustoms(); }
        }));
        its.add(new Item("노트 속도", new Adj() {
            public String value() { return new String[]{"느림", "보통", "빠름", "아주 빠름"}[cm.diff]; }
            public void adjust(int d) { cm.diff = Math.max(0, Math.min(3, cm.diff + d)); saveCustoms(); }
        }));
        its.add(new Item("녹음 박자 맞춤", new Adj() {
            public String value() { return new String[]{"끄기 (자유)", "8분음표", "16분음표"}[cm.quant]; }
            public void adjust(int d) { cm.quant = Math.floorMod(cm.quant + d, 3); saveCustoms(); }
        }));
        for (int i = 0; i < Song.LAYER_NAMES.length; i++) {
            final int bit = 1 << i;
            its.add(new Item("악기: " + Song.LAYER_NAMES[i], new Adj() {
                public String value() { return (cm.layers & bit) != 0 ? "켜기" : "끄기"; }
                public void adjust(int d) { cm.layers ^= bit; saveCustoms(); }
            }));
        }
        its.add(new Item("미리 듣기", "바꾼 음악을 노트 편집에서 들어봐요", () -> startEdit(cm)));
        pushMenu("곡 설정", its);
    }

    // =================== 녹음 ===================

    private void startRec(final CustomMap cm, final boolean overdub) {
        curCustom = cm;
        loadCustomSong(cm, () -> {
            if (!overdub) cm.notes.clear();
            edUndo.clear();
            recCount = 0;
            recDownT = -1;
            lastRecSpinT = -9;
            recLast = "";
            stopPlayer();
            player = new AudioPlayer(song.pcm);
            player.start();
            screen = S_REC;
        });
    }

    private void recAdd(double t, double end, int type, int dir) {
        CustomMap cm = curCustom;
        double st = cm.snap(t);
        // 같은 자리 노트는 교체
        for (int i = cm.notes.size() - 1; i >= 0; i--) {
            if (Math.abs(cm.notes.get(i)[0] - st) < 0.04) cm.notes.remove(i);
        }
        double en = type == Note.HOLD ? Math.max(st + cm.beat() / 2, cm.snap(end)) : st;
        cm.notes.add(new double[]{st, en, type, dir, -1, 0});
        recCount++;
        recFlash = 1f;
        recLast = type == Note.HOLD ? "줄" : type == Note.SPIN ? (dir > 0 ? "베젤 ↻" : "베젤 ↺") : "탭";
    }

    private void recDown(long et) {
        if (player == null) return;
        recDownT = songTimeAt(et);
    }

    private void recUp(long et) {
        if (player == null || recDownT < 0) return;
        double t = songTimeAt(et);
        double dur = t - recDownT;
        if (dur >= 0.3) recAdd(recDownT, t, Note.HOLD, 0);
        else recAdd(recDownT, recDownT, Note.TAP, 0);
        recDownT = -1;
    }

    private void recSpin(int dir, long et) {
        if (player == null) return;
        double t = songTimeAt(et);
        if (t - lastRecSpinT < 0.3) return; // 연속 회전은 한 번으로
        lastRecSpinT = t;
        recAdd(t, t, Note.SPIN, dir);
    }

    private void finishRec() {
        stopPlayer();
        saveCustoms();
        if (curCustom != null) replaceMenu(curCustom.name, customItems(curCustom));
        showToast("노트 " + recCount + "개 녹음 · 총 " + (curCustom != null ? curCustom.notes.size() : 0) + "개");
        screen = S_MENU;
    }

    // =================== 노트 편집 ===================

    private void startEdit(final CustomMap cm) {
        curCustom = cm;
        loadCustomSong(cm, () -> {
            edT = Song.LEAD_IN;
            edSel = null;
            edPicker = false;
            edPatterns = false;
            edAngleMode = false;
            edUndo.clear();
            screen = S_EDIT;
            updateEdInfo();
        });
    }

    private void stopEdPlayer() {
        if (edPlayer != null) {
            edPlayer.release();
            edPlayer = null;
        }
    }

    private static final String[] GRID_NAMES = {"1박", "1/8", "1/16", "1/32", "자유"};
    private static final String[] PICK_NAMES = {"탭", "줄", "베젤↻", "베젤↺", "베젤 아무", "패턴 ★"};
    private static final String[] PATTERN_NAMES = {"연타", "계단", "지그재그", "베젤 교대", "폭탄 사이", "트릭 쇼"};
    private static final String[] TRICK_SHORT = {"", "숨", "깜", "가", "감", "회", "흔", "✕"};
    private int edGrid = 2;
    private double[] edSel;
    private boolean edPicker, edPatterns, edAngleMode;
    private final ArrayList<ArrayList<double[]>> edUndo = new ArrayList<>();

    /** 바꾸기 전에 지금 노트를 저장 (되돌리기용, 최대 40번) */
    private void pushUndo() {
        ArrayList<double[]> snap = new ArrayList<>();
        for (double[] n : curCustom.notes) snap.add(n.clone());
        edUndo.add(snap);
        if (edUndo.size() > 40) edUndo.remove(0);
    }

    private void edDoUndo() {
        if (edUndo.isEmpty()) {
            edInfo = "되돌릴 게 없어요";
            return;
        }
        ArrayList<double[]> snap = edUndo.remove(edUndo.size() - 1);
        curCustom.notes.clear();
        curCustom.notes.addAll(snap);
        edSel = null;
        edAngleMode = false;
        saveCustoms();
        tick();
        updateEdInfo();
        edInfo = "되돌렸어요 · 노트 " + curCustom.notes.size() + "개";
    }

    private double autoAngle(double t) {
        double a = (((t - Song.LEAD_IN) / curCustom.beat()) * 45.0 + 270) % 360;
        return a < 0 ? a + 360 : a;
    }

    private static double norm(double a) {
        a %= 360;
        return a < 0 ? a + 360 : a;
    }

    /** 패턴 도장: 지금 위치부터 미리 만든 노트 묶음을 찍고, 끝난 다음 칸으로 이동 */
    private void edStamp(int k) {
        CustomMap cm = curCustom;
        double b = cm.beat(), t0 = edT, base = Math.round(autoAngle(t0) / 15.0) * 15;
        ArrayList<double[]> add = new ArrayList<>();
        double step;
        switch (k) {
            case 0: // 연타: 16분음표 4개, 같은 자리
                step = b / 4;
                for (int i = 0; i < 4; i++) add.add(new double[]{t0 + i * step, t0 + i * step, Note.TAP, 0, base, 0});
                break;
            case 1: // 계단: 8분음표 4개, 45도씩 돌기
                step = b / 2;
                for (int i = 0; i < 4; i++) add.add(new double[]{t0 + i * step, t0 + i * step, Note.TAP, 0, norm(base + i * 45), 0});
                break;
            case 2: // 지그재그: 반대편 왔다 갔다
                step = b / 2;
                for (int i = 0; i < 4; i++) add.add(new double[]{t0 + i * step, t0 + i * step, Note.TAP, 0, norm(base + (i % 2) * 180), 0});
                break;
            case 3: // 베젤 교대: ↻ ↺ ↻ ↺ 한 박마다
                step = b;
                for (int i = 0; i < 4; i++) add.add(new double[]{t0 + i * step, t0 + i * step, Note.SPIN, i % 2 == 0 ? 1 : -1, -1, 0});
                break;
            case 4: // 폭탄 사이: 탭 사이사이에 누르면 안 되는 폭탄
                step = b / 2;
                for (int i = 0; i < 6; i++) {
                    boolean bomb = i % 2 == 1;
                    add.add(new double[]{t0 + i * step, t0 + i * step, Note.TAP, 0, norm(base + (bomb ? 180 : (i / 2) * 60)), bomb ? Note.TR_BOMB : 0});
                }
                break;
            default: // 트릭 쇼: 숨김 · 깜짝 · 회오리 · 흔들
                step = b / 2;
                int[] tr = {Note.TR_HIDDEN, Note.TR_SUDDEN, Note.TR_SPIRAL, Note.TR_WOBBLE};
                for (int i = 0; i < 4; i++) add.add(new double[]{t0 + i * step, t0 + i * step, Note.TAP, 0, norm(base + i * 90), tr[i]});
        }
        pushUndo();
        for (double[] n : add) {
            for (int i = cm.notes.size() - 1; i >= 0; i--) {
                double[] o = cm.notes.get(i);
                if (Math.abs(o[0] - n[0]) < 0.02 && (int) o[2] == (int) n[2]) cm.notes.remove(i);
            }
            cm.notes.add(n);
        }
        edT = add.get(add.size() - 1)[0] + step;
        edSel = null;
        edAngleMode = false;
        tick();
        saveCustoms();
        updateEdInfo();
        edInfo = "패턴 " + PATTERN_NAMES[k] + " · 노트 " + add.size() + "개";
    }

    /** 바로 앞 한 마디를 지금 위치에 그대로 복사 (덮어쓰기) */
    private void edCopyBar() {
        CustomMap cm = curCustom;
        double bar = cm.beat() * 4, from = edT - bar;
        ArrayList<double[]> src = new ArrayList<>();
        for (double[] n : cm.notes) if (n[0] >= from - 0.005 && n[0] < edT - 0.005) src.add(n);
        if (src.isEmpty()) {
            edInfo = "앞 마디에 노트가 없어요";
            return;
        }
        pushUndo();
        for (int i = cm.notes.size() - 1; i >= 0; i--) {
            double t = cm.notes.get(i)[0];
            if (t >= edT - 0.005 && t < edT + bar - 0.005) cm.notes.remove(i);
        }
        for (double[] n : src) {
            double[] c = n.clone();
            c[0] += bar;
            c[1] += bar;
            cm.notes.add(c);
        }
        edT += bar;
        edSel = null;
        edAngleMode = false;
        tick();
        saveCustoms();
        updateEdInfo();
        edInfo = "앞 마디 복사 · 노트 " + src.size() + "개";
    }

    private double edStep() {
        double b = curCustom.beat();
        switch (edGrid) {
            case 0: return b;
            case 1: return b / 2;
            case 2: return b / 4;
            case 3: return b / 8;
            default: return 0.01;
        }
    }

    private void edRotary(int dir) {
        if (edPlayer != null || edPicker || edPatterns) return;
        if (edAngleMode && edSel != null && (int) edSel[2] != Note.SPIN) {
            edSel[4] = Math.floorMod((int) Math.round(edAngle(edSel)) + dir * 15, 360);
            saveCustoms();
            updateEdInfo();
            return;
        }
        double g = edStep();
        if (edGrid == 4) edT = Math.max(0, edT + dir * g);
        else edT = Math.max(0, Song.LEAD_IN + Math.round((edT - Song.LEAD_IN) / g + dir) * g);
        edSel = null;
        edAngleMode = false;
        updateEdInfo();
    }

    private ArrayList<double[]> notesAtEd() {
        double tol = edGrid == 4 ? 0.02 : Math.min(edStep() / 2, 0.06);
        ArrayList<double[]> out = new ArrayList<>();
        for (double[] n : curCustom.notes) if (Math.abs(n[0] - edT) <= tol) out.add(n);
        return out;
    }

    private double[] noteAtEd() {
        ArrayList<double[]> ns = notesAtEd();
        if (edSel != null && ns.contains(edSel)) return edSel;
        return ns.isEmpty() ? null : ns.get(0);
    }

    private double edAngle(double[] n) {
        if (n.length > 4 && n[4] >= 0) return n[4];
        double a = (((n[0] - Song.LEAD_IN) / curCustom.beat()) * 45.0 + 270) % 360;
        return a < 0 ? a + 360 : a;
    }

    private static String typeName(double[] n) {
        int type = (int) n[2], dir = (int) n[3];
        int tr = n.length > 5 ? (int) n[5] : 0;
        if (tr == Note.TR_BOMB) return "폭탄";
        String base = typeNameBase(type, dir);
        return tr > 0 ? base + "+" + Note.TRICK_NAMES[tr] : base;
    }

    private static String typeNameBase(int type, int dir) {
        if (type == Note.HOLD) return "줄";
        if (type == Note.SPIN) return dir > 0 ? "베젤 시계" : dir < 0 ? "베젤 반시계" : "베젤 아무쪽";
        return "탭";
    }

    private void updateEdInfo() {
        ArrayList<double[]> ns = notesAtEd();
        if (edSel == null || !ns.contains(edSel)) edSel = ns.isEmpty() ? null : ns.get(0);
        if (edSel == null) {
            edInfo = "빈 칸 · 가운데 탭: 노트 추가";
            edAngleMode = false;
        } else {
            edInfo = typeName(edSel) + (ns.size() > 1 ? " (" + (ns.indexOf(edSel) + 1) + "/" + ns.size() + ")" : "")
                    + ((int) edSel[2] != Note.SPIN ? " · " + (int) edAngle(edSel) + "°" : "")
                    + " · 탭: 겹쳐 추가";
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private float[] pickPos(int k, int count) {
        double a = Math.toRadians(-90 + k * 360.0 / count);
        return new float[]{cx + (float) Math.cos(a) * W * 0.25f, cy + (float) Math.sin(a) * W * 0.25f};
    }

    private void edAddNote(int k) {
        CustomMap cm = curCustom;
        double[] n;
        switch (k) {
            case 0: n = new double[]{edT, edT, Note.TAP, 0, -1, 0}; break;
            case 1: n = new double[]{edT, edT + cm.beat(), Note.HOLD, 0, -1, 0}; break;
            case 2: n = new double[]{edT, edT, Note.SPIN, 1, -1, 0}; break;
            case 3: n = new double[]{edT, edT, Note.SPIN, -1, -1, 0}; break;
            default: n = new double[]{edT, edT, Note.SPIN, 0, -1, 0};
        }
        for (double[] o : notesAtEd()) {
            if ((int) o[2] == (int) n[2] && (int) o[3] == (int) n[3]) {
                edSel = o;
                updateEdInfo();
                edInfo = "같은 노트가 이미 있어요";
                return;
            }
        }
        pushUndo();
        cm.notes.add(n);
        edSel = n;
        tick();
        saveCustoms();
        updateEdInfo();
    }

    private void edTap(float x, float y, boolean longPress) {
        CustomMap cm = curCustom;
        if (edPicker || edPatterns) {
            boolean pat = edPatterns;
            edPicker = edPatterns = false;
            int count = pat ? PATTERN_NAMES.length : PICK_NAMES.length;
            for (int k = 0; k < count; k++) {
                float[] pp = pickPos(k, count);
                if (dist(x, y, pp[0], pp[1]) < W * 0.1f) {
                    if (pat) edStamp(k);
                    else if (k == 5) edPatterns = true;
                    else edAddNote(k);
                    return;
                }
            }
            if (dist(x, y, cx, cy) < W * 0.12f) {
                if (pat) edCopyBar();
                else edDoUndo();
            }
            return;
        }
        if (y < H * 0.24f && Math.abs(x - cx) < W * 0.22f) {
            if (edPlayer != null) {
                stopEdPlayer();
            } else {
                edPlayer = new AudioPlayer(song.pcm);
                edPlayer.start(Math.max(0, edT - 1.0));
            }
            return;
        }
        if (edPlayer != null) {
            stopEdPlayer();
            if (edGrid != 4) {
                double g = edStep();
                edT = Song.LEAD_IN + Math.round((edT - Song.LEAD_IN) / g) * g;
            }
            updateEdInfo();
            return;
        }
        // 아래 버튼 줄: 격자 / 길이 / 각도 / 트릭 / 겹친 노트 고르기
        if (Math.abs(y - H * 0.83f) < W * 0.065f && Math.abs(x - cx) < W * 0.375f) {
            int k = Math.max(0, Math.min(4, (int) Math.floor((x - (cx - W * 0.375f)) / (W * 0.15f))));
            if (k == 0) {
                edGrid = (edGrid + 1) % GRID_NAMES.length;
                updateEdInfo();
                edInfo = "격자: " + GRID_NAMES[edGrid];
            } else if (k == 1) {
                if (edSel != null && (int) edSel[2] == Note.HOLD) {
                    double[] lens = {0.5, 1, 2, 3, 4, 6, 8};
                    double curLen = (edSel[1] - edSel[0]) / cm.beat();
                    pushUndo();
                    int i = 0;
                    while (i < lens.length && lens[i] <= curLen + 0.01) i++;
                    edSel[1] = edSel[0] + lens[i % lens.length] * cm.beat();
                    saveCustoms();
                    updateEdInfo();
                    edInfo = "줄 길이 " + lens[i % lens.length] + "박";
                } else {
                    edInfo = "줄 노트를 먼저 고르세요";
                }
            } else if (k == 2) {
                if (edSel != null && (int) edSel[2] != Note.SPIN) {
                    edAngleMode = !edAngleMode;
                    if (edAngleMode) pushUndo();
                    if (edAngleMode && edSel[4] < 0) edSel[4] = Math.round(edAngle(edSel) / 15.0) * 15 % 360;
                    updateEdInfo();
                    edInfo = edAngleMode ? "베젤로 방향 돌리기 · " + (int) edAngle(edSel) + "°" : "방향 조절 끝";
                } else {
                    edInfo = "탭/줄 노트만 방향을 정해요";
                }
            } else if (k == 3) {
                if (edSel != null) {
                    pushUndo();
                    int type = (int) edSel[2], tr = (int) edSel[5];
                    do tr = (tr + 1) % Note.TR_COUNT; while (!Note.trickAllowed(type, tr));
                    edSel[5] = tr;
                    saveCustoms();
                    updateEdInfo();
                    edInfo = "트릭: " + Note.TRICK_NAMES[tr] + (tr == Note.TR_BOMB ? " (누르면 안 돼요)" : "");
                } else {
                    edInfo = "노트를 먼저 고르세요";
                }
            } else {
                ArrayList<double[]> ns = notesAtEd();
                if (ns.size() > 1) {
                    edSel = ns.get((ns.indexOf(edSel) + 1) % ns.size());
                    edAngleMode = false;
                }
                updateEdInfo();
            }
            return;
        }
        if (x < W * 0.2f || x > W * 0.8f) {
            boolean next = x > cx;
            double bestT = next ? Double.MAX_VALUE : -1;
            for (double[] n : cm.notes) {
                if (next && n[0] > edT + 0.01 && n[0] < bestT) bestT = n[0];
                if (!next && n[0] < edT - 0.01 && n[0] > bestT) bestT = n[0];
            }
            if (bestT >= 0 && bestT < Double.MAX_VALUE) edT = bestT;
            edSel = null;
            edAngleMode = false;
            updateEdInfo();
            return;
        }
        if (longPress) {
            if (edSel != null) {
                pushUndo();
                cm.notes.remove(edSel);
                edSel = null;
                edAngleMode = false;
                buzz(30);
                saveCustoms();
            }
            updateEdInfo();
            return;
        }
        edPicker = true;
    }

    private void startPlay() {
        stopPlayer();
        for (Note n : song.notes) {
            n.result = -1;
            n.tailResult = -1;
        }
        activeHold = null;
        hasBombs = false;
        for (Note n : song.notes) if (n.trick == Note.TR_BOMB) hasBombs = true;
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
            return;
        }
        // 폭탄을 눌렀는지
        List<Note> ns = song.notes;
        for (int i = nextIdx; i < ns.size(); i++) {
            Note n = ns.get(i);
            if (n.time > t + W_GREAT) break;
            if (n.result >= 0 || n.trick != Note.TR_BOMB || Math.abs(n.time - t) > W_GREAT) continue;
            double ang = Math.toRadians(n.angle);
            float bx = (float) (cx + Math.cos(ang) * R), by = (float) (cy + Math.sin(ang) * R);
            buzz(90);
            miss(n);
            if (screen == S_PLAY) {
                judgeText = "펑!";
                burst(bx, by, Color.rgb(255, 60, 40));
                burst(bx, by, Color.rgb(255, 200, 60));
            }
            return;
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
            if (n.result >= 0 || n.trick == Note.TR_BOMB) continue;
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
        String key = curCustom != null ? "best_c_" + curCustom.id : "best_" + cur.code();
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
        toastT = Math.max(0, toastT - dt);
        recFlash = Math.max(0, recFlash - dt * 3);
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
            case S_REC: updateRec(); if (screen == S_REC) drawRec(c); break;
            case S_EDIT: drawEdit(c); break;
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
            String sub = it.adj != null ? (menuEditing && i == menuSel ? "◀ " + it.adj.value() + " ▶" : it.adj.value()) : it.sub;
            if (sub == null) sub = "";
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
        c.drawText(menuTitle, cx, H * 0.1f, text);
        if (toastT > 0) {
            p.setColor(Color.argb((int) (200 * Math.min(1f, toastT)), 0, 0, 0));
            text.setTextSize(W * 0.04f);
            float tw = text.measureText(toast) / 2 + W * 0.04f;
            r.set(cx - tw, H * 0.8f, cx + tw, H * 0.88f);
            c.drawRoundRect(r, W * 0.04f, W * 0.04f, p);
            text.setColor(Color.argb((int) (255 * Math.min(1f, toastT)), 255, 230, 120));
            c.drawText(toast, cx, H * 0.855f, text);
        } else if (menuEditing) {
            text.setTextSize(W * 0.038f);
            text.setColor(Color.LTGRAY);
            c.drawText("베젤로 조절 · 탭하면 완료", cx, H * 0.93f, text);
        }
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
            c.drawText("최고 " + rank(b, false) + "  " + String.format("%.1f%%", b), cx, H * 0.645f, text);
        }
        boolean saved = isSaved(cur.code());
        p.setColor(saved ? Color.argb(90, 255, 210, 63) : Color.argb(60, 255, 255, 255));
        r.set(cx - W * 0.2f, H * 0.67f, cx + W * 0.2f, H * 0.74f);
        c.drawRoundRect(r, W * 0.05f, W * 0.05f, p);
        text.setTextSize(W * 0.045f);
        text.setColor(saved ? Color.rgb(255, 210, 63) : Color.WHITE);
        c.drawText(saved ? "★ 저장됨" : "☆ 저장하기", cx, H * 0.72f, text);
        boolean on = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        text.setTextSize(W * 0.06f);
        text.setColor(on ? Color.rgb(255, 230, 120) : Color.rgb(200, 170, 80));
        c.drawText("탭해서 시작", cx, H * 0.81f, text);
        text.setTextSize(W * 0.034f);
        text.setColor(Color.GRAY);
        c.drawText("오른쪽 스와이프: 뒤로", cx, H * 0.88f, text);
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
            if (n.trick == Note.TR_BOMB) {
                if (playT > n.time + W_GREAT) {          // 폭탄을 잘 피했음
                    n.result = R_PERFECT;
                    counts[R_PERFECT]++;
                    score += 100;
                    nextIdx++;
                    continue;
                }
                break;
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
            float lin = (float) (1 - (n.time - t) / ap);
            if (lin < 0) continue;
            float na = trickAlpha(n.trick, lin);
            if (na <= 0.01f) continue;
            float prog = trickProg(n.trick, lin);
            float rr = R * prog;
            if (n.type == Note.TAP) {
                double a = Math.toRadians(trickAngle(n, lin, t));
                float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                float size = W * 0.045f * (0.45f + 0.55f * Math.min(1f, prog));
                if (n.trick == Note.TR_BOMB) {
                    drawBomb(c, x, y, size, na, t);
                    continue;
                }
                glow(c, x, y, size * 2.2f, alpha(col, (int) (120 * na)));
                p.setColor(alpha(col, (int) (255 * na)));
                c.drawCircle(x, y, size, p);
                p.setColor(alpha(Color.WHITE, (int) (255 * na)));
                c.drawCircle(x, y, size * 0.55f, p);
                if (n.trick != Note.TR_NONE) {          // 트릭 표시: 작은 테두리
                    stroke.setStrokeWidth(W * 0.006f);
                    stroke.setColor(alpha(trickColor(n.trick), (int) (230 * na)));
                    c.drawCircle(x, y, size * 1.35f, stroke);
                }
            } else {
                int sc = spinColor(n.dir);
                stroke.setColor(alpha(sc, (int) ((120 + 135 * Math.min(1f, prog)) * na)));
                stroke.setStrokeWidth(W * 0.022f);
                c.drawCircle(cx, cy, rr, stroke);
                drawArrows(c, rr, n.dir, alpha(sc, (int) (255 * na)), t);
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
            if (hasBombs) {
                text.setColor(Color.rgb(255, 110, 90));
                c.drawText("빨간 ✕ 폭탄은 누르지 마세요!", cx, cy + W * 0.29f, text);
            }
        }
    }

    // ---------- 트릭 ----------

    /** 트릭에 따른 진행도 (0 = 가운데, 1 = 판정 링) */
    private static float trickProg(int trick, float lin) {
        if (trick == Note.TR_ACCEL) return lin < 1 ? lin * lin : lin;
        if (trick == Note.TR_DECEL) return lin < 1 ? 1 - (1 - lin) * (1 - lin) : lin;
        return lin;
    }

    /** 트릭에 따른 투명도 (숨김: 중간에 사라짐, 깜짝: 늦게 나타남) */
    private static float trickAlpha(int trick, float lin) {
        if (trick == Note.TR_HIDDEN) return lin < 0.4f ? 1f : Math.max(0f, 1f - (lin - 0.4f) / 0.22f);
        if (trick == Note.TR_SUDDEN) return lin < 0.55f ? 0f : Math.min(1f, (lin - 0.55f) / 0.12f);
        return 1f;
    }

    /** 트릭에 따른 각도 (회오리: 돌면서 다가옴, 흔들: 좌우로 흔들림). 링에 닿을 땐 원래 각도. */
    private static double trickAngle(Note n, float lin, double t) {
        float k = 1 - Math.max(0f, Math.min(1f, lin));
        if (n.trick == Note.TR_SPIRAL) return n.angle + k * 240;
        if (n.trick == Note.TR_WOBBLE) return n.angle + Math.sin(t * 9 + n.time * 3) * 30 * k;
        return n.angle;
    }

    private static int trickColor(int trick) {
        switch (trick) {
            case Note.TR_HIDDEN: return Color.rgb(160, 160, 200);
            case Note.TR_SUDDEN: return Color.rgb(255, 240, 120);
            case Note.TR_ACCEL: return Color.rgb(255, 120, 80);
            case Note.TR_DECEL: return Color.rgb(100, 180, 255);
            case Note.TR_SPIRAL: return Color.rgb(200, 120, 255);
            case Note.TR_WOBBLE: return Color.rgb(120, 255, 200);
            case Note.TR_BOMB: return Color.rgb(255, 60, 50);
            default: return Color.WHITE;
        }
    }

    /** 폭탄: 누르면 안 되는 빨간 가시 공 */
    private void drawBomb(Canvas c, float x, float y, float size, float na, double t) {
        float pulse = 0.85f + 0.15f * (float) Math.sin(t * 20);
        glow(c, x, y, size * 2.4f, alpha(Color.rgb(255, 40, 30), (int) (140 * na)));
        stroke.setStrokeWidth(W * 0.008f);
        stroke.setColor(alpha(Color.rgb(255, 90, 60), (int) (255 * na)));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + t * 2;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            c.drawLine(x + ca * size * 0.8f, y + sa * size * 0.8f, x + ca * size * 1.45f * pulse, y + sa * size * 1.45f * pulse, stroke);
        }
        p.setColor(alpha(Color.rgb(70, 10, 15), (int) (255 * na)));
        c.drawCircle(x, y, size, p);
        stroke.setStrokeWidth(W * 0.009f);
        stroke.setColor(alpha(Color.rgb(255, 70, 60), (int) (255 * na)));
        float d = size * 0.45f;
        c.drawLine(x - d, y - d, x + d, y + d, stroke);
        c.drawLine(x - d, y + d, x + d, y - d, stroke);
    }

    /** 줄(롱노트): 머리부터 꼬리까지 이어진 굵은 선. 누르고 있으면 머리가 링에 고정. */
    private void drawHold(Canvas c, Note n, double t, double ap, int col, boolean active) {
        float linHead = (float) (1 - (n.time - t) / ap);
        float linTail = (float) (1 - (n.end - t) / ap);
        if (!active && linHead < 0) return;
        float na = active ? 1f : trickAlpha(n.trick, linHead);
        if (na <= 0.01f) return;
        col = alpha(col, (int) (255 * na));
        float pHead = active ? 1f : trickProg(n.trick, linHead);
        float pTail = linTail < 0 ? 0f : trickProg(n.trick, linTail);
        pHead = Math.min(pHead, 1.05f);
        pTail = Math.max(0f, Math.min(pTail, pHead));
        double a = Math.toRadians(active ? n.angle : trickAngle(n, linHead, t));
        double at = Math.toRadians(trickAngle(n, Math.max(0f, linTail), t));
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float hx = cx + ca * R * pHead, hy = cy + sa * R * pHead;
        float tx = cx + (float) Math.cos(at) * R * pTail, ty = cy + (float) Math.sin(at) * R * pTail;
        float width = W * 0.07f * (0.5f + 0.5f * Math.min(1f, pHead));
        stroke.setStrokeWidth(width * 1.5f);
        stroke.setColor(alpha(col, (int) ((active ? 110 : 60) * na)));
        c.drawLine(hx, hy, tx, ty, stroke);
        stroke.setStrokeWidth(width);
        stroke.setColor(alpha(col, (int) ((active ? 255 : 190) * na)));
        c.drawLine(hx, hy, tx, ty, stroke);
        stroke.setStrokeWidth(width * 0.35f);
        stroke.setColor(alpha(Color.WHITE, (int) ((active ? 230 : 150) * na)));
        c.drawLine(hx, hy, tx, ty, stroke);
        float size = W * 0.05f * (0.5f + 0.5f * Math.min(1f, pHead));
        glow(c, hx, hy, size * (active ? 3f : 2.2f), alpha(col, (int) ((active ? 200 : 120) * na)));
        p.setColor(alpha(Color.WHITE, (int) (255 * na)));
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

    // ---------- 녹음 ----------

    private void updateRec() {
        if (player == null) return;
        playT = songTime();
        if (playT > song.musicEnd() + 0.6) finishRec();
    }

    private void drawRec(Canvas c) {
        double t = playT;
        int col = STYLE_COLOR[cur.style];
        double beats = (t - Song.LEAD_IN) / song.beatSec;
        float frac = beats >= 0 ? (float) (beats - Math.floor(beats)) : 0.5f;
        float pulse = (float) Math.exp(-frac * 5) * song.energyAt(t);
        glow(c, cx, cy, W * (0.3f + 0.2f * pulse), alpha(col, (int) (40 + 120 * pulse)));
        stroke.setStrokeWidth(W * (0.012f + 0.02f * recFlash));
        stroke.setColor(alpha(Color.WHITE, 150 + (int) (100 * recFlash)));
        c.drawCircle(cx, cy, R, stroke);
        // 최근 노트 (시계 방향으로 흘러감)
        for (double[] n : curCustom.notes) {
            double dt = n[0] - t;
            if (dt < -2 || dt > 0.3) continue;
            double a = Math.toRadians(-90 + dt * 90);
            float x = cx + (float) Math.cos(a) * R, y = cy + (float) Math.sin(a) * R;
            int nc = (int) n[2] == Note.SPIN ? spinColor((int) n[3]) : col;
            p.setColor(alpha(nc, (int) (255 * Math.max(0.2, 1 + dt / 2))));
            c.drawCircle(x, y, W * 0.025f, p);
        }
        boolean blink = (SystemClock.uptimeMillis() / 400) % 2 == 0;
        p.setColor(blink ? Color.rgb(255, 60, 70) : Color.rgb(120, 20, 30));
        c.drawCircle(cx - W * 0.1f, H * 0.2f, W * 0.022f, p);
        text.setTextSize(W * 0.05f);
        text.setColor(Color.WHITE);
        c.drawText("REC", cx + W * 0.03f, H * 0.215f, text);
        text.setTextSize(W * 0.13f);
        c.drawText(String.valueOf(recCount), cx, cy + W * 0.03f, text);
        if (recFlash > 0) {
            text.setTextSize(W * 0.055f);
            text.setColor(alpha(col, (int) (255 * recFlash)));
            c.drawText(recLast, cx, cy + W * 0.12f, text);
        }
        text.setTextSize(W * 0.038f);
        text.setColor(Color.LTGRAY);
        c.drawText("탭 · 길게 누르기 · 베젤", cx, H * 0.72f, text);
        c.drawText("뒤로: 저장하고 끝내기", cx, H * 0.78f, text);
        float ro = W * 0.485f;
        r.set(cx - ro, cy - ro, cx + ro, cy + ro);
        stroke.setStrokeWidth(W * 0.014f);
        stroke.setColor(Color.argb(60, 255, 255, 255));
        c.drawArc(r, 230, 80, false, stroke);
        stroke.setColor(Color.rgb(255, 60, 70));
        float prog = (float) Math.max(0, Math.min(1, t / song.musicEnd()));
        c.drawArc(r, 230, 80 * prog, false, stroke);
        if (t < Song.LEAD_IN) {
            text.setTextSize(W * 0.05f);
            text.setColor(Color.rgb(255, 230, 120));
            c.drawText("곧 시작!", cx, H * 0.88f, text);
        }
    }

    // ---------- 노트 편집 ----------

    private void drawEdit(Canvas c) {
        CustomMap cm = curCustom;
        int col = STYLE_COLOR[cm.style];
        if (edPlayer != null) edT = edPlayer.time() - syncMs / 1000.0;
        double beat = cm.beat();
        float pps = W / 2.4f;
        float laneY = cy;
        glow(c, cx, cy, W * 0.5f, alpha(col, 40));
        // 박자 격자
        double g = beat / 4;
        long k0 = (long) Math.floor((edT - 1.4 - Song.LEAD_IN) / g);
        for (long k = k0; ; k++) {
            double tt = Song.LEAD_IN + k * g;
            float x = cx + (float) ((tt - edT) * pps);
            if (x > W + 5) break;
            if (x < -5 || k < 0) continue;
            boolean barLine = k % 16 == 0, beatLine = k % 4 == 0;
            stroke.setStrokeWidth(barLine ? 3 : 1.5f);
            stroke.setColor(barLine ? Color.argb(160, 255, 255, 255) : beatLine ? Color.argb(90, 255, 255, 255) : Color.argb(35, 255, 255, 255));
            c.drawLine(x, laneY - H * 0.16f, x, laneY + H * 0.16f, stroke);
            if (barLine) {
                text.setTextSize(W * 0.03f);
                text.setColor(Color.GRAY);
                c.drawText(String.valueOf(k / 16 + 1), x, laneY - H * 0.18f, text);
            }
        }
        // 노트 (세 줄: 위 베젤 / 가운데 탭 / 아래 줄)
        for (double[] n : cm.notes) {
            float x = cx + (float) ((n[0] - edT) * pps);
            float xe = cx + (float) ((n[1] - edT) * pps);
            if (xe < -30 || x > W + 30) continue;
            int type = (int) n[2], dir = (int) n[3];
            boolean sel = n == edSel;
            float ny;
            if (type == Note.HOLD) {
                ny = laneY + H * 0.08f;
                stroke.setStrokeWidth(W * 0.045f);
                stroke.setColor(alpha(col, 200));
                c.drawLine(x, ny, xe, ny, stroke);
                p.setColor(Color.WHITE);
                c.drawCircle(x, ny, W * 0.027f, p);
            } else if (type == Note.SPIN) {
                ny = laneY - H * 0.09f;
                stroke.setStrokeWidth(W * 0.014f);
                stroke.setColor(spinColor(dir));
                c.drawCircle(x, ny, W * 0.033f, stroke);
                text.setTextSize(W * 0.04f);
                text.setColor(spinColor(dir));
                c.drawText(dir > 0 ? "↻" : dir < 0 ? "↺" : "○", x, ny + W * 0.014f, text);
            } else {
                ny = laneY;
                p.setColor(col);
                c.drawCircle(x, ny, W * 0.03f, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x, ny, W * 0.014f, p);
            }
            int tr = n.length > 5 ? (int) n[5] : 0;
            if (tr == Note.TR_BOMB) {
                p.setColor(Color.rgb(70, 10, 15));
                c.drawCircle(x, ny, W * 0.03f, p);
                text.setTextSize(W * 0.04f);
                text.setColor(Color.rgb(255, 80, 60));
                c.drawText("✕", x, ny + W * 0.014f, text);
            } else if (tr > 0) {
                text.setTextSize(W * 0.03f);
                text.setColor(trickColor(tr));
                c.drawText(TRICK_SHORT[tr], x, ny + W * (type == Note.SPIN ? -0.045f : 0.065f), text);
            }
            if (sel) {
                stroke.setStrokeWidth(W * 0.008f);
                stroke.setColor(Color.rgb(255, 230, 120));
                c.drawCircle(x, ny, W * 0.048f, stroke);
            }
        }
        // 재생 위치
        stroke.setStrokeWidth(W * 0.008f);
        stroke.setColor(Color.rgb(255, 230, 120));
        c.drawLine(cx, laneY - H * 0.2f, cx, laneY + H * 0.2f, stroke);
        // 선택 노트의 방향 (링 위 위치)
        if (edSel != null && (int) edSel[2] != Note.SPIN) {
            double a = Math.toRadians(edAngle(edSel));
            float ro = W * 0.47f;
            stroke.setStrokeWidth(W * 0.006f);
            stroke.setColor(Color.argb(edAngleMode ? 120 : 50, 255, 255, 255));
            c.drawCircle(cx, cy, ro, stroke);
            p.setColor(edAngleMode ? Color.rgb(255, 230, 120) : col);
            c.drawCircle(cx + (float) Math.cos(a) * ro, cy + (float) Math.sin(a) * ro, W * (edAngleMode ? 0.03f : 0.02f), p);
        }
        // 위: 재생 버튼 / 시간
        p.setColor(Color.argb(180, 20, 20, 30));
        c.drawCircle(cx, H * 0.13f, W * 0.075f, p);
        text.setTextSize(W * 0.06f);
        text.setColor(edPlayer != null ? Color.rgb(255, 90, 100) : Color.rgb(120, 255, 140));
        c.drawText(edPlayer != null ? "■" : "▶", cx, H * 0.15f, text);
        text.setTextSize(W * 0.07f);
        text.setColor(Color.argb(170, 255, 255, 255));
        c.drawText("◀", W * 0.08f, cy + W * 0.025f, text);
        c.drawText("▶", W * 0.92f, cy + W * 0.025f, text);
        double rel = Math.max(0, edT - Song.LEAD_IN);
        int bar = (int) Math.floor(rel / (beat * 4)) + 1;
        text.setTextSize(W * 0.038f);
        text.setColor(Color.WHITE);
        c.drawText(String.format("%d:%05.2f", (int) rel / 60, rel % 60) + " · " + bar + "마디 · 노트 " + cm.notes.size(), cx, H * 0.27f, text);
        text.setTextSize(W * 0.04f);
        text.setColor(Color.rgb(255, 230, 120));
        c.drawText(edPlayer != null ? "재생 중 · 탭하면 멈춤" : edInfo, cx, H * 0.725f, text);
        // 아래 버튼 줄
        int selTr = edSel != null ? (int) edSel[5] : 0;
        String[] labels = {GRID_NAMES[edGrid], "길이", "각도", selTr > 0 ? Note.TRICK_NAMES[selTr] : "트릭", "⇅"};
        boolean[] on = {true, edSel != null && (int) edSel[2] == Note.HOLD, edSel != null && (int) edSel[2] != Note.SPIN,
                edSel != null, notesAtEd().size() > 1};
        for (int k = 0; k < 5; k++) {
            float bx = cx - W * 0.30f + k * W * 0.15f, by = H * 0.83f;
            p.setColor(Color.argb(190, 20, 20, 30));
            c.drawCircle(bx, by, W * 0.055f, p);
            stroke.setStrokeWidth(W * 0.005f);
            boolean act = (k == 2 && edAngleMode) || (k == 3 && selTr > 0);
            stroke.setColor(k == 3 && selTr > 0 ? trickColor(selTr) : act ? Color.rgb(255, 230, 120) : on[k] ? col : Color.argb(70, 255, 255, 255));
            c.drawCircle(bx, by, W * 0.055f, stroke);
            text.setTextSize(W * (labels[k].length() > 2 ? 0.028f : 0.036f));
            text.setColor(on[k] ? Color.WHITE : Color.GRAY);
            c.drawText(labels[k], bx, by + W * 0.012f, text);
        }
        text.setTextSize(W * 0.03f);
        text.setColor(Color.GRAY);
        c.drawText("베젤: 이동 · 길게: 삭제 · 가운데: 추가", cx, H * 0.94f, text);
        // 노트 고르기 창
        if (edPicker || edPatterns) {
            p.setColor(Color.argb(205, 5, 5, 12));
            c.drawRect(0, 0, W, H, p);
            String[] names = edPatterns ? PATTERN_NAMES : PICK_NAMES;
            int[] pc = edPatterns
                    ? new int[]{col, Color.rgb(120, 220, 255), Color.rgb(255, 160, 220), C_CW, Color.rgb(255, 80, 60), Color.rgb(200, 120, 255)}
                    : new int[]{col, col, C_CW, C_CCW, C_ANY, Color.rgb(255, 215, 60)};
            for (int k = 0; k < names.length; k++) {
                float[] pp = pickPos(k, names.length);
                p.setColor(alpha(pc[k], 70));
                c.drawCircle(pp[0], pp[1], W * 0.088f, p);
                stroke.setStrokeWidth(W * 0.008f);
                stroke.setColor(pc[k]);
                c.drawCircle(pp[0], pp[1], W * 0.088f, stroke);
                text.setTextSize(W * (names[k].length() > 3 ? 0.032f : 0.04f));
                text.setColor(Color.WHITE);
                c.drawText(names[k], pp[0], pp[1] + W * 0.013f, text);
            }
            // 가운데: 되돌리기 / 앞 마디 복사
            boolean can = edPatterns || !edUndo.isEmpty();
            p.setColor(Color.argb(200, 30, 30, 45));
            c.drawCircle(cx, cy, W * 0.1f, p);
            stroke.setStrokeWidth(W * 0.006f);
            stroke.setColor(can ? Color.rgb(255, 230, 120) : Color.argb(70, 255, 255, 255));
            c.drawCircle(cx, cy, W * 0.1f, stroke);
            text.setTextSize(W * 0.034f);
            text.setColor(can ? Color.WHITE : Color.GRAY);
            c.drawText(edPatterns ? "앞 마디" : "↶", cx, cy - W * 0.006f, text);
            c.drawText(edPatterns ? "복사" : "되돌리기", cx, cy + W * 0.036f, text);
        }
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
