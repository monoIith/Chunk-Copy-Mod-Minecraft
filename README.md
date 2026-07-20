# Chunk Copy

Chunk Copy is a server-authoritative Fabric mod for Minecraft Java 1.21.10. It mirrors the player-authored block mutation at the same chunk-local X/Z coordinate and absolute Y in every eligible loaded chunk, then lets vanilla Minecraft calculate each chunk's consequences independently.

## Behavior

| Source event | Result |
| --- | --- |
| Place, break, or transform a block | The root block mutation is copied to each eligible chunk. |
| Open or close an existing door | The door state change remains local; neither half is copied. |
| Creeper explosion | The explosion and its damage stay local. |
| Ignite TNT with flint and steel | The copied TNT blocks are removed, but only the source creates primed TNT. |
| Place a redstone torch beside TNT | The torch is copied, so vanilla redstone independently primes TNT in each destination. |
| Complete an iron-golem structure | The copied pumpkin completes every matching local structure, and vanilla spawns one golem per complete structure. |
| Standard monster spawner succeeds | Its mob is cloned to the other currently loaded chunks. |

Replication never force-loads chunks. The `loaded` mode affects chunks that are full at action time; `persistent` additionally records final block outcomes so stale chunks can materialize the latest state when they later load. Historical effects, explosions, and entities are not replayed.

## Requirements

- Minecraft Java 1.21.10
- Java 21
- Fabric Loader 0.19.3 or newer
- Fabric API 0.138.4+1.21.10

The mod has no client entrypoint or networking requirement. Install it in the singleplayer instance for integrated-server play or on a dedicated server; players joining a dedicated server do not need the mod locally.

## Building

The project uses Gradle 9.5.0, Loom Remap 1.17.16, and Yarn `1.21.10+build.3`.

```sh
./gradlew build
```

Use a Java 21 JDK. The remapped mod JAR is written to `build/libs/`. Fabric's server GameTest source set is configured and participates in the Gradle verification lifecycle.

## Configuration

On first start, the mod writes `config/chunkcopy.json`. The packaged `chunkcopy.default.json` documents the defaults:

```json
{
  "schemaVersion": 1,
  "defaultEnabled": true,
  "defaultMode": "loaded",
  "maxCapturedPositionsPerAction": 4096,
  "maxBlockWritesPerTick": 4096,
  "maxQueuedBlockWrites": 262144,
  "maxSpawnerClonesPerTick": 256,
  "maxQueuedEntityClones": 32768,
  "mirrorMonsterSpawnerSpawns": true,
  "logQueueOverflows": true,
  "logSkippedProtectedBlocks": false
}
```

Operator commands (permission level 2) are:

- `/chunkcopy status`
- `/chunkcopy enable`
- `/chunkcopy disable`
- `/chunkcopy mode loaded|persistent`
- `/chunkcopy reset-persistent confirm`

## Protected blocks

Replication skips blocks in the data-pack-extensible `#chunkcopy:protected` block tag. The built-in tag covers bedrock, barriers, End portals and frames, End gateways, reinforced deepslate, command blocks, structure blocks and voids, and jigsaws. A data pack may append blocks without replacing the defaults.

## License

Chunk Copy is available under the MIT License.
