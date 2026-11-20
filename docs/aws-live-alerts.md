# AWSで実災害の通知を受ける

ECS FargateのワーカーがP2P地震情報のWebSocketに常時接続し、既存の登録地域判定で
緊急地震速報と津波警報・注意報を選別する。対象の警報はAPNs経由でiPhoneに表示する。
通知をタップすると、iPhoneが有効期限と現在の登録地域を照合し、緊急地震速報または
津波警報のガイドを訓練ラベルなしで開く。津波注意報は通知のみ。
手動の訓練送信は[別のLambda](aws-training-push.md)で行う。

## TestFlightの4台を登録する

1. 下の手順でAWSに配置し、`AppRegistrationEnabled=true` を設定する。スタック出力
   `LiveRegistrationUrl` のホスト名を `nigeyo-ios/Local.xcconfig` の
   `NIGEYO_LIVE_REGISTRATION_HOST` に設定する。Push Notifications対応のApp IDで
   `NIGEYO_APNS_ENTITLEMENTS = Config/Nigeyo.entitlements` を有効にしてTestFlightへ配布する。
2. 各iPhoneで通知とロック画面表示を許可する。「設定 → 診断ログ」で **Register for APNs** を
   実行し、**Copy APNs device token** で端末トークンを取得する。
3. `LiveSecretArn` で指定したSecrets Managerシークレットに、APNsの `teamId`、`keyId`、
   `privateKey`、`bundleId` と、端末の `devices` を登録する。最大4台で、`id` と
   `deviceToken` はそれぞれ一意にする。下の `deviceToken` は各端末で取得した値に置き換える。

   ```json
   [
     {"id":"tokyo","deviceToken":"TOKEN_FROM_TOKYO_IPHONE"},
     {"id":"osaka","deviceToken":"TOKEN_FROM_OSAKA_IPHONE"},
     {"id":"nagoya","deviceToken":"TOKEN_FROM_NAGOYA_IPHONE"},
     {"id":"hakata","deviceToken":"TOKEN_FROM_HAKATA_IPHONE"}
   ]
   ```

4. 各iPhoneで「設定 → 通知対象地域」を開き、自宅・大学の区域名を確認する。
   「自動監視を有効にする」をオンにして、「本番通知の配信先に登録済み」の表示を確認する。
   AWSはこの区域を訓練と本番の両方に使う。同じ地域を自宅と大学に登録しても通知は端末につき1件。
