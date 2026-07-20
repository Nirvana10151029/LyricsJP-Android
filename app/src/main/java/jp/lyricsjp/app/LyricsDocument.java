package jp.lyricsjp.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class LyricsDocument {
    public static final class Line {
        public final long timeMs;
        public final String original;
        public String translation;

        public Line(long timeMs, String original) {
            this(timeMs, original, "");
        }

        public Line(long timeMs, String original, String translation) {
            this.timeMs = timeMs;
            this.original = original == null ? "" : original.trim();
            this.translation = translation == null ? "" : translation.trim();
        }
    }

    public final boolean synced;
    public final String provider;
    public final List<Line> lines;

    public LyricsDocument(boolean synced, String provider, List<Line> lines) {
        this.synced = synced;
        this.provider = provider == null ? "LRCLIB" : provider;
        this.lines = new ArrayList<>(lines);
        if (synced) Collections.sort(this.lines, Comparator.comparingLong(line -> line.timeMs));
    }

    public int activeLineIndex(long positionMs) {
        if (lines.isEmpty()) return -1;
        if (!synced) return 0;
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) index = i;
            else break;
        }
        return index;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("synced", synced);
        json.put("provider", provider);
        JSONArray items = new JSONArray();
        for (Line line : lines) {
            JSONObject item = new JSONObject();
            item.put("timeMs", line.timeMs);
            item.put("original", line.original);
            item.put("translation", line.translation);
            items.put(item);
        }
        json.put("lines", items);
        return json;
    }

    public static LyricsDocument fromJson(JSONObject json) {
        List<Line> lines = new ArrayList<>();
        JSONArray items = json.optJSONArray("lines");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) lines.add(new Line(
                        item.optLong("timeMs", -1),
                        item.optString("original"),
                        item.optString("translation")
                ));
            }
        }
        return new LyricsDocument(json.optBoolean("synced"), json.optString("provider", "LRCLIB"), lines);
    }
}
