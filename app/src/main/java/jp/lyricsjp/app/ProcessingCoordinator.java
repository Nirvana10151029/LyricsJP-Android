package jp.lyricsjp.app;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProcessingCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();
    private static String queuedKey = "";

    private ProcessingCoordinator() {}

    public static void enqueue(Context sourceContext, TrackInfo track, boolean force) {
        if (track == null || track.title.isEmpty()) return;
        Context context = sourceContext.getApplicationContext();
        TrackInfo previous = AppState.loadTrack(context);
        boolean changed = previous == null || !previous.stableKey().equals(track.stableKey());
        AppState.saveTrack(context, track);
        if (changed) {
            AppState.clearLyrics(context);
            AppState.setStatus(context, "歌詞を検索しています…");
        } else {
            AppState.notifyChanged(context);
        }
        if (!changed && !force) return;

        String key = track.stableKey();
        synchronized (LOCK) {
            if (!force && queuedKey.equals(key)) return;
            queuedKey = key;
        }
        EXECUTOR.execute(() -> process(context, track, force));
    }

    private static void process(Context context, TrackInfo track, boolean force) {
        try {
            LyricsDocument lyrics = new LyricsRepository().find(track);
            if (!isStillCurrent(context, track)) return;
            AppState.saveLyrics(context, lyrics);
            AppState.setStatus(context, lyrics.synced ? "同期歌詞を取得しました" : "歌詞を取得しました");

            if ((AppState.isAutoTranslate(context) || force)
                    && !SecretStore.hasApiKey(context, AppState.getProvider(context))) {
                AppState.setStatus(context, "歌詞を取得しました。翻訳APIキーを設定してください。");
                return;
            }
            if (AppState.isAutoTranslate(context) || force) {
                AppState.setStatus(context, "日本語へ翻訳しています…");
                new TranslationClient(context).translate(lyrics);
                if (!isStillCurrent(context, track)) return;
                AppState.saveLyrics(context, lyrics);
                AppState.setStatus(context, "自動翻訳が完了しました");
            }
        } catch (Exception error) {
            if (isStillCurrent(context, track)) {
                String message = error.getMessage() == null ? "処理に失敗しました" : error.getMessage();
                AppState.setStatus(context, message);
            }
        } finally {
            synchronized (LOCK) {
                if (queuedKey.equals(track.stableKey())) queuedKey = "";
            }
        }
    }

    private static boolean isStillCurrent(Context context, TrackInfo expected) {
        TrackInfo current = AppState.loadTrack(context);
        return current != null && current.stableKey().equals(expected.stableKey());
    }
}
