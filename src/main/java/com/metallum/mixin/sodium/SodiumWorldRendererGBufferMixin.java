package com.metallum.mixin.sodium;

import com.metallum.shader.ShaderConfig;
import com.metallum.shader.ShaderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * Supersedes {@link com.metallum.mixin.render.LevelRendererGBufferMixin}'s
 * begin/composite pair for the Sodium path.
 * <p>
 * That mixin anchors on the {@code ChunkSectionsToRender.renderGroup(...)}
 * call inside {@code LevelRenderer.lambda$addMainPass$0}. With Sodium
 * installed, that call site is still present in the bytecode and still
 * fires (confirmed via debug logging) — but Sodium's own mixins redirect
 * it to {@link SodiumWorldRenderer#drawChunkLayer}, whose body for
 * {@code ChunkSectionLayerGroup.OPAQUE} synchronously calls
 * {@code renderLayer(SOLID, ...)} then {@code renderLayer(CUTOUT, ...)} —
 * which is what actually reaches {@code DefaultChunkRenderer.render}.
 * <p>
 * Empirically, the vanilla-anchored AFTER-inject fires <em>before</em> the
 * real terrain draw completes (logs showed
 * {@code compositeGBufferBackToMain} firing, then only later
 * {@code redirectTerrainPassTarget} — i.e. compositing an empty/stale
 * GBuffer, then drawing terrain into it too late for anyone to read).
 * <p>
 * Anchoring directly on {@code drawChunkLayer}'s HEAD/TAIL for the OPAQUE
 * group instead guarantees begin/clear happens before, and composite
 * happens strictly after, the actual synchronous terrain draw — no matter
 * which mixin (ours, Sodium's, anyone else's) sits in between the
 * higher-level call chain.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class SodiumWorldRendererGBufferMixin {
    // Render-thread only (drawChunkLayer is never called concurrently),
    // so plain booleans are fine — no need for Atomic* here.
    //
    // WHY THIS EXISTS: drawChunkLayer(OPAQUE, ...) turned out to fire
    // more than once per frame (once per sub-layer — SOLID, then CUTOUT
    // — each as its own top-level call, not nested inside one call like
    // the old javadoc on this class assumed). Clearing+compositing on
    // every one of those calls meant: the 2nd call's clear wiped out the
    // 1st call's SOLID geometry from `scene` before CUTOUT ever got
    // drawn into it, AND main got composited onto twice per frame
    // (doubled/ghosted AO, geometry that appears to "move" as the two
    // partial composites interact with stale main depth).
    //
    // Fix: clear only on the first OPAQUE call seen since the last
    // composite. Never clear again until after the next composite.
    // Actually flush the composite when TRANSLUCENT's `drawChunkLayer`
    // call starts — by then every OPAQUE sub-layer for this frame is
    // guaranteed to have already drawn into `scene` (translucent always
    // renders after all opaque groups), so this is the first point we
    // can be sure it's safe to read `scene` as "this frame's complete
    // opaque terrain", exactly once.
    private static boolean clearedThisFrame = false;
    private static boolean compositedThisFrame = false;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"))
    private void metallum$redirectOpaqueToGBuffer(
            final ChunkSectionLayerGroup group,
            final ChunkRenderMatrices matrices,
            final double x, final double y, final double z,
            final GpuSampler terrainSampler,
            final CallbackInfo ci
    ) {
        if (!ShaderConfig.get().enabled) {
            return;
        }

        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            if (clearedThisFrame && !compositedThisFrame) {
                GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
                RenderTarget main = gameRenderer.mainRenderTarget();
                // Single-stage composite (no fog split) — matches
                // ShaderPipeline#compositeAndPresent, the only composite
                // entry point that exists. Runs once per frame, right
                // after all OPAQUE sub-layers (SOLID + CUTOUT) have drawn
                // into `scene` and before TRANSLUCENT starts.
                ShaderPipeline.get().compositeAndPresent(main, gameRenderer);
                compositedThisFrame = true;
                // Ready for next frame's first OPAQUE call to clear again.
                clearedThisFrame = false;
            }
            return;
        }

        if (group != ChunkSectionLayerGroup.OPAQUE) {
            return;
        }

        if (clearedThisFrame) {
            // Already cleared for this frame's opaque pass (e.g. this is
            // the CUTOUT call after SOLID already ran) — do NOT clear
            // again, or we'd wipe out what SOLID already drew.
            return;
        }

        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        RenderTarget main = gameRenderer.mainRenderTarget();
        ShaderPipeline pipeline = ShaderPipeline.get();
        pipeline.beginFrame(main.width, main.height);

        // Reverse-Z: DepthStencilState.DEFAULT uses CompareOp.GREATER_THAN_OR_EQUAL,
        // so "far"/background is 0.0 and "near" approaches 1.0. Clearing to 1.0 (the
        // old-school near=0/far=1 assumption) made every real terrain fragment fail
        // the depth test immediately, so nothing was ever written into the GBuffer.
        TextureTarget scene = pipeline.gbuffer().sceneTarget();
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                scene.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), scene.getDepthTexture(), 0.0
        );
        clearedThisFrame = true;
        compositedThisFrame = false;
    }
}