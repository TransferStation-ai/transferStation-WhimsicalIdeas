# Valve Content — NPC Model Files

This directory holds model and material assets extracted from **Valve Source SDK 2013**
(Half-Life 2), used by the **TransferStation Whimsical Ideas** mod under Fair Use /
Transformative Work provisions.

## Directory Layout

```
valve_content/
├── models/npc/<name>/        ← per-NPC model files
│   ├── <name>.mdl            StudioMDL model
│   ├── <name>.vvd            vertex data
│   ├── <name>.dx90.vtx       DirectX 90 hardware vertices
│   └── <name>.phy            physics collision hull
└── materials/models/<name>/  ← per-NPC material files (VMT/VTF → PNG)
```

## Included NPCs

| # | ID              | File Name     |
|---|-----------------|---------------|
| 1 | metrocop        | metrocop      |
| 2 | combine_soldier | combine_soldier |
| 3 | zombie_classic  | zombie_classic |
| 4 | headcrab        | headcrab      |
| 5 | vortigaunt      | vortigaunt    |
| 6 | antlion         | antlion       |
| 7 | fast_zombie     | fast_zombie   |
| 8 | manhack         | manhack       |
| 9 | rollermine      | rollermine    |
|10 | stalker         | stalker       |

## How to Extract Model Files

You need **Valve Source SDK 2013** (free on Steam) or a clean HL2 installation.

### 1. Locate source files

From your HL2 install (e.g. `steamapps/common/Half-Life 2/hl2/`):

```
models/npc/<name>.mdl
models/npc/<name>.vvd
models/npc/<name>.dx90.vtx
models/npc/<name>.phy
```

### 2. Copy into the correct directory

```
valve_content/models/npc/<name>/
```

### 3. Convert materials

Materials are stored as `.vmt` + `.vtf` pairs in `hl2/materials/models/`.
Use **VTFEdit** or **VTFCmd** to convert `.vtf` to `.png`, then place them in:

```
valve_content/materials/models/<name>/
```

## License

These assets are derived from Valve's works and are subject to the **Valve Source SDK
License Agreement**. They are included here solely as bundled content for the
TransferStation Whimsical Ideas mod. Redistribution outside of this mod's compiled
JAR is not permitted.
