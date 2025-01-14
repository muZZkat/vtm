/*
 * Copyright 2013 Ahmad Saleem
 * Copyright 2013 Hannes Janetzek
 * Copyright 2016-2019 devemux86
 * Copyright 2016 ocsike
 * Copyright 2017 Mathieu De Brito
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.renderer;

import org.oscim.backend.CanvasAdapter;
import org.oscim.backend.GL;
import org.oscim.backend.canvas.Color;
import org.oscim.core.Box;
import org.oscim.core.Point;
import org.oscim.core.Tile;
import org.oscim.layers.Layer;
import org.oscim.map.Map;
import org.oscim.utils.FastMath;
import org.oscim.utils.math.Interpolation;

import static org.oscim.backend.GLAdapter.gl;

public class LocationRenderer extends LayerRenderer {

    private static final long ANIM_RATE = 50;
    private static final long INTERVAL = 2000;

    public static float CIRCLE_SIZE = 30;
    private static final int COLOR = 0xff3333cc;
    private static final int SHOW_ACCURACY_ZOOM = 16;

    private final Map mMap;
    private final Layer mLayer;
    protected final float mScale;

    private String mShaderFile;
    protected int mShaderProgram;
    private int hVertexPosition;
    private int hMatrixPosition;
    private int hScale;
    private int hPhase;
    private int hDirection;
    private int uColor;
    private int uMode;

    private final Point mIndicatorPosition = new Point();

    private final Point mScreenPoint = new Point();
    private final Box mBBox = new Box();

    private boolean mInitialized;

    private boolean mLocationIsVisible;

    private boolean mRunAnim;
    private boolean mAnimate = true;
    private long mAnimStart;

    private Callback mCallback;
    private final float[] mColors = new float[4];
    private final Point mLocation = new Point(Double.NaN, Double.NaN);
    private double mRadius;
    private int mShowAccuracyZoom = SHOW_ACCURACY_ZOOM;

    public LocationRenderer(Map map, Layer layer) {
        this(map, layer, CanvasAdapter.getScale());
    }

    public LocationRenderer(Map map, Layer layer, float scale) {
        mMap = map;
        mLayer = layer;
        mScale = scale;

        float a = Color.aToFloat(COLOR);
        mColors[0] = a * Color.rToFloat(COLOR);
        mColors[1] = a * Color.gToFloat(COLOR);
        mColors[2] = a * Color.bToFloat(COLOR);
        mColors[3] = a;
    }

    public void setAnimate(boolean animate) {
        mAnimate = animate;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setColor(int color) {
        float a = Color.aToFloat(color);
        mColors[0] = a * Color.rToFloat(color);
        mColors[1] = a * Color.gToFloat(color);
        mColors[2] = a * Color.bToFloat(color);
        mColors[3] = a;
    }

    public void setLocation(double x, double y, double radius) {
        mLocation.x = x;
        mLocation.y = y;
        mRadius = radius;
    }

    public void setShader(String shaderFile) {
        mShaderFile = shaderFile;
        mInitialized = false;
    }

    public void setShowAccuracyZoom(int showAccuracyZoom) {
        mShowAccuracyZoom = showAccuracyZoom;
    }

    public void animate(boolean enable) {
        if (mRunAnim == enable)
            return;

        mRunAnim = enable;
        if (!enable)
            return;
        if (!mAnimate)
            return;

        final Runnable action = new Runnable() {
            private long lastRun;

            @Override
            public void run() {
                if (!mRunAnim)
                    return;
                if (!mAnimate)
                    return;

                long diff = System.currentTimeMillis() - lastRun;
                mMap.postDelayed(this, Math.min(ANIM_RATE, diff));
                mMap.render();
                lastRun = System.currentTimeMillis();
            }
        };

        mAnimStart = System.currentTimeMillis();
        mMap.postDelayed(action, ANIM_RATE);
    }

    private float animPhase() {
        return (float) ((MapRenderer.frametime - mAnimStart) % INTERVAL) / INTERVAL;
    }

    @Override
    public void update(GLViewport v) {

        if (!mInitialized) {
            init();
            mInitialized = true;
        }

        if (!mLayer.isEnabled()) {
            setReady(false);
            return;
        }

            /*if (!v.changed() && isReady())
                return;*/

        setReady(true);

        int width = mMap.getWidth();
        int height = mMap.getHeight();

        // clamp location to a position that can be
        // savely translated to screen coordinates
        v.getBBox(mBBox, 0);

        double x = mLocation.x;
        double y = mLocation.y;

        if (!mBBox.contains(mLocation)) {
            x = FastMath.clamp(x, mBBox.xmin, mBBox.xmax);
            y = FastMath.clamp(y, mBBox.ymin, mBBox.ymax);
        }

        // get position of Location in pixel relative to
        // screen center
        v.toScreenPoint(x, y, mScreenPoint);

        x = mScreenPoint.x + width / 2;
        y = mScreenPoint.y + height / 2;

        // clip position to screen boundaries
        int visible = 0;

        if (x > width - 5)
            x = width;
        else if (x < 5)
            x = 0;
        else
            visible++;

        if (y > height - 5)
            y = height;
        else if (y < 5)
            y = 0;
        else
            visible++;

        mLocationIsVisible = (visible == 2);

        // set location indicator position
        v.fromScreenPoint(x, y, mIndicatorPosition);
    }

    @Override
    public void render(GLViewport v) {

        GLState.useProgram(mShaderProgram);
        GLState.blend(true);
        GLState.test(false, false);

        GLState.enableVertexArrays(hVertexPosition, GLState.DISABLED);
        MapRenderer.bindQuadVertexVBO(hVertexPosition/*, true*/);

        float radius = CIRCLE_SIZE * mScale;

        boolean viewShed = false;
        if (!mLocationIsVisible /* || pos.zoomLevel < SHOW_ACCURACY_ZOOM */) {
            animate(true);
        } else {
            if (v.pos.zoomLevel >= mShowAccuracyZoom)
                radius = (float) (mRadius * v.pos.scale);
            radius = Math.max(CIRCLE_SIZE * mScale, radius);

            viewShed = true;
            animate(false);
        }
        gl.uniform1f(hScale, radius);

        double x = mIndicatorPosition.x - v.pos.x;
        double y = mIndicatorPosition.y - v.pos.y;
        double tileScale = Tile.SIZE * v.pos.scale;

        v.mvp.setTransScale((float) (x * tileScale), (float) (y * tileScale), 1);
        v.mvp.multiplyMM(v.viewproj, v.mvp);
        v.mvp.setAsUniform(hMatrixPosition);

        if (!viewShed && mAnimate) {
            float phase = Math.abs(animPhase() - 0.5f) * 2;
            //phase = Interpolation.fade.apply(phase);
            phase = Interpolation.swing.apply(phase);

            gl.uniform1f(hPhase, 0.8f + phase * 0.2f);
        } else {
            gl.uniform1f(hPhase, 1);
        }

        if (viewShed && mLocationIsVisible) {
            if (mCallback != null && mCallback.hasRotation()) {
                float rotation = mCallback.getRotation();
                rotation -= 90;
                gl.uniform2f(hDirection,
                        (float) Math.cos(Math.toRadians(rotation)),
                        (float) Math.sin(Math.toRadians(rotation)));
                gl.uniform1i(uMode, 1); // With bearing
            } else {
                gl.uniform2f(hDirection, 0, 0);
                gl.uniform1i(uMode, 0); // Without bearing
            }
        } else
            gl.uniform1i(uMode, -1); // Outside screen

        GLUtils.glUniform4fv(uColor, 1, mColors);

        gl.drawArrays(GL.TRIANGLE_STRIP, 0, 4);
    }

    protected boolean init() {
        int program = GLShader.loadShader(mShaderFile != null ? mShaderFile : "location_1");
        if (program == 0)
            return false;

        mShaderProgram = program;
        hVertexPosition = gl.getAttribLocation(program, "a_pos");
        hMatrixPosition = gl.getUniformLocation(program, "u_mvp");
        hPhase = gl.getUniformLocation(program, "u_phase");
        hScale = gl.getUniformLocation(program, "u_scale");
        hDirection = gl.getUniformLocation(program, "u_dir");
        uColor = gl.getUniformLocation(program, "u_color");
        uMode = gl.getUniformLocation(program, "u_mode");

        return true;
    }

    public interface Callback {
        /**
         * Usually true, can be used with e.g. Android Location.hasBearing().
         */
        boolean hasRotation();

        float getRotation();
    }
}
