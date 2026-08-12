#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ProjUniforms {
    mat4 ProjMat;
    mat4 InvProjMat;
};

in vec2 texCoord;

out vec4 fragColor;

const int SAMPLE_COUNT = 8;
const float RADIUS = 0.8;
const float BIAS = 0.025;

const vec3 KERNEL[SAMPLE_COUNT] = vec3[](
    vec3( 0.041, -0.048,  0.045), vec3( 0.096,  0.045,  0.156),
    vec3( 0.145, -0.110,  0.267), vec3(-0.161, -0.098,  0.331),
    vec3( 0.221, -0.187,  0.452), vec3(-0.244,  0.163,  0.531),
    vec3( 0.102,  0.298,  0.612), vec3(-0.187, -0.276,  0.708)
);

vec3 viewPosAt(vec2 uv) {
    float rawDepth = texture(DepthSampler, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, rawDepth, 1.0);
    vec4 viewPos = InvProjMat * clip;
    return viewPos.xyz / viewPos.w;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;

    // Sky: preserve the sky that is already drawn in main framebuffer (depth is ~0.0 in Reverse-Z).
    // This is also a huge optimization because we skip the expensive AO kernel for the sky.
    if (rawDepth <= 0.0001) {
        discard;
    }

    vec3 sceneColor = texture(InSampler, texCoord).rgb;

    vec3 origin = viewPosAt(texCoord);

    // Otimização: calcular a normal a partir das derivadas parciais do espaço de visão
    // Isso evita buscar o depth 3 vezes por pixel (economizando 2 samples e cálculos)
    vec3 normal = normalize(cross(dFdx(origin), dFdy(origin)));

    float angle = hash(texCoord) * 6.28318530718;
    vec3 randomVec = vec3(cos(angle), sin(angle), 0.0);
    vec3 tangent = normalize(randomVec - normal * dot(randomVec, normal));
    vec3 bitangent = cross(normal, tangent);
    mat3 TBN = mat3(tangent, bitangent, normal);

    float occlusion = 0.0;
    for (int i = 0; i < SAMPLE_COUNT; i++) {
        vec3 samplePos = origin + (TBN * KERNEL[i]) * RADIUS;

        vec4 offset = ProjMat * vec4(samplePos, 1.0);
        offset.xyz /= offset.w;
        vec2 sampleUV = offset.xy * 0.5 + 0.5;

        if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) {
            continue;
        }

        vec3 sampledGeometry = viewPosAt(sampleUV);
        // Corrige o "cutout" limitando o sangramento do AO em objetos distantes
        float rangeCheck = smoothstep(0.0, 1.0, 1.0 - (abs(origin.z - sampledGeometry.z) / RADIUS));
        occlusion += (sampledGeometry.z >= samplePos.z + BIAS ? 1.0 : 0.0) * rangeCheck;
    }

    float ao = 1.0 - (occlusion / float(SAMPLE_COUNT));
    
    // Diminui um pouco a intensidade para ficar mais suave
    ao = pow(ao, 1.8);
    
    // Multiplica o AO na cor original da cena
    fragColor = vec4(sceneColor * ao, 1.0);
    gl_FragDepth = rawDepth;
}
