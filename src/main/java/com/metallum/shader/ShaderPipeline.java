package com.metallum.shader;

/*
 * Entry point for the MetalAlloy shading layer. One instance lives for the
 * whole client session. Mixins call into this at the points marked below;
 * everything else in this file is backend-agnostic orchestration.
 * <p>
 * Frame order:
 * <ol>
 *   <li>{@link #beginFrame(int, int)} — resize GBuffer if needed</li>
 *   <li>gbuffer pass — vanilla terrain/entity rendering redirected to write
 *       into {@code gbuffer} instead of the swapchain directly</li>
 *   <li>{@link #compositeAndPresent} — after OPAQUE terrain draws, before
 *       translucent/entities: composite back onto main</li>
 * </ol>
 * Shadow map, bloom, and fog were pulled out (see ShaderConfig's class
 * doc) — none of them had their resource bindings right, and rebuilding
 * three half-working passes at once made every binding bug look like a
 * different one. This class stays deliberately thin until the buffer/
 * binding layer in {@code com.metallum.render} is solid enough that a new
 * pass is "add a GpuBuffer + BindGroupLayout entry", not "debug why the
 * whole composite goes black". With {@link ShaderConfig#enabled} = false
 * this is a no-op and Metallum behaves exactly as before.
 */
public final class ShaderPipeline {
    private static final ShaderPipeline INSTANCE = new ShaderPipeline();

    private final GBuffer gbuffer = new GBuffer();

    private ShaderPipeline() {
    }

    public static ShaderPipeline get() {
        return INSTANCE;
    }

    public boolean active() {
        return ShaderConfig.get().enabled;
    }

    /** Called once per frame before world rendering starts. */
    public void beginFrame(final int width, final int height) {
        if (!active()) {
            return;
        }

        ShaderConfig cfg = ShaderConfig.get();
        int scaledWidth = Math.max(1, Math.round(width * cfg.renderScale));
        int scaledHeight = Math.max(1, Math.round(height * cfg.renderScale));
        this.gbuffer.resizeIfNeeded(scaledWidth, scaledHeight);
        // Must happen exactly once per displayed frame (this method already
        // is, gated by SodiumWorldRendererGBufferMixin's clearedThisFrame) —
        // picks the ring-buffer slot every clear/draw/composite this frame
        // will share. See GBuffer#beginFrame for why this exists.
        this.gbuffer.beginFrame();
    }

    public GBuffer gbuffer() {
        return this.gbuffer;
    }

    /**
     * Called after opaque geometry has been written to the GBuffer. Hands
     * off to CompositePass to composite the result into the frame vanilla
     * is about to present. Runs BEFORE translucent/entities draw — so
     * only opaque-only effects (currently just debug_depth) belong here.
     * See {@link CompositePass#runFull} for anything that needs the
     * complete scene (fog, once rebuilt).
     */
    public void compositeAndPresent(final com.mojang.blaze3d.pipeline.RenderTarget main, final net.minecraft.client.renderer.GameRenderer gameRenderer) {
        if (!active()) {
            return;
        }
        CompositePass.runOpaque(this.gbuffer, main, gameRenderer);
    }

    /**
     * Called after translucent terrain AND entities have drawn onto
     * {@code main} — the TAIL of {@code LevelRenderer}'s
     * {@code lambda$addMainPass$0}, confirmed against a decompile of this
     * MC version (see {@link CompositePass}'s class doc). This is the
     * correct home for fog once it's rebuilt; today {@link CompositePass#runFull}
     * is a no-op, so this call is safe to leave wired in even with nothing
     * behind it yet.
     */
    public void compositeFullAndPresent(final com.mojang.blaze3d.pipeline.RenderTarget main, final net.minecraft.client.renderer.GameRenderer gameRenderer) {
        if (!active()) {
            return;
        }
        CompositePass.runFull(this.gbuffer, main, gameRenderer);
    }

    public void shutdown() {
        this.gbuffer.close();
        // Was previously missing -- CompositePass's static pipelines/
        // samplers were never released, leaking a GPU pipeline + two
        // samplers every time the shading layer got torn down and
        // rebuilt (F3+T, leaving/rejoining a world, etc).
        CompositePass.close();
    }
}