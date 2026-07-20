package jp.lyricsjp.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class LyricsWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        TrackInfo track = AppState.loadTrack(context);
        LyricsDocument lyrics = AppState.loadLyrics(context);
        for (int id : appWidgetIds) manager.updateAppWidget(id, buildViews(context, track, lyrics));
    }

    public static int updateAll(Context context, TrackInfo trackOverride, LyricsDocument lyricsOverride) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, LyricsWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        TrackInfo track = trackOverride != null ? trackOverride : AppState.loadTrack(context);
        LyricsDocument lyrics = lyricsOverride != null ? lyricsOverride : AppState.loadLyrics(context);
        RemoteViews views = buildViews(context, track, lyrics);
        for (int id : ids) manager.updateAppWidget(id, views);
        return activeLineIndex(track, lyrics);
    }

    private static RemoteViews buildViews(Context context, TrackInfo track, LyricsDocument lyrics) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_lyrics);
        Intent openApp = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                100,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pending);

        if (track == null) {
            views.setTextViewText(R.id.widget_title, "LyricsJP");
            views.setTextViewText(R.id.widget_artist, "アプリを開いて通知アクセスを許可してください");
            views.setTextViewText(R.id.widget_original, "");
            views.setTextViewText(R.id.widget_translation, "曲を待っています");
            views.setTextViewText(R.id.widget_footer, "Spotify / Apple Music");
            return views;
        }

        views.setTextViewText(R.id.widget_title, track.title);
        views.setTextViewText(R.id.widget_artist, track.artist);
        String original = "歌詞を検索しています…";
        String translation = AppState.getStatus(context);
        int index = activeLineIndex(track, lyrics);
        if (lyrics != null && index >= 0 && index < lyrics.lines.size()) {
            LyricsDocument.Line line = lyrics.lines.get(index);
            original = line.original;
            translation = line.translation.isEmpty() ? "翻訳しています…" : line.translation;
        }
        views.setTextViewText(R.id.widget_original, original);
        views.setTextViewText(R.id.widget_translation, translation);
        String provider = providerLabel(AppState.getProvider(context));
        views.setTextViewText(R.id.widget_footer, track.sourceLabel() + " · " + provider);
        return views;
    }

    static int activeLineIndex(TrackInfo track, LyricsDocument lyrics) {
        if (track == null || lyrics == null) return -1;
        return lyrics.activeLineIndex(track.estimatedPositionMs());
    }

    private static String providerLabel(String provider) {
        if (provider.equals("gemini")) return "Gemini";
        if (provider.equals("deepl")) return "DeepL";
        return "GPT";
    }
}