5. 同じ画面で本番用の地震・津波ショートカットを追加する。ショートカットアプリで
   対象をNigeyoにして「すぐに実行」を選び、手動で一度ずつ実行して壁紙変更を許可する。
   [ショートカットの手順](shortcuts.md#本番用ショートカット)
6. [訓練通知](aws-training-push.md#4台で確認する)で対象端末だけが受信することを確認した後、
   `LiveEnabled=true` で本番フィードの監視を開始する。

切替をオフにすると、そのiPhoneはAWSの地域別訓練と本番配信の両方から外れる。
訓練は最新の登録を直接参照する。稼働中の本番ワーカーは約3秒ごとに登録を取り込み、
送信の直前にも照合する。端末やトークンを交換したらシークレットを更新し、ECSタスクを
再起動した上で対象端末から登録し直す。トークンは購読更新の認証にも使うため、AWSに登録する
担当者にだけ渡す。

以前の単一端末構成はトップレベルの `deviceToken` を引き続き利用できる。
`devices` に移行した場合は各iPhoneで自動監視をオンにし直す。`AppRegistrationEnabled=false`
の従来の手動本番構成では、`deviceToken` と診断ログからコピーした `subscription` を使う。

## ロック中のLive Activity

対応するTestFlight版を一度起動し、iPhoneでLive Activityを許可します。自動監視が有効な端末は
通常通知用トークンに加えて開始用トークンも登録します。コード更新後はLambda、登録API、
ECSワーカーを更新してください。設定済みの端末はアプリを開くと開始用トークンを登録し直します。

- 地域別訓練では、対象端末のカードに訓練ラベルが出ます。本番の緊急地震速報、津波警報・
  大津波警報のカードには訓練ラベルが付きません。津波注意報は通常通知のみです。
- 壁紙ショートカットには従来の通常通知を使います。ActivityKitの開始プッシュにも表示用の
  アラートが付くため、ロック中の通知の見え方と鳴動を実機で確認してください。
- 実機では、登録地域に一致する端末をロックして地震訓練を送り、カードの表示、ボタンで
  安全確認への遷移、通常通知タップ後の同一ガイドへの合流を確認します。対象外端末と、
  Live Activityを無効にした端末も確認してください。本番の取消と津波注意報への引き下げは
  隔離したテスト環境で再現してください。

## AWSに配置する

リポジトリのルートからDockerイメージをビルドし、デプロイ先と同じリージョンの
既存ECRリポジトリにプッシュする。

```sh
worker_image='ECRリポジトリのURI:regional-worker'
docker buildx build --platform linux/amd64 -f aws/Dockerfile.regional -t "$worker_image" --push .
```

訓練と同じ [template.yaml](../aws/template.yaml) を [SAMの手順](aws-training-push.md#初回セットアップ)で
配置する。 `WorkerImage` はECRのダイジェスト付きURI、`LiveSecretArn` は監視用シークレットのARN。
`VpcId` と、インターネットゲートウェイへの経路がある `PublicSubnet` を指定する。
最初は `LiveEnabled=false` のまま訓練を確認し、実配信の開始時に同じスタックを
`LiveEnabled=true` に更新する。監視の有効化は発表用端末と対象地域を確認してから行う。

ECSは監視タスクを1個起動する。ワーカーは外部からの接続を受け付けず、端末ごとの判定状態と
送信待ちの通知を非公開・暗号化済みS3バケットの `regional-monitor.json` に保存する。
同時に複数の監視タスクを起動しない。状態の読み込みに失敗すると起動を中止し、
保存に失敗した新規警報はAPNsへ渡さずCloudWatch Logsに記録する。
スタックを削除しても状態バケットは保持する。

## 確認方法と範囲

```sh
npm ci --prefix aws
npm ci --prefix aws/apns-training
node --test aws/apns-training/index.test.mjs aws/live-registration.test.mjs aws/regional-worker.test.mjs
sam validate --lint --template-file aws/template.yaml
```

ローカルテストでは、警報の再生を模擬フィードと模擬APNsの間だけで行う。
対象地域の警報が送信され、対象外、訓練フラグ付き、期限切れ、重複、取り消しが
警報として送信されないこと、再起動後も保存済みの状態が使われることを確認する。実機では署名済みの同じビルドを使い、
隔離したテスト環境で、ロック中の表示とタップ後のガイドを別々に確認する。

対象は緊急地震速報（556）と津波警報・注意報（552）。地震情報（551）は壁紙変更を起動しない。
地域は[区域ID付きの購読設定](notification-region-data.md)で照合する。
単一タスク・単一サブネットのため更新時などに短い監視中断があり、
フィード切断中の警報は取り直さない。[P2P地震情報の仕様](https://www.p2pquake.net/develop/json_api_v2/)では配信品質が無保証で、障害時の再配信もない。APNs受付は端末への到着を保証しない。また、APNsが受け付けてから送信済みの状態を保存するまでに障害が起きると、再送する可能性がある。
期限は発表時刻から2分で、APNsに渡した通知を後から取り消す経路はない。
壁紙切り替えは別途実機で検証する。

## DynamoDBによる端末の自己登録

自己登録を使うスタックは `AppRegistrationEnabled=true` と
`SelfRegistrationEnabled=true` を指定する。登録APIのホストは
`LiveRegistrationUrl` のホスト部分を iOS の `NIGEYO_LIVE_REGISTRATION_HOST`
に設定する。新しいiOSビルドはこのスタックの `/v2/` を使う。APNs署名シークレットには
`teamId`・`keyId`・`privateKey`・`bundleId` を設定する。端末の追加作業は不要。

利用者が通知をオンにすると、iPhoneはAPNsの通知先を取得し、アプリのインストール単位で
登録する。登録の認証情報は端末のKeychainに保存する。地域を変更して通知をオンにし直すと
新しい地域で登録される。通知をオフにした場合はサーバーの登録も削除する。
ネットワークエラー時は画面から再試行できる。有効な登録のトークンや設定は、
アプリが前面に戻ったときにも同期する。

ワーカーは警報の受信時にDynamoDBから有効な購読を取得し、送信の直前に
対象端末の設定を再確認する。監視状態と送信待ちは端末単位で保存する。監視ワーカーは
1タスクで稼働させる。1台の端末で受け取れない場合に備え、訓練はテストする端末だけを
対象とする。AWSコンソールまたは既存のIAM運用経路から訓練Lambdaを呼び出すときは、
次の形式でDynamoDBの `kind=installation` の該当 `id` を指定する。

```json
{"hazard":"earthquake","installationId":"端末のインストールID"}
```

### 既存の手動登録からの切り替え

1. 既存スタックを残し、自己登録用のスタックを `LiveEnabled=false` で用意する。
2. 既存端末を新スタックのホストを設定したビルドに更新する。通知が有効な端末は
   起動時に端末内の地域設定を自己登録先に同期する。通知が無効な端末は設定画面で
   通知をオンにする。
3. 既存端末と新しい端末で登録済み表示を確認し、端末を指定した訓練通知を試す。
4. 旧ワーカーの実配信を停止し、新ワーカーの実配信を開始する。切替前の保留中の警報は
   新環境へ持ち越さない。切り戻すときは新ワーカーを止めてから旧ワーカーを再開する。
   旧版アプリ側での設定変更が必要な場合は、旧スタックを指すビルドを使用する。

## 配信の復旧と運用

自己登録モードでは、各端末の警報状態と送信待ちを同じDynamoDB項目に保存してから
配信を始める。ワーカーはDBの送信待ちを再起動時に読み込み、通常通知、Live Activity開始、
Live Activity終了ごとに保存した配信結果と再試行時刻を使う。DynamoDBだけで警報状態と
送信待ちを同じ書き込みで扱えるため、別キューとの二重書き込みは不要。SQSは監視と配信を
別サービスで動かす必要が出た段階で再検討する。

- 配信は最大32端末ずつ処理する。通常通知とLive Activityはそれぞれの結果を記録する。
- 送信直前に警報の期限・改訂・取消、端末の購読と通知先を確認する。期限は発表から2分。
  通信断や送信制限での再試行はバックオフし、期限内に限る。APNsが5xxを返した場合は
  Appleが推奨する再試行間隔が通知期限を超えるため、失敗を記録して終了する。
  すでにAPNsに渡した通知を取り消す経路はない。
  [APNsの応答仕様](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns)
- 受け付けた結果の保存後にワーカーが停止しても、その配信経路は再送しない。APNs受付と
  結果保存の間で停止すると、同じリクエストIDで再送する可能性がある。
- 端末のAPNsトークン失効を認識すると、その時点のトークンへの配信を停止する。
  端末から後に更新されたトークンは、古い失効応答で無効化しない。

ワーカーはWebSocketのpingに応答がなければ再接続し、間隔は最大1分まで広げる。
タスク自体の応答が止まった場合はECSがループバックのヘルスチェックで検知する。
デプロイは1タスクを入れ替えるため短い監視中断が生じる。発表済みの警報状態はDBから
再開する。監視中断中にフィードから受け取れなかった警報は取り直せない。
[配信元の仕様](https://www.p2pquake.com/develop/json_api_v2/)

### 障害を調べる

本番監視を有効にしたスタックで、CloudWatchの `Nigeyo/Worker` に1分ごとに
生存・フィード接続・配信失敗・最も古い送信待ちを記録する。運用者が通知を受け取る
既存のSNSトピックを `OperationsAlarmTopicArn` に指定する。監視を停止した検証スタックでは
アラームの通知アクションも停止する。CloudWatch Logsは14日保持する。

1. **生存アラーム**では ECS のサービスイベントとタスクの停止理由を確認する。
   イメージの取得・起動に失敗した場合は、利用可能な直前のイメージに戻す。
2. **配信アラーム**では CloudWatch Logs のイベントIDと通知種別を検索する。
   フィード断は再接続ログ、署名エラーは Secrets Manager のAPNs設定とバージョン、
   滞留はDynamoDBへのアクセスとAPNsの応答を確認する。
3. 復旧後、最新の生存・接続状態と保留数を確認し、登録済みのテスト端末で訓練を試す。

新しいECSタスク定義はヘルスチェックのある新しいイメージと一緒に配備する。
検証環境は独立したスタックを使用し、通常は `LiveEnabled=false` にする。新APIのテストには
本番端末の購読データを複製せず、同意を得たテスト端末を明示的に登録する。

### 管理画面

自己登録モードで、運用設定を格納したSecrets ManagerのシークレットARNを
`OperationsSecretArn` に指定すると `OperationsUrl` が出力される。

- `operators` に運用者名と、各運用者へ渡した64桁の小文字16進キーのSHA-256を設定する。
  キーは運用者ごとに `openssl rand -hex 32` で生成し、安全な経路で本人に渡す。
  実災害配信を停止した検証スタックでは短い英数字のキーも設定できる。実配信を有効にする前に
  64桁のキーに交換する。
- `testInstallations` に画面に表示するテスト端末名と、DynamoDBのインストールIDを
  設定する。管理画面からはこの一覧にあり、受信が有効な端末だけに訓練を送れる。
- ブラウザのログイン画面で運用キーを入力する。配信履歴の「APNs受付」はApple側の
  受付結果を示す。画面には個別利用者の通知先トークンや住所を表示しない。
- 監視データは1日、訓練操作履歴は7日を目安にDynamoDB TTLで削除する。
  訓練要求の保存後に処理が止まった場合は「結果待ち」のまま残る。実機とログを確認して
  から新しい訓練を送る。同じ操作IDの再送は拒否される。
