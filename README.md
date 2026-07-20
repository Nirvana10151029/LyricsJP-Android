# LyricsJP for Android

SpotifyまたはApple Musicで再生中の曲を自動検知し、歌詞と日本語訳をアプリ／ホーム画面ウィジェットへ表示するAndroidアプリです。

## 主な機能

- Spotify／Apple Musicの曲変更を自動検知
- Spotify Developer登録や曲の共有操作は不要
- LRCLIBの同期歌詞を優先し、表記違いを含む複数検索で誤一致と取りこぼしを抑制
- LRCLIBに同期歌詞がない場合はlrc muxで複数の公開プロバイダーを横断検索
- LRCLIBで見つからない場合はLyrics.ovhの通常歌詞へ自動切り替え
- GPT／Gemini／DeepLを切り替え可能
- 曲が変わると歌詞取得と翻訳を自動実行
- 同期歌詞は再生位置に合わせてウィジェットを行送り
- APIキーはAndroid Keystoreを使って端末内で暗号化
- 広告・解析SDK・開発者サーバーなし

## 対応環境

- Android 8.0（API 26）以上
- Android Studio
- JDK 17（Android Studio同梱で可）
- compileSdk / targetSdk 36

## Windowsで実行する

1. Android Studioをインストールする。
2. この`LyricsJP-Android`フォルダーを開く。
3. SDK 36のインストールを求められたら許可する。
4. Android端末で「開発者向けオプション」→「USBデバッグ」を有効にする。
5. USBで接続し、Android Studio上部の実行ボタンを押す。
6. アプリで「通知へのアクセスを許可」を押し、LyricsJPをオンにする。
7. GPT／Gemini／DeepLを選び、そのサービスのAPIキーを入力する。
8. SpotifyまたはApple Musicで曲を再生する。
9. 「ホーム画面にウィジェットを追加」を押す。

APIキーをこのプロジェクトやGitHubへ書き込まないでください。ChatGPT Plus等の月額契約とOpenAI API料金は別です。

## GitHubで無料ビルドする

このリポジトリにはGitHub ActionsのAPKビルド設定が含まれています。GitHubの`Actions`タブで`Build Android APK`を開き、完了した実行の`Artifacts`から`LyricsJP-Android-debug`をダウンロードできます。

これは動作確認用のデバッグAPKです。一般公開の更新版を継続配布するときは、同じ秘密署名鍵で署名したリリースAPKを使ってください。署名鍵やAPIキーをリポジトリへ追加してはいけません。

## Google Play用のAABを作る

Android Studioで`Build`→`Generate Signed App Bundle or APK`→`Android App Bundle`を選びます。初回は署名鍵（`.jks`）を作成し、安全な場所へ複数バックアップしてください。同じアプリの更新に同じアップロード鍵が必要です。

公開前に必ず次を変更してください。

- `app/build.gradle.kts`の`applicationId`、`versionCode`、`versionName`
- `PrivacyActivity.java`の問い合わせ先
- `PRIVACY_POLICY.md`の開発者名・メールアドレス・公開URL
- アイコン、スクリーンショット、ストア説明

公開作業は[PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md)を参照してください。

## 注意点

- 端末メーカーの省電力設定により自動検知が止まる場合は、LyricsJPのバッテリー最適化を解除してください。
- 再生アプリがMediaSessionへ正しい曲情報を公開しない場合、検知や同期位置がずれることがあります。
- 翻訳APIの利用料金・上限は各サービスのアカウントに適用されます。
- LRCLIBや翻訳サービスの規約、歌詞の権利、Google Playのポリシーは公開者自身でも確認してください。

## ビルド構成

外部Androidライブラリを使わず、Android標準APIとJava 17だけで構成しています。ビルドにはAGP 8.13.2／Gradle 8.13を使用します。

## 検証状況

Java 12ファイルとAndroid XML 10ファイルの構文検証、秘密情報の混入確認、Gradle Wrapperの起動確認を実施済みです。この生成環境にはAndroid SDKがなく、外部ダウンロードも制限されているため、`assembleDebug`によるAPKの最終ビルドと実機検証は未実施です。Android Studioで初回同期後、まず内部テスト用APKを実機で確認してください。

## ライセンス

アプリのソースコードはMIT Licenseです。第三者サービスから取得する歌詞や翻訳結果に、このライセンスは適用されません。
