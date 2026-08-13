# 04. Deferred Rendering Pipeline, PBR Shaders & Post-Processing

## 1. Custom Deferred Rendering Architecture (WebGL2 / WebGPU)

To support hundreds of dynamic light sources (torches, glowing lava, magical projectiles, mob eyes, glowing ores) without quadratic performance degradation, the engine implements a **High-Performance Multi-Render-Target (MRT) Deferred Renderer**:

```
+--------------------------------------------------------------------------------------------------+
|                                    GEOMETRY PASS (MRT)                                          |
| Render all Voxel Meshes & Instanced Foliage into G-Buffer Targets (Single Pass):                |
|                                                                                                  |
| +-------------------------+ +-------------------------+ +-------------------------+ +----------+ |
| | G-Buffer 0: RGBA16F     | | G-Buffer 1: RGBA8       | | G-Buffer 2: RGBA8       | | Depth /  | |
| | RGB: World Position     | | RGB: Normal (Oct-Enc)   | | RGB: Base Albedo / Color| | Stencil  | |
| | A:   Linear Depth       | | A:   Roughness          | | A:   Metallic           | | Target   | |
| +-------------------------+ +-------------------------+ +-------------------------+ +----------+ |
| +---------------------------------------------------------------------------------+              |
| | G-Buffer 3: RGBA8                                                               |              |
| | RGB: Emissive Color (Lava / Torches / Glowing Runes) | A: Voxel SkyLight Mask   |              |
| +---------------------------------------------------------------------------------+              |
+-------------------------------------------------+------------------------------------------------+
                                                  |
                                                  v
+--------------------------------------------------------------------------------------------------+
|                                   SHADOW PASS & OCCLUSION PASS                                   |
| - Cascaded Shadow Maps (CSM: 4 Cascades, 2048x2048 Depth Map with Poisson Disk Filtering)       |
| - Screen-Space Ambient Occlusion (SSAO: 16 Sample Kernel with Depth-Aware Bilateral Blur)        |
+-------------------------------------------------+------------------------------------------------+
                                                  |
                                                  v
+--------------------------------------------------------------------------------------------------+
|                                   DEFERRED LIGHTING PASS                                         |
| Full-Screen Quad / Clustered Light Grid:                                                         |
| - Cook-Torrance Specular BRDF + Lambertian Diffuse                                               |
| - PBR Sun Light + Sky Ambient evaluated with CSM Shadows & SSAO attenuation                      |
| - Up to 256 Dynamic Point/Spot Lights evaluated in screen-space tiles (Zero Draw Call Overhead!)|
+-------------------------------------------------+------------------------------------------------+
                                                  |
                                                  v
+--------------------------------------------------------------------------------------------------+
|                             ATMOSPHERIC & POST-PROCESSING PASSES                                 |
| 1. Volumetric Rayleigh/Mie Fog & God Rays (Light Shafts)                                        |
| 2. Water / Transparent Forward Pass with Screen-Space Refractions & Caustics                    |
| 3. Temporal Anti-Aliasing (TAA) with Motion Vector Reprojection (Eliminates Shimmering)          |
| 4. Physically-Based Bloom (Dual-Filter Kawase Blur)                                              |
| 5. ACES Filmic Tone Mapping & Contrast/Saturation Color Grading                                  |
+--------------------------------------------------------------------------------------------------+
```

---

## 2. PBR Voxel Shaders (GLSL Spec)

### 2.1 Geometry Pass Vertex Shader (`voxel_gbuffer.vert.glsl`)
```glsl
#version 300 es
precision highp float;

layout(location = 0) in uint aPackedData1;
layout(location = 1) in uint aPackedData2;

uniform mat4 uModelViewMatrix;
uniform mat4 uProjectionMatrix;
uniform mat4 uNormalMatrix;
uniform mat4 uModelMatrix;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUV;
flat out uint vTextureId;
out float vAO;
out float vLightLevel;

const vec3 NORMALS[6] = vec3[6](
    vec3( 0.0,  1.0,  0.0), // TOP (+Y)
    vec3( 0.0, -1.0,  0.0), // BOTTOM (-Y)
    vec3( 0.0,  0.0,  1.0), // NORTH (+Z)
    vec3( 0.0,  0.0, -1.0), // SOUTH (-Z)
    vec3( 1.0,  0.0,  0.0), // EAST (+X)
    vec3(-1.0,  0.0,  0.0)  // WEST (-X)
);

void main() {
    // Unpack data from bitfields
    float x = float(aPackedData1 & 0x1Fu);
    float y = float((aPackedData1 >> 5u) & 0x1FFu);
    float z = float((aPackedData1 >> 14u) & 0x1Fu);
    uint normalIdx = (aPackedData1 >> 19u) & 0x7u;
    uint aoIdx = (aPackedData1 >> 22u) & 0x3u;
    uint light = (aPackedData1 >> 24u) & 0xFFu;

    float u = float(aPackedData2 & 0x3Fu);
    float v = float((aPackedData2 >> 6u) & 0x3Fu);
    vTextureId = (aPackedData2 >> 12u) & 0xFFFu;

    vNormal = NORMALS[normalIdx];
    vAO = float(aoIdx) / 3.0; // 0.0 to 1.0 ambient occlusion
    vLightLevel = float(light) / 255.0;
    vUV = vec2(u, v);

    vec4 worldPos = uModelMatrix * vec4(x, y, z, 1.0);
    vWorldPos = worldPos.xyz;
    gl_Position = uProjectionMatrix * uModelViewMatrix * vec4(x, y, z, 1.0);
}
```

