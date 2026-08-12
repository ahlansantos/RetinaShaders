package com.metallum.mixin.sodium;

import com.metallum.shader.ShaderConfig;
import com.metallum.shader.ShaderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/*x
 * Sodium equivalent of {@link com.metallum.mixin.render.ChunkSectionsToRenderGBufferMixin}.
 * <p>
 * With Sodium installed, vanilla's {@code ChunkSectionsToRender.renderGroup}
 * (the method the render-package mixin redirects) is never called — Sodium
 * completely replaces terrain rendering with its own pipeline. The
 * equivalent decision point there is {@link TerrainRenderPass#getTarget()},
 * which {@link DefaultChunkRenderer#render} calls twice (once for the
 * color view, once for the depth view) to build its
 * {@code CommandEncoder.createRenderPass(...)} call. Confirmed via
 * {@code javap -p -v} on Sodium's own classes: {@code getTarget()} takes no
 * arguments and internally picks between
 * {@code LevelRenderer.translucentTarget()} (translucent passes, when
 * shader transparency is on) and {@code GameRenderer.mainRenderTarget()}
 * (everything else) — the exact same hardcoded role
 * {@code ChunkSectionLayerGroup.outputTarget()} plays in vanilla.
 * <p>
 * This redirects only the non-translucent (opaque) pass's target to the
 * GBuffer scene target; translucent (water) keeps using Sodium's own
 * translucent target untouched, same split as the vanilla-path mixin.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererGBufferMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;getTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            )
    )
    private RenderTarget metallum$redirectTerrainPassTarget(final TerrainRenderPass pass) {
        if (pass.isTranslucent() || !ShaderConfig.get().enabled) {
            return pass.getTarget();
        }

        RenderTarget scene = ShaderPipeline.get().gbuffer().sceneTarget();
        if (scene != null) {
            return scene;
        }
        // beginFrame() hasn't allocated the scene target yet this frame —
        // don't render into a null target.
        return pass.getTarget();
    }
}