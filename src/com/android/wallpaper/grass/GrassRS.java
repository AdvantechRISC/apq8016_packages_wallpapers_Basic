/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.grass;

import android.renderscript.Sampler;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.Allocation;
import android.renderscript.ProgramVertex;
import static android.renderscript.Element.*;
import static android.util.MathUtils.*;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.renderscript.Dimension;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import static android.renderscript.Sampler.Value.*;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import android.os.Bundle;
import android.text.format.Time;
import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

import java.util.TimeZone;
import java.util.Calendar;

class GrassRS extends RenderScriptScene {
    @SuppressWarnings({"UnusedDeclaration"})
    private static final String LOG_TAG = "Grass";
    private static final boolean DEBUG = false;

    private static final int LOCATION_UPDATE_MIN_TIME = DEBUG ? 5 * 60 * 1000 : 60 * 60 * 1000; // 1 hour
    private static final int LOCATION_UPDATE_MIN_DISTANCE = DEBUG ? 10 : 150 * 1000; // 150 km
    private static final float TESSELATION = 0.5f;
    private static final int TEXTURES_COUNT = 5;
    private static final int BLADES_COUNT = 200;

    private ScriptField_Blade mBlades;
    private ScriptField_Vertex mVertexBuffer;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;

    //private Allocation mBladesBuffer;
    private Allocation mBladesIndicies;
    private SimpleMesh mBladesMesh;

    private ScriptC_Grass mScript;

    private int mVerticies;
    private int mIndicies;
    private int[] mBladeSizes;

    private final Context mContext;
    private final LocationManager mLocationManager;

    private LocationUpdater mLocationUpdater;
    private GrassRS.TimezoneTracker mTimezoneTracker;

    GrassRS(Context context, int width, int height) {
        super(width, height);

        mContext = context;
        mLocationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void start() {
        super.start();

        if (mTimezoneTracker == null) {
            mTimezoneTracker = new TimezoneTracker();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            mContext.registerReceiver(mTimezoneTracker, filter);
        }

        if (mLocationUpdater == null) {
            mLocationUpdater = new LocationUpdater();
            mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME, LOCATION_UPDATE_MIN_DISTANCE, mLocationUpdater);
        }

        updateLocation();
    }

    @Override
    public void stop() {
        super.stop();

        if (mTimezoneTracker != null) {
            mContext.unregisterReceiver(mTimezoneTracker);
            mTimezoneTracker = null;
        }

        if (mLocationUpdater != null) {
            mLocationManager.removeUpdates(mLocationUpdater);
            mLocationUpdater = null;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mScript.set_gWidth(width);
        mScript.set_gHeight(height);
        mScript.invokable_updateBlades();
        mPvOrthoAlloc.setupOrthoWindow(width, height);
    }

    @Override
    protected ScriptC createScript() {
        mScript = new ScriptC_Grass(mRS, mResources, R.raw.grass_bc, true);

        final boolean isPreview = isPreview();
        createProgramVertex();
        createProgramFragmentStore();
        loadTextures();
        createProgramFragment();
        createBlades();

        mScript.set_gBladesCount(BLADES_COUNT);
        mScript.set_gIndexCount(mIndicies);
        mScript.set_gWidth(mWidth);
        mScript.set_gHeight(mHeight);
        mScript.set_gXOffset(isPreview ? 0.5f : 0.f);
        mScript.set_gIsPreview(isPreview ? 1 : 0);
        mScript.set_gBladesMesh(mBladesMesh);

        mScript.setTimeZone(TimeZone.getDefault().getID());
        mScript.bind_Blades(mBlades);
        mScript.bind_Verticies(mVertexBuffer);

        // set these to reasonable defaults.
        mScript.set_gDawn(6.f / 24.f);
        mScript.set_gDusk(18.f / 24.f);
        mScript.set_gMorning(8.f / 24.f); // 2 hours for sunrise
        mScript.set_gAfternoon(16.f / 24.f); // 2 hours for sunset

        return mScript;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mScript.set_gXOffset(xOffset);
    }

    private void createBlades() {
        mVerticies = 0;
        mIndicies = 0;

        mBlades = new ScriptField_Blade(mRS, BLADES_COUNT);

        mBladeSizes = new int[BLADES_COUNT];
        for (int i = 0; i < BLADES_COUNT; i++) {
            ScriptField_Blade.Item item = new ScriptField_Blade.Item();
            createBlade(item);
            mBlades.set(item, i, false);

            mIndicies += item.size * 2 * 3;
            mVerticies += item.size + 2;
            mBladeSizes[i] = item.size;
        }
        mBlades.copyAll();

        createMesh();
    }

    private void createMesh() {
        Builder elementBuilder = new Builder(mRS);
        elementBuilder.add(Element.ATTRIB_COLOR_U8_4(mRS), "color");
        elementBuilder.add(Element.ATTRIB_POSITION_2(mRS), "position");
        elementBuilder.add(Element.ATTRIB_TEXTURE_2(mRS), "texture");
        final Element vertexElement = elementBuilder.create();

        mVertexBuffer = new ScriptField_Vertex(mRS, mVerticies * 2);

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(mVertexBuffer.getType());
        meshBuilder.setIndexType(Element.INDEX_16(mRS), mIndicies);
        meshBuilder.setPrimitive(Primitive.TRIANGLE);
        mBladesMesh = meshBuilder.create();

        //mBladesBuffer = mBladesMesh.createVertexAllocation(vertexSlot);
        mBladesMesh.bindVertexAllocation(mVertexBuffer.getAllocation(), 0);
        mBladesIndicies = mBladesMesh.createIndexAllocation();
        mBladesMesh.bindIndexAllocation(mBladesIndicies);

        short[] idx = new short[mIndicies];
        int idxIdx = 0;
        int vtxIdx = 0;
        for (int i = 0; i < mBladeSizes.length; i++) {
            for (int ct = 0; ct < mBladeSizes[i]; ct ++) {
                idx[idxIdx + 0] = (short)(vtxIdx + 0);
                idx[idxIdx + 1] = (short)(vtxIdx + 1);
                idx[idxIdx + 2] = (short)(vtxIdx + 2);
                idx[idxIdx + 3] = (short)(vtxIdx + 1);
                idx[idxIdx + 4] = (short)(vtxIdx + 3);
                idx[idxIdx + 5] = (short)(vtxIdx + 2);
                idxIdx += 6;
                vtxIdx += 2;
            }
            vtxIdx += 2;
        }

        mBladesIndicies.data(idx);
        mBladesIndicies.uploadToBufferObject();
    }

    private void createBlade(ScriptField_Blade.Item blades) {
        final float size = random(4.0f) + 4.0f;
        final int xpos = random(-mWidth, mWidth);

        //noinspection PointlessArithmeticExpression
        blades.angle = 0.0f;
        blades.size = (int)(size / TESSELATION);
        blades.xPos = xpos;
        blades.yPos = mHeight;
        blades.offset = random(0.2f) - 0.1f;
        blades.scale = 4.0f / (size / TESSELATION) + (random(0.6f) + 0.2f) * TESSELATION;
        blades.lengthX = (random(4.5f) + 3.0f) * TESSELATION * size;
        blades.lengthY = (random(5.5f) + 2.0f) * TESSELATION * size;
        blades.hardness = (random(1.0f) + 0.2f) * TESSELATION;
        blades.h = random(0.02f) + 0.2f;
        blades.s = random(0.22f) + 0.78f;
        blades.b = random(0.65f) + 0.35f;
        blades.turbulencex = xpos * 0.006f;
    }

    private void loadTextures() {
        mScript.set_gTNight(loadTexture(R.drawable.night));
        mScript.set_gTSunrise(loadTexture(R.drawable.sunrise));
        mScript.set_gTSky(loadTexture(R.drawable.sky));
        mScript.set_gTSunset(loadTexture(R.drawable.sunset));
        mScript.set_gTAa(generateTextureAlpha(4, 1, new int[] { 0x00FFFF00 }));
    }

    private Allocation generateTextureAlpha(int width, int height, int[] data) {
        final Type.Builder builder = new Type.Builder(mRS, A_8(mRS));
        builder.add(Dimension.X, width);
        builder.add(Dimension.Y, height);
        builder.add(Dimension.LOD, 1);

        final Allocation allocation = Allocation.createTyped(mRS, builder.create());
        int[] grey1 = new int[] {0x3f3f3f3f};
        int[] grey2 = new int[] {0x00000000};
        Allocation.Adapter2D a = allocation.createAdapter2D();
        a.setConstraint(Dimension.LOD, 0);
        a.subData(0, 0, 4, 1, data);
        a.setConstraint(Dimension.LOD, 1);
        a.subData(0, 0, 2, 1, grey1);
        a.setConstraint(Dimension.LOD, 2);
        a.subData(0, 0, 1, 1, grey2);

        allocation.uploadToTexture(0);
        return allocation;
    }

    private Allocation loadTexture(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR_MIP_LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        Sampler sl = samplerBuilder.create();

        samplerBuilder.setMin(NEAREST);
        samplerBuilder.setMag(NEAREST);
        Sampler sn = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                           ProgramFragment.Builder.Format.ALPHA, 0);
        ProgramFragment pf = builder.create();
        mScript.set_gPFGrass(pf);
        pf.bindSampler(sl, 0);

        builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                           ProgramFragment.Builder.Format.RGB, 0);
        pf = builder.create();
        mScript.set_gPFBackground(pf);
        pf.bindSampler(sn, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mScript.set_gPSBackground(builder.create());
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        ProgramVertex pv = pvb.create();
        pv.bindAllocation(mPvOrthoAlloc);
        mScript.set_gPVBackground(pv);
    }

    private void updateLocation() {
        updateLocation(mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
    }

    private void updateLocation(Location location) {
        float dawn = 0.3f;
        float dusk = 0.75f;

        if (location != null) {
            final String timeZone = Time.getCurrentTimezone();
            final SunCalculator calculator = new SunCalculator(location, timeZone);
            final Calendar now = Calendar.getInstance();

            final double sunrise = calculator.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
            dawn = SunCalculator.timeToDayFraction(sunrise);

            final double sunset = calculator.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
            dusk = SunCalculator.timeToDayFraction(sunset);
        }

        mScript.set_gDawn(dawn);
        mScript.set_gDusk(dusk);
        mScript.set_gMorning(dawn + 1.0f / 12.0f); // 2 hours for sunrise
        mScript.set_gAfternoon(dusk - 1.0f / 12.0f); // 2 hours for sunset
    }

    private class LocationUpdater implements LocationListener {
        public void onLocationChanged(Location location) {
            updateLocation(location);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
        }
    }

    private class TimezoneTracker extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            getScript().setTimeZone(Time.getCurrentTimezone());
            updateLocation();
        }
    }
}
