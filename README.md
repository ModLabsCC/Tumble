# Tumble

Tumble brings physical objects to Minecraft. It is a Fabric mod built around Box3D, with a focus on multiplayer and familiar Minecraft behavior.

Tumble is in early development. You can turn blocks into props and move them with the tools in the creative inventory. Multiplayer, saving and performance are still being tested.

The Gravity Gun, Physics Gun and Tool Gun are creative and developer tools. They have no default survival recipes or progression. Find them in the Tumble creative tab or use the operator commands `/tumble gravitygun`, `/tumble physicsgun` and `/tumble toolgun`. A server can add recipes through an optional datapack.

Physics runs on the CPU using Box3D. Minecraft uses the GPU for rendering; Tumble does not currently run its physics on the GPU.

## Try a build

Development JARs are available from [Jenkins](https://ci.flwc.cc/job/ModLabsPublic/job/Tumble/). Releases will be published on [GitHub Releases](https://github.com/ModLabsCC/Tumble/releases). This branch targets Minecraft 26.4 snapshot 7 and needs Fabric Loader, Fabric API and Fabric Language Kotlin.

## Build from source

Use JDK 25, then run:

```sh
./gradlew clean build -PacceptMinecraftEula=true
```

This builds the mod and runs the physics tests and server GameTests. Run `./gradlew runClientGameTest -PacceptMinecraftEula=true` for the client tests, and `./gradlew runProductionGameTest -PacceptMinecraftEula=true` to test the packaged JAR with a Fabric server. The flag accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the test server. The finished JAR is in `build/libs/`.

This branch follows a Minecraft snapshot and may break as Minecraft and Fabric change. The main branch targets Minecraft 26.3.

## Contributing

Contributions are welcome, including work made with coding agents. See [CONTRIBUTING.md](CONTRIBUTING.md) for the build, testing and pull request guidelines.

Making a server-side addon? Start with the [addon API guide](docs/addons.md).

## License

Tumble is licensed under [MPL 2.0](LICENSE). Box3D4j and the libraries it bundles keep their own licenses.
