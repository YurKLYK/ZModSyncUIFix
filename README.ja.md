# ZModSync

ZModSync は Forge 1.20.1 の Minecraft モッドで、サーバーがクライアントに MOD やリソースファイルを配布できるツールです。

**現在のバージョン**: `1.1.0`

## 特徴

- 🎮 **MOD 自動配布**: サーバー管理者が指定した MOD をクライアントに自動ダウンロード
- 📦 **複数形式対応**: リソースパック、シェーダーパック、設定ファイル、tacz フォルダに対応
- 🚀 **高速同期**: ファイルハッシュキャッシュにより効率的な差分同期
- 🌐 **Modrinth CDN フォールバック**: 対応する MOD は Modrinth から高速ダウンロード
- 📊 **マルチプレイ画面統合**: バニラ風デザインの統合されたマルチプレイ画面
- 🎯 **自動クリーンアップ**: 不要な古い MOD やファイルを自動削除

## 必要環境

- Java 17
- Gradle 8.x (または Gradle ラッパー)
- Minecraft Forge 1.20.1 / Forge 47.4.18+

## ビルド方法

```bash
./gradlew clean build -x test
```

ビルド完了後、JAR ファイルは `build/libs/modsync-1.1.0.jar` に出力されます。

## インストール

1. 上記のビルドコマンドで JAR ファイルを生成します
2. JAR ファイルを Minecraft の `mods` フォルダにコピーします
3. Minecraft を起動します

## サーバー設定

サーバー側で `modsync.toml` を編集して、同期する MOD やファイルを指定できます。

### ファイルレイアウト

サーバーの MOD は直接 `mods/` フォルダから同期されます。

クライアント専用ファイルは `sync_repo/` に配置します：

```
server/
  mods/              # サーバー MOD（直接同期）
  tacz/              # tacz ガンモッドデータ
  config/            # 設定ファイル
  sync_repo/
    resourcepacks/   # リソースパック
    shaderpacks/     # シェーダーパック
    configs/         # その他の設定
    optional_client/ # オプショナルクライアントファイル
```

## 設定オプション

`modsync.toml` で以下の設定が可能です：

```toml
# Modrinth CDN フォールバックを有効化（デフォルト: true）
enable_modrinth_cdn_fallback = true

# HTTP ファイルサーバーポート（デフォルト: 8080）
http_server_port = 8080

# 同期から除外するファイル拡張子
skip_file_extensions = [".bak", ".tmp"]

# 同期対象フォルダ（デフォルト: ["tacz"]）
sync_folders = ["tacz"]

# tacz フォルダ同期を有効化
enable_tacz_sync = true
```

## マルチプレイ画面

改善されたマルチプレイ画面では以下の機能が利用できます：

- **サーバーリスト**: 登録されたサーバーの一覧表示
- **同期ステータス**: 各サーバーの同期状態を表示
- **高速接続**: ワンクリックでサーバーに接続
- **ダウンロード管理**: 必要なファイルの自動ダウンロード

## トラブルシューティング

### ファイルがダウンロードされない

- サーバーが HTTP ファイルサーバーを実行しているか確認
- ファイアウォール設定を確認（デフォルトポート: 8080）
- `skip_file_extensions` に対象ファイルが含まれていないか確認

### MOD の不一致エラー

- サーバーとクライアントの MOD バージョンが一致しているか確認
- `modsync.toml` の設定を確認

## ライセンス

[LICENSE](LICENSE) ファイルを参照してください。

## 貢献

バグ報告や機能提案は [GitHub Issues](https://github.com/YurKLYK/ZModSyncUIFix/issues) にお願いします。

## リンク

- **オリジナルリポジトリ**: https://github.com/ZICteam/ZModSync
- **フォークリポジトリ**: https://github.com/YurKLYK/ZModSyncUIFix
