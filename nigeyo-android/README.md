# nigeyo Android

Kotlin と Jetpack Compose の Android アプリです。日本語・英語・韓国語の初期設定、地震・津波ガイド、避難場所、訓練、登録地域の監視を備えています。

## Android Studio で起動

1. リポジトリ内の `nigeyo-android/` を Android Studio の **Open** で開きます。共有素材をビルド時に同梱するため、リポジトリ内の配置を保ってください。
2. SDK Manager で **Android 17 (API 37)** と **Android SDK Build-Tools 36.0.0** を入れ、Gradle JDK に Android Studio の同梱 JDK を選びます。[Android Gradle Plugin の互換性](https://developer.android.com/build/releases/agp-9-5-0-release-notes)
3. Gradle Sync 後に **app** を選択し、Android 8.0 (API 26) 以降のエミュレーターまたは実機で **Run** します。

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

デバッグ APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

## 初期設定と訓練

- 初期設定で言語、自宅・学校の避難場所、通知地域、ロック画面の通知を確認します。ホームや設定から後で変更できます。
- 避難場所は自宅・学校ごとに地震用と津波用を保存できます。現在地、住所検索、地図ピン、緯度・経度から、災害の種類に合う候補を直線距離で検索します。候補検索と保存済みの情報はオフライン対応です。地図表示とGoogle マップでの徒歩経路表示には通信が必要です。利用者が入力した住所や現在地は保存しません。[指定緊急避難場所データ](https://www.gsi.go.jp/bousaichiri/hinanbasho) · [地理院タイル](https://maps.gsi.go.jp/development/ichiran.html)
- 地震訓練は開始から約3秒後に案内を表示します。地震から津波への訓練では、約10秒後に津波へ切り替わります。津波のみの訓練と、ホームの「揺れを感じました」から始める手動案内もあります。
- ロック画面通知を許可すると、行動ガイドのピクトグラムを通知から確認できます。地震の通知上で揺れの収まりを確認すると3枚の安全確認に進みます。避難を開始するか、訓練を終了すると行動ガイド通知を閉じます。表示や操作はAndroidのロック画面設定に従います。
- **ロック画面の壁紙を変更** をオンにして訓練を開始すると、ロック画面を地震用の黄色または津波用の赤色の壁紙に変えます。訓練後は端末の壁紙設定で元の画像を選び直してください。遠隔通知の壁紙変更はロック画面設定で別に有効化できます。
- Mac のデモサーバーの `wss://…/public/device?token=…` を設定の **デモ接続** に入力します。同じサーバーの HTTPS 配信元から、設定の **言語パッケージを更新** で検証済みパックを取り込めます。デモ接続はアプリが稼働している間の試験用です。
- 設定の診断で直近100件の動作記録を確認できます。データの削除はサーバーの通知登録解除を試みてから端末内の設定を消します。

## AWSの遠隔訓練・登録地域の監視

Androidは既存の監視ワーカーが災害種別・登録地域を検証し、端末ごとのキューに保存した通知を認証付き HTTPS で取得します。API は有効な登録だけに `GET /v2/alerts` で期限内の情報を返します。Android が監視を有効にすると常時通知が出て、接続中は約10秒ごとに取得し、障害時は間隔を延ばして再試行します。端末のスリープ、通信、バックグラウンド制限によって受信は遅れる場合があります。端末の緊急速報や自治体などの公式情報を確認してください。FCMの利用規約では緊急警報用途が禁止されています。[FCMの利用条件](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-priority#life-critical-use-cases) · [Androidのフォアグラウンドサービス](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)

検証環境では、既存AWSスタックの自己登録オプションに `AndroidDeliveryEnabled=true` を加えたうえで、その環境の登録APIのホストを Android のビルドに渡します。

```sh
./gradlew :app:assembleDebug -PnigeyoRegistrationHost=example.execute-api.ap-northeast-1.amazonaws.com
```

アプリの **設定 → 通知地域** で地域を確認して監視をオンにします。遠隔訓練の対象には、診断画面のインストールIDを使用します。既存の訓練Lambdaには `{"hazard":"earthquake","installationId":"<ID>"}` または津波用の `hazard: "tsunami"` を渡します。「Android配信待ち」はサーバーがキューへ保存したことを表します。検証では端末のロック画面でも通知を確認してください。利用者へ配布する前にはAPIのスロットリング、電池消費、Androidのバックグラウンド受信、Play Consoleのサービス種別申告を確認します。[AWS側の既存運用手順](../docs/aws-live-alerts.md)
