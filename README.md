I developed it in collaboration with AI.

# Structure-Arbiter
**Stop structure mods from overlapping each other.**

Install a dozen structure mods and you'll eventually see a fortress cut in half by a village, or a dungeon swallowed by a mineshaft. Structure Arbiter settles these conflicts *before* anything is built, so big structures stay intact and small ones keep their distance.

## How it works

Instead of letting every mod stamp blocks over its neighbours, Structure Arbiter looks at where each structure *wants* to go (its bounding box) and decides, per structure, whether it gets to exist:

- Structures are ranked by **priority**, then by structure ID, so the result never depends on mod load order.
- A structure that would collide with a higher-ranked one is **dropped**, so the higher-ranked one is never overwritten.
- Decisions depend only on the **world seed and coordinates**, so the same seed gives the same layout wherever you walk.
- The same structure never competes with itself. Each mod's own spacing rules still apply.


## Features

- Works with structures registered through vanilla structure sets, including most structure mods
- No new blocks, items or world-gen features; it only arbitrates what already generates
- Lightweight lookahead with biome pre-filtering, so adding many mods doesn't freeze world generation
- Configurable: spacing threshold, margin between structures, excluded mods or structures, lookahead range
- In-game debug tools (OP only):
  - `/arbiter stats` shows what was kept or dropped, and which structures are slow
  - `/arbiter here` lists the structures around you and why each was kept or dropped
  - `/arbiter debug on|off` logs every rejection and its cause
- Verbose JVM debug flag:
  - Add `-Darbiter.debug=true` to your Java launch arguments to output detailed Arbiter logs directly to the launcher console.


## Limitations

- Ring-placed structures (such as strongholds) are not arbitrated.
- Mods that place blocks with custom code, outside the structure system, can't be seen by the arbiter.
- Dropped structures are removed, not moved. Moving is supported internally but off by default.


## Known Issues

- Certain specific structures added by the `trek` mod are currently prone to being frequently rejected. A fix is planned for a future update.


## Requirements

Minecraft 1.20.1, Forge. Safe to add to an existing world, but only newly generated chunks are affected.

*Early release: feedback and conflict reports are welcome.*
