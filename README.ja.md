<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas ロゴ" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><a href="README.md">English</a> | <a href="README.zh-CN.md">简体中文</a> | <strong>日本語</strong> | <a href="README.ru.md">Русский</a></p>

<p align="center"><strong>セルフホスト型 AI クリエイティブキャンバス</strong><br />テキスト、画像、動画、音声、Agent をひとつのワークスペースに。</p>

<p align="center">Agenvas は、高額なサブスクリプションや最上位モデルに縛られず、個人でも低コストで創作キャンバスを使い、手頃な選択肢を自分で選べる環境を目指しています。</p>

<p align="center">
  <a href="#クイックスタート">クイックスタート</a> ·
  <a href="#デプロイとメンテナンス">デプロイ</a> ·
  <a href="#ローカル開発">ローカル開発</a> ·
  <a href="#ドキュメントと貢献">ドキュメントと貢献</a> ·
  <a href="LICENSE">MIT</a>
</p>

<p align="center">作者：<a href="https://x.com/Grayrepo">X / Twitter @Grayrepo</a> · メール：<a href="mailto:yoshioka8084806@gmail.com">yoshioka8084806@gmail.com</a></p>

<p align="center">
  <img src="docs/assets/wechat-official-account.jpg" alt="WeChat 公式アカウントの QR コード" width="180" /><br />
  スキャンして WeChat 公式アカウントをフォロー
</p>

> 現在は、管理者ひとりでセルフホストするための開発版です。今後のバージョンとのデータ互換性は保証されません。

## 機能

| 機能 | 説明 |
| --- | --- |
| キャンバスでの創作 | ドラッグ、範囲選択、接続、自動整列、グループの命名・折りたたみ、ノード単体・グループ全体の複製、同一セッション内の表示編集の元に戻す・やり直し、ローカルメディアのアップロード |
| マルチモーダル生成 | テキスト・画像・動画・音声をカードごとに実行。キュー、キャンセル、再生成、バージョン履歴に対応 |
| Agent との共同作業 | 右下のディレクターチャットが必要に応じて現在のキャンバスを読み取り、操作。会話の永続化と Skill に対応し、メディア生成の提案はユーザー承認後に実行 |
| MCP 接続 | 内蔵ディレクターを維持しつつ、ユーザートークンで外部クライアントに 13 個の創作ツール（インテリジェントなナレーション合成を含む）を公開。許可済みのメディア呼び出しは、呼び出しごとの課金承認なしで受け付け |
| 素材の再利用 | プロジェクト素材、個人アセットライブラリ、プロンプトテンプレート、メディアスタイル、複数形式を組み合わせた参照入力 |
| キャラクターの一貫性 | プロジェクトのキャラクター、複数の外見、画像・音声参照。プレビュー後にショットの下書きへ適用し、変更の影響を追跡して結果を個別に確認 |
| 画像処理 | 注釈、切り抜き、回転、反転、拡大、深度抽出、および設定済みの機能による AI 編集 |
| セルフホスト管理 | モデル設定、ローカルおよび OSS/COS/S3 ストレージ、タスクと呼び出し記録、プロジェクトマニフェストのエクスポート |

UI は中国語、英語、ロシア語、日本語に対応しています。メディアアダプターには GPT Image、Google Nano Banana、Volcano Ark Seedance、MiniMax H3 公式 API、Kling の画像・動画生成プロトコル全体、Wan の画像・動画、Seedream 5.0 の画像、Google Veo/Omni の動画、Seed Audio、インポート・マッピング・公開した ComfyUI API ワークフロー、RunningHub V2 ワークフロー / AI アプリがあります。利用できる操作は、管理者が公開した機能によって異なります。MiniMax H3 の公式エンドポイント、入力制限、アップグレードについては [ADR 0042](docs/adr/0042-minimax-h3-official-api.md) を参照してください。Kling の API Key、地域別エンドポイント、モデルと操作は[接続ガイド](docs/kling-api-integration.md)、Wan・Seedream・Google 動画の設定とパラメーター範囲は[ネイティブ生成接続ガイド](docs/native-media-api-integration.md)に記載しています。リンク先の詳細資料は主に中国語です。

## クイックスタート

### 1. コマンドで起動

以下のコマンドを使う前に、**Docker Engine / Docker Desktop** をインストールして起動し、**Docker Compose v2** が使えることを確認してください。起動とログインには Git、ローカルのビルド環境、GPU、`.env`、モデルの API Key は不要です。

新規インストールでは、**macOS / Linux** のターミナルに以下をまとめて貼り付けて実行します。

```sh
mkdir agenvas && cd agenvas && \
  curl -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml \
    -o docker-compose.yml && \
  docker compose up -d --wait
```

**Windows：コマンドでインストール**

新規インストールでは CMD または PowerShell を開き、対応するコマンドをまとめて貼り付けて実行してください。どちらか一方を選びます。

<details>
<summary><strong>CMD（コマンドプロンプト）</strong></summary>

```cmd
mkdir agenvas && cd agenvas && curl.exe -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml -o docker-compose.yml && docker compose up -d --wait
```

</details>

<details>
<summary><strong>PowerShell（Windows PowerShell 5.1 / PowerShell 7）</strong></summary>

```powershell
& {
  $ErrorActionPreference = 'Stop'
  New-Item -ItemType Directory -Path agenvas | Out-Null
  Set-Location agenvas
  Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml' -OutFile docker-compose.yml -UseBasicParsing
  docker compose up -d --wait
}
```

</details>

**Windows：AI Agent にインストールを依頼**

[インストール用プロンプト（中国語）](docs/operations/windows-ai-install-prompt.md)を、WorkBuddy など PC 上で操作できる AI Agent に渡してください。環境の確認、インストール、起動を依頼できます。完了後、Agent が示したアドレスを開いてアカウントを作成します。

手動でのインストールは [Windows インストールガイド（中国語）](docs/operations/windows-install.md)を参照してください。

上記の操作で `agenvas` ディレクトリと Compose ファイルを用意し、イメージを取得して、3 つのサービスが正常になるまで待機します。以後の管理もこのディレクトリで行うため、削除せず保管してください。Compose ファイルが既にある場合は、そのディレクトリで `docker compose up -d --wait` を実行します。

データベースのパスワードと認証情報の暗号化キーは、初回起動時に自動生成され、永続保存されます。手動での入力は不要です。初回のイメージ取得には数分かかることがあります。

### 2. 管理者を作成

ローカルでは **<http://127.0.0.1:8088/setup>** を開きます。別のデバイスからは `http://<server-ip>:8088/setup` を使ってください。管理者のログイン名とパスワードを設定し、作成後にログインします。インターネットに公開する前に、管理下のネットワークで初期設定を完了してください。

### 3. モデルを設定して創作を開始

- **テキストと Agent**：「設定 → モデル設定」で OpenAI 互換エンドポイント、モデル ID、API Key を追加します。Agent の利用前に、管理者がツール呼び出し診断を実行する必要があります。
- **画像・動画・音声**：「メディア設定」で接続を作成し、機能を公開して、既定のモデルを設定します。通常のデプロイでは、実モデルの設定を完了してからメディアを生成できます。

プロジェクトを作成し、キャンバスの右クリックメニューからカードを追加するか、素材をアップロードします。モデル、プロンプト、参照入力を設定し、見積もり費用を確認して実行してください。完了後はプレビュー、結果の採用、再生成ができます。右下のディレクターチャットを開くと、必要に応じて現在のキャンバスを読み取り、操作するプロジェクトアシスタントを使えます。ノードを選択して、今回の作業対象を明確にできます。Skill の必須入力にはキャンバス上の内容を明示的に選択し、正確なバージョンを固定します。メディア生成の提案は会話内でまとめて承認・拒否できます。Agent ノードの追加や素材の紐付けは不要です。

メディアリレーは、OpenAI 互換 LLM の画像入力と OpenAI Images の参照画像・マスクに対して個別に有効化できます。無効の場合は従来の転送方法を使います。Seedance と MiniMax H3 のローカル動画参照には、インターネットからアクセス可能なリレーが必要です。設定は[メディアリレーガイド](docs/media-relay-design.md)を参照してください。

> 結果が不明（UNKNOWN）の場合、明示的な再試行が必要で、料金が重複して発生する可能性があります。キャンセルしても外部サービスの停止や返金は保証されません。プロジェクトマニフェストにはデータと素材のメタデータが含まれますが、データベースやメディアファイルのバックアップの代わりにはなりません。

## デプロイとメンテナンス

以下のコマンドは `docker-compose.yml` があるディレクトリ（上記で作成した `agenvas`）で実行します。

