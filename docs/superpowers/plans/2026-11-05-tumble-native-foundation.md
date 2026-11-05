# Tumble native foundation implementation plan

**Goal:** Adapt the proven loader-independent ProjectResonance physics core into Tumble, keeping Minecraft and Fabric out of the native simulation.

**Architecture:** A `physics-core` JVM module owns native worlds, bodies and collision geometry. Fabric integration will communicate with it using immutable values. Port only code covered by the user's ownership confirmation; preserve provenance and test behavior while changing names.

**Tech stack:** Java 25, Kotlin, Box3D4j, Fabric 26.3, Gradle, Minecraft GameTests and Jenkins.

**Source:** Tumble architecture and roadmap in the ModLabs project Space; ProjectResonance commit e45e49e375dc552831ad72647747114ef8fc44ac.

## Milestones

- [x] Migrate core types, world lifecycle, terrain, water and existing tests; compile without Minecraft dependencies and package it in Tumble.
- [ ] Verify native precision and large-coordinate behavior; ensure Java bindings and all native platforms use a matching ABI before enabling large-world simulation.
- [ ] Port server-thread integration, terrain and props to 26.3 with automated GameTests for world lifecycle and block conversion.
- [ ] Port persistence, multiplayer, rendering and player interaction with targeted server and client tests.
- [ ] Test and optimize representative scenes, then deliver the documented alpha and moving-assembly features in further verified milestones.

## Review focus

Thread ownership, resource disposal, stale handles, failed conversions and large coordinates must be tested. Native precision and every supported platform remain unverified until tested with the exact shipped binary. Avoid exposing native handles or passing Minecraft objects to the physics worker.
