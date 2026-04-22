# EndCity

A third-party dedicated server for **Minecraft: Legacy Console Edition** on Windows 10 / Win64.

LCE shipped without a dedicated server. Every multiplayer game was peer-to-peer, with one player acting as the host. When that host quit, the session ended. EndCity is an attempt to fix that — a server you can leave running, that a real LCE client can connect straight to.

> ⚠️ **Work in progress.** EndCity can accept a real client and hold the handshake, but it doesn't send any world data yet — clients currently get stuck on the "Downloading terrain" screen. See **Status** below for what works today.

## Status

| | |
|---|---|
| ✅ | Accepts real LCE Win64 clients on TCP 25565 |
| ✅ | Completes the full handshake (PreLogin → Login → Play) |
| ✅ | Keep-alives / timeouts working, client holds the connection indefinitely |
| ✅ | Rejects mismatched client versions cleanly |
| 🚧 | No world data sent yet — client reaches "Downloading terrain" and hangs there |
| 🚧 | No player movement, no blocks, no mobs, no chat |
| 🚧 | No world persistence, no `.ms` save integration |

The project is being built in milestones. Right now the transport layer and handshake are complete. World rendering, movement, blocks, inventory, multiplayer, mobs, and persistence are planned.

## Running it

You need **JDK 22**. If you don't have it, Gradle will auto-download one on first build.

```
./gradlew run
```

That's it. EndCity listens on port 25565. Point a real LCE Win64 client at the host and watch it connect.

To produce a distributable launch script:

```
./gradlew installDist
# Output in build/install/EndCity/bin/
```

## Running the tests

```
./gradlew test
```

## License

Apache License 2.0. See `LICENSE`.

## Disclaimer

EndCity is a clean-room reimplementation of the LCE Win64 network protocol. It contains no code or assets from Minecraft: Legacy Console Edition. No affiliation with Mojang, Microsoft, 4J Studios, or Xbox Game Studios. "Minecraft" is a trademark of Mojang Synergies AB; this project is not endorsed by or associated with Mojang.
