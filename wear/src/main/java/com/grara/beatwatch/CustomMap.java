package com.grara.beatwatch;

import android.content.SharedPreferences;

import com.grara.beat.core.MapCode;
import com.grara.beat.core.Note;
import com.grara.beat.core.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 직접 만든 맵: 기본 음악(맵 코드) + 악기 레이어 + 직접 찍은 노트. */
public final class CustomMap {
    public String id;
    public String name;
    public int style, bpm, diff, len, key, seed;
    public boolean minor;
    public int layers = Song.L_ALL;
    /** 녹음 박자 맞춤: 0 끔, 1 8분음표, 2 16분음표 */
    public int quant = 2;
    /** 노트: {time, end, type, dir} */
    public final ArrayList<double[]> notes = new ArrayList<>();

    public MapCode code() {
        return new MapCode(style, bpm, diff, len, key, minor, seed);
    }

    public void setFrom(MapCode m) {
        style = m.style;
        bpm = m.bpm;
        diff = m.diff;
        len = m.len;
        key = m.key;
        minor = m.minor;
        seed = m.seed;
    }

    public double beat() {
        return 60.0 / bpm;
    }

    /** 노트를 게임용 Note 리스트로 (시간순, 각도 계산) */
    public List<Note> toNotes() {
        ArrayList<double[]> sorted = new ArrayList<>(notes);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        ArrayList<Note> out = new ArrayList<>();
        double beat = beat();
        for (double[] n : sorted) {
            float ang = (float) ((((n[0] - Song.LEAD_IN) / beat) * 45.0 + 270) % 360);
            if (ang < 0) ang += 360;
            out.add(new Note(n[0], Math.max(n[0], n[1]), (int) n[2], (int) n[3], ang));
        }
        return out;
    }

    public void setFromNotes(List<Note> ns) {
        notes.clear();
        for (Note n : ns) notes.add(new double[]{n.time, n.end, n.type, n.dir});
    }

    public double snap(double t) {
        if (quant == 0) return t;
        double g = beat() / (quant == 1 ? 2 : 4);
        return Song.LEAD_IN + Math.round((t - Song.LEAD_IN) / g) * g;
    }

    // ---------- 저장 ----------

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("code", code().code());
        o.put("layers", layers);
        o.put("quant", quant);
        JSONArray a = new JSONArray();
        for (double[] n : notes) {
            a.put(new JSONArray().put(Math.round(n[0] * 1000) / 1000.0).put(Math.round(n[1] * 1000) / 1000.0)
                    .put((int) n[2]).put((int) n[3]));
        }
        o.put("notes", a);
        return o;
    }

    static CustomMap fromJson(JSONObject o) throws Exception {
        CustomMap c = new CustomMap();
        c.id = o.getString("id");
        c.name = o.optString("name", "내 맵");
        MapCode m = MapCode.parse(o.getString("code"));
        if (m == null) m = new MapCode(0, 124, 1, 1, 9, true, 1);
        c.setFrom(m);
        c.layers = o.optInt("layers", Song.L_ALL);
        c.quant = o.optInt("quant", 2);
        JSONArray a = o.optJSONArray("notes");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray n = a.getJSONArray(i);
                c.notes.add(new double[]{n.getDouble(0), n.getDouble(1), n.getInt(2), n.getInt(3)});
            }
        }
        return c;
    }

    static List<CustomMap> loadAll(SharedPreferences prefs) {
        ArrayList<CustomMap> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("custom_maps", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveAll(SharedPreferences prefs, List<CustomMap> maps) {
        try {
            JSONArray a = new JSONArray();
            for (CustomMap c : maps) a.put(c.toJson());
            prefs.edit().putString("custom_maps", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
