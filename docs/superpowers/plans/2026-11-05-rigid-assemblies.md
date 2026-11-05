# Rigid Assemblies Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and test-driven development for each task.

**Goal:** Convert connected vanilla blocks into one persistent, networked rigid body that players can stand on.

**Architecture:** Capture block states and collision boxes on the server thread. Store positions relative to one origin with a block-state palette; build a compound body on the physics thread. The entity carries the durable definition, while runtime body IDs remain temporary. Clients receive the same palette and render every block under the authoritative transform.

**Tech stack:** Fabric, Minecraft 26.3, Kotlin/Java, Box3D4j and Minecraft GameTests.

**Spec:** [Voxel Collision & Moving Structures](https://chatgpt.com/space/page_5500055952e48191939bafce80e3d124) and [Delivery Roadmap](https://chatgpt.com/space/page_a1a889ade16c8191af30e016f6dee21b).

## Constraints and review focus

- One native body for a 250-block staircase; collision must use each block's actual shape.
- Preserve block states and positions through save, unload, reload, transfer and client tracking.
- Validate every block and permission before changing the world; roll back atomically when creation fails.
- Reject block entities and unsupported shapes rather than silently losing their data.
- Bound assembly size, geometry, network input and work per tick; never access Minecraft objects on the physics thread.
- Test mixed materials, rotated partial shapes, multiple riders, chunk boundaries, interrupted conversion and late acknowledgements.

## Tasks

1. Add a validated, immutable assembly definition and compact palette codec. Test 250 distinct positions, repeated states, malformed input and round-trip state properties.
2. Build a compound from captured local collision boxes with per-shape material and mass. Test stairs, slabs, mixed density and a 250-block staircase as one body.
3. Add server-side capture and permission-checked atomic conversion. Test denial, failed native creation, chunk visibility and save/reload without duplication or missing blocks.
4. Persist and transmit assembly definitions, including late client tracking. Test bounded decoding, round trips, reconnect and authoritative poses.
5. Render each block under the shared transform and use the complete collision bounds for selection and actor sweeps. Test rotated shapes, support and riding with multiplayer GameTests.
6. Add a safe operator-facing demonstration and document the addon surface. Run the server, client and packaged GameTests on the release target and the snapshot branch, then measure the 250-block case.
