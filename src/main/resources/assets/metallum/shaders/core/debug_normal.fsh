#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ProjUniforms {
    mat4 ProjMat;
    mat4 InvProjMat;
};

in vec2 texCoord;

out vec4 fragColor;

// DEBUG: no dedicated normal G-buffer yet (that's real MRT work in the
// terrain draw itself, not done here — see CompositePass class doc).
// This reconstructs an approximate view-space normal FROM DEPTH ALONE,
// same technique screen-space AO commonly falls back to when a geometric
// normal buffer isn't available: take the view-space position at this
// texel and its neighbors, build two screen-space edge vectors, cross
// them. It's noisier at silhouette edges than a real geometric normal
// (the two neighbor taps can land on different surfaces there), but it's
// enough to validate the reconstruction math and get something on screen
// before committing to the MRT rewrite.
//
// Reused verbatim from debug_depth.fsh: reverse-Z, [0,1] NDC z (Metal/
// Vulkan convention), inverse-projection unproject.
vec3 viewPosAt(vec2 uv) {
    float rawDepth = texture(DepthSampler, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, rawDepth, 1.0);
    vec4 viewPos = InvProjMat * clip;
    return viewPos.xyz / viewPos.w;
}

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;

    // Sky: pin to a flat "facing camera" normal so it reads as neutral
    // gray instead of noise from unprojecting the far plane.
    if (rawDepth <= 0.0001) {
        fragColor = vec4(0.5, 0.5, 1.0, 1.0);
        gl_FragDepth = rawDepth;
        return;
    }

    // Otimização: calcular a normal a partir das derivadas parciais do espaço de visão
    // Isso evita buscar o depth 3 vezes por pixel (economizando 2 samples e cálculos)
    vec3 center = viewPosAt(texCoord);
    vec3 normal = normalize(cross(dFdx(center), dFdy(center)));

    // View-space normal, remapped from [-1,1] to [0,1] for display --
    // same convention as any packed normal G-buffer, so this doubles as
    // a preview of what a real normal buffer's debug view should look
    // like once MRT is wired up.
    fragColor = vec4(normal * 0.5 + 0.5, 1.0);
    gl_FragDepth = rawDepth;
}