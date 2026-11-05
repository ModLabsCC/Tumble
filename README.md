# Tumble

Tumble is an early-stage, standalone Fabric physics mod. This repository currently contains the build and a tested Box3D world lifecycle, not playable physics yet.

## Build and test

Use JDK 25. The pinned target is Minecraft 26.3; Fabric API and Box3D4j are fetched from Fabric and the public ModLabs Maven repository.

```sh
./gradlew clean build -PacceptMinecraftEula=true
```

The build runs server GameTests inside Minecraft and packages Box3D4j and its native libraries in the mod JAR. Passing `acceptMinecraftEula=true` accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for the test server. Jenkins uses the same command and archives the resulting JARs.

Snapshots are experimental: override `minecraft_version` and `fabric_api_version` with compatible versions when testing a snapshot; no compatibility is promised without a passing build and GameTests.

## Releases

Jenkins artifacts are for development builds. Publish tested release JARs through GitHub Releases after replacing the snapshot version and tagging the matching commit.

## Development

ProjectResonance is an existing ModLabs prototype and a source for future audited migration. Tumble does not depend on it. The public physics API, terrain, props, networking, rendering, and performance targets are not implemented yet.

Original Tumble source is licensed under MPL-2.0. Box3D4j and bundled components retain their own licenses in the nested dependency.
