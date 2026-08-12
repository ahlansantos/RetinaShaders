#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ProjUniforms {
    mat4 ProjMat;
    mat4 InvProjMat;
};

in vec2 texCoord;

out vec4 fragColor;

// DEBUG: shows the depth the composite pass reads from the GBuffer,
// linearized to view-space distance and remapped for human viewing.
// Ported from the old SSAO composite's debug-depth mode (ssao_composite_
// debug_depth.fsh, deleted along with the rest of SSAO) -- same math,
// just renamed SsaoUniforms -> ProjUniforms to match the current
// composite pass's uniform block name.
//
// Raw reverse-Z depth follows near/distance (near ~= 0.05 blocks in
// Minecraft), so it collapses toward 0 within the first ~1-2 blocks and
// stays visually flat for everything past that even though the real
// values are still distinct. This view is ONLY for human debugging --
// don't "fix" flatness here by changing the real depth buffer, only this
// visualization.
//
// White = far away, black = close, using a log-ish falloff so mid-range
// terrain doesn't just look uniformly white. Sky (raw depth ~0.0) is
// pinned to pure white.
void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;

    if (rawDepth <= 0.0001) {
        fragColor = vec4(1.0);
        gl_FragDepth = rawDepth;
        return;
    }

    // NDC z here is [0,1] (Metal/Vulkan convention, matches this engine's
    // MSL backend) -- NOT OpenGL's [-1,1]. Only x/y go through the -1..1
    // remap; z is used as-is.
    vec4 clip = vec4(texCoord * 2.0 - 1.0, rawDepth, 1.0);
    vec4 viewPos = InvProjMat * clip;
    float viewSpaceDistance = length(viewPos.xyz / viewPos.w);

    // log1p-style remap: spreads near distances (where most detail is)
    // across the visible range instead of saturating to black in the
    // first block. Tune the divisor if everything still looks too dark
    // or too washed out for your render distance.
    float linear = 1.0 - exp(-viewSpaceDistance / 16.0);

    fragColor = vec4(vec3(linear), 1.0);
    gl_FragDepth = rawDepth;
}