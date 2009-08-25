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
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

#define RSID_PARTICLES 1

#define PARTICLE_STRUCT_FIELDS_COUNT 6
#define PARTICLE_STRUCT_ANGLE 0
#define PARTICLE_STRUCT_DISTANCE 1
#define PARTICLE_STRUCT_SPEED 2
#define PARTICLE_STRUCT_RADIUS 3
#define PARTICLE_STRUCT_S 4
#define PARTICLE_STRUCT_T 5

#define RSID_PARTICLES_BUFFER 2
#define PARTICLE_BUFFER_COMPONENTS_COUNT 5

#define PARTICLES_TEXTURES_COUNT 2

#define ELLIPSE_RATIO 0.892f

void drawSpace(float xOffset, int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSpace);
    drawQuadTexCoords(
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            width, 0.0f, 0.0f, 2.0f, 1.0f,
            width, height, 0.0f, 2.0f, 0.0f,
            0.0f, height, 0.0f, 0.0f, 0.0f);
}

void drawLights(float xOffset, int width, int height) {
    float x = (width - 512.0f) * 0.5f + xOffset;
    float y = (height - 512.0f) * 0.5f;
    
    // increase the size of the texture by 5% on each side
    x -= 512.0f * 0.05f;

    bindProgramFragment(NAMED_PFBackground);
    bindTexture(NAMED_PFBackground, 0, NAMED_TLight1);
    drawQuad(x + 512.0f * 1.1f, y         , 0.0f,
             x                , y         , 0.0f,
             x                , y + 512.0f, 0.0f,
             x + 512.0f * 1.1f, y + 512.0f, 0.0f);
}

void drawParticle(float *particle, float *particleBuffer, float w, float h) {
    float distance = particle[PARTICLE_STRUCT_DISTANCE];
    float angle = particle[PARTICLE_STRUCT_ANGLE];
    float speed = particle[PARTICLE_STRUCT_SPEED];
    float r = particle[PARTICLE_STRUCT_RADIUS];

    float a = angle + speed;
    float x = distance * sinf_fast(a);
    float y = distance * cosf_fast(a) * ELLIPSE_RATIO;
    float s = particle[PARTICLE_STRUCT_S];
    float t = particle[PARTICLE_STRUCT_T];

    float sX = t * x + s * y + w;
    float sY = s * x - t * y + h;

    // lower left vertex of the particle's triangle
    particleBuffer[1] = sX - r;     // X
    particleBuffer[2] = sY + r;     // Y

    // lower right vertex of the particle's triangle
    particleBuffer[6] = sX + r;     // X
    particleBuffer[7] = sY + r;     // Y

    // upper middle vertex of the particle's triangle
    particleBuffer[11] = sX;         // X
    particleBuffer[12] = sY - r;     // Y

    particle[PARTICLE_STRUCT_ANGLE] = a;
}

void drawParticles(float xOffset, int width, int height) {
    bindProgramFragment(NAMED_PFLighting);
    bindProgramFragmentStore(NAMED_PFSLights);    
    bindTexture(NAMED_PFLighting, 0, NAMED_TFlares);

    int radius = State_galaxyRadius;
    int particlesCount = State_particlesCount;

    float *particle = loadArrayF(RSID_PARTICLES, 0);
    float *particleBuffer = loadArrayF(RSID_PARTICLES_BUFFER, 0);

    float w = width * 0.5f + xOffset;
    float h = height * 0.5f;

    int i = 0;
    for ( ; i < particlesCount; i++) {
        drawParticle(particle, particleBuffer, w, h);
        particle += PARTICLE_STRUCT_FIELDS_COUNT;
        // each particle is a triangle (3 vertices) of 5 properties (ABGR, X, Y, S, T)
        particleBuffer += 3 * PARTICLE_BUFFER_COMPONENTS_COUNT;
    }

    uploadToBufferObject(NAMED_ParticlesBuffer);
    drawSimpleMeshRange(NAMED_ParticlesMesh, 0, particlesCount * 3);
}

int main(int index) {
    int width = State_width;
    int height = State_height;
   
    float w = width * 0.5f;
    float x = lerpf(w, -w, State_xOffset);

    drawSpace(x, width, height);
    drawParticles(x, width, height);
    drawLights(x, width, height);

    return 1;
}
