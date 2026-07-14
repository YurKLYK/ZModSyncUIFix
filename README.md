# ZModSync

日本語版readmeはこちら　https://github.com/YurKLYK/ZModSyncUIFix/blob/main/README.ja.md
ZModSync is a Forge 1.20.1 Minecraft mod that enables servers to distribute MODs and resource files to connecting clients.

**Current Version**: 1.1.0

## Features

- 🎮 **Automatic MOD Distribution**: Server administrators can automatically distribute MODs to clients
- 📦 **Multi-Format Support**: Compatible with resourcepacks, shaderpacks, config files, and tacz folders
- 🚀 **Fast Synchronization**: Efficient differential sync through file hash caching
- 🌐 **Modrinth CDN Fallback**: Supported MODs download quickly from Modrinth CDN
- 📊 **Integrated Multiplayer Screen**: Vanilla-style unified multiplayer screen
- 🎯 **Automatic Cleanup**: Automatically removes unnecessary old MODs and files

## Requirements

- Java 17
- Gradle 8.x or Gradle wrapper
- Minecraft Forge 1.20.1 / Forge 47.4.18+

## Build

`ash
./gradlew clean build -x test
`

After building, the JAR file will be output to uild/libs/modsync-1.1.0.jar.

## Installation

1. Generate the JAR file using the build command above
2. Copy the JAR file to your Minecraft mods folder
3. Launch Minecraft

## Server Configuration

Edit modsync.toml on the server to specify which MODs and files to synchronize.

### File Layout

Server MODs are synchronized directly from the mods/ folder.

Client-only files should be placed in sync_repo/:

`
server/
  mods/              # Server MODs (synchronized directly)
  tacz/              # tacz gun mod data
  config/            # Configuration files
  sync_repo/
    resourcepacks/   # Resource packs
    shaderpacks/     # Shaders
    configs/         # Other configurations
    optional_client/ # Optional client files
`

## Configuration Options

The following options can be configured in modsync.toml:

`	oml
# Enable Modrinth CDN fallback (default: true)
enable_modrinth_cdn_fallback = true

# HTTP file server port (default: 8080)
http_server_port = 8080

# File extensions to exclude from synchronization
skip_file_extensions = [".bak", ".tmp"]

# Folders to synchronize (default: ["tacz"])
sync_folders = ["tacz"]

# Enable tacz folder synchronization
enable_tacz_sync = true
`

## Multiplayer Screen

The improved multiplayer screen provides the following features:

- **Server List**: Display registered servers
- **Sync Status**: Show synchronization status for each server
- **Quick Connect**: One-click server connection
- **Download Management**: Automatic download of required files

## Troubleshooting

### Files are not downloading

- Verify the server is running the HTTP file server
- Check firewall settings (default port: 8080)
- Ensure target files are not in skip_file_extensions

### MOD version mismatch error

- Verify server and client MOD versions match
- Check modsync.toml configuration

## License

See the [LICENSE](LICENSE) file.

## Contributing

Please report bugs or suggest features via [GitHub Issues](https://github.com/YurKLYK/ZModSyncUIFix/issues).

## Links

- **Original Repository**: https://github.com/ZICteam/ZModSync
- **Fork Repository**: https://github.com/YurKLYK/ZModSyncUIFix
