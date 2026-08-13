package com.metallum.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime toggles for the MetalAlloy shading layer.
 * <p>
 * Backed by {@code config/metallum-shaders.json}. Values here are read once
 * on load and cached; call {@link #save()} after mutating from a settings
 * screen.
 * <p>
 * STRIPPED DOWN (see /docs/metallum-buffer-rework.md): SSAO, shadows/CSM,
 * bloom, and fog are all gone for now -- every one of them either never
 * got its resource bindings right (shadow map, bloom) or shipped visibly
 * broken (SSAO) or was ripped out with nothing replacing it (fog). Rather
 * than debug four half-built passes at once, this config was cut back to
 * the one thing that's actually solid: the GBuffer + a single debug_depth
 * pass, so the buffer/binding plumbing in the render package can be
 * hardened first. Anyone with an old {@code metallum-shaders.json} on disk
 * that still has any of the old keys is fine: Gson just ignores fields it
 * can't map back onto this class.
 */
public final class ShaderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("metallum-shaders.json");

    private static ShaderConfig instance;

    // Master switch. If false, ShaderPipeline never engages and the
    // Metallum backend behaves exactly as it does today.
    public boolean enabled = true;

    // Debug visualization: shows the GBuffer's depth (linearized,
    // white = far, black = close) instead of the normal composited
    // image. This is currently the ONLY composite path -- see
    // CompositePass -- so it's on by default. Flip it off once a real
    // color composite exists again.
    public boolean debugDepth = false;

    // Debug visualization: shows the depth-reconstructed view-space
    // normal (see debug_normal.fsh) instead of the normal composited
    // image. Independent of debugDepth -- if both are true, debugNormal
    // wins in CompositePass#runFull (it's the full-scene stage; debugDepth
    // here only affects the opaque-only stage). Off by default since it's
    // heavier than debugDepth (see README's "Known perf issue" section).
    public boolean debugNormal = false;

    // Debug visualization: raw AO term from debug_occlusion.fsh (grayscale,
    // white = unoccluded, black = fully occluded), NOT yet multiplied
    // into scene color -- see debug_occlusion.fsh's header comment. Wins over
    // both debugNormal and debugDepth in CompositePass#runFull if true.
    // Heaviest of the three debug views (12-tap kernel, full-res) --
    // expect this to need the same half-res+upscale treatment discussed
    // for debugNormal, likely sooner.
    public boolean debugSSAO = false;
    public boolean ssrEnabled = true;

    // The real, full SSAO pass applied over opaque geometry.
    public boolean ssaoEnabled = false;

    // Render resolution scale for the deferred passes, independent of the
    // final present resolution. <1.0 lets you keep ultra features at
    // reasonable frametime on lower-end Apple Silicon.
    public float renderScale = 1.0f;

    public static synchronized ShaderConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ShaderConfig load() {
        if (Files.isRegularFile(PATH)) {
            try (var reader = Files.newBufferedReader(PATH)) {
                ShaderConfig loaded = GSON.fromJson(reader, ShaderConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | com.google.gson.JsonSyntaxException ignored) {
                // fall through to defaults; we don't want a bad config file
                // to prevent the game from starting.
            }
        }
        ShaderConfig defaults = new ShaderConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException ignored) {
            // Non-fatal: worst case the toggle doesn't persist across restarts.
        }
    }
}