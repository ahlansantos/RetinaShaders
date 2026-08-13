#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ProjUniforms {
    mat4 ProjMat;
    mat4 InvProjMat;
    vec4 SunScreenPos;
};

in vec2 texCoord;

out vec4 fragColor;

const float STEP_SIZE = 0.2;
const int MAX_STEPS = 40;
const float THICKNESS = 0.1; // Base depth tolerance
const float MAX_RAY_DIST = 24.0; // Cuts the ray early if it has traveled too far without hitting anything

vec3 viewPosAt(vec2 uv) {
    float rawDepth = texture(DepthSampler, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, rawDepth, 1.0);
    vec4 viewPos = InvProjMat * clip;
    return viewPos.xyz / viewPos.w;
}

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;
    vec4 baseColor = texture(InSampler, texCoord);

    // Sky
    if (rawDepth <= 0.0001) {
        fragColor = baseColor;
        gl_FragDepth = rawDepth;
        return;
    }

    // ==========================================
    // DEPTH EDGE DETECTION (Silhouette)
    // ==========================================
    // rawDepth is NON-LINEAR (standard depth buffer). Using dFdx/dFdy
    // directly on it causes false positives far from the camera (any normal
    // variation becomes an "edge") and false negatives close to the camera. That's why
    // we calculate the normal/viewPos first and compare LINEAR depth
    // (view-space Z), with a distance-relative threshold.
    vec3 viewPos = viewPosAt(texCoord);

    float linearEdgeX = abs(dFdx(viewPos.z));
    float linearEdgeY = abs(dFdy(viewPos.z));
    float edgeThreshold = max(0.15, abs(viewPos.z) * 0.05); // Relative to distance
    if (linearEdgeX + linearEdgeY > edgeThreshold) {
        fragColor = baseColor;
        gl_FragDepth = rawDepth;
        return;
    }

    // Read material (assuming alpha tells if it is reflective or not, e.g.: < 0.8 = water)
    // For global testing, we temporarily disabled this check!
    // if (baseColor.a > 0.8) {
    //     fragColor = vec4(baseColor.rgb, 1.0);
    //     gl_FragDepth = rawDepth;
    //     return;
    // }

    vec3 normal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));

    // Ensure the normal points TOWARDS the camera (depending on how dFdx/dFdy behave)
    if (dot(normal, -viewPos) < 0.0) {
        normal = -normal;
    }

    // Camera to pixel vector (in view space, camera is at origin 0,0,0)
    vec3 viewDir = normalize(viewPos);

    // Reflection vector
    vec3 reflectDir = normalize(reflect(viewDir, normal));

    // ==========================================
    // DITHER ON INITIAL STEP
    // ==========================================
    // With a fixed step size, neighboring rays always sample the same points
    // relative to the geometry, which generates banding/clipping patterns when
    // depth varies subtly between neighboring pixels (especially
    // at shallow angles). A small per-pixel noise at the start of the ray
    // breaks this pattern.
    // Cheap dither (simple 2D hash, no sin/cos)
    float dither = fract(dot(texCoord, vec2(0.75, 0.5)) * 1000.0);

    // Using the static method, but with an initial "jump" (slight Normal Bias) + dither
    vec3 currentPos = viewPos + (reflectDir * (STEP_SIZE * (0.5 + dither * 0.5)));
    vec3 colorAcc = vec3(0.0);
    float hit = 0.0;
    float travelled = 0.0;

    for (int i = 0; i < MAX_STEPS; i++) {
        currentPos += reflectDir * STEP_SIZE;
        travelled += STEP_SIZE;

        // Cut early if ray has traveled too far (avoids wasting steps on rays that won't hit)
        if (travelled > MAX_RAY_DIST) {
            break;
        }

        vec4 proj = ProjMat * vec4(currentPos, 1.0);
        proj.xyz /= proj.w;
        vec2 sampleUV = proj.xy * 0.5 + 0.5;

        if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) {
            break;
        }

        float sampleDepthRaw = texture(DepthSampler, sampleUV).r;
        vec4 clipSample = vec4(sampleUV * 2.0 - 1.0, sampleDepthRaw, 1.0);
        vec4 sampleView = InvProjMat * clipSample;
        float sampleViewZ = (sampleView.z / sampleView.w);

        float depthDiff = sampleViewZ - currentPos.z;

        // ==========================================
        // ADAPTIVE THICKNESS
        // ==========================================
        // Fixed view-space THICKNESS is huge near the camera and
        // tiny far from it. Near, it accepts wrong hits; far, the
        // STEP_SIZE "jumps" over the tolerance band and misses valid hits,
        // creating holes in the middle of the reflection. We scale it with
        // -currentPos.z (cheap, no sqrt) instead of length().
        float thickness = THICKNESS * (1.0 + abs(currentPos.z) * 0.05);

        if (depthDiff > 0.0 && depthDiff < thickness) {
            // ==========================================
            // BINARY SEARCH REFINEMENT
            // ==========================================
            // The ray hit! But since the step is large, it might have entered too deep into the wall.
            // Let's take some "steps backward and forward" halving the step size each time,
            // to find the exact surface of the wall.
            vec3 searchPos = currentPos;
            float searchStep = STEP_SIZE * 0.5;

            // 3 refinement steps (visually enough, cheaper than 5)
            for(int j = 0; j < 3; j++) {
                searchPos -= reflectDir * searchStep;

                vec4 searchProj = ProjMat * vec4(searchPos, 1.0);
                searchProj.xyz /= searchProj.w;
                vec2 searchUV = searchProj.xy * 0.5 + 0.5;

                float sDepthRaw = texture(DepthSampler, searchUV).r;
                vec4 sClip = vec4(searchUV * 2.0 - 1.0, sDepthRaw, 1.0);
                vec4 sView = InvProjMat * sClip;
                float sViewZ = (sView.z / sView.w);

                float sDepthDiff = sViewZ - searchPos.z;

                // If exited the object (sDepthDiff < 0), move forward. If still inside, move backward.
                if(sDepthDiff < 0.0) {
                    searchPos += reflectDir * searchStep;
                }

                searchStep *= 0.5; // Halve the step size each iteration
            }

            // After refinement, we get the exact final color
            vec4 finalProj = ProjMat * vec4(searchPos, 1.0);
            finalProj.xyz /= finalProj.w;
            vec2 finalUV = finalProj.xy * 0.5 + 0.5;

            colorAcc = texture(InSampler, finalUV).rgb;

            // Edge Fading to avoid texture repetition on screen edges
            float fadeX = smoothstep(0.0, 0.05, finalUV.x) * smoothstep(1.0, 0.95, finalUV.x);
            float fadeY = smoothstep(0.0, 0.05, finalUV.y) * smoothstep(1.0, 0.95, finalUV.y);
            hit = fadeX * fadeY;

            break;
        }
    }

    // Simple Fresnel
    float fresnel = pow(1.0 - max(dot(-viewDir, normal), 0.0), 3.0);

    vec3 finalColor = mix(baseColor.rgb, colorAcc, hit * fresnel * 0.8);

    fragColor = vec4(finalColor, baseColor.a);
    gl_FragDepth = rawDepth;
}