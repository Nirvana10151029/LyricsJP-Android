package jp.lyricsjp.app;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.Locale;

public final class TrackInfo {
    public final String title;
    public final String artist;
    public final String album;
    public final String packageName;
    public final long durationMs;
    public final long positionMs;
    public final long positionUpdatedAt;
    public final float playbackSpeed;
    public final boolean playing;

    public TrackInfo(
            String title,
            String artist,
            String album,
            String packageName,
            long durationMs,
            long positionMs,
            long positionUpdatedAt,
            float playbackSpeed,
            boolean playing
    ) {
        this.title = clean(title);
        this.artist = clean(artist);
        this.album = clean(album);
        this.packageName = clean(packageName);
        this.durationMs = Math.max(0, durationMs);
        this.positionMs = Math.max(0, positionMs);
        this.positionUpdatedAt = positionUpdatedAt > 0 ? positionUpdatedAt : SystemClock.elapsedRealtime();
        this.playbackSpeed = playbackSpeed > 0 ? playbackSpeed : 1f;
        this.playing = playing;
    }

    public String stableKey() {
        return normalize(title) + "|" + normalize(artist) + "|" + Math.round(durationMs / 1000.0);
    }

    public long estimatedPositionMs() {
        if (!playing) return positionMs;
        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - positionUpdatedAt);
        long value = positionMs + Math.round(elapsed * playbackSpeed);
        return durationMs > 0 ? Math.min(value, durationMs) : value;
    }

    public String sourceLabel() {
        if (packageName.equals("com.spotify.music")) return "Spotify";
        if (packageName.equals("com.apple.android.music")) return "Apple Music";
        return "音楽アプリ";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("title", title);
        json.put("artist", artist);
        json.put("album", album);
        json.put("packageName", packageName);
        json.put("durationMs", durationMs);
        json.put("positionMs", positionMs);
        json.put("positionUpdatedAt", positionUpdatedAt);
        json.put("playbackSpeed", playbackSpeed);
        json.put("playing", playing);
        return json;
    }

    public static TrackInfo fromJson(JSONObject json) {
        return new TrackInfo(
                json.optString("title"),
                json.optString("artist"),
                json.optString("album"),
                json.optString("packageName"),
                json.optLong("durationMs"),
                json.optLong("positionMs"),
                json.optLong("positionUpdatedAt"),
                (float) json.optDouble("playbackSpeed", 1.0),
                json.optBoolean("playing")
        );
    }

    public static String normalize(String value) {
        String text = Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                .replaceAll("(?i)\\b(feat|ft)\\.?\\s+.*$", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return text.replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
