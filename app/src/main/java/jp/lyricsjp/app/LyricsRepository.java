package jp.lyricsjp.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LyricsRepository {
    private static final String BASE_URL = "https://lrclib.net/api";
    private static final String LYRICS_OVH_URL = "https://api.lyrics.ovh/v1";
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    public LyricsDocument find(TrackInfo track) throws Exception {
        Map<String, JSONObject> candidates = new LinkedHashMap<>();
        JSONObject exact = fetchExact(track);
        if (exact != null && score(exact, track) >= 70
                && !exact.optString("syncedLyrics").trim().isEmpty()) {
            LyricsDocument parsed = parseEntry(exact);
            if (parsed != null) return parsed;
        }
        addCandidate(candidates, exact);

        String simpleTitle = simplifyTitle(track.title);
        String simpleArtist = simplifyArtist(track.artist);
        searchStructured(candidates, track.title, track.artist);
        if (!simpleTitle.equals(track.title) || !simpleArtist.equals(track.artist)) {
            searchStructured(candidates, simpleTitle, simpleArtist);
        }
        searchKeyword(candidates, track.title + " " + track.artist);
        String simpleQuery = (simpleTitle + " " + simpleArtist).trim();
        if (!simpleQuery.equals((track.title + " " + track.artist).trim())) {
            searchKeyword(candidates, simpleQuery);
        }

        JSONObject best = null;
        int bestScore = -1;
        for (JSONObject candidate : candidates.values()) {
            int candidateScore = score(candidate, track);
            if (!candidate.optString("syncedLyrics").trim().isEmpty()) candidateScore += 10;
            if (candidateScore > bestScore && hasLyrics(candidate)) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        if (best != null && bestScore >= 55) {
            LyricsDocument parsed = parseEntry(best);
            if (parsed != null) return parsed;
        }

        LyricsDocument fallback = fetchLyricsOvh(track, simpleTitle, simpleArtist);
        if (fallback != null) return fallback;
        throw new Exception("複数の歌詞サービスで検索しましたが、歌詞が見つかりませんでした。");
    }

    private void searchStructured(Map<String, JSONObject> candidates, String title, String artist) {
        if (title.trim().isEmpty()) return;
        String url = BASE_URL + "/search?track_name=" + encode(title);
        if (!artist.trim().isEmpty()) url += "&artist_name=" + encode(artist);
        addSearchResults(candidates, url);
    }

    private void searchKeyword(Map<String, JSONObject> candidates, String query) {
        if (query.trim().isEmpty()) return;
        addSearchResults(candidates, BASE_URL + "/search?q=" + encode(query));
    }

    private void addSearchResults(Map<String, JSONObject> candidates, String url) {
        try {
            HttpClient.Response response = HttpClient.get(url);
            if (!response.isSuccessful()) return;
            JSONArray results = new JSONArray(response.body);
            for (int i = 0; i < results.length(); i++) {
                addCandidate(candidates, results.optJSONObject(i));
            }
        } catch (Exception ignored) {}
    }

    private void addCandidate(Map<String, JSONObject> candidates, JSONObject candidate) {
        if (candidate == null || !hasLyrics(candidate)) return;
        String id = candidate.optString("id");
        String key = id.isEmpty()
                ? TrackInfo.normalize(candidate.optString("trackName")) + "|"
                + TrackInfo.normalize(candidate.optString("artistName")) + "|"
                + Math.round(candidate.optDouble("duration", 0))
                : id;
        candidates.putIfAbsent(key, candidate);
    }

    private LyricsDocument fetchLyricsOvh(TrackInfo track, String simpleTitle, String simpleArtist) {
        Set<String> titles = new LinkedHashSet<>();
        Set<String> artists = new LinkedHashSet<>();
        titles.add(track.title);
        titles.add(simpleTitle);
        artists.add(track.artist);
        artists.add(simpleArtist);
        for (String artist : artists) {
            if (artist.trim().isEmpty()) continue;
            for (String title : titles) {
                if (title.trim().isEmpty()) continue;
                try {
                    String url = LYRICS_OVH_URL + "/" + encodePath(artist) + "/" + encodePath(title);
                    HttpClient.Response response = HttpClient.get(url);
                    if (!response.isSuccessful()) continue;
                    String plain = new JSONObject(response.body).optString("lyrics").trim();
                    LyricsDocument parsed = parsePlainLyrics(plain, "Lyrics.ovh 通常歌詞");
                    if (parsed != null) return parsed;
                } catch (Exception ignored) {}
            }
        }
        return null;
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
            LyricsDocument parsed = parsePlainLyrics(plain, "LRCLIB 通常歌詞");
            if (parsed != null) return parsed;
        }
        return null;
    }

    private LyricsDocument parsePlainLyrics(String plain, String provider) {
        if (plain == null || plain.trim().isEmpty()) return null;
        List<LyricsDocument.Line> lines = new ArrayList<>();
        for (String raw : plain.split("\\r?\\n")) {
            String text = raw.trim();
            if (!text.isEmpty()) lines.add(new LyricsDocument.Line(-1, text));
        }
        return lines.isEmpty() ? null : new LyricsDocument(false, provider, lines);
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

    private String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private String simplifyTitle(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        text = text.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", " ");
        text = text.replaceAll("(?i)\\s+-\\s+(?:remaster(?:ed)?|live|radio edit|edit|version|mono|stereo|mix).*$", " ");
        text = text.replaceAll("(?i)\\b(?:feat(?:uring)?|ft)\\.?\\s+.*$", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private String simplifyArtist(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        text = text.replaceAll("(?i)\\s+(?:feat(?:uring)?|ft)\\.?\\s+.*$", " ");
        String[] artists = text.split("[,、]", 2);
        return artists[0].replaceAll("\\s+", " ").trim();
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
