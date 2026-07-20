package jp.lyricsjp.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String[] PROVIDER_LABELS = {"GPT 自然訳", "Gemini 自然訳", "DeepL 翻訳"};
    private static final String[] PROVIDER_VALUES = {"gpt", "gemini", "deepl"};

    private TextView accessStatus;
    private TextView currentTrack;
    private TextView currentArtist;
    private TextView processStatus;
    private TextView lyricsText;
    private ScrollView mainScroll;
    private Spinner providerSpinner;
    private EditText apiKeyInput;
    private EditText modelInput;
    private CheckBox autoTranslate;
    private CheckBox deepLFree;
    private boolean receiverRegistered;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int[] lyricOffsets = new int[0];
    private int renderedLyricsSignature;
    private int lastActiveLine = Integer.MIN_VALUE;

    private final Runnable lyricTicker = new Runnable() {
        @Override
        public void run() {
            updateActiveLyric();
            uiHandler.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureProviderSpinner();
        loadSettings();
        configureButtons();
        registerStateReceiver();
        renderState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessStatus();
        renderState();
        uiHandler.removeCallbacks(lyricTicker);
        uiHandler.post(lyricTicker);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(lyricTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(stateReceiver);
        super.onDestroy();
    }

    private void bindViews() {
        mainScroll = findViewById(R.id.main_scroll);
        accessStatus = findViewById(R.id.access_status);
        currentTrack = findViewById(R.id.current_track);
        currentArtist = findViewById(R.id.current_artist);
        processStatus = findViewById(R.id.process_status);
        lyricsText = findViewById(R.id.lyrics_text);
        providerSpinner = findViewById(R.id.provider_spinner);
        apiKeyInput = findViewById(R.id.api_key_input);
        modelInput = findViewById(R.id.model_input);
        autoTranslate = findViewById(R.id.auto_translate_checkbox);
        deepLFree = findViewById(R.id.deepl_free_checkbox);
    }

    private void configureProviderSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PROVIDER_LABELS
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(adapter);
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String provider = PROVIDER_VALUES[position];
                deepLFree.setVisibility(provider.equals("deepl") ? View.VISIBLE : View.GONE);
                modelInput.setVisibility(provider.equals("deepl") ? View.GONE : View.VISIBLE);
                if (!provider.equals("deepl")) modelInput.setText(AppState.getModel(MainActivity.this, provider));
                apiKeyInput.setText("");
                updateKeyHint(provider);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadSettings() {
        String provider = AppState.getProvider(this);
        int selection = 0;
        for (int i = 0; i < PROVIDER_VALUES.length; i++) {
            if (PROVIDER_VALUES[i].equals(provider)) selection = i;
        }
        providerSpinner.setSelection(selection);
        modelInput.setText(AppState.getModel(this));
        autoTranslate.setChecked(AppState.isAutoTranslate(this));
        deepLFree.setChecked(AppState.isDeepLFree(this));
        updateKeyHint(provider);
    }

    private void configureButtons() {
        Button access = findViewById(R.id.grant_access_button);
        access.setOnClickListener(view -> showNotificationAccessDisclosure());

        Button pinWidget = findViewById(R.id.pin_widget_button);
        pinWidget.setOnClickListener(view -> requestWidgetPin());

        Button save = findViewById(R.id.save_settings_button);
        save.setOnClickListener(view -> saveSettings());

        Button reprocess = findViewById(R.id.reprocess_button);
        reprocess.setOnClickListener(view -> {
            TrackInfo track = AppState.loadTrack(this);
            if (track == null) {
                Toast.makeText(this, "先にSpotifyまたはApple Musicで曲を再生してください", Toast.LENGTH_LONG).show();
            } else {
                ProcessingCoordinator.enqueue(this, track, true);
            }
        });

        Button privacy = findViewById(R.id.privacy_button);
        privacy.setOnClickListener(view -> startActivity(new Intent(this, PrivacyActivity.class)));
    }

    private void saveSettings() {
        int position = providerSpinner.getSelectedItemPosition();
        String provider = PROVIDER_VALUES[Math.max(0, position)];
        String model = modelInput.getText().toString().trim();
        if (!provider.equals("deepl") && model.isEmpty()) {
            model = provider.equals("gemini") ? "gemini-2.5-flash" : "gpt-5.6-sol";
        }
        String key = apiKeyInput.getText().toString().trim();
        try {
            if (!key.isEmpty()) SecretStore.saveApiKey(this, provider, key);
            AppState.saveSettings(this, provider, model, autoTranslate.isChecked(), deepLFree.isChecked());
            apiKeyInput.setText("");
            updateKeyHint(provider);
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show();
            AppState.notifyChanged(this);
        } catch (Exception error) {
            Toast.makeText(this, "APIキーを保存できませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private void requestWidgetPin() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, LyricsWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            Intent successIntent = new Intent(this, MainActivity.class);
            PendingIntent success = PendingIntent.getActivity(
                    this,
                    200,
                    successIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            manager.requestPinAppWidget(provider, null, success);
        } else {
            Toast.makeText(this, "ホーム画面を長押ししてLyricsJPを追加してください", Toast.LENGTH_LONG).show();
        }
    }

    private void showNotificationAccessDisclosure() {
        new AlertDialog.Builder(this)
                .setTitle("通知へのアクセスについて")
                .setMessage("曲の切り替わりを自動検知するため、通知へのアクセスを使用します。SpotifyとApple Musicの曲名・アーティスト・再生状態だけを処理し、他の通知内容は保存・送信しません。曲情報は歌詞検索のためLRCLIBへ送信され、同期歌詞が見つからない場合はlrc mux、通常歌詞も見つからない場合はLyrics.ovhへ送信されます。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("設定を開く", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .show();
    }

    private void updateKeyHint(String provider) {
        boolean saved = SecretStore.hasApiKey(this, provider);
        apiKeyInput.setHint(saved
                ? PROVIDER_LABELS[Math.max(0, providerSpinner.getSelectedItemPosition())] + "のAPIキーは保存済みです"
                : "APIキー（端末内で暗号化して保存）");
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(AppState.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
        receiverRegistered = true;
    }

    private void updateAccessStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean granted = enabled != null && enabled.contains(getPackageName());
        accessStatus.setText(granted
                ? "✓ 自動曲検知が有効です"
                : "自動検知には「通知へのアクセス」の許可が必要です");
    }

    private void renderState() {
        TrackInfo track = AppState.loadTrack(this);
        LyricsDocument lyrics = AppState.loadLyrics(this);
        processStatus.setText(AppState.getStatus(this));
        if (track == null) {
            currentTrack.setText("曲を待っています");
            currentArtist.setText("SpotifyまたはApple Musicを再生してください");
            lyricsText.setText("");
            lyricOffsets = new int[0];
            renderedLyricsSignature = 0;
            lastActiveLine = Integer.MIN_VALUE;
            return;
        }
        currentTrack.setText(track.title);
        String detail = track.artist;
        if (!track.album.isEmpty()) detail += "  ·  " + track.album;
        detail += "  ·  " + track.sourceLabel();
        currentArtist.setText(detail);
        if (lyrics == null) {
            lyricsText.setText("");
            lyricOffsets = new int[0];
            renderedLyricsSignature = 0;
            lastActiveLine = Integer.MIN_VALUE;
            return;
        }
        int activeLine = LyricsWidgetProvider.activeLineIndex(track, lyrics);
        renderLyrics(lyrics, activeLine, false);
    }

    private void updateActiveLyric() {
        TrackInfo track = AppState.loadTrack(this);
        LyricsDocument lyrics = AppState.loadLyrics(this);
        if (track == null || lyrics == null || lyrics.lines.isEmpty()) return;
        int activeLine = LyricsWidgetProvider.activeLineIndex(track, lyrics);
        int signature = lyricsSignature(lyrics);
        if (signature != renderedLyricsSignature || activeLine != lastActiveLine) {
            renderLyrics(lyrics, activeLine, activeLine != lastActiveLine);
        }
    }

    private void renderLyrics(LyricsDocument lyrics, int activeLine, boolean scrollToLine) {
        SpannableStringBuilder body = new SpannableStringBuilder();
        lyricOffsets = new int[lyrics.lines.size()];
        java.util.Arrays.fill(lyricOffsets, -1);
        int activeStart = -1;
        int activeEnd = -1;

        for (int i = 0; i < lyrics.lines.size(); i++) {
            LyricsDocument.Line line = lyrics.lines.get(i);
            if (line.original.isEmpty()) continue;
            lyricOffsets[i] = body.length();
            if (i == activeLine) activeStart = body.length();
            body.append(line.original).append('\n');
            if (!line.translation.isEmpty()) body.append(line.translation).append('\n');
            if (i == activeLine) activeEnd = body.length();
            body.append('\n');
        }

        if (body.length() > 0 && body.charAt(body.length() - 1) == '\n') {
            body.delete(body.length() - 1, body.length());
        }
        if (activeStart >= 0 && activeEnd > activeStart) {
            body.setSpan(
                    new ForegroundColorSpan(getColor(R.color.primary)),
                    activeStart,
                    Math.min(activeEnd, body.length()),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    activeStart,
                    Math.min(activeEnd, body.length()),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        lyricsText.setText(body);
        renderedLyricsSignature = lyricsSignature(lyrics);
        int previousActiveLine = lastActiveLine;
        lastActiveLine = activeLine;
        if (scrollToLine && activeLine >= 0 && activeLine != previousActiveLine) {
            lyricsText.post(() -> scrollToLyric(activeLine));
        }
    }

    private void scrollToLyric(int index) {
        if (index < 0 || index >= lyricOffsets.length || lyricOffsets[index] < 0) return;
        Layout layout = lyricsText.getLayout();
        if (layout == null || lyricsText.length() == 0) return;
        int offset = Math.min(lyricOffsets[index], lyricsText.length() - 1);
        int textLine = layout.getLineForOffset(offset);
        int targetY = lyricsText.getTop() + layout.getLineTop(textLine) - mainScroll.getHeight() / 3;
        mainScroll.smoothScrollTo(0, Math.max(0, targetY));
    }

    private int lyricsSignature(LyricsDocument lyrics) {
        int result = lyrics.synced ? 1 : 0;
        result = 31 * result + lyrics.provider.hashCode();
        for (LyricsDocument.Line line : lyrics.lines) {
            result = 31 * result + Long.hashCode(line.timeMs);
            result = 31 * result + line.original.hashCode();
            result = 31 * result + line.translation.hashCode();
        }
        return result;
    }
}