| 操作 | コマンド |
| --- | --- |
| 起動 / 設定されたイメージバージョンへ更新 | `docker compose up -d --wait` |
| データを保持して停止 | `docker compose down` |
| サービスの状態を確認 | `docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"` |
| 最近の起動ログを確認 | `docker compose logs --tail=100 server postgres` |

> 更新前にデータベース、メディア、設定、暗号化キーをバックアップしてください。**`docker compose down -v` は実行しないでください。** データとキーが削除されます。詳細は[バックアップと復元](docs/operations/backup-restore.md)を参照してください。

<details>
<summary><strong>ポート、イメージバージョン、インターネットへの公開</strong></summary>

- 既定の `docker-compose.yml` は Web の `8088` だけを `0.0.0.0` に公開し、サーバー IP からアクセスできます。API の `8080` と PostgreSQL の `5432` はコンテナ内部ネットワーク専用で、ホストには公開しません。ソースビルドと Mock の Compose は、開発・調査用に 3 つのポートをループバックで公開します。Web ポートが使用中の場合は `ports` のホスト側ポートを変更してください。
- 既定では `docker.io/grayrepo/agenvas-server:latest`、`docker.io/grayrepo/agenvas-web:latest`、公式の `postgres:17.11-alpine` を取得します。アプリケーションイメージは amd64 / arm64 向けに公開する構成で、ローカルでのコンパイルは不要です。
- アプリケーションイメージは `up` のたびに取得します。バージョンを固定するには、`latest` を公開済みバージョン、コミットタグ、または digest に変更してください。
- 各 Compose ファイルには環境変数、ポート、ボリューム、ヘルスチェック、リソース制限を記載しています。追加の設定ファイルなしで、選んだファイルを直接編集できます。
- 公開時には HTTPS とリバースプロキシを設定し、Compose の `AGENVAS_SECURE_COOKIES` を `"true"` にしてください。旧 V1–V77 開発用データベースの移行制限は[バックアップと復元](docs/operations/backup-restore.md)を参照してください。
- ソースビルドではコード更新後に `./deploy/update-local.sh` を実行します。停止には `docker compose -f docker-compose.local.yml down` を使います。

</details>

<details>
<summary><strong>キーの自動生成、管理者の初期設定、復元</strong></summary>

初期設定の完了状態はデータベースに永続記録されます。同時リクエストは 1 件だけが成功し、完了後の再リクエストは `409 SETUP_ALREADY_COMPLETED` を返します。再起動、管理者の無効化・削除でも初期設定は再開されません。失敗した初期設定はロールバックされ、再試行できます。公開リバースプロキシを開放する前に、管理下のネットワークで初期設定を完了してください。

データベースのパスワードと認証情報のマスターキーは自動設定されます。ファイルは server コンテナの `/run/agenvas/credentials/installation/` にあり、通常の利用では読み取りや入力は不要です。

| 環境変数 | 自動生成される値 | 保存ファイル |
| --- | --- | --- |
| `AGENVAS_DB_PASSWORD` | 32 バイトの乱数を 64 文字の 16 進数にしたパスワード | `database-password` |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | 32 バイトのランダムキーの Base64 表現 | `credential-master-key` |

データベースのパスワードを確認する場合は、`postgres` コンテナの実行中に、Compose ファイルのあるディレクトリで次を実行します。

```sh
docker compose exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

サービス名は `postgres` です。起動時に `-f` や `-p` を使った場合は、確認時にも同じオプションを使ってください。ソースビルドの場合の例：

```sh
docker compose -f docker-compose.local.yml exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

`no configuration file provided: not found` と表示された場合は、Compose ファイルのあるディレクトリに移動するか、`-f` で正しいパスを指定してください。

キーは Compose、Git、起動ログには書き込まれません。バックアップ時は `credentials-data` を別途暗号化して保管してください。`down -v` はデータとキーを削除します。既存データベースをこの起動方式に移行する場合は、キー用ボリュームを復元するか、リポジトリ外の非公開 Compose 設定で元のデータベースパスワードとマスターキーを渡してください。これらがない場合、既存の認証情報を復号できなくなるのを防ぐため、起動を停止します。詳細は[バックアップと復元](docs/operations/backup-restore.md)を参照してください。

</details>

<details>
<summary><strong>起動時のトラブルシューティング</strong></summary>

