package com.grara.beatmaker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.grara.beat.core.AudioPlayer;
import com.grara.beat.core.MapCode;
import com.grara.beat.core.Note;
import com.grara.beat.core.Song;
import com.grara.beat.core.Updater;

import java.util.Random;

/** 폰용 맵 메이커: 설정을 고르고 미리 들어본 뒤 워치에 입력할 코드를 만든다. */
public class MakerActivity extends Activity {
    private static final int ACCENT = Color.rgb(0, 229, 255);

    private Spinner styleSp, diffSp, lenSp, keySp;
    private SeekBar bpmBar;
    private TextView bpmLabel, codeView, infoView, updateView;
    private Switch minorSw;
    private Button playBtn, updateBtn;
    private EditText loadInput;
    private ChartView chart;
    private int seed;
    private boolean suppress;
    private AudioPlayer player;
    private Song song;
    private boolean generating;
    private Updater updater;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        seed = new Random().nextInt(0x3FFFFF);
        updater = new Updater(this, "beat-maker.apk");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(10, 8, 20));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(col);

        TextView title = text("BEAT MAKER", 28, ACCENT, true);
        col.addView(title);
        col.addView(text("비트워치용 맵 만들기 — 코드를 워치의 '코드 입력'에 넣으세요", 13, Color.LTGRAY, false));
        space(col, 12);

        col.addView(label("장르"));
        styleSp = spinner(MapCode.STYLES);
        col.addView(styleSp);

        bpmLabel = label("BPM");
        col.addView(bpmLabel);
        bpmBar = new SeekBar(this);
        bpmBar.setMax((MapCode.BPM_MAX - MapCode.BPM_MIN) / 2);
        col.addView(bpmBar);

        col.addView(label("난이도"));
        diffSp = spinner(MapCode.DIFFS);
        col.addView(diffSp);

        col.addView(label("길이 (마디)"));
        String[] lens = new String[MapCode.BARS.length];
        for (int i = 0; i < lens.length; i++) lens[i] = MapCode.BARS[i] + "마디";
        lenSp = spinner(lens);
        col.addView(lenSp);

        col.addView(label("키"));
        keySp = spinner(MapCode.KEYS);
        col.addView(keySp);
        minorSw = new Switch(this);
        minorSw.setText("단조 (어두운 느낌)");
        minorSw.setTextColor(Color.WHITE);
        minorSw.setChecked(true);
        col.addView(minorSw);

        Button dice = button("🎲 멜로디 바꾸기");
        col.addView(dice);
        dice.setOnClickListener(v -> {
            seed = new Random().nextInt(0x3FFFFF);
            refresh();
        });

        space(col, 16);
        col.addView(label("맵 코드"));
        codeView = text("", 40, Color.WHITE, true);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        col.addView(codeView);
        infoView = text("", 14, Color.LTGRAY, false);
        infoView.setGravity(Gravity.CENTER);
        col.addView(infoView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        playBtn = button("▶ 미리 듣기");
        Button copy = button("코드 복사");
        row.addView(playBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(row);
        playBtn.setOnClickListener(v -> togglePreview());
        copy.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("map code", current().pretty()));
            Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
        });

        chart = new ChartView(this);
        col.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));

        space(col, 16);
        col.addView(label("코드 불러오기"));
        loadInput = new EditText(this);
        loadInput.setHint("예: AB12-CD34-E");
        loadInput.setTextColor(Color.WHITE);
        loadInput.setHintTextColor(Color.GRAY);
        loadInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        col.addView(loadInput);
        Button load = button("불러오기");
        col.addView(load);
        load.setOnClickListener(v -> {
            MapCode m = MapCode.parse(loadInput.getText().toString());
            if (m == null) {
                Toast.makeText(this, "코드가 올바르지 않아요", Toast.LENGTH_SHORT).show();
                return;
            }
            apply(m);
        });

        space(col, 20);
        updateView = text("v" + Updater.currentVersionName(this), 12, Color.GRAY, false);
        col.addView(updateView);
        updateBtn = button("새 버전 설치");
        updateBtn.setVisibility(View.GONE);
        updateBtn.setOnClickListener(v -> updater.installIfReady());
        col.addView(updateBtn);

        setContentView(scroll);

        AdapterView.OnItemSelectedListener sel = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent == styleSp && !suppress) {
                    suppress = true;
                    bpmBar.setProgress((MapCode.STYLE_BPM[position] - MapCode.BPM_MIN) / 2);
                    suppress = false;
                }
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        styleSp.setOnItemSelectedListener(sel);
        diffSp.setOnItemSelectedListener(sel);
        lenSp.setOnItemSelectedListener(sel);
        keySp.setOnItemSelectedListener(sel);
        minorSw.setOnCheckedChangeListener((v, c) -> refresh());
        bpmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                refresh();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        diffSp.setSelection(1);
        lenSp.setSelection(1);
        keySp.setSelection(9);
        bpmBar.setProgress((MapCode.STYLE_BPM[0] - MapCode.BPM_MIN) / 2);
        refresh();
        pollUpdate();
    }

    private void pollUpdate() {
        updater.checkIfDue();
        String st = updater.status();
        updateView.setText(st.isEmpty() ? "v" + Updater.currentVersionName(this) : st);
        updateBtn.setVisibility(updater.isReady() ? View.VISIBLE : View.GONE);
        main.postDelayed(this::pollUpdate, 1000);
    }

    private MapCode current() {
        return new MapCode(styleSp.getSelectedItemPosition(), MapCode.BPM_MIN + bpmBar.getProgress() * 2,
                diffSp.getSelectedItemPosition(), lenSp.getSelectedItemPosition(), keySp.getSelectedItemPosition(),
                minorSw.isChecked(), seed);
    }

    private void apply(MapCode m) {
        suppress = true;
        styleSp.setSelection(m.style);
        diffSp.setSelection(m.diff);
        lenSp.setSelection(m.len);
        keySp.setSelection(m.key);
        minorSw.setChecked(m.minor);
        bpmBar.setProgress((m.bpm - MapCode.BPM_MIN) / 2);
        seed = m.seed;
        suppress = false;
        refresh();
    }

    private void refresh() {
        if (codeView == null || styleSp == null) return;
        MapCode m = current();
        bpmLabel.setText("BPM  " + m.bpm);
        codeView.setText(m.pretty());
        infoView.setText(m.summary() + "\n" + m.keyName() + " · 길이 " + m.lengthText());
        stopPreview();
        song = null;
        chart.setSong(null, null);
    }

    private void togglePreview() {
        if (player != null) {
            stopPreview();
            return;
        }
        if (generating) return;
        final MapCode m = current();
        generating = true;
        playBtn.setText("만드는 중…");
        new Thread(() -> {
            Song s = Song.generate(m);
            main.post(() -> {
                generating = false;
                if (!m.code().equals(current().code())) {
                    playBtn.setText("▶ 미리 듣기");
                    return;
                }
                song = s;
                infoView.setText(m.summary() + "\n" + m.keyName() + " · 길이 " + m.lengthText()
                        + "\n노트 " + s.notes.size() + "개 (탭 " + s.count(Note.TAP) + " · 줄 " + s.count(Note.HOLD) + " · 베젤 " + s.count(Note.SPIN) + ")");
                player = new AudioPlayer(s.pcm);
                player.start();
                chart.setSong(s, player);
                playBtn.setText("■ 정지");
            });
        }).start();
    }

    private void stopPreview() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (chart != null) chart.setSong(song, null);
        if (playBtn != null && !generating) playBtn.setText("▶ 미리 듣기");
    }

    @Override
    protected void onPause() {
        stopPreview();
        super.onPause();
    }

    // ---------- UI 헬퍼 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, ACCENT, true);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private void space(LinearLayout l, int h) {
        View v = new View(this);
        l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    /** 미리 듣기 중 노트가 판정선으로 흘러가는 모습. */
    static final class ChartView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Song song;
        private AudioPlayer player;

        ChartView(Context c) {
            super(c);
        }

        void setSong(Song s, AudioPlayer pl) {
            song = s;
            player = pl;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(Color.rgb(20, 16, 36));
            c.drawRoundRect(0, 0, w, h, 24, 24, p);
            float hitX = w * 0.15f;
            p.setColor(Color.argb(160, 255, 255, 255));
            c.drawRect(hitX - 2, 8, hitX + 2, h - 8, p);
            if (song == null) {
                p.setColor(Color.GRAY);
                p.setTextSize(h * 0.16f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("미리 듣기를 누르면 박자가 보여요", w / 2, h * 0.55f, p);
                return;
            }
            double t = player != null ? player.time() : 0;
            float pps = w / 4f;
            for (Note n : song.notes) {
                float x = hitX + (float) ((n.time - t) * pps);
                float xe = hitX + (float) ((n.end - t) * pps);
                if (xe < -20) continue;
                if (x > w + 20) break;
                if (n.type == Note.HOLD) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.argb(170, 0, 229, 255));
                    c.drawRoundRect(x, h * 0.52f, xe, h * 0.72f, h * 0.1f, h * 0.1f, p);
                    p.setColor(Color.WHITE);
                    c.drawCircle(x, h * 0.62f, h * 0.09f, p);
                } else if (n.type == Note.TAP) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(0, 229, 255));
                    c.drawCircle(x, h * 0.62f, h * 0.09f, p);
                } else {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(h * 0.05f);
                    p.setColor(n.dir > 0 ? Color.rgb(255, 150, 40) : n.dir < 0 ? Color.rgb(190, 90, 255) : Color.rgb(120, 255, 140));
                    c.drawCircle(x, h * 0.36f, h * 0.12f, p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.LTGRAY);
            p.setTextSize(h * 0.12f);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("● 탭  ━ 줄(누르기/베젤)  ○ 베젤 (주황=시계, 보라=반시계)", 16, h * 0.93f, p);
            if (player != null) postInvalidateOnAnimation();
        }
    }
}
