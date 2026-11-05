# Tumble

Tumble brings physical objects to Minecraft. It is a Fabric mod built around Box3D, with a focus on multiplayer and familiar Minecraft behavior.

Tumble is in early development. The physics core can simulate bodies and terrain, but it is not connected to gameplay yet.

## Try a build

Development JARs are available from [Jenkins](https://ci.flwc.cc/job/ModLabsPublic/job/Tumble/job/main/). Releases will be published on [GitHub Releases](https://github.com/ModLabsCC/Tumble/releases). You'll need Minecraft 26.3, Fabric Loader, Fabric API and Fabric Language Kotlin.

## Build from source

Use JDK 25, then run:

```sh
./gradlew clean build -PacceptMinecraftEula=true
```

This builds the mod and runs the physics tests and Minecraft GameTests. The flag accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the test server. The finished JAR is in `build/libs/`.

The build pins Minecraft 26.3. Snapshot builds are experimental: override `minecraft_version` and `fabric_api_version` with compatible versions, then run the tests.

## Contributing

Contributions are welcome, including work made with coding agents. See [CONTRIBUTING.md](CONTRIBUTING.md) for the build, testing and pull request guidelines.

## License

Tumble is licensed under [MPL 2.0](LICENSE). Box3D4j and the libraries it bundles keep their own licenses.
