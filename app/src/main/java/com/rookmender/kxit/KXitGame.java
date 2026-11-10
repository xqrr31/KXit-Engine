package com.rookmender.kxit;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.rookmender.kxit.module.KXitModule;
import com.rookmender.kxit.module.KXitModuleApi;
import com.rookmender.kxit.module.KXitModuleLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * KXit's complete gameplay/render loop, implemented with libGDX Java APIs.
 * Terrain is a single indexed mesh with hidden-face elimination, not one draw call per block.
 */
public final class KXitGame extends ApplicationAdapter {
    public interface FatalListener { void onFatal(String message); }

    private static final int WORLD_W = 48;
    private static final int WORLD_D = 48;
    private static final int WORLD_H = 20;
    private static final int AIR = 0;
    private static final int GRASS = 1;
    private static final int STONE = 2;
    private static final int COBBLE = 3;
    private static final int PLANKS = 4;
    private static final int DIRT = 5;
    private static final int LEAVES = 6;
    private static final int WOOD = 7;
    private static final int SAND = 8;
    private static final float PLAYER_EYE = 1.62f;
    private static final float MOVE_SPEED = 4.0f;
    private static final float GRAVITY = 17.5f;
    private static final float JUMP_SPEED = 6.0f;
    private static final float ACTION_REPEAT_SECONDS = .22f;

    private final File shaderDirectory;
    private final File moduleDirectory;
    private final File privateFilesDirectory;
    private final FatalListener fatalListener;

    private final byte[][][] world = new byte[WORLD_W][WORLD_H][WORLD_D];
    private final Vector3 cameraDirection = new Vector3();
    private final com.badlogic.gdx.math.Matrix4 hudProjection = new com.badlogic.gdx.math.Matrix4();
    private final Rectangle upRect = new Rectangle();
    private final Rectangle downRect = new Rectangle();
    private final Rectangle leftRect = new Rectangle();
    private final Rectangle rightRect = new Rectangle();
    private final Rectangle jumpRect = new Rectangle();
    private final Rectangle breakRect = new Rectangle();
    private final Rectangle placeRect = new Rectangle();
    private final Rectangle viewRect = new Rectangle();
    private final Rectangle[] hotbarRects = new Rectangle[6];
    private final boolean[] held = new boolean[4];
    private final int[] heldPointers = {-1, -1, -1, -1};
    private final boolean[] actionHeld = new boolean[2];
    private final int[] actionPointers = {-1, -1};
    private final float[] actionRepeatTimers = new float[2];
    private final int[] hotbarBlocks = {GRASS, STONE, COBBLE, PLANKS, SAND, DIRT};
    private final List<String> loadedModuleNames = new ArrayList<>();

    private Texture atlas;
    private TextureRegion[] blockRegions;
    private ShaderProgram terrainShader;
    private Mesh terrainMesh;
    private SpriteBatch spriteBatch;
    private ShapeRenderer shapeRenderer;
    private BitmapFont font;
    private PerspectiveCamera perspectiveCamera;
    private OrthographicCamera orthographicCamera;
    private Camera activeCamera;
    private KXitModuleLoader moduleLoader;

    private int screenWidth;
    private int screenHeight;
    private float density = 1f;
    private int selectedBlock = GRASS;
    private int fps;
    private int fpsFrames;
    private float fpsAccumulator;
    private float hudTextAccumulator;
    private String fpsText = "KXIT ENGINE   |   FPS 0";
    private String coordinatesText = "XYZ  0 / 0 / 0";
    private float playerX = 24f;
    private float playerZ = 41f;
    private float playerFeetY = 4f;
    private float verticalVelocity;
    private float yaw;
    private float pitch = -0.16f;
    private float daylight = 1f;
    private float meshFogEnd = 42f;
    private boolean meshDirty = true;
    private boolean orthographicView;
    private boolean initialized;
    private boolean failed;
    private int lookPointer = -1;
    private float previousLookX;
    private float previousLookY;
    private String moduleLabel = "0 MODULES";
    private boolean showCoordinates = true;

    public KXitGame(File shaderDirectory, File moduleDirectory, File privateFilesDirectory,
                    FatalListener fatalListener) {
        this.shaderDirectory = shaderDirectory;
        this.moduleDirectory = moduleDirectory;
        this.privateFilesDirectory = privateFilesDirectory;
        this.fatalListener = fatalListener;
    }