### 2.2 Deferred Composition Lighting Shader (`deferred_lighting.frag.glsl`)
```glsl
#version 300 es
precision highp float;

uniform sampler2D uGBufferPos;       // World Position + Depth
uniform sampler2D uGBufferNormal;    // Oct-Encoded Normals + Roughness
uniform sampler2D uGBufferAlbedo;    // RGB Base Color + Metallic
uniform sampler2D uGBufferEmissive;  // RGB Emissive + SkyLight
uniform sampler2D uSSAOTexture;      // Screen-Space Ambient Occlusion
uniform sampler2DArray uShadowMap;   // Cascaded Shadow Maps

struct PointLight {
    vec3 position;
    vec3 color;
    float radius;
    float intensity;
};

layout (std140) uniform LightBlock {
    PointLight uLights[128];
    int uNumLights;
};

in vec2 vTexCoord;
out vec4 FragColor;

// Cook-Torrance GGX Specular Distribution
float DistributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    return a2 / (3.14159265 * denom * denom);
}

void main() {
    vec4 posDepth = texture(uGBufferPos, vTexCoord);
    if (posDepth.w >= 1.0) {
        // Sky background
        FragColor = vec4(0.4, 0.65, 0.95, 1.0);
        return;
    }

    vec3 worldPos = posDepth.xyz;
    vec4 normRough = texture(uGBufferNormal, vTexCoord);
    vec3 N = normalize(normRough.xyz);
    float roughness = normRough.w;

    vec4 albedoMetal = texture(uGBufferAlbedo, vTexCoord);
    vec3 albedo = albedoMetal.rgb;
    float metallic = albedoMetal.w;

    vec4 emissiveSky = texture(uGBufferEmissive, vTexCoord);
    float ssao = texture(uSSAOTexture, vTexCoord).r;

    vec3 totalLight = emissiveSky.rgb; // Base emissive contribution

    // Evaluate dynamic point lights
    for (int i = 0; i < uNumLights; i++) {
        vec3 lightDir = uLights[i].position - worldPos;
        float dist = length(lightDir);
        if (dist < uLights[i].radius) {
            lightDir = normalize(lightDir);
            float NdotL = max(dot(N, lightDir), 0.0);
            float attenuation = 1.0 - smoothstep(0.0, uLights[i].radius, dist);
            totalLight += albedo * uLights[i].color * uLights[i].intensity * NdotL * attenuation;
        }
    }

    // Direct Sun Light & Ambient Occlusion composite
    totalLight += albedo * ssao * 0.2; // Ambient base

    FragColor = vec4(totalLight, 1.0);
}
```

---

## 3. Post-Processing & Anti-Aliasing Stack

1. **Temporal Anti-Aliasing (TAA)**:
   - Jitters projection matrix with 8-phase Halton sequence sub-pixel offsets.
   - Samples history buffer using motion vectors and applies neighborhood color clamping (AABB clipping in YCoCg space) to prevent ghosting.
2. **Cascaded Shadow Maps (CSM)**:
   - 4 split planes calculated with logarithmic-uniform distribution:
   $$z_i = n \cdot \left(\frac{f}{n}\right)^{\frac{i}{m}}$$
   - 16-tap Poisson disk PCF (Percentage-Closer Filtering) for soft, cinema-quality shadows.
3. **Screen-Space Water & Refractions**:
   - Depth-buffer difference calculates volumetric water murkiness and depth fog.
   - Animated normal maps with caustic projection onto underlying terrain.
