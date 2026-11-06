# Contributing

Thanks for helping with Tumble. Bug reports, tests, documentation and code are all welcome. For a large change, open an issue first so we can discuss the direction.

## Make a change

1. Fork the repository and create a branch.
2. Use JDK 25 and run `./gradlew clean build -PacceptMinecraftEula=true`. The flag accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the test server.
3. Add or update tests for the behavior you change. Keep gameplay and native code safe on dedicated servers, and don't pass Minecraft objects to the physics thread.
4. Open a pull request describing the change and how you tested it. Include screenshots or a short recording when they help explain a visual change.

For a local physics baseline, run `./gradlew :physics-core:benchmark`. It measures moving bodies and snapshot collection, not full server performance. Compare results on the same machine.

You are welcome to use coding agents or other AI tools. Review and test their changes as you would any other contribution: the person submitting the pull request is responsible for its correctness, security and licensing.

New source files should include `SPDX-License-Identifier: MPL-2.0`. Only contribute code and assets you have the right to share, and retain any required third-party notices.
# Assembly restart test

Run `./gradlew runAssemblyRestartTest -PacceptMinecraftEula=true` with Java 25 to test a converted assembly across two production server processes and an orderly disk save. The task removes only its dedicated `build/run/assemblyRestart/world` test world. This does not simulate crashes or independent chunk/entity save ordering.
