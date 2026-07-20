package jp.lyricsjp.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class PrivacyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy);
        TextView text = findViewById(R.id.privacy_text);
        text.setText(
                "LyricsJPは、再生中の曲を検知して歌詞と日本語訳を表示するアプリです。\n\n"
                + "取得する情報\n"
                + "通知へのアクセスを利用し、SpotifyまたはApple Musicが表示する曲名、アーティスト名、アルバム名、再生状態を読み取ります。他の通知本文は保存・送信しません。\n\n"
                + "外部サービスへの送信\n"
                + "歌詞検索のため、曲名・アーティスト名・アルバム名・再生時間をLRCLIBへ送信します。LRCLIBで見つからない場合は、曲名とアーティスト名をLyrics.ovhへ送信します。翻訳を有効にした場合、取得した歌詞を利用者が選択したOpenAI、Google GeminiまたはDeepLのAPIへ送信します。各サービスの規約とプライバシーポリシーも適用されます。\n\n"
                + "APIキーと保存データ\n"
                + "APIキーはAndroid Keystoreを使って端末内で暗号化します。曲情報、歌詞、翻訳結果、設定は端末内だけに保存し、開発者のサーバーには送信しません。アプリを削除すると保存データも削除されます。\n\n"
                + "第三者提供・広告\n"
                + "広告SDK、解析SDK、開発者独自のサーバーは使用しません。上記APIへの処理に必要な送信を除き、データを販売・共有しません。\n\n"
                + "問い合わせ先\n"
                + "Google Play公開前に、開発者の連絡先メールアドレスをここへ記載します。"
        );
    }
}
