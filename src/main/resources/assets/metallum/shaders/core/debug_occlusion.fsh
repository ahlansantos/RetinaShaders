#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ProjUniforms {
    mat4 ProjMat;
    mat4 InvProjMat;
};

in vec2 texCoord;

out vec4 fragColor;

// DEBUG STAGE: same status as debug_normal.fsh was before this -- this is
// the raw AO term alone (grayscale, white = fully open, black = fully
// occluded), NOT yet multiplied back into scene color. That blend is the
// next step once this reads correctly; keeping it separate for now makes
// it possible to actually see what the AO term is doing instead of
// guessing from a subtle darkening in the composited image.
//
// No dedicated normal G-buffer (still no MRT -- see CompositePass class
// doc / README), so the normal used here is reconstructed from depth,
// same 3-tap technique as debug_normal.fsh (kept in sync with it --
// if that technique changes, mirror the change here).
//
// Kernel + rotation: 12 hemisphere sample directions, cheap per-pixel
// pseudo-random rotation via a hash of screen position (no noise texture
// binding needed, avoids adding a new sampler to the shared bind group
// layout in CompositePass#buildScreenPipeline).

const int SAMPLE_COUNT = 12;
const float RADIUS = 0.5;   // view-space units (blocks); tune per-scene
const float BIAS = 0.025;   // avoids self-occlusion / acne on flat faces

// Fixed hemisphere kernel (unit sphere directions, +Z hemisphere,
// biased toward the center via importance-style scaling like the
// classic Learn/OpenGL SSAO kernel) -- precomputed constants instead of
// generated at load time since there's no compute/init pass here.
const vec3 KERNEL[SAMPLE_COUNT] = vec3[](
    vec3( 0.041, -0.048,  0.045), vec3( 0.062,  0.028,  0.078),
    vec3(-0.028, -0.072,  0.112), vec3( 0.096,  0.045,  0.156),
    vec3(-0.086,  0.102,  0.201), vec3( 0.145, -0.110,  0.267),
    vec3(-0.161, -0.098,  0.331), vec3( 0.083,  0.211,  0.398),
    vec3( 0.221, -0.187,  0.452), vec3(-0.244,  0.163,  0.531),
    vec3( 0.102,  0.298,  0.612), vec3(-0.187, -0.276,  0.708)
);

vec3 viewPosAt(vec2 uv) {
    float rawDepth = texture(DepthSampler, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, rawDepth, 1.0);
    vec4 viewPos = InvProjMat * clip;
    return viewPos.xyz / viewPos.w;
}

// Cheap per-pixel hash -> angle, used to rotate the kernel so banding
// between neighboring pixels' fixed sample directions breaks up into
// noise instead of visible rings (still needs a blur pass to clean up;
// not done here yet, see composite recap).
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;

    // Sky: fully unoccluded, skip the kernel loop entirely.
    if (rawDepth <= 0.0001) {
        fragColor = vec4(1.0, 1.0, 1.0, 1.0);
        gl_FragDepth = rawDepth;
        return;
    }

    vec2 texel = 1.0 / vec2(textureSize(DepthSampler, 0));
    vec3 origin = viewPosAt(texCoord);

    // Same 3-tap reconstruction as debug_normal.fsh (center+right+up) --
    // see that file for why this is what it is and its known silhouette
    // noise. Kept inline rather than sampled from a buffer because there
    // is no persistent normal buffer to sample from yet.
    vec3 right = viewPosAt(texCoord + vec2(texel.x, 0.0));
    vec3 up = viewPosAt(texCoord + vec2(0.0, texel.y));
    vec3 normal = normalize(cross(right - origin, up - origin));

    // Build a rotated TBN so the fixed +Z-hemisphere kernel orients
    // around this pixel's actual normal.
    float angle = hash(texCoord) * 6.28318530718;
    vec3 randomVec = vec3(cos(angle), sin(angle), 0.0);
    vec3 tangent = normalize(randomVec - normal * dot(randomVec, normal));
    vec3 bitangent = cross(normal, tangent);
    mat3 TBN = mat3(tangent, bitangent, normal);

    float occlusion = 0.0;
    for (int i = 0; i < SAMPLE_COUNT; i++) {
        vec3 samplePos = origin + (TBN * KERNEL[i]) * RADIUS;

        // Project the sample's view-space position back to screen space
        // to look up what's actually in the depth buffer there.
        vec4 offset = ProjMat * vec4(samplePos, 1.0);
        offset.xyz /= offset.w;
        vec2 sampleUV = offset.xy * 0.5 + 0.5;

        // Sample fell outside the screen -- don't count it either way.
        if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) {
            continue;
        }

        vec3 sampledGeometry = viewPosAt(sampleUV);

        // Range check: a surface far in front of/behind the kernel
        // sample (e.g. a foreground object at a totally different depth)
        // shouldn't count as occlusion -- classic SSAO haloing fix.
        float rangeCheck = smoothstep(0.0, 1.0, RADIUS / max(abs(origin.z - sampledGeometry.z), 0.0001));

        // View space here has -Z forward (matches viewPosAt's unprojection
        // convention already used by debug_depth/debug_normal), so
        // "closer to camera" = larger Z. If the actual geometry at this
        // screen position is closer than the kernel sample expected
        // (+ bias), the kernel sample point is occluded by that geometry.
        occlusion += (sampledGeometry.z >= samplePos.z + BIAS ? 1.0 : 0.0) * rangeCheck;
    }

    float ao = 1.0 - (occlusion / float(SAMPLE_COUNT));
    fragColor = vec4(vec3(ao), 1.0);
    gl_FragDepth = rawDepth;
}