package com.razerdp.widget.animatedpieview.render;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;

import com.razerdp.widget.animatedpieview.AnimatedPieViewConfig;
import com.razerdp.widget.animatedpieview.IPieView;
import com.razerdp.widget.animatedpieview.data.IPieInfo;
import com.razerdp.widget.animatedpieview.utils.AnimationCallbackUtils;
import com.razerdp.widget.animatedpieview.utils.PLog;
import com.razerdp.widget.animatedpieview.utils.ToolUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by 大灯泡 on 2018/2/1.
 */
public class PieChartRender extends BaseRender implements ITouchRender {

    enum DrawMode {
        DRAW,
        TOUCH
    }

    private List<PieInfoWrapper> mDataWrappers;
    private List<PieInfoWrapper> mCachedDrawWrappers;
    private PathMeasure mPathMeasure;
    private AnimatedPieViewConfig mConfig;
    private DrawMode mDrawMode = DrawMode.DRAW;
    //-----------------------------------------draw area-----------------------------------------
    private RectF pieBounds;
    private float pieRadius;
    private int maxDescTextSize;
    private volatile boolean isInAnimating;
    //-----------------------------------------anim area-----------------------------------------
    private PieInfoWrapper mDrawingPie;
    private float animAngle;
    //-----------------------------------------other-----------------------------------------
    private TouchHelper mTouchHelper;
    private RenderAnimation mRenderAnimation;
    private volatile boolean animaHasStart;

    public PieChartRender(IPieView iPieView) {
        super(iPieView);
        mDataWrappers = new ArrayList<>();
        mCachedDrawWrappers = new ArrayList<>();
        mPathMeasure = new PathMeasure();
        pieBounds = new RectF();
        mTouchHelper = new TouchHelper();
        pieRadius = 0;
    }

    @Override
    public void reset() {
        mTouchHelper.reset();
        pieBounds.setEmpty();
        animaHasStart = false;
        isInAnimating = false;
        pieRadius = 0;

        mDataWrappers = mDataWrappers == null ? new ArrayList<PieInfoWrapper>() : mDataWrappers;
        mDataWrappers.clear();

        mCachedDrawWrappers = mCachedDrawWrappers == null ? new ArrayList<PieInfoWrapper>() : mCachedDrawWrappers;
        mCachedDrawWrappers.clear();

        mDrawingPie = null;
        mRenderAnimation = null;
        mIPieView.getPieView().clearAnimation();
    }

    @Override
    public boolean onPrepare() {
        mConfig = mIPieView.getConfig();
        if (mConfig == null) {
            Log.e(TAG, "onPrepare: config is null,abort draw because of preparing failed");
            return false;
        }
        mTouchHelper.prepare();
        prepareAnim();
        //wrap datas and calculate sum value
        //包裹数据并且计算总和
        double sum = 0;
        for (Pair<IPieInfo, Boolean> info : mConfig.getDatas()) {
            sum += Math.abs(info.first.getValue());
            PieInfoWrapper wrapper = new PieInfoWrapper(info.first);
            wrapper.setAutoDesc(info.second);
            mDataWrappers.add(wrapper);
        }

        //calculate degree for each pieInfoWrapper
        //计算每个wrapper的角度
        float lastAngle = mConfig.getStartAngle();
        for (PieInfoWrapper dataWrapper : mDataWrappers) {
            dataWrapper.prepare(mConfig);
            lastAngle = dataWrapper.calculateDegree(lastAngle, sum, mConfig);
            maxDescTextSize = Math.max(maxDescTextSize, mPieManager.measureTextBounds(dataWrapper.getDesc(), dataWrapper.getDrawPaint()).width());
        }

        return true;
    }

    private void prepareAnim() {
        if (mConfig.isAnimPie()) {
            mRenderAnimation = new RenderAnimation();
            mRenderAnimation.setDuration(mConfig.getDuration());
            mRenderAnimation.setAnimationListener(new AnimationCallbackUtils.SimpleAnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    super.onAnimationStart(animation);
                    isInAnimating = true;
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    isInAnimating = false;
                }
            });
        }

    }

    @Override
    public void onSizeChanged(int width, int height, int paddingLeft, int paddingTop, int paddingRight, int paddingBottom) {

    }

    //-----------------------------------------render draw-----------------------------------------
    @Override
    public void onDraw(Canvas canvas) {
        float width = mPieManager.getDrawWidth();
        float height = mPieManager.getDrawHeight();

        float centerX = width / 2;
        float centerY = height / 2;

        canvas.translate(centerX, centerY);

        measurePieRadius(width, height);

        switch (mDrawMode) {
            case DRAW:
                renderDraw(canvas);
                break;
            case TOUCH:
                renderTouch(canvas);
                break;
        }
    }

    private void renderDraw(Canvas canvas) {
        if (mConfig.isAnimPie()) {
            if (mRenderAnimation != null && !isInAnimating && !animaHasStart) {
                animaHasStart = true;
                mIPieView.getPieView().startAnimation(mRenderAnimation);
            }
            renderAnimaDraw(canvas);
        } else {
            renderNormalDraw(canvas);
        }
    }

    private void renderTouch(Canvas canvas) {
        drawCachedPie(canvas, mTouchHelper.floatingWrapper);
        renderTouchDraw(canvas, mTouchHelper.lastFloatWrapper, mTouchHelper.floatDownTime);
        PLog.i("lastFloatWrapper id = " + (mTouchHelper.lastFloatWrapper == null ? "null" : mTouchHelper.lastFloatWrapper.getId()) + "  downTime = " + mTouchHelper.floatDownTime);
        renderTouchDraw(canvas, mTouchHelper.floatingWrapper, mTouchHelper.floatUpTime);
        PLog.d("floatingWrapper id = " + (mTouchHelper.floatingWrapper == null ? "null" : mTouchHelper.floatingWrapper.getId()) + "  upTime = " + mTouchHelper.floatUpTime);

    }

    private void renderNormalDraw(Canvas canvas) {
        if (ToolUtil.isListEmpty(mCachedDrawWrappers) || mCachedDrawWrappers.size() != mDataWrappers.size()) {
            mCachedDrawWrappers.clear();
            mCachedDrawWrappers.addAll(mDataWrappers);
        }
        drawCachedPie(canvas, null);
    }

    private void renderAnimaDraw(Canvas canvas) {
        if (mDrawingPie != null) {
            drawCachedPie(canvas, mDrawingPie);
            canvas.drawArc(pieBounds,
                    mDrawingPie.getFromAngle(),
                    animAngle - mDrawingPie.getFromAngle() - mConfig.getSplitAngle(),
                    !mConfig.isStrokeMode(),
                    mDrawingPie.getDrawPaint());
            if (mConfig.isDrawText() && animAngle >= mDrawingPie.getMiddleAngle() && animAngle <= mDrawingPie.getToAngle()) {
                drawText(canvas, mDrawingPie);
            }
        }
    }

    private void renderTouchDraw(Canvas canvas, PieInfoWrapper wrapper, float timeSet) {
        if (wrapper == null) return;
        mTouchHelper.setTouchBounds(timeSet);
        Paint touchPaint = mTouchHelper.prepareTouchPaint(wrapper);
        touchPaint.setShadowLayer(mConfig.getFloatShadowRadius() * timeSet, 0, 0, touchPaint.getColor());
        touchPaint.setStrokeWidth(mConfig.getStrokeWidth() + (10 * timeSet));
        applyAlphaToPaint(wrapper, touchPaint);
        canvas.drawArc(mTouchHelper.touchBounds,
                wrapper.getFromAngle() - (mConfig.getFloatExpandSize() * timeSet),
                wrapper.getSweepAngle() + (mConfig.getFloatExpandAngle() * 2 * timeSet) - mConfig.getSplitAngle(),
                !mConfig.isStrokeMode(),
                touchPaint);
    }


    private void drawCachedPie(Canvas canvas, PieInfoWrapper excluded) {
        if (!ToolUtil.isListEmpty(mCachedDrawWrappers)) {
            for (PieInfoWrapper cachedDrawWrapper : mCachedDrawWrappers) {
                if (mConfig.isDrawText()) {
                    drawText(canvas, cachedDrawWrapper);
                }
                Paint paint = cachedDrawWrapper.getAlphaDrawPaint();
                applyAlphaToPaint(cachedDrawWrapper, paint);
                if (cachedDrawWrapper.equals(excluded)) {
                    continue;
                }
                canvas.drawArc(pieBounds,
                        cachedDrawWrapper.getFromAngle(),
                        cachedDrawWrapper.getSweepAngle() - mConfig.getSplitAngle(),
                        !mConfig.isStrokeMode(),
                        paint);
            }
        }
    }

    private void drawText(Canvas canvas, PieInfoWrapper wrapper) {
        if (wrapper == null) return;

    }

    //-----------------------------------------render draw fin-----------------------------------------

    private void applyAlphaToPaint(PieInfoWrapper cachedDrawWrapper, Paint paint) {

    }


    @Override
    public void onDestroy() {

    }

    //-----------------------------------------touch-----------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mTouchHelper.handleTouch(event);
    }

    @Override
    public void forceAbortTouch() {

    }

    //-----------------------------------------tools-----------------------------------------

    private void setCurPie(PieInfoWrapper infoWrapper, float degree) {
        if (mDrawingPie != null) {
            //角度切换时就把画过的添加到缓存，因为角度切换只有很少的几次，所以这里允许循环，并不会造成大量的循环
            if (degree >= mDrawingPie.getToAngle()) {
                boolean hasAdded = mCachedDrawWrappers.contains(mDrawingPie);
                if (!hasAdded) {
                    PLog.v("添加到缓存： " + mDrawingPie.toString());
                    mCachedDrawWrappers.add(mDrawingPie);
                }
            }
        }
        mDrawingPie = infoWrapper;
        animAngle = degree;
        callInvalidate();
    }

    private void setDrawMode(DrawMode drawMode) {
        if (drawMode == DrawMode.TOUCH && isInAnimating) return;
        mDrawMode = drawMode;

    }

    private void measurePieRadius(float width, float height) {
        if (pieRadius > 0) {
            pieBounds.set(-pieRadius, -pieRadius, pieRadius, pieRadius);
            return;
        }
        final float minSize = Math.min(width, height);
        //最低接收0.5的最小高宽值
        float minPieRadius = minSize / 4;
        if (mConfig.isAutoSize()) {
            if (mConfig.isStrokeMode()) {
                //stroke模式跟pie模式测量不同
                //按照最大的文字测量
                pieRadius = minSize / 2 - maxDescTextSize - (mConfig.getStrokeWidth() >> 1);
                pieRadius = Math.max(minPieRadius, pieRadius);
            } else {
                //饼图只需要看外径
                pieRadius = minSize / 2 - maxDescTextSize;
            }
        } else {
            //优先判定size
            if (mConfig.getPieRadius() > 0) {
                pieRadius = mConfig.getPieRadius();
            } else if (mConfig.getPieRadiusRatio() > 0) {
                pieRadius = minSize / 2 * mConfig.getPieRadiusRatio();
            } else {
                pieRadius = minPieRadius;
            }
        }
        pieBounds.set(-pieRadius, -pieRadius, pieRadius, pieRadius);
    }

    private float absMathSin(double angdeg) {
        return (float) Math.abs(Math.sin(Math.toRadians(angdeg)));
    }

    private float absMathCos(double angdeg) {
        return (float) Math.abs(Math.cos(Math.toRadians(angdeg)));
    }

    private float angleToProgress(float angle, PieInfoWrapper wrapper) {
        if (wrapper == null) return 1f;
        if (angle < wrapper.getMiddleAngle()) return 0f;
        if (angle >= wrapper.getToAngle()) return 1f;
        return (angle - wrapper.getMiddleAngle()) / (wrapper.getToAngle() - wrapper.getMiddleAngle());
    }

    //-----------------------------------------inner helper-----------------------------------------

    private class RenderAnimation extends Animation {
        private PieInfoWrapper lastFoundWrapper;

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            super.applyTransformation(interpolatedTime, t);
            if (mConfig == null) {
                throw new NullPointerException("viewConfig为空");
            }
            if (interpolatedTime >= 0.0f && interpolatedTime <= 1.0f) {
                float angle = 360 * interpolatedTime + mConfig.getStartAngle();
                PieInfoWrapper info = findPieinfoWithAngle(angle);
                setCurPie(info, angle);
            }
        }

        public PieInfoWrapper findPieinfoWithAngle(float angle) {
            if (ToolUtil.isListEmpty(mDataWrappers)) return null;
            if (lastFoundWrapper != null && lastFoundWrapper.contains(angle)) {
                return lastFoundWrapper;
            }
            for (PieInfoWrapper infoWrapper : mDataWrappers) {
                if (infoWrapper.contains(angle)) {
                    lastFoundWrapper = infoWrapper;
                    return infoWrapper;
                }
            }
            return null;
        }
    }

    private class TouchHelper {
        //因为判断点击时是判断内圆和外圆半径，可能很苛刻，所以这里可以考虑增加点击范围
        private int expandClickRange;
        private float centerX;
        private float centerY;

        private RectF touchBounds;
        private PieInfoWrapper floatingWrapper;
        private ValueAnimator floatUpAnim;
        private float floatUpTime;
        private PieInfoWrapper lastFloatWrapper;
        private ValueAnimator floatDownAnim;
        private float floatDownTime;

        private float touchX = -1;
        private float touchY = -1;

        private Paint mTouchPaint;


        private PieInfoWrapper lastTouchWrapper;

        TouchHelper() {
            this(25);
        }

        TouchHelper(int expandClickRange) {
            this.expandClickRange = expandClickRange;
            touchBounds = new RectF();
        }

        void reset() {
            centerX = 0;
            centerY = 0;
            touchBounds.setEmpty();
            floatUpAnim = floatUpAnim == null ? ValueAnimator.ofFloat(0, 1) : floatUpAnim;
            floatUpAnim.removeAllUpdateListeners();
            floatUpTime = 0;

            floatDownAnim = floatDownAnim == null ? ValueAnimator.ofFloat(0, 1) : floatDownAnim;
            floatDownAnim.removeAllUpdateListeners();
            floatUpTime = 0;

            floatingWrapper = null;
            lastFloatWrapper = null;
            lastTouchWrapper = null;

            touchX = -1;
            touchY = -1;
        }

        void prepare() {
            centerX = mPieManager.getDrawWidth() / 2;
            centerY = mPieManager.getDrawHeight() / 2;

            mTouchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            floatUpAnim = ValueAnimator.ofFloat(0, 1);
            floatUpAnim.setDuration(mConfig.getFloatUpDuration());
            floatUpAnim.setInterpolator(new DecelerateInterpolator());
            floatUpAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    floatUpTime = (float) animation.getAnimatedValue();
                    callInvalidate();
                }
            });

            floatDownAnim = ValueAnimator.ofFloat(1, 0);
            floatDownAnim.setDuration(mConfig.getFloatDownDuration());
            floatDownAnim.setInterpolator(new DecelerateInterpolator());
            floatDownAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    floatDownTime = (float) animation.getAnimatedValue();
                    callInvalidate();
                }
            });
        }

        Paint prepareTouchPaint(PieInfoWrapper wrapper) {
            if (mTouchPaint == null) {
                mTouchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            if (wrapper != null) {
                mTouchPaint.set(wrapper.getDrawPaint());
            }
            return mTouchPaint;
        }

        PieInfoWrapper pointToPieInfoWrapper(float x, float y) {
            final boolean isStrokeMode = mConfig.isStrokeMode();
            final float strokeWidth = mConfig.getStrokeWidth();
            //外圆半径
            final float exCircleRadius = isStrokeMode ? pieRadius + strokeWidth / 2 : pieRadius;
            //内圆半径
            final float innerCircleRadius = isStrokeMode ? pieRadius - strokeWidth / 2 : 0;
            //点击位置到圆心的直线距离(没开根)
            final double touchDistancePow = Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2);
            //内圆半径<=直线距离<=外圆半径
            final boolean isTouchInRing = touchDistancePow >= expandClickRange + Math.pow(innerCircleRadius, 2)
                    && touchDistancePow <= expandClickRange + Math.pow(exCircleRadius, 2);
            if (!isTouchInRing) return null;
            return findWrapper(x, y);
        }

        PieInfoWrapper findWrapper(float x, float y) {
            //得到角度
            double touchAngle = Math.toDegrees(Math.atan2(y - centerY, x - centerX));
            if (touchAngle < 0) {
                touchAngle += 360.0f;
            }
            if (lastTouchWrapper != null && lastTouchWrapper.containsTouch((float) touchAngle)) {
                return lastTouchWrapper;
            }
            PLog.i("touch角度 = " + touchAngle);
            for (PieInfoWrapper wrapper : mDataWrappers) {
                if (wrapper.containsTouch((float) touchAngle)) {
                    lastTouchWrapper = wrapper;
                    return wrapper;
                }
            }
            return null;
        }

        void setTouchBounds(float timeSet) {
            final float scaleSizeInTouch = !mConfig.isStrokeMode() ? mConfig.getFloatExpandSize() : 0;
            touchBounds.set(pieBounds.left - scaleSizeInTouch * timeSet,
                    pieBounds.top - scaleSizeInTouch * timeSet,
                    pieBounds.right + scaleSizeInTouch * timeSet,
                    pieBounds.bottom + scaleSizeInTouch * timeSet);
        }

        boolean handleTouch(MotionEvent event) {
            if (!mConfig.isCanTouch() || isInAnimating) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX = event.getX();
                    touchY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    PieInfoWrapper touchWrapper = pointToPieInfoWrapper(touchX, touchY);
                    if (touchWrapper == null) return false;
                    setDrawMode(DrawMode.TOUCH);
                    if (touchWrapper.equals(floatingWrapper)) {
                        //如果点的是当前正在浮起的wrapper，则移到上一个，当前的置空
                        lastFloatWrapper = touchWrapper;
                        floatingWrapper = null;
                    } else {
                        lastFloatWrapper = floatingWrapper;
                        floatingWrapper = touchWrapper;
                    }

                    if (mConfig.isAnimTouch()) {
                        floatUpAnim.start();
                        floatDownAnim.start();
                    } else {
                        floatUpTime = 1;
                        floatDownTime = 1;
                        callInvalidate();
                    }

                    if (mConfig.getSelectListener() != null) {
                        mConfig.getSelectListener().onSelectPie(touchWrapper.getPieInfo(), touchWrapper.equals(floatingWrapper));
                    }

                    return true;
            }

            return false;
        }
    }
}