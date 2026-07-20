package jp.lyricsjp.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

public final class AppState {
    public static final String ACTION_CHANGED = "jp.lyricsjp.app.STATE_CHANGED";
    private static final String PREFS = "lyricsjp_state";

    private AppState() {}

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveTrack(Context context, TrackInfo track) {
        try {
            preferences(context).edit().putString("track", track.toJson().toString()).apply();
        } catch (Exception ignored) {}
    }

    public static TrackInfo loadTrack(Context context) {
        try {
            String value = preferences(context).getString("track", "");
            return value == null || value.isEmpty() ? null : TrackInfo.fromJson(new JSONObject(value));
        } catch (Exception error) {
            return null;
        }
    }

    public static void saveLyrics(Context context, LyricsDocument lyrics) {
        try {
            preferences(context).edit().putString("lyrics", lyrics.toJson().toString()).apply();
        } catch (Exception ignored) {}
    }

    public static LyricsDocument loadLyrics(Context context) {
        try {
            String value = preferences(context).getString("lyrics", "");
            return value == null || value.isEmpty() ? null : LyricsDocument.fromJson(new JSONObject(value));
        } catch (Exception error) {
            return null;
        }
    }

    public static void clearLyrics(Context context) {
        preferences(context).edit().remove("lyrics").apply();
    }

    public static void setStatus(Context context, String status) {
        preferences(context).edit().putString("status", status).apply();
        notifyChanged(context);
    }

    public static String getStatus(Context context) {
        return preferences(context).getString("status", "曲を待っています");
    }

    public static String getProvider(Context context) {
        return preferences(context).getString("provider", "gpt");
    }

    public static String getModel(Context context) {
        String provider = getProvider(context);
        return getModel(context, provider);
    }

    public static String getModel(Context context, String provider) {
        String fallback = provider.equals("gemini") ? "gemini-2.5-flash" : "gpt-5.6-sol";
        return preferences(context).getString("model." + provider, fallback);
    }

    public static boolean isAutoTranslate(Context context) {
        return preferences(context).getBoolean("auto_translate", true);
    }

    public static boolean isDeepLFree(Context context) {
        return preferences(context).getBoolean("deepl_free", true);
    }

    public static void saveSettings(Context context, String provider, String model, boolean autoTranslate, boolean deepLFree) {
        preferences(context).edit()
                .putString("provider", provider)
                .putString("model." + provider, model)
                .putBoolean("auto_translate", autoTranslate)
                .putBoolean("deepl_free", deepLFree)
                .apply();
    }

    public static void notifyChanged(Context context) {
        Intent intent = new Intent(ACTION_CHANGED).setPackage(context.getPackageName());
        context.sendBroadcast(intent);
        LyricsWidgetProvider.updateAll(context, null, null);
    }
}
