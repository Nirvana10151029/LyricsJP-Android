package jp.lyricsjp.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricsRepository {
    private static final String BASE_URL = "https://lrclib.net/api";
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    public LyricsDocument find(TrackInfo track) throws Exception {
        JSONObject exact = fetchExact(track);
        if (exact != null && score(exact, track) >= 70) {
            LyricsDocument parsed = parseEntry(exact);
            if (parsed != null) return parsed;
        }

        String searchUrl = BASE_URL + "/search?track_name=" + encode(track.title)
                + "&artist_name=" + encode(track.artist);
        HttpClient.Response response = HttpClient.get(searchUrl);
        if (!response.isSuccessful()) throw new Exception(remoteError(response));
        JSONArray results = new JSONArray(response.body);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < results.length(); i++) {
            JSONObject candidate = results.optJSONObject(i);
            if (candidate == null) continue;
            int candidateScore = score(candidate, track);
            if (candidateScore > bestScore && hasLyrics(candidate)) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        if (best == null || bestScore < 62) throw new Exception("一致する歌詞が見つかりませんでした。");
        LyricsDocument parsed = parseEntry(best);
        if (parsed == null) throw new Exception("歌詞データが空でした。");
        return parsed;
    }

    private JSONObject fetchExact(TrackInfo track) {
        try {
            StringBuilder url = new StringBuilder(BASE_URL)
                    .append("/get?track_name=").append(encode(track.title))
                    .append("&artist_name=").append(encode(track.artist));
            if (!track.album.isEmpty()) url.append("&album_name=").append(encode(track.album));
            if (track.durationMs > 0) {
                url.append("&duration=").append(String.format(Locale.ROOT, "%.1f", track.durationMs / 1000.0));
            }
            HttpClient.Response response = HttpClient.get(url.toString());
            return response.isSuccessful() ? new JSONObject(response.body) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int score(JSONObject candidate, TrackInfo track) {
        String expectedTitle = TrackInfo.normalize(track.title);
        String expectedArtist = TrackInfo.normalize(track.artist);
        String actualTitle = TrackInfo.normalize(candidate.optString("trackName", candidate.optString("name")));
        String actualArtist = TrackInfo.normalize(candidate.optString("artistName"));
        int score = 0;
        if (actualTitle.equals(expectedTitle)) score += 55;
        else if (!actualTitle.isEmpty() && (actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle))) score += 36;
        if (actualArtist.equals(expectedArtist)) score += 35;
        else if (!actualArtist.isEmpty() && (actualArtist.contains(expectedArtist) || expectedArtist.contains(actualArtist))) score += 22;
        double duration = candidate.optDouble("duration", 0);
        if (duration > 0 && track.durationMs > 0) {
            double difference = Math.abs(duration - track.durationMs / 1000.0);
            if (difference <= 3) score += 18;
            else if (difference <= 8) score += 8;
            else if (difference > 20) score -= 20;
        }
        if (!candidate.optString("syncedLyrics").trim().isEmpty()) score += 5;
        return score;
    }

    private boolean hasLyrics(JSONObject candidate) {
        return candidate.optBoolean("instrumental")
                || !candidate.optString("syncedLyrics").trim().isEmpty()
                || !candidate.optString("plainLyrics").trim().isEmpty();
    }

    private LyricsDocument parseEntry(JSONObject entry) {
        if (entry.optBoolean("instrumental")) {
            List<LyricsDocument.Line> lines = new ArrayList<>();
            lines.add(new LyricsDocument.Line(0, "♪ Instrumental", "♪ インストゥルメンタル"));
            return new LyricsDocument(false, "LRCLIB", lines);
        }
        String synced = entry.optString("syncedLyrics").trim();
        if (!synced.isEmpty()) {
            List<LyricsDocument.Line> lines = parseLrc(synced);
            if (!lines.isEmpty()) return new LyricsDocument(true, "LRCLIB 同期歌詞", lines);
        }
        String plain = entry.optString("plainLyrics").trim();
        if (!plain.isEmpty()) {
            List<LyricsDocument.Line> lines = new ArrayList<>();
            for (String raw : plain.split("\\r?\\n")) {
                String text = raw.trim();
                if (!text.isEmpty()) lines.add(new LyricsDocument.Line(-1, text));
            }
            if (!lines.isEmpty()) return new LyricsDocument(false, "LRCLIB", lines);
        }
        return null;
    }

    private List<LyricsDocument.Line> parseLrc(String text) {
        List<LyricsDocument.Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            Matcher matcher = LRC_TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            while (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = 0;
                if (fraction != null) {
                    if (fraction.length() == 1) millis = Long.parseLong(fraction) * 100;
                    else if (fraction.length() == 2) millis = Long.parseLong(fraction) * 10;
                    else millis = Long.parseLong(fraction.substring(0, 3));
                }
                times.add((minutes * 60 + seconds) * 1000 + millis);
            }
            String lyric = LRC_TIME.matcher(raw).replaceAll("").trim();
            if (lyric.isEmpty()) continue;
            for (long time : times) lines.add(new LyricsDocument.Line(time, lyric));
        }
        return lines;
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String remoteError(HttpClient.Response response) {
        try {
            JSONObject json = new JSONObject(response.body);
            String message = json.optString("message", json.optString("error"));
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {}
        return "LRCLIBへの接続に失敗しました（HTTP " + response.status + "）";
    }
}
