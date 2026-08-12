#version 330

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float rawDepth = texture(DepthSampler, texCoord).r;
    
    // Sky: preserve main framebuffer (depth is ~0.0 in Reverse-Z)
    if (rawDepth <= 0.0001) {
        discard;
    }
    
    vec3 sceneColor = texture(InSampler, texCoord).rgb;
    fragColor = vec4(sceneColor, 1.0);
    gl_FragDepth = rawDepth;
}