    @Override
    public void create() {
        try {
            ShaderProgram.pedantic = true;
            loadExternalShaders();
            atlas = new Texture(Gdx.files.internal("blocks.png"), false);
            if (atlas.getWidth() != 128 || atlas.getHeight() != 128) {
                throw new GdxRuntimeException("assets/blocks.png must be exactly 128×128 (8×8 tiles, 16×16 each).");
            }
            atlas.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            atlas.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            createBlockRegions();

            spriteBatch = new SpriteBatch(128);
            shapeRenderer = new ShapeRenderer(256);
            font = new BitmapFont();
            density = MathUtils.clamp(Gdx.graphics.getDensity(), .85f, 1.25f);
            font.getData().setScale(density);

            createCameras();
            generateWorld();
            rebuildTerrainMesh();
            loadModules();
            Gdx.input.setInputProcessor(new GameInput());
            initialized = true;
        } catch (Throwable error) {
            fail("KXit startup failed:\n\n" + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void loadExternalShaders() throws Exception {
        File vertexFile = new File(shaderDirectory, "terrain.vert");
        File fragmentFile = new File(shaderDirectory, "terrain.frag");
        if (!vertexFile.isFile() || vertexFile.length() == 0) {
            throw new IllegalStateException("Missing vertex shader: " + vertexFile.getAbsolutePath());
        }
        if (!fragmentFile.isFile() || fragmentFile.length() == 0) {
            throw new IllegalStateException("Missing fragment shader: " + fragmentFile.getAbsolutePath());
        }
        String vertexSource = readTextFile(vertexFile);
        String fragmentSource = readTextFile(fragmentFile);
        terrainShader = new ShaderProgram(vertexSource, fragmentSource);
        if (!terrainShader.isCompiled()) {
            throw new IllegalStateException("External shader compilation failed.\n\n" + terrainShader.getLog());
        }
        String[] requiredUniforms = {"u_projViewTrans", "u_texture", "u_cameraPosition", "u_fogColor", "u_fogStart", "u_fogEnd", "u_daylight"};
        for (String uniform : requiredUniforms) {
            if (terrainShader.getUniformLocation(uniform) < 0) {
                throw new IllegalStateException("Shader interface is missing required uniform: " + uniform);
            }
        }
    }

    private static String readTextFile(File file) throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private void createBlockRegions() {
        blockRegions = new TextureRegion[10];
        // Texture atlas row 0: grass top, stone, cobble, planks, grass side, dirt, leaves, wood.
        // Atlas row 1 column 0: sand.
        for (int id = 1; id <= 8; id++) {
            int tile = tileForBlock(id, 4);
            blockRegions[id] = new TextureRegion(atlas, (tile % 8) * 16, (tile / 8) * 16, 16, 16);
        }
    }

    private void createCameras() {
        screenWidth = Math.max(1, Gdx.graphics.getWidth());
        screenHeight = Math.max(1, Gdx.graphics.getHeight());
        perspectiveCamera = new PerspectiveCamera(72f, screenWidth, screenHeight);
        perspectiveCamera.near = 0.08f;
        perspectiveCamera.far = 72f;
        orthographicCamera = new OrthographicCamera(30f, 30f * screenHeight / screenWidth);
        activeCamera = perspectiveCamera;
        updateLayout();
        updateCamera();
    }

    private void generateWorld() {
        for (int x = 0; x < WORLD_W; x++) {
            for (int z = 0; z < WORLD_D; z++) {
                float wave = MathUtils.sin(x * 0.21f) * 1.15f
                        + MathUtils.cos(z * 0.19f) * 0.92f
                        + MathUtils.sin((x + z) * 0.13f) * 0.58f
                        + MathUtils.cos((x - z) * 0.09f) * 0.32f;
                int height = MathUtils.clamp(Math.round(5.0f + wave), 2, 10);
                for (int y = 0; y < height; y++) {
                    world[x][y][z] = (byte) (y == height - 1 ? GRASS : (y < height - 3 ? STONE : DIRT));
                }
            }
        }

        // A winding sandy path makes the spawn area readable from the starting camera.
        for (int x = 8; x < 39; x++) {
            int centerZ = 22 + Math.round(MathUtils.sin(x * .18f) * 3f);
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                int top = surfaceHeight(x, z) - 1;
                if (top >= 0 && world[x][top][z] == GRASS) world[x][top][z] = SAND;
            }
        }

        // Clear only the spawn pocket; surrounding hills and trees remain visible.
        for (int x = 20; x < 29; x++) {
            for (int z = 36; z < 45; z++) {
                int floor = surfaceHeight(x, z);
                for (int y = floor; y < WORLD_H; y++) world[x][y][z] = AIR;
            }
        }

        growTree(6, 8); growTree(11, 18); growTree(7, 34); growTree(17, 10);
        growTree(31, 8); growTree(39, 18); growTree(39, 36); growTree(9, 42);
        growTree(32, 43); growTree(19, 30); growTree(26, 13); growTree(43, 27);
        playerX = 24.2f;
        playerZ = 41.5f;
        playerFeetY = surfaceHeight(MathUtils.floor(playerX), MathUtils.floor(playerZ));
        yaw = 0f;
        pitch = -0.16f;
        meshDirty = true;
    }

    private void growTree(int x, int z) {
        int base = surfaceHeight(x, z);
        if (base < 1 || base + 5 >= WORLD_H) return;
        for (int y = base; y < base + 3; y++) world[x][y][z] = WOOD;
    }

    private int surfaceHeight(int x, int z) {
        if (x < 0 || z < 0 || x >= WORLD_W || z >= WORLD_D) return 2;
        for (int y = WORLD_H - 1; y >= 0; y--) if (world[x][y][z] != AIR) return y + 1;
        return 1;
    }

    private int blockAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= WORLD_W || y >= WORLD_H || z >= WORLD_D) return AIR;
        return world[x][y][z] & 0xff;
    }

    private void setBlock(int x, int y, int z, int id) {
        if (x < 0 || y < 0 || z < 0 || x >= WORLD_W || y >= WORLD_H || z >= WORLD_D) return;
        if (id < AIR || id > SAND) return;
        if (world[x][y][z] == (byte) id) return;
        world[x][y][z] = (byte) id;
        meshDirty = true;
    }

    private void rebuildTerrainMesh() {
        MeshBuffer buffer = new MeshBuffer();
        for (int x = 0; x < WORLD_W; x++) {
            for (int y = 0; y < WORLD_H; y++) {
                for (int z = 0; z < WORLD_D; z++) {
                    int id = blockAt(x, y, z);
                    if (id == AIR) continue;
                    addBlockFaces(buffer, x, y, z, id);
                }
            }
        }
        addLowPolyProps(buffer);
        if (buffer.vertexCount() > 65530) {
            throw new GdxRuntimeException("Terrain mesh exceeds the 16-bit index limit. Reduce world size or split it into chunks.");
        }
        if (terrainMesh != null) terrainMesh.dispose();
        terrainMesh = new Mesh(true, Math.max(4, buffer.vertexCount()), Math.max(6, buffer.indexCount()),
                VertexAttribute.Position(), VertexAttribute.TexCoords(0), VertexAttribute.ColorUnpacked());
        terrainMesh.setVertices(buffer.vertices());
        terrainMesh.setIndices(buffer.indices());
        meshDirty = false;
    }

    private void addBlockFaces(MeshBuffer out, int x, int y, int z, int id) {
        float x0 = x, x1 = x + 1f, y0 = y, y1 = y + 1f, z0 = z, z1 = z + 1f;
        if (blockAt(x, y, z + 1) == AIR) out.quad(
                x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1, tileForBlock(id, 0), .87f);
        if (blockAt(x, y, z - 1) == AIR) out.quad(
                x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0, tileForBlock(id, 1), .73f);
        if (blockAt(x - 1, y, z) == AIR) out.quad(
                x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0, tileForBlock(id, 2), .79f);
        if (blockAt(x + 1, y, z) == AIR) out.quad(
                x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1, tileForBlock(id, 3), .82f);
        if (blockAt(x, y + 1, z) == AIR) out.quad(
                x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, tileForBlock(id, 4), 1.08f);
        if (y > 0 && blockAt(x, y - 1, z) == AIR) out.quad(
                x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, tileForBlock(id, 5), .56f);
    }

    private int tileForBlock(int id, int face) {
        if (id == GRASS) return face == 4 ? 0 : (face == 5 ? 5 : 4);
        if (id == STONE) return 1;
        if (id == COBBLE) return 2;
        if (id == PLANKS) return 3;
        if (id == DIRT) return 5;
        if (id == LEAVES) return 6;
        if (id == WOOD) return 7;
        if (id == SAND) return 8;
        return 1;
    }

    private void addLowPolyProps(MeshBuffer out) {
        // Low-poly crowns use faceted leaf pyramids; trunks remain textured voxel columns.
        addTreeCrown(out, 6.5f, surfaceHeight(6, 8) + .65f, 8.5f, 1.55f);
        addTreeCrown(out, 11.5f, surfaceHeight(11, 18) + .65f, 18.5f, 1.45f);
        addTreeCrown(out, 7.5f, surfaceHeight(7, 34) + .65f, 34.5f, 1.65f);
        addTreeCrown(out, 17.5f, surfaceHeight(17, 10) + .65f, 10.5f, 1.42f);
        addTreeCrown(out, 31.5f, surfaceHeight(31, 8) + .65f, 8.5f, 1.65f);
        addTreeCrown(out, 39.5f, surfaceHeight(39, 18) + .65f, 18.5f, 1.52f);
        addTreeCrown(out, 39.5f, surfaceHeight(39, 36) + .65f, 36.5f, 1.62f);
        addTreeCrown(out, 9.5f, surfaceHeight(9, 42) + .65f, 42.5f, 1.42f);
        addTreeCrown(out, 32.5f, surfaceHeight(32, 43) + .65f, 43.5f, 1.55f);
        addTreeCrown(out, 19.5f, surfaceHeight(19, 30) + .65f, 30.5f, 1.48f);
        addTreeCrown(out, 26.5f, surfaceHeight(26, 13) + .65f, 13.5f, 1.58f);
        addTreeCrown(out, 43.5f, surfaceHeight(43, 27) + .65f, 27.5f, 1.48f);
        addRock(out, 8.5f, surfaceHeight(8, 11), 11.5f, .65f);
        addRock(out, 23.5f, surfaceHeight(23, 12), 12.5f, .48f);
        addRock(out, 18.5f, surfaceHeight(18, 31), 31.5f, .56f);
        addRock(out, 35.5f, surfaceHeight(35, 28), 28.5f, .72f);
        addRock(out, 14.5f, surfaceHeight(14, 39), 39.5f, .52f);
    }

    private void addTreeCrown(MeshBuffer out, float x, float y, float z, float radius) {
        float[][] ring = {{x-radius,y-.45f,z},{x,y-.45f,z-radius},{x+radius,y-.45f,z},{x,y-.45f,z+radius}};
        float[] top = {x,y+1.2f,z};
        float[] bottom = {x,y-.9f,z};
        for (int i=0;i<4;i++) {
            int j=(i+1)%4;
            out.triangle(top, ring[j], ring[i], 6, .98f - (i%2)*.08f);
            out.triangle(bottom, ring[i], ring[j], 6, .68f);
        }
    }

    private void addRock(MeshBuffer out, float x, float y, float z, float radius) {
        float[] top={x,y+radius*1.25f,z};
        float[] a={x-radius,y,z-radius*.7f};
        float[] b={x+radius,y,z-radius*.7f};
        float[] c={x+radius*.8f,y,z+radius};
        float[] d={x-radius*.8f,y,z+radius};
        out.triangle(top,b,a,2,.88f); out.triangle(top,c,b,2,.76f);
        out.triangle(top,d,c,2,.82f); out.triangle(top,a,d,2,.93f);
        out.quad(new float[][]{a,b,c,d},2,.58f);
    }

    private void loadModules() throws Exception {
        moduleLoader = new KXitModuleLoader();
        KXitModuleApi api = new KXitModuleApi(new KXitModuleApi.Host() {
            @Override public int getBlock(int x, int y, int z) { return blockAt(x, y, z); }
            @Override public void setBlock(int x, int y, int z, int blockId) { KXitGame.this.setBlock(x, y, z, blockId); }
            @Override public void setDaylight(float amount) { daylight = MathUtils.clamp(amount, .25f, 1.25f); }
            @Override public void log(String message) { Gdx.app.log("KXitModule", message == null ? "" : message); }
        });
        loadedModuleNames.addAll(moduleLoader.load(moduleDirectory, privateFilesDirectory, api));
        moduleLabel = loadedModuleNames.size() + " MODULES";
    }

    private void updateLayout() {
        float w = screenWidth;
        float h = screenHeight;
        float d = density;
        float button = Math.min(58f * d, Math.min(w * .105f, h * .19f));
        float gap = 7f * d;
        float margin = 15f * d;

        // Left D-pad: directional symbols are drawn as vector geometry, never text glyphs.
        upRect.set(margin + button + gap, margin + button + gap, button, button);
        leftRect.set(margin, margin + button + gap, button, button);
        downRect.set(margin + button + gap, margin, button, button);
        rightRect.set(margin + (button + gap) * 2f, margin + button + gap, button, button);

        // Right action cluster is separated from the look-drag region.
        jumpRect.set(w - margin - (button + gap) * 2f, margin + button + gap, button, button);
        breakRect.set(w - margin - button, margin + button + gap, button, button);
        placeRect.set(w - margin - (button + gap) * 2f, margin, button, button);
        viewRect.set(w - margin - button, margin, button, button);

        float slot = Math.min(49f * d, Math.max(34f * d, w * .052f));
        float slotGap = 5f * d;
        float total = slot * hotbarRects.length + slotGap * (hotbarRects.length - 1);
        float start = (w - total) * .5f;
        for (int i = 0; i < hotbarRects.length; i++) {
            if (hotbarRects[i] == null) hotbarRects[i] = new Rectangle();
            hotbarRects[i].set(start + i * (slot + slotGap), margin + 2f * d, slot, slot);
        }
    }

    private void updateCamera() {
        float cp = MathUtils.cos(pitch);
        cameraDirection.set(MathUtils.sin(yaw) * cp, MathUtils.sin(pitch), -MathUtils.cos(yaw) * cp).nor();
        if (!orthographicView) {
            activeCamera = perspectiveCamera;
            perspectiveCamera.viewportWidth = screenWidth;
            perspectiveCamera.viewportHeight = screenHeight;
            perspectiveCamera.position.set(playerX, playerFeetY + PLAYER_EYE, playerZ);
            perspectiveCamera.direction.set(cameraDirection);
            perspectiveCamera.up.set(Vector3.Y);
            perspectiveCamera.update();
        } else {
            activeCamera = orthographicCamera;
            orthographicCamera.viewportWidth = 30f;
            orthographicCamera.viewportHeight = 30f * screenHeight / Math.max(1f, screenWidth);
            orthographicCamera.position.set(playerX + 10f, playerFeetY + 12f, playerZ + 10f);
            orthographicCamera.direction.set(playerX - orthographicCamera.position.x,
                    playerFeetY - orthographicCamera.position.y + 1f,
                    playerZ - orthographicCamera.position.z).nor();
            orthographicCamera.up.set(Vector3.Y);
            orthographicCamera.update();
        }
    }

    @Override
    public void render() {
        if (failed) {
            Gdx.gl.glClearColor(.025f, .035f, .055f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            return;
        }
        if (!initialized) return;
        float delta = Math.min(Gdx.graphics.getDeltaTime(), .05f);
        updatePlayer(delta);
        updateCamera();
        updateHeldActions(delta);
        if (moduleLoader != null) {
            try { moduleLoader.update(delta); }
            catch (Throwable error) { fail("KXit module update failed:\n\n" + error); return; }
        }
        if (meshDirty) rebuildTerrainMesh();
        updateFps(delta);
        updateHudText(delta);

        Gdx.gl.glViewport(0, 0, screenWidth, screenHeight);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        // Faces are already removed during mesh generation. Disabling culling also
        // keeps the faceted low-poly prop triangles visible on GLES2 drivers.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glClearColor(.42f, .63f, .78f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        terrainShader.bind();
        terrainShader.setUniformMatrix("u_projViewTrans", activeCamera.combined);
        terrainShader.setUniformi("u_texture", 0);
        terrainShader.setUniformf("u_cameraPosition", activeCamera.position.x, activeCamera.position.y, activeCamera.position.z);
        terrainShader.setUniformf("u_fogColor", .55f, .69f, .77f, 1f);
        terrainShader.setUniformf("u_fogStart", 16f);
        terrainShader.setUniformf("u_fogEnd", meshFogEnd);
        terrainShader.setUniformf("u_daylight", daylight);
        atlas.bind(0);
        if (terrainMesh != null) terrainMesh.render(terrainShader, GL20.GL_TRIANGLES);
        drawHud();
    }

    private void updatePlayer(float delta) {
        float forward = (held[0] || Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP) ? 1f : 0f)
                - (held[1] || Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN) ? 1f : 0f);
        float side = (held[3] || Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT) ? 1f : 0f)
                - (held[2] || Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT) ? 1f : 0f);
        float length = (float)Math.sqrt(forward*forward + side*side);
        if (length > 1f) { forward /= length; side /= length; }

        float dx = (MathUtils.sin(yaw) * forward + MathUtils.cos(yaw) * side) * MOVE_SPEED * delta;
        float dz = (-MathUtils.cos(yaw) * forward + MathUtils.sin(yaw) * side) * MOVE_SPEED * delta;
        if (dx != 0f || dz != 0f) {
            float nextX = MathUtils.clamp(playerX + dx, 1.2f, WORLD_W - 1.2f);
            float nextZ = MathUtils.clamp(playerZ + dz, 1.2f, WORLD_D - 1.2f);
            int nextGround = surfaceHeight(MathUtils.floor(nextX), MathUtils.floor(nextZ));
            if (nextGround <= playerFeetY + .65f) playerX = nextX;
            nextGround = surfaceHeight(MathUtils.floor(playerX), MathUtils.floor(nextZ));
            if (nextGround <= playerFeetY + .65f) playerZ = nextZ;
        }

        if ((Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) && Math.abs(verticalVelocity) < .01f) verticalVelocity = JUMP_SPEED;
        verticalVelocity -= GRAVITY * delta;
        playerFeetY += verticalVelocity * delta;
        int ground = surfaceHeight(MathUtils.floor(playerX), MathUtils.floor(playerZ));
        if (playerFeetY <= ground) { playerFeetY = ground; verticalVelocity = 0f; }
        if (playerFeetY > WORLD_H + 4) {
            playerFeetY = ground;
            verticalVelocity = 0f;
        }
    }

    private void updateHeldActions(float delta) {
        for (int i = 0; i < actionHeld.length; i++) {
            if (!actionHeld[i]) continue;
            actionRepeatTimers[i] -= delta;
            if (actionRepeatTimers[i] <= 0f) {
                performAction(i == 0 ? 1 : 2);
                actionRepeatTimers[i] = ACTION_REPEAT_SECONDS;
            }
        }
    }

    private void updateFps(float delta) {
        fpsFrames++;
        fpsAccumulator += delta;
        if (fpsAccumulator >= .5f) {
            fps = Math.round(fpsFrames / fpsAccumulator);
            fpsFrames = 0;
            fpsAccumulator = 0f;
        }
    }

    private void updateHudText(float delta) {
        hudTextAccumulator += delta;
        if (hudTextAccumulator < .20f) return;
        hudTextAccumulator = 0f;
        fpsText = "KXIT ENGINE   |   FPS " + fps;
        coordinatesText = "XYZ  " + MathUtils.floor(playerX) + " / "
                + MathUtils.floor(playerFeetY) + " / " + MathUtils.floor(playerZ);
    }

    private void drawHud() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        hudProjection.setToOrtho2D(0, 0, screenWidth, screenHeight);
        shapeRenderer.setProjectionMatrix(hudProjection);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawButton(upRect, held[0], "UP");
        drawButton(leftRect, held[2], "LEFT");
        drawButton(downRect, held[1], "DOWN");
        drawButton(rightRect, held[3], "RIGHT");
        drawButton(jumpRect, false, "JUMP");
        drawButton(breakRect, actionHeld[0], "MINE");
        drawButton(placeRect, actionHeld[1], "PLACE");
        drawButton(viewRect, false, "VIEW");

        for (int i = 0; i < hotbarRects.length; i++) {
            Rectangle r = hotbarRects[i];
            boolean selected = hotbarBlocks[i] == selectedBlock;
            shapeRenderer.setColor(selected ? .14f : .035f, selected ? .32f : .075f,
                    selected ? .55f : .12f, selected ? .96f : .78f);
            shapeRenderer.rect(r.x, r.y, r.width, r.height);
            if (selected) {
                shapeRenderer.setColor(.42f, .69f, 1f, .95f);
                shapeRenderer.rect(r.x, r.y, r.width, 2.2f * density);
                shapeRenderer.rect(r.x, r.y + r.height - 2.2f * density, r.width, 2.2f * density);
            }
        }
        // Small clean crosshair.
        float cx = screenWidth * .5f, cy = screenHeight * .53f, cross = 8f * density;
        shapeRenderer.setColor(1f, 1f, 1f, .88f);
        shapeRenderer.rect(cx - cross, cy - .8f * density, cross * 2f, 1.6f * density);
        shapeRenderer.rect(cx - .8f * density, cy - cross, 1.6f * density, cross * 2f);
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudProjection);
        spriteBatch.begin();
        font.setColor(1f, 1f, 1f, .96f);
        font.draw(spriteBatch, fpsText, 16f * density, screenHeight - 18f * density);
        font.setColor(.86f, .92f, .98f, .92f);
        if (showCoordinates) font.draw(spriteBatch, coordinatesText, 16f * density, screenHeight - 39f * density);
        font.draw(spriteBatch, moduleLabel, 16f * density, screenHeight - (showCoordinates ? 60f : 39f) * density);

        for (int i = 0; i < hotbarRects.length; i++) {
            Rectangle r = hotbarRects[i];
            int block = hotbarBlocks[i];
            if (block >= 0 && block < blockRegions.length && blockRegions[block] != null) {
                float inset = Math.max(4f, 5f * density);
                spriteBatch.draw(blockRegions[block], r.x + inset, r.y + inset,
                        r.width - inset * 2f, r.height - inset * 2f);
            }
        }
        // Labels are positioned under the icon area and fit narrow screens.
        drawButtonText(jumpRect, "JUMP");
        drawButtonText(breakRect, "MINE");
        drawButtonText(placeRect, "PLACE");
        drawButtonText(viewRect, orthographicView ? "3D" : "VIEW");
        spriteBatch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    private void drawButton(Rectangle r, boolean active, String title) {
        float alpha = active ? .94f : .72f;
        shapeRenderer.setColor(active ? .12f : .025f, active ? .28f : .055f,
                active ? .49f : .105f, alpha);
        shapeRenderer.rect(r.x, r.y, r.width, r.height);
        shapeRenderer.setColor(.42f, .66f, .91f, active ? 1f : .72f);
        shapeRenderer.rect(r.x, r.y, r.width, 2f * density);
        float cx = r.x + r.width * .5f;
        float cy = r.y + r.height * .52f;
        float scale = Math.max(.75f, Math.min(1.2f, r.width / (58f * density)));
        shapeRenderer.setColor(.78f, .88f, 1f, .97f);
        if ("UP".equals(title)) drawArrow(cx, cy, 0, 1, scale);
        else if ("DOWN".equals(title)) drawArrow(cx, cy, 0, -1, scale);
        else if ("LEFT".equals(title)) drawArrow(cx, cy, -1, 0, scale);
        else if ("RIGHT".equals(title)) drawArrow(cx, cy, 1, 0, scale);
        else if ("JUMP".equals(title)) drawArrow(cx, cy + 3f * density, 0, 1, scale * .88f);
        else if ("MINE".equals(title)) {
            shapeRenderer.rectLine(cx - 10f * scale, cy - 9f * scale,
                    cx + 10f * scale, cy + 10f * scale, 3f * scale);
            shapeRenderer.rectLine(cx - 10f * scale, cy + 8f * scale,
                    cx + 10f * scale, cy + 8f * scale, 4f * scale);
        } else if ("PLACE".equals(title)) {
            float q = 10f * scale;
            shapeRenderer.triangle(cx, cy + q, cx - q, cy + 3f * scale, cx, cy - 2f * scale);
            shapeRenderer.triangle(cx, cy + q, cx + q, cy + 3f * scale, cx, cy - 2f * scale);
            shapeRenderer.triangle(cx - q, cy + 3f * scale, cx, cy - 2f * scale, cx, cy - q);
            shapeRenderer.triangle(cx + q, cy + 3f * scale, cx, cy - 2f * scale, cx, cy - q);
        } else if ("VIEW".equals(title)) {
            shapeRenderer.rect(cx - 10f * scale, cy - 7f * scale, 20f * scale, 14f * scale);
            shapeRenderer.setColor(.025f, .07f, .12f, 1f);
            shapeRenderer.circle(cx, cy, 4f * scale, 16);
        }
    }

    private void drawArrow(float cx, float cy, int dx, int dy, float scale) {
        float shaft = 4f * scale;
        float half = 4f * scale;
        float tip = 13f * scale;
        float base = 3f * scale;
        if (dy > 0) {
            shapeRenderer.rect(cx - shaft, cy - 8f * scale, shaft * 2f, 12f * scale);
            shapeRenderer.triangle(cx, cy + tip, cx - half * 2f, cy + base, cx + half * 2f, cy + base);
        } else if (dy < 0) {
            shapeRenderer.rect(cx - shaft, cy - 4f * scale, shaft * 2f, 12f * scale);
            shapeRenderer.triangle(cx, cy - tip, cx - half * 2f, cy - base, cx + half * 2f, cy - base);
        } else if (dx < 0) {
            shapeRenderer.rect(cx - 8f * scale, cy - shaft, 12f * scale, shaft * 2f);
            shapeRenderer.triangle(cx - tip, cy, cx - base, cy - half * 2f, cx - base, cy + half * 2f);
        } else {
            shapeRenderer.rect(cx - 4f * scale, cy - shaft, 12f * scale, shaft * 2f);
            shapeRenderer.triangle(cx + tip, cy, cx + base, cy - half * 2f, cx + base, cy + half * 2f);
        }
    }

    private void drawButtonText(Rectangle r, String text) {
        font.setColor(1f, 1f, 1f, .90f);
        float width = text.length() * 6.2f * density;
        float baseline = r.y + 7f * density;
        font.draw(spriteBatch, text, r.x + (r.width - width) * .5f, baseline);
    }

    private void setShapeColor(float r, float g, float b, float a) {
        shapeRenderer.setColor(r, g, b, a);
    }

    private void performAction(int action) {
        Hit hit = rayHit(6.2f);
        if (hit == null) return;
        if (action == 1) {
            setBlock(hit.x, hit.y, hit.z, AIR);
        } else if (action == 2) {
            if (hit.previousX < 0 || hit.previousY < 0 || hit.previousZ < 0) return;
            if (hit.previousY >= WORLD_H || hit.previousX >= WORLD_W || hit.previousZ >= WORLD_D) return;
            if (blockAt(hit.previousX, hit.previousY, hit.previousZ) != AIR) return;
            if (hit.previousY >= MathUtils.floor(playerFeetY) && hit.previousY <= MathUtils.floor(playerFeetY + 1.75f)
                    && hit.previousX == MathUtils.floor(playerX) && hit.previousZ == MathUtils.floor(playerZ)) return;
            setBlock(hit.previousX, hit.previousY, hit.previousZ, selectedBlock);
        }
    }

    private Hit rayHit(float maxDistance) {
        Vector3 origin = activeCamera.position;
        Vector3 direction = activeCamera.direction;
        int previousX = -1, previousY = -1, previousZ = -1;
        for (float distance = .12f; distance <= maxDistance; distance += .045f) {
            int x = MathUtils.floor(origin.x + direction.x * distance);
            int y = MathUtils.floor(origin.y + direction.y * distance);
            int z = MathUtils.floor(origin.z + direction.z * distance);
            if (x == previousX && y == previousY && z == previousZ) continue;
            if (blockAt(x, y, z) != AIR) return new Hit(x, y, z, previousX, previousY, previousZ);
            previousX = x; previousY = y; previousZ = z;
        }
        return null;
    }

    private static final class Hit {
        final int x,y,z,previousX,previousY,previousZ;
        Hit(int x,int y,int z,int px,int py,int pz) { this.x=x; this.y=y; this.z=z; previousX=px; previousY=py; previousZ=pz; }
    }

    private void fail(String message) {
        if (failed) return;
        failed = true;
        initialized = false;
        if (terrainMesh != null) { terrainMesh.dispose(); terrainMesh = null; }
        String full = message == null ? "Unknown KXit startup error" : message;
        Gdx.app.postRunnable(() -> fatalListener.onFatal(full));
    }

    private final class GameInput extends InputAdapter {
        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            float x = screenX;
            float y = screenHeight - screenY;
            if (upRect.contains(x,y)) { held[0] = true; heldPointers[0] = pointer; return true; }
            if (downRect.contains(x,y)) { held[1] = true; heldPointers[1] = pointer; return true; }
            if (leftRect.contains(x,y)) { held[2] = true; heldPointers[2] = pointer; return true; }
            if (rightRect.contains(x,y)) { held[3] = true; heldPointers[3] = pointer; return true; }
            if (jumpRect.contains(x,y)) { if (Math.abs(verticalVelocity) < .01f) verticalVelocity = JUMP_SPEED; return true; }
            if (breakRect.contains(x,y)) { actionHeld[0] = true; actionPointers[0] = pointer; actionRepeatTimers[0] = 0f; return true; }
            if (placeRect.contains(x,y)) { actionHeld[1] = true; actionPointers[1] = pointer; actionRepeatTimers[1] = 0f; return true; }
            if (viewRect.contains(x,y)) { orthographicView = !orthographicView; return true; }
            for (int i=0;i<hotbarRects.length;i++) {
                if (hotbarRects[i].contains(x,y)) { selectedBlock = hotbarBlocks[i]; return true; }
            }
            if (x > screenWidth * .34f && y > 100f && lookPointer == -1) {
                lookPointer = pointer;
                previousLookX = x;
                previousLookY = y;
                return true;
            }
            return false;
        }

        @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (pointer != lookPointer) return false;
            float x = screenX;
            float y = screenHeight - screenY;
            float dx = x - previousLookX;
            float dy = y - previousLookY;
            yaw -= dx * .0043f;
            pitch = MathUtils.clamp(pitch + dy * .0032f, -.78f, .82f);
            previousLookX = x;
            previousLookY = y;
            return true;
        }

        @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (pointer == lookPointer) lookPointer = -1;
            for (int i=0;i<heldPointers.length;i++) {
                if (heldPointers[i] == pointer) {
                    heldPointers[i] = -1;
                    held[i] = false;
                }
            }
            for (int i=0;i<actionPointers.length;i++) {
                if (actionPointers[i] == pointer) {
                    actionPointers[i] = -1;
                    actionHeld[i] = false;
                    actionRepeatTimers[i] = 0f;
                }
            }
            return true;
        }

        @Override public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.SPACE && Math.abs(verticalVelocity) < .01f) verticalVelocity = JUMP_SPEED;
            if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) orthographicView = !orthographicView;
            return false;
        }

        @Override public boolean keyUp(int keycode) { return false; }
    }

    @Override
    public void resize(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        updateLayout();
        if (perspectiveCamera != null) {
            perspectiveCamera.viewportWidth = screenWidth;
            perspectiveCamera.viewportHeight = screenHeight;
            perspectiveCamera.update();
        }
    }

    @Override
    public void pause() {
        for (int i=0;i<held.length;i++) { held[i] = false; heldPointers[i] = -1; }
        for (int i=0;i<actionHeld.length;i++) { actionHeld[i] = false; actionPointers[i] = -1; actionRepeatTimers[i] = 0f; }
        lookPointer = -1;
    }

    @Override
    public void dispose() {
        if (moduleLoader != null) moduleLoader.dispose();
        if (terrainMesh != null) terrainMesh.dispose();
        if (terrainShader != null) terrainShader.dispose();
        if (atlas != null) atlas.dispose();
        if (spriteBatch != null) spriteBatch.dispose();
        if (shapeRenderer != null) shapeRenderer.dispose();
        if (font != null) font.dispose();
    }

    /** Compact growable mesh buffer. One world rebuild, one GPU mesh, one terrain draw call. */
    private static final class MeshBuffer {
        private float[] vertices = new float[131072];
        private short[] indices = new short[65536];
        private int vertexFloats;
        private int indexCount;

        int vertexCount() { return vertexFloats / 9; }
        int indexCount() { return indexCount; }
        float[] vertices() { return java.util.Arrays.copyOf(vertices, vertexFloats); }
        short[] indices() { return java.util.Arrays.copyOf(indices, indexCount); }

        private void ensureVertices(int extra) {
            int required = vertexFloats + extra;
            if (required > vertices.length) vertices = java.util.Arrays.copyOf(vertices, Math.max(required, vertices.length * 2));
        }
        private void ensureIndices(int extra) {
            int required = indexCount + extra;
            if (required > indices.length) indices = java.util.Arrays.copyOf(indices, Math.max(required, indices.length * 2));
        }

        void quad(float x0, float y0, float z0,
                  float x1, float y1, float z1,
                  float x2, float y2, float z2,
                  float x3, float y3, float z3,
                  int tile, float shade) {
            int base = vertexCount();
            if (base + 4 >= 65535) throw new GdxRuntimeException("Mesh reached the unsigned-short vertex limit.");
            int tx = tile & 7;
            int ty = tile >> 3;
            float u0 = (tx * 16f + .5f) / 128f;
            float u1 = ((tx + 1) * 16f - .5f) / 128f;
            // PNG row 0 is stored at the top, while GL v=0 is the bottom.
            // Invert atlas V so manual mesh sampling matches TextureRegion.
            float vTop = 1f - (ty * 16f + .5f) / 128f;
            float vBottom = 1f - ((ty + 1) * 16f - .5f) / 128f;
            addVertex(x0,y0,z0,u0,vBottom,shade);
            addVertex(x1,y1,z1,u1,vBottom,shade);
            addVertex(x2,y2,z2,u1,vTop,shade);
            addVertex(x3,y3,z3,u0,vTop,shade);
            ensureIndices(6);
            indices[indexCount++] = (short)base;
            indices[indexCount++] = (short)(base+1);
            indices[indexCount++] = (short)(base+2);
            indices[indexCount++] = (short)base;
            indices[indexCount++] = (short)(base+2);
            indices[indexCount++] = (short)(base+3);
        }

        void quad(float[][] points, int tile, float shade) {
            int base = vertexCount();
            if (base + 4 >= 65535) throw new GdxRuntimeException("Mesh reached the unsigned-short vertex limit.");
            int tx = tile & 7;
            int ty = tile >> 3;
            float u0 = (tx * 16f + .5f) / 128f;
            float u1 = ((tx + 1) * 16f - .5f) / 128f;
            // PNG row 0 is stored at the top, while GL v=0 is the bottom.
            // Invert atlas V so manual mesh sampling matches TextureRegion.
            float vTop = 1f - (ty * 16f + .5f) / 128f;
            float vBottom = 1f - ((ty + 1) * 16f - .5f) / 128f;
            addVertex(points[0][0],points[0][1],points[0][2],u0,vBottom,shade);
            addVertex(points[1][0],points[1][1],points[1][2],u1,vBottom,shade);
            addVertex(points[2][0],points[2][1],points[2][2],u1,vTop,shade);
            addVertex(points[3][0],points[3][1],points[3][2],u0,vTop,shade);
            ensureIndices(6);
            indices[indexCount++] = (short)base;
            indices[indexCount++] = (short)(base+1);
            indices[indexCount++] = (short)(base+2);
            indices[indexCount++] = (short)base;
            indices[indexCount++] = (short)(base+2);
            indices[indexCount++] = (short)(base+3);
        }

        void triangle(float[] a, float[] b, float[] c, int tile, float shade) {
            int base = vertexCount();
            if (base + 3 >= 65535) throw new GdxRuntimeException("Mesh reached the unsigned-short vertex limit.");
            int tx = tile & 7;
            int ty = tile >> 3;
            float u0 = (tx * 16f + .5f) / 128f;
            float u1 = ((tx + 1) * 16f - .5f) / 128f;
            // PNG row 0 is stored at the top, while GL v=0 is the bottom.
            // Invert atlas V so manual mesh sampling matches TextureRegion.
            float vTop = 1f - (ty * 16f + .5f) / 128f;
            float vBottom = 1f - ((ty + 1) * 16f - .5f) / 128f;
            addVertex(a[0],a[1],a[2],u0,vBottom,shade);
            addVertex(b[0],b[1],b[2],u1,vBottom,shade);
            addVertex(c[0],c[1],c[2],(u0+u1)*.5f,vTop,shade);
            ensureIndices(3);
            indices[indexCount++] = (short)base;
            indices[indexCount++] = (short)(base+1);
            indices[indexCount++] = (short)(base+2);
        }

        private void addVertex(float x, float y, float z, float u, float v, float shade) {
            ensureVertices(9);
            vertices[vertexFloats++] = x;
            vertices[vertexFloats++] = y;
            vertices[vertexFloats++] = z;
            vertices[vertexFloats++] = u;
            vertices[vertexFloats++] = v;
            vertices[vertexFloats++] = shade;
            vertices[vertexFloats++] = shade;
            vertices[vertexFloats++] = shade;
            vertices[vertexFloats++] = 1f;
        }
    }
}