| 症状 | 対処 |
| --- | --- |
| Docker に接続できない / Compose コマンドを使えない | Docker Desktop または Docker Engine を起動し、`docker compose version` が実行できるか確認 |
| `agenvas` ディレクトリが既に存在する | インストール済みならそのディレクトリで `docker compose up -d --wait` を実行。新規インストールなら未使用のディレクトリ名を指定 |
| Compose のダウンロードに失敗する | `raw.githubusercontent.com` への接続を確認。[docker-compose.yml](docker-compose.yml) を手動でダウンロードすることも可能 |
| `manifest unknown` / イメージを取得できない | Docker Hub への接続と公開済みタグを確認。利用可能なリリースがなければ[コンテナでソースをビルド](#コンテナでソースをビルド) |
| ポートが使用中 | Compose の `ports` でホスト側ポートを変更して再起動。`8088` を変更した場合はブラウザーでも新しいポートを使用 |
| ヘルスチェックが失敗する | 上記のログ確認コマンドで原因を調べ、修正してから起動コマンドを再実行 |

</details>

## ローカル開発

| レイヤー | 技術スタック |
| --- | --- |
| フロントエンド | Vite · React · TypeScript · React Flow · TanStack Query · Zustand |
| バックエンド | Java 21 · Spring Boot · Spring AI · jOOQ |
| データとデプロイ | PostgreSQL · Flyway · Docker Compose · REST / SSE |

ツールチェーン：JDK 21、Node 24 LTS（24.12+）、pnpm 12.5.1。詳細なバージョンは[依存関係の基準](docs/dependency-baseline.md)を参照してください。

### コンテナでソースをビルド

リポジトリのルートで次を実行し、現在の server と web のソースをビルドして 3 つのサービスを起動します。PostgreSQL はバージョン固定の公式イメージを使います。

```sh
docker compose -f docker-compose.local.yml up -d --build --wait
```

`./deploy/update-local.sh` も利用できます。先にイメージをビルドしてからコンテナを更新し、ヘルスチェックを待機して、それぞれの所要時間を表示します。既定ではベースイメージを再利用し、`./deploy/update-local.sh --pull` で明示的に更新します。片方だけを変更した場合は `./deploy/update-local.sh server` または `./deploy/update-local.sh web` を使えます（初回は両方をビルドしてください）。ソース用 Compose は独立した BuildKit Maven キャッシュを使い、`pom.xml` が変わっても取得済みパッケージを保持します。初回や Docker キャッシュ削除後は再取得が必要です。ホストの `~/.m2` は自動共有されません。バックエンドは `package -Dmaven.test.skip=true` を実行し、テストのコンパイルと実行を省略します。`mvn verify` は自動実行されないため、全テストは必要に応じて手動または CI で実行してください。完了後の一覧にはコンテナ名、サービス、状態、ポートのみを表示します。ソースビルドに Docker Hub のログインは不要です。テキストとメディアの既定値は引き続き `configured` です。従来の `deploy/compose.yaml` も同じソースビルド設定を使います。

イメージによるデプロイとソースビルドは、既定のプロジェクト名がどちらも `agenvas` で、同じデータベース・素材・キーのボリュームを使います。切り替える前にバックアップとバージョン互換性を確認してください。同時に独立した環境を動かす場合は、`-p` で別のプロジェクト名を指定し、ポートも変更します。

### Mock 環境

以下で直接起動できます。データベースのパスワードとマスターキーは自動生成され、外部モデルのアカウントは不要です。

```sh
docker compose -f deploy/compose.dev.yaml up -d --build --wait
```

テキストとメディアは Mock を使います。画像・動画・音声は合成デモ素材で、実モデルの出力を示すものではありません。アクセス先と初期設定の手順は通常環境と同じです。停止時は同じ Compose ファイルを指定して `down` を実行します。

通常のデプロイと Mock は別のデータボリュームを使いますが、既定の Web ポートは同じです。同時に動かす場合はポートを変更してください。

<details>
<summary><strong>ソースから直接実行</strong></summary>

フロントエンド（別のターミナル）：

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm dev
```

バックエンド（別のターミナル。先に PostgreSQL と環境変数を設定）：

```sh
cd backend
./mvnw spring-boot:run
```

単独の JVM で実行する場合は、`AGENVAS_DB_URL`、`AGENVAS_DB_USER`、`AGENVAS_DB_PASSWORD` を自分で指定してください。変数は `.env.example` を参照できますが、JVM はこのファイルを自動で読み込みません。キーの自動生成はコンテナのエントリーポイント専用です。ソースからの実行は既定で Mock を使います。メディア処理には FFmpeg / FFprobe が必要です。深度抽出については[ローカル画像処理の設定](docs/local-image-processing.md)を参照してください。

ディレクターはトークン予算に応じたコンテキストの自動整理と、永続化された長時間タスクの再開に対応しています。一時的なモデル障害の復旧期間は既定で 24 時間です。モデルのコンテキストウィンドウ、要約、再試行予算は管理画面の「ディレクター設定」で保存し、再起動は不要です。詳細は[ディレクター実行設定](docs/agent-runtime.md)を参照してください。

Vite は既定で `5173` を使い、`/api` を `localhost:8080` にプロキシします。

</details>

## Docker Hub への自動公開

<details>
<summary><strong>イメージ公開設定（メンテナー向け）</strong></summary>

GitHub リポジトリの **Settings → Secrets and variables → Actions** で次を設定します。

| 種類 | 名前 | 内容 |
| --- | --- | --- |
| Secret | `DOCKERHUB_USERNAME` | イメージを push できる Docker Hub ユーザー名 |
| Secret | `DOCKERHUB_TOKEN` | 対象リポジトリへの読み書き権限を持つ Docker Hub Access Token |

匿名でイメージを取得できるよう、Docker Hub に `grayrepo/agenvas-server` と `grayrepo/agenvas-web` の公開リポジトリを用意します。CI の公開先は `grayrepo` に固定しているため、変更時はワークフローと Compose のアドレスを編集してください。PostgreSQL は公式リポジトリから取得し、CI で SBOM とライセンス一覧を保存します。

`.github/workflows/ci.yml` は PR、`main` への push、`v*.*.*` バージョンタグへの push、手動実行時に動作し、テストとイメージビルドを並列で実行します。

| トリガー | 検証 | イメージと一覧 |
| --- | --- | --- |
| コードの PR | フロントエンド、バックエンド単体テスト、PostgreSQL IT の全 4 分割、jOOQ、Compose、シークレットスキャン | server/web の amd64 ビルド |
| ドキュメントのみの PR | 実行計画の検証、シークレットスキャン、CI Gate | 重いジョブを省略 |
| main / 手動実行 | すべての検証 | server/web の amd64 + arm64 ビルド |
| バージョンタグへの push | すべての検証 | 両アーキテクチャのアプリケーションビルドと公式 PostgreSQL の一覧。CI Gate 通過後に公開 |

`CI Gate` は常に生成され、失敗・キャンセル・想定外のスキップを検出すると通過しません。ブランチの必須チェックに指定できます。Buildx キャッシュはサービスとアーキテクチャごとに分離し、PR では読み取りのみです。server のパッケージ化ではテスト全体を省略しますが、CI ではネイティブアーキテクチャごとに深度モデル/JNI のスモークテストを 1 件実行します。

Docker Hub にアップロードするのはバージョンタグへの push だけです。公開イメージを一度スキャンして JSON を CycloneDX に変換し、全チェックの通過後、短期保存アーティファクトから同じイメージを読み込んで push します。失敗レポートは 7 日、公開待ちイメージは 1 日、SBOM とライセンスの証跡は 90 日保存します。

- 公開ごと：`sha-<40 文字の完全なコミット SHA>`。
- バージョンタグ `v0.1.0`：先頭の `v` を除いた `0.1.0` も公開します。
- 安定版タグ（`v0.1.0` のようにプレリリース接尾辞がないもの）：通常の Compose で使う `latest` もそのバージョンに更新します。`v0.1.0-rc.1` のようなプレリリースは接尾辞を保持し、`latest` は更新しません。

PR、`main` への push、手動実行では Docker Hub の認証情報にアクセスせず、イメージも push しません。公開時はサービス・アーキテクチャごとに SBOM とライセンス一覧を保存し、成功したタグを Actions の概要に表示します。`ci-<実行 ID>-<試行回数>-<アーキテクチャ>` は中間タグです。デプロイには `latest`、または最終バージョン・コミットタグ・digest を指定してください。

server/web の両マルチアーキテクチャイメージの公開完了後、ワークフローは対応するタグの GitHub Release を作成します。リリースノートにはバージョン付きイメージのアドレス、ソースコミット、前バージョン以降の実際のコミット一覧、GitHub が生成する PR・貢献者・変更履歴リンクを含めます。安定版は直前の安定版タグと比較し、プレリリースは前のプレリリースタグと比較することもあります。プレリリースと、既存の安定版より古いリリースの補完は Latest になりません。再実行時は既存の説明を維持し、空欄だけを補います。既存の下書きは維持し、手動公開が必要です。このジョブだけに `contents: write` を付与し、バージョンタグの push、CI Gate 通過、Docker Hub 公開成功後に実行します。イメージは再ビルドしません。

既存タグの Release は自動で補完されません。対象タグの CI と両アーキテクチャのイメージ公開を確認してから、説明をプレビューし、明示的に公開します。

```sh
python3 .github/scripts/release.py notes v0.0.2 --repo grayrepo-byte/Agenvas --output release-notes.md
python3 .github/scripts/release.py publish v0.0.2 --repo grayrepo-byte/Agenvas
```

ローカルコマンドはログイン済みの GitHub CLI を使い、Token をコマンド引数に書き込みません。`notes` は説明の生成のみ、`publish` は GitHub への書き込みを行います。スクリプトはリモートタグとソースコミットを照合し、タグの作成・移動はしません。自動生成には [GitHub Release Notes API](https://docs.github.com/en/rest/releases/releases#generate-release-notes-content-for-a-release) を使います。

公開処理の詳細は [Docker のマルチプラットフォームビルド](https://docs.docker.com/build/ci/github-actions/multi-platform/)と [Docker イメージのタグ付け](https://github.com/docker/metadata-action)を参照してください。

</details>

## ドキュメントと貢献

以下の詳細資料は主に中国語です。

| ドキュメント | 内容 |
| --- | --- |
| [製品仕様](docs/MVP-SPEC.md) | 機能の範囲と動作の取り決め |
| [MCP クライアント接続](docs/mcp-client-guide.md) | ユーザートークン、権限、クライアント設定、ツール呼び出し |
| [開発チェックリスト](docs/DEVELOPMENT-CHECKLIST.md) | 実装の進捗と検証状況 |
| [開発ガイドライン](AGENTS.md) | アーキテクチャ、コーディング、テストの要件 |
| [バックアップと復元](docs/operations/backup-restore.md) | アップグレード、データのバックアップ、障害復旧 |
| [セキュリティポリシー](SECURITY.md) | 脆弱性の報告とサポート範囲 |

Issue と Pull Request を歓迎します。貢献の前に製品仕様と開発ガイドラインを読み、機能変更時は対応するテストを実行してください。

```text
frontend/       フロントエンドの画面とキャンバス操作
backend/        業務 API、Agent Runtime、タスク処理
contracts/      正式な OpenAPI とコンテンツスキーマ
configs/        Agent、Skill、信頼済みメディアワークフローの設定
deploy/         Compose、Nginx、コンテナビルド
docs/           仕様、設計、開発ドキュメント
```

## オープンソースへの謝辞

Agenvas に内蔵する短編ドラマ制作 Skills は [zenstory-ai/drama-skills](https://github.com/zenstory-ai/drama-skills) に由来します。創作スキルとワークフローをオープンソースで共有してくださった作者・貢献者の皆様に感謝します。

動画制作とプロンプトの手法は [MiniMax-H3/skills](https://github.com/MiniMax-AI/MiniMax-H3/tree/main/skills) も参考にし、キャンバスツールとメディア承認ワークフローに適応させています。サードパーティーのライセンスは以下を参照してください。

## ライセンス

[MIT License](LICENSE) を採用しています。サードパーティーの依存関係、モデルの重み、ワークフロー、FFmpeg などのコンポーネントには、それぞれのライセンスが適用されます。

内蔵の [MiniMax H3 Skill コレクション](backend/src/main/resources/builtin-skills/minimax-h3/manifest.json) の元資料と適応版には、[MiniMax H3 Community License](backend/src/main/resources/builtin-skills/minimax-h3/LICENSE) が適用されます。地域、商用利用、再配布に関する条件があり、MIT として再ライセンスされるものではありません。出典と改変の説明は [NOTICE](backend/src/main/resources/builtin-skills/minimax-h3/NOTICE) を参照してください。元の Drama Skills は [MIT ライセンス](backend/src/main/resources/builtin-skills/drama-skills/LICENSE) を維持します。
