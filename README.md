# KXit Engine

**Rookmender Studios** · Standalone Android voxel sandbox / 2D–3D engine foundation.

This project contains KXit Engine only. APK Lens and XZP are not included.

## v2.1.1 changes

- Corrected all three manual mesh UV paths to invert PNG top-left rows into OpenGL's bottom-left texture coordinate system. The test script checks both top and bottom V coordinates in quads and low-poly triangles.
- Updated the atlas test to read the PNG IHDR and verify the 128×128 dimensions without external image tools.
- Publishing now validates the repository slug, updates `kxit.lock` when an explicit repository override is used, and describes the release accurately without claiming a device test was performed automatically.
- App version is `2.1.1` / version code `12` so the corrected build can install as an update.

## Build on Termux

KXit uses **libGDX 1.14.2 + OpenGL ES 2.0** and the prebuilt Android ARM64 libGDX runtime. It does not compile C/C++, invoke the NDK, or configure CMake. The Android package still contains the prebuilt `arm64-v8a` libGDX runtime library.

Requirements:
- Android SDK platform 35 / build tools available to the `studio` command
- JDK 17
- Termux `studio` wrapper or Gradle
- Internet on first build to download Maven dependencies

Build one APK:

```bash
bash ~/KXitEngine/build-termux.sh
```

or directly:

```bash
studio build ~/KXitEngine
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The project is intentionally one Gradle module (`:app`). There is no `:xzp`, `:apklens`, `externalNativeBuild`, CMake, or NDK compile task.

## Gameplay

- Landscape orientation.
- 48×48×20 generated terrain with rolling hills and a winding sand path.
- First-person 3D perspective plus an orthographic/isometric view toggle.
- Left D-pad uses held-state movement and delta-time velocity (not one tile per tap).
- Right-side drag rotates the camera.
- Jump, mine, place, and six-slot hotbar controls.
- Holding MINE or PLACE repeats the action with a bounded interval.
- Basic ground collision, stepping, gravity and jumping.
- FPS and XYZ coordinates on the HUD.
- Low-poly faceted tree crowns and rocks are generated as geometry.
- `KXitObjLoader` wraps libGDX's Wavefront OBJ loader for future models. The sample OBJ is deliberately **not** loaded into the default world.

## Renderer / performance

- One indexed static terrain mesh is generated at world creation; blocks are not drawn with one draw call per block.
- Hidden block faces are not added to the mesh.
- The world mesh is rebuilt only when a block is changed, not every frame.
- Vertex data is batched into one mesh draw call for the terrain.
- Atlas uses nearest filtering and 16×16 tile UVs with a half-pixel inset to reduce texture bleeding.
- PNG pixels are uploaded in image row order while OpenGL texture coordinates use a bottom-left origin. Terrain mesh UVs therefore invert the atlas V coordinate (`1 - v`) in all quad and triangle primitives. `TextureRegion` handles this inversion for HUD icons. The source test script checks all three mesh paths so the atlas cannot silently flip again.
- No MSAA, no per-frame terrain mesh allocation, and frame delta is capped to avoid huge movement jumps after a stall.
- Tree/rock props use a small number of low-poly triangles.
- The starter world is intentionally bounded to keep the mesh within GLES2's 16-bit index range.

The project is optimized structurally for mobile, but no exact FPS claim is made without measuring on the target phone. GPU driver, heat, display resolution and Android version affect the result.

## Texture atlas

`app/src/main/assets/blocks.png` is a generated, opaque 128×128 pixel-art atlas: 8×8 tiles, each 16×16 pixels. The first block IDs follow the requested mapping:

1. Grass (top tile 0, side tile 4, bottom tile 5)
2. Stone (tile 1)
3. Cobblestone (tile 2)
4. Planks (tile 3)

The remaining initial tiles include dirt, leaves, wood bark, sand, water, bricks, gravel, log end, snow, iron ore and coal ore. `blocks_atlas.json` documents the mapping. The atlas contains no transparent/black filler tiles.

## Runtime folders

On first launch KXit creates these folders under Android app-specific external storage:

```text
Android/data/com.mazfons.kxit/
├── shaders/
│   ├── terrain.vert
│   └── terrain.frag
├── modules/
│   └── README.txt
└── models/
    └── README.txt
```

### External shaders

The app copies the bundled default shaders only when the external files do not exist. Both external shader files are loaded and compiled at **every application startup**. They are not hot-reloaded during gameplay. Missing files, compile/link errors, or missing required uniforms stop startup and show a dialog with COPY ERROR and EXIT actions. User-edited shader files are preserved.

### Modules

To avoid requiring the Android NDK on Termux, the external extension folder uses trusted Java DEX modules rather than native C `.so` modules. Each module consists of:

```text
example.dex
example.kxm
```

The first non-empty, non-comment line in `example.kxm` is the Java class name. The class implements `com.rookmender.kxit.module.KXitModule`. A small API exposes block get/set, daylight and logging. Modules load at startup. **Only load code you trust**: DEX modules execute with the host app's process privileges.

A template Java module is in `module-template/`.

### OBJ models

`KXitObjLoader` is included as a future-facing wrapper around libGDX's `ObjLoader`. The default world renderer does not enumerate or load files from `models/`; the included sample marker OBJ is not used at startup.

## Run tests before publishing

Run the build/static checks:

```bash
bash ~/KXitEngine/test-kxit.sh
```

Then install the APK and manually test the scene, movement, camera drag, jumping, mining, placing, hotbar, shader error handling and resume/pause lifecycle on the phone.

The test script also checks that the terrain UV row mapping is not vertically inverted and that the visible touch controls are wired. It writes `.kxit-tests-passed` with a source fingerprint and APK SHA-256. If any source or the APK changes after the test, the publishing script refuses to publish until tests run again.

## GitHub project creation and APK release

Install Git and GitHub CLI in Termux and authenticate once:

```bash
pkg install git gh
gh auth login
```

After `test-kxit.sh` succeeds and you have manually smoke-tested the APK:

```bash
bash ~/KXitEngine/publish-kxit.sh --tests-passed
```

The script:

1. Checks GitHub CLI authentication.
2. Creates the GitHub repository if it does not exist. GitHub's repository slug is `KXit-Engine` (spaces are not used in repository URLs); the human-facing project name is **KXit Engine**.
3. Initializes or uses the local Git repository and verifies that `origin` points to the expected GitHub repository.
4. Creates `kxit.lock` in the project folder on first publication. When it already exists, the script reads its `REPOSITORY` value and reuses that repository by default.
5. Commits new/changed source files and pushes them. Build output, APK files, local SDK paths and signing keys are ignored by Git.
6. If the tested APK exists, creates a GitHub Release and uploads the APK plus a SHA-256 checksum. The release tag is based on `versionName` and `versionCode`, e.g. `v2.1.0-build11-<commit>`. If that release already exists, the assets are replaced with `gh release upload --clobber`.

Defaults to a public repository. To use a private repo:

```bash
KXIT_GH_VISIBILITY=private bash ~/KXitEngine/publish-kxit.sh --tests-passed
```

To use a repository you already created under a particular owner:

```bash
KXIT_GH_REPO=OWNER/KXit-Engine bash ~/KXitEngine/publish-kxit.sh --tests-passed
```

The script never force-pushes. If a local origin points to another repository, it aborts instead of pushing to the wrong place.
