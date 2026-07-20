package jp.lyricsjp.app;

import android.app.Notification;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NowPlayingListenerService extends NotificationListenerService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager sessionManager;
    private MediaController activeController;
    private int lastWidgetLine = -2;
    private String lastWidgetTrack = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            this::attachControllers;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            MediaController controller = activeController;
            if (controller != null) {
                TrackInfo track = trackFromController(controller);
                if (track != null) {
                    TrackInfo current = AppState.loadTrack(NowPlayingListenerService.this);
                    if (current == null || !current.stableKey().equals(track.stableKey())) {
                        ProcessingCoordinator.enqueue(NowPlayingListenerService.this, track, false);
                    } else {
                        LyricsDocument lyrics = AppState.loadLyrics(NowPlayingListenerService.this);
                        int line = LyricsWidgetProvider.activeLineIndex(track, lyrics);
                        if (!lastWidgetTrack.equals(track.stableKey()) || line != lastWidgetLine) {
                            lastWidgetTrack = track.stableKey();
                            lastWidgetLine = line;
                            LyricsWidgetProvider.updateAll(NowPlayingListenerService.this, track, lyrics);
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 1_000);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(this, NowPlayingListenerService.class);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listener, mainHandler);
            attachControllers(sessionManager.getActiveSessions(listener));
        } catch (SecurityException ignored) {}
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.post(progressTicker);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        NotificationListenerService.requestRebind(new ComponentName(this, NowPlayingListenerService.class));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        super.onNotificationPosted(notification);
        if (!isSupportedPackage(notification.getPackageName())) return;
        refreshControllers();
        if (activeController == null) {
            TrackInfo fallback = trackFromNotification(notification);
            if (fallback != null) ProcessingCoordinator.enqueue(this, fallback, false);
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(progressTicker);
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (Exception ignored) {}
        }
        detachControllers();
        super.onDestroy();
    }

    private void refreshControllers() {
        if (sessionManager == null) return;
        try {
            attachControllers(sessionManager.getActiveSessions(new ComponentName(this, NowPlayingListenerService.class)));
        } catch (SecurityException ignored) {}
    }

    private void attachControllers(List<MediaController> controllers) {
        detachControllers();
        MediaController pausedCandidate = null;
        for (MediaController controller : controllers) {
            if (!isSupportedPackage(controller.getPackageName())) continue;
            MediaController.Callback callback = new ControllerCallback(controller);
            controller.registerCallback(callback, mainHandler);
            callbacks.put(controller, callback);
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                activeController = controller;
                processController(controller);
            } else if (pausedCandidate == null) {
                pausedCandidate = controller;
            }
        }
        if (activeController == null && pausedCandidate != null) {
            activeController = pausedCandidate;
            processController(pausedCandidate);
        }
    }

    private void detachControllers() {
        for (Map.Entry<MediaController, MediaController.Callback> entry : callbacks.entrySet()) {
            try {
                entry.getKey().unregisterCallback(entry.getValue());
            } catch (Exception ignored) {}
        }
        callbacks.clear();
        activeController = null;
    }

    private void processController(MediaController controller) {
        TrackInfo track = trackFromController(controller);
        if (track == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) activeController = controller;
        ProcessingCoordinator.enqueue(this, track, false);
    }

    private TrackInfo trackFromController(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return null;
        String title = firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        );
        String artist = firstNonEmpty(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        );
        if (title.isEmpty() || artist.isEmpty()) return null;
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState state = controller.getPlaybackState();
        long position = 0;
        long updatedAt = SystemClock.elapsedRealtime();
        float speed = 1f;
        boolean playing = false;
        if (state != null) {
            position = Math.max(0, state.getPosition());
            updatedAt = state.getLastPositionUpdateTime() > 0 ? state.getLastPositionUpdateTime() : updatedAt;
            speed = state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1f;
            playing = state.getState() == PlaybackState.STATE_PLAYING;
        }
        return new TrackInfo(
                title,
                artist,
                album,
                controller.getPackageName(),
                duration,
                position,
                updatedAt,
                speed,
                playing
        );
    }

    private TrackInfo trackFromNotification(StatusBarNotification statusBarNotification) {
        Notification notification = statusBarNotification.getNotification();
        if (notification == null || notification.extras == null) return null;
        CharSequence titleValue = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence artistValue = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence albumValue = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String title = titleValue == null ? "" : titleValue.toString();
        String artist = artistValue == null ? "" : artistValue.toString();
        if (title.trim().isEmpty() || artist.trim().isEmpty()) return null;
        return new TrackInfo(
                title,
                artist,
                albumValue == null ? "" : albumValue.toString(),
                statusBarNotification.getPackageName(),
                0,
                0,
                SystemClock.elapsedRealtime(),
                1f,
                true
        );
    }

    private boolean isSupportedPackage(String packageName) {
        return "com.spotify.music".equals(packageName)
                || "com.apple.android.music".equals(packageName);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private final class ControllerCallback extends MediaController.Callback {
        private final MediaController controller;

        private ControllerCallback(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            processController(controller);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            processController(controller);
        }

        @Override
        public void onSessionDestroyed() {
            refreshControllers();
        }
    }
}
