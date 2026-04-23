<h1 align="center">EndCity</h1>

<p align="center">
  A third-party dedicated server for <b>Minecraft: Legacy Console Edition</b> (Win64).
</p>

<p align="center">
  <img alt="Status" src="https://img.shields.io/badge/status-WIP-orange?style=flat-square">
  <img alt="Java" src="https://img.shields.io/badge/Java-22-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img alt="Gradle" src="https://img.shields.io/badge/Gradle-8.9-02303A?style=flat-square&logo=gradle&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square">
  <img alt="Protocol" src="https://img.shields.io/badge/protocol-LCE%20Win64-8A2BE2?style=flat-square">
  <img alt="Tests" src="https://img.shields.io/badge/tests-43%20passing-success?style=flat-square">
</p>


## What is this?

LCE never got a proper dedicated server. If you wanted to play with friends, one person had to host, and when they quit, everyone got kicked. That sucks.

EndCity is a dedicated server that speaks LCE's Win64 TCP protocol (net version 560, protocol 78) natively. Run it on a box you leave on, and an unmodified LCE client can just connect to it like it would any other server.

> **Heads up:** this is early. Right now the server can do the handshake but doesn't send any world data yet, so clients hit "Downloading terrain" and just sit there. See below for what works.


## What works

- [x] Listens on TCP 25565
- [x] Accepts real LCE Win64 clients
- [x] Full handshake (PreLogin, Login, enter Play)
- [x] Keep-alives every second, timeout detection, login watchdog
- [x] Rejects outdated/future client versions with the right error code
- [x] Clean disconnects, proper small ID pool recycling

## What doesn't (yet)

- [ ] World data (clients hang on "Downloading terrain")
- [ ] Player movement
- [ ] Blocks, placing, breaking
- [ ] Inventory and chests
- [ ] Chat
- [ ] Other players showing up
- [ ] Mobs
- [ ] Saving worlds to disk

These are all planned. The project is being built in order, one piece at a time, with each piece tested against a real client before moving on.


## Running it

You need **JDK 22**. If you don't have it, Gradle will grab one automatically.

```bash
./gradlew run
```

Server listens on `25565`. Point a real LCE Win64 client at the host and it'll connect.

Want a launch script you can run without Gradle?

```bash
./gradlew installDist
# Then run: build/install/EndCity/bin/EndCity
```

## Tests

```bash
./gradlew test
```

Everything gets tested against a live server on an ephemeral port. No mocks, no fakes, real TCP end to end.


## License

Apache 2.0. Do what you want.

## Disclaimer

Clean-room reimplementation of the LCE Win64 network protocol. No code or assets from the actual game. Not affiliated with Mojang, Microsoft, 4J Studios, or Xbox Game Studios. "Minecraft" is a trademark of Mojang Synergies AB.
