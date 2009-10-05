// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateRaster(parent)
#pragma stateFragment(PFBackground)
#pragma stateStore(PFSBackground)

#define ELLIPSE_RATIO 0.892f

#define PI 3.1415f
#define TWO_PI 6.283f
#define ELLIPSE_TWIST 0.023333333f

float angle;
float distance;

/**
 * Script initialization. Called automatically.
 */
void init() {
    angle = 37.0f;
    distance = 0.55f;
}

/**
 * Helper function to generate the stars.
 */
float randomGauss() {
    float x1;
    float x2;
    float w = 2.f;

    while (w >= 1.0f) {
        x1 = 2.0f * randf2(0.0f, 1.0f) - 1.0f;
        x2 = 2.0f * randf2(0.0f, 1.0f) - 1.0f;
        w = x1 * x1 + x2 * x2;
    }

    w = sqrtf(-2.0 * logf(w) / w);
    return x1 * w;
}

/**
 * Generates the properties for a given star.
 */
void createParticle(struct Stars_s *star, struct Particles_s *part, float scale) {
    float d = fabsf(randomGauss()) * State->galaxyRadius * 0.5f + randf(64.0f);
    float id = d / State->galaxyRadius;
    float z = randomGauss() * 0.4f * (1.0f - id);
    float p = -d * ELLIPSE_TWIST;

    if (d < State->galaxyRadius * 0.33f) {
        part->r = (int) (220 + id * 35);
        part->g = 220;
        part->b = 220;
    } else {
        part->r= 180;
        part->g = 180;
        part->b = (int) clampf(140.f + id * 115.f, 140.f, 255.f);
    }
    part->a = (int) (140 + (1.0f - id) * 115);

    if (d > State->galaxyRadius * 0.15f) {
        z *= 0.6f * (1.0f - id);
    } else {
        z *= 0.72f;
    }

    // Map to the projection coordinates (viewport.x = -1.0 -> 1.0)
    d = mapf(-4.0f, State->galaxyRadius + 4.0f, 0.0f, scale, d);

    star->angle = randf(TWO_PI);
    star->distance = d;
    star->speed = randf2(0.0015f, 0.0025f) * (0.5f + (scale / d)) * 0.8f;
    star->s = cosf(p);
    star->t = sinf(p);

    part->z = z / 5.0f;
    part->pointSize = randf2(1.2f, 2.1f) * 6;
}

/**
 * Initialize all the stars. Called from Java.
 */
void initParticles() {
    struct Stars_s *star = Stars;
    struct Particles_s *part = Particles;
    int particlesCount = State->particlesCount;
    float scale = State->galaxyRadius / (State->width * 0.5f);

    int i;
    for (i = 0; i < particlesCount; i ++) {
        createParticle(star, part, scale);
        star++;
        part++;
    }
}

void drawSpace(float xOffset, int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSpace);
    drawQuadTexCoords(
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            width, 0.0f, 0.0f, 2.0f, 1.0f,
            width, height, 0.0f, 2.0f, 0.0f,
            0.0f, height, 0.0f, 0.0f, 0.0f);
}

void drawLights(float xOffset, int width, int height) {
    bindProgramVertex(NAMED_PVStars);
    bindProgramFragment(NAMED_PFBackground);
    bindTexture(NAMED_PFBackground, 0, NAMED_TLight1);

    float scale = 512.0f / width;
    float x = -scale + xOffset - scale * 0.05f;
    float y = -scale;

    scale *= 2.0f;

    drawQuad(x, y, 0.0f,
             x + scale * 1.1f, y, 0.0f,
             x + scale * 1.1f, y + scale, 0.0f,
             x, y + scale, 0.0f);
}

void drawParticles(float xOffset, int width, int height) {
    bindProgramVertex(NAMED_PVStars);
    bindProgramFragment(NAMED_PFStars);
    bindProgramFragmentStore(NAMED_PFSLights);
    bindTexture(NAMED_PFStars, 0, NAMED_TFlares);

    float matrix[16];
    matrixLoadTranslate(matrix, 0.0f, 0.0f, 10.0f - 6.0f * distance);
    matrixScale(matrix, 6.6f, 6.0f, 1.0f);
    matrixRotate(matrix, angle, 1.0f, 0.5f, 0.0f);
    vpLoadModelMatrix(matrix);

    // quadratic attenuation
    pointAttenuation(0.1f, 0.0f, 0.06f);

    int radius = State->galaxyRadius;
    int particlesCount = State->particlesCount;

    struct Stars_s *star = Stars;
    struct Particles_s *vtx = Particles;

    int i = 0;
    for ( ; i < particlesCount; i++) {
        float a = star->angle + star->speed;
        float x = star->distance * sinf(a);
        float y = star->distance * cosf(a) * ELLIPSE_RATIO;

        vtx->x = star->t * x + star->s * y + xOffset;
        vtx->y = star->s * x - star->t * y;

        star->angle = a;

        star++;
        vtx++;
    }

    uploadToBufferObject(NAMED_ParticlesBuffer);
    drawSimpleMeshRange(NAMED_ParticlesMesh, 0, particlesCount);
}

int main(int index) {
    int width = State->width;
    int height = State->height;

    float x = lerpf(1.0f, -1.0f, State->xOffset);

    drawSpace(x, width, height);
    drawParticles(x, width, height);
    drawLights(x, width, height);

    if (State->isPreview == 0) {
        if (angle > 0.0f) {
            angle -= 0.4f;
            distance = angle / 68.0f;
        }
    } else {
        // Unfortunately this cannot happen in init()
        // since the State structure instance does not
        // exist at this point
        angle = 0.0f;
        distance = 0.0f;
    }

    return 1;
}
