package com.yalantis.pulltomakesoup;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.NonNull;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;
import android.widget.AbsListView;
import android.widget.ImageView;

import com.yalantis.pulltomakesoup.refresh_view.SoupRefreshView;
import com.yalantis.pulltomakesoup.utils.Utils;

import java.security.InvalidParameterException;

/**
 * Created by Alexey on 28.01.2016.
 */

// mSoupRefreshView.setPercent(1f, true);设置拖动时刷新动画的变化
public class PullToRefreshView extends ViewGroup {

    private static final int STYLE_SOUP = 0;
    private static final int MAX_OFFSET_ANIMATION_DURATION = 1000;
    private static final int DRAG_MAX_DISTANCE = 165;
    private static final float DRAG_RATE = .5f;
    private static final float DECELERATE_INTERPOLATION_FACTOR = 1f;
    private static final int INVALID_POINTER = -1;

    private View mTarget;
    private final ImageView mRefreshView;
    private final Interpolator mDecelerateInterpolator;
    private final int mTouchSlop;//触发事件移动的最小距离
    private final int mTotalDragDistance;
    private SoupRefreshView mSoupRefreshView;
    private float mCurrentDragPercent;
    private int mCurrentOffsetTop;
    private boolean mRefreshing;
    private int mActivePointerId;
    private boolean mIsBeingDragged;
    private float mInitialMotionY;
    private int mFromOffSetTop;
    private float mFromDragPercent;
    private final Animation mAnimateToCorrectPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop;
            int endTarget = mTotalDragDistance;
            targetTop = (mFromOffSetTop + (int) ((endTarget - mFromOffSetTop) * interpolatedTime));
            int offset = targetTop - mTarget.getTop();

            mCurrentDragPercent = mFromDragPercent - (mFromDragPercent - 1.0f) * interpolatedTime;
            mSoupRefreshView.setPercent(mCurrentDragPercent, false);

            //设置target的偏移，比如下拉的距离太大，会弹回来
            setTargetOffsetTop(offset, false /* requires update */);
        }
    };
    private boolean mNotify;
    private OnRefreshListener mListener;
    private int mTargetPaddingTop;
    private int mTargetPaddingBottom;
    private int mTargetPaddingRight;
    private int mTargetPaddingLeft;
    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            moveToStart(interpolatedTime);
        }
    };
    private final Animation.AnimationListener mToStartListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            mSoupRefreshView.stop();
            mCurrentOffsetTop = mTarget.getTop();
        }
    };

    public PullToRefreshView(Context context) {
        this(context, null);
    }


    public PullToRefreshView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RefreshView);
        final int type = a.getInteger(R.styleable.RefreshView_type, STYLE_SOUP);
        a.recycle();
        mDecelerateInterpolator = new DecelerateInterpolator(DECELERATE_INTERPOLATION_FACTOR);//先快后慢
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();//触发事件移动的最小距离
        mTotalDragDistance = Utils.convertDpToPixel(context, DRAG_MAX_DISTANCE);
        mRefreshView = new ImageView(context);//初始化mRefreshView
        setRefreshStyle(type);//设置下拉刷新的样式，并给mRefreshView设置图片
        addView(mRefreshView);//添加mRefreshView到ViewGroup
        setWillNotDraw(false);//为了onDraw()的执行
        ViewCompat.setChildrenDrawingOrderEnabled(this, true);//低版本兼容

    }

    //设置下拉刷新的样式，并给mRefreshView设置图片
    private void setRefreshStyle(int type) {
        setRefreshing(false);
        switch (type) {
            case STYLE_SOUP:
                mSoupRefreshView = new SoupRefreshView(this);
                break;
            default:
                throw new InvalidParameterException("Type does not exist");
        }

        mRefreshView.setImageDrawable(mSoupRefreshView);
    }

    //获取最大的下拉距离
    public int getTotalDragDistance() {
        return mTotalDragDistance;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        //获取target
        ensureTarget();
        if (mTarget == null)
            return;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth() - getPaddingRight() - getPaddingLeft(), MeasureSpec.EXACTLY);
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight() - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY);
        mTarget.measure(widthMeasureSpec, heightMeasureSpec);
        mRefreshView.measure(widthMeasureSpec, heightMeasureSpec);
    }

    //获取target（除了刷新动画的第一个子view，这里少了一个break），target通常为一个列表或scrollview
    private void ensureTarget() {
        if (mTarget != null)
            return;
        if (getChildCount() > 0) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child != mRefreshView) {
                    mTarget = child;
                    mTargetPaddingBottom = mTarget.getPaddingBottom();
                    mTargetPaddingLeft = mTarget.getPaddingLeft();
                    mTargetPaddingRight = mTarget.getPaddingRight();
                    mTargetPaddingTop = mTarget.getPaddingTop();
                }
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        //如果未使能或者target还没有滑到顶部或者正在刷新，则不拦截事件
        if (!isEnabled() || canChildScrollUp() || mRefreshing) {
            return false;
        }

        final int action = MotionEventCompat.getActionMasked(ev);//多点触控

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                //此时不拦截事件
                setTargetOffsetTop(0, true);
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);//获取index为0的触点id
                mIsBeingDragged = false;
                final float initialMotionY = getMotionEventY(ev, mActivePointerId);//获取对应pointId触点的y
                if (initialMotionY == -1) {
                    return false;
                }
                mInitialMotionY = initialMotionY;//设置最初的y值
                break;
            case MotionEvent.ACTION_MOVE:
                //如果触点消失，则不拦截
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }
                final float y = getMotionEventY(ev, mActivePointerId);
                if (y == -1) {
                    return false;
                }
                final float yDiff = y - mInitialMotionY;//计算移动时产生的偏移量
                //如果偏移量大于触发下拉刷新事件的最小距离且还没开启刷新
                if (yDiff > mTouchSlop && !mIsBeingDragged) {
                    //此时拦截事件
                    mIsBeingDragged = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                //此时不拦截事件
                //撤销所有触点
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                //多点触控时，当某一触点抬起时
                //此时拦截事件
                onSecondaryPointerUp(ev);
                break;
        }

        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent ev) {

        //不拦截
        //mIsBeingDragged--true（拦截），此时已经发生了位移，最后一个触点为活跃触点
        //mIsBeingDragged--false（不拦截），此时已经还没发生位移，第一个触点为活跃触点
        if (!mIsBeingDragged) {
            return super.onTouchEvent(ev);
        }

        final int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                if (pointerIndex < 0) {
                    return false;
                }

                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float yDiff = y - mInitialMotionY;
                final float scrollTop = yDiff * DRAG_RATE;//减一半
                mCurrentDragPercent = scrollTop / mTotalDragDistance;
                if (mCurrentDragPercent < 0) {
                    return false;
                }
                //计算适合的target偏移量
                float boundedDragPercent = Math.min(1f, Math.abs(mCurrentDragPercent));
                float extraOS = Math.abs(scrollTop) - mTotalDragDistance;
                float slingshotDist = mTotalDragDistance;
                float tensionSlingshotPercent = Math.max(0,
                        Math.min(extraOS, slingshotDist * 2) / slingshotDist);
                float tensionPercent = (float) ((tensionSlingshotPercent / 4) - Math.pow(
                        (tensionSlingshotPercent / 4), 2)) * 2f;
                float extraMove = (slingshotDist) * tensionPercent / 2;
                int targetY = (int) ((slingshotDist * boundedDragPercent) + extraMove);
                mSoupRefreshView.setPercent(mCurrentDragPercent, true);//根据下拉的偏移量于最大下拉距离的比，设置刷新动画的变化程度
                setTargetOffsetTop(targetY - mCurrentOffsetTop, true);//移动target和刷新动画
                break;
            }
            case MotionEventCompat.ACTION_POINTER_DOWN:
                //发生位移后，当有新的触点加入时，转换活跃触点的id
                final int index = MotionEventCompat.getActionIndex(ev);
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActivePointerId == INVALID_POINTER) {
                    return false;
                }

                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, mActivePointerId);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float overScrollTop = (y - mInitialMotionY) * DRAG_RATE;
                mIsBeingDragged = false;
                if (overScrollTop > mTotalDragDistance) {//下拉的距离达到最大的下拉距离时，开启刷新模式
                    setRefreshing(true, true);
                } else {
                    mRefreshing = false;
                    animateOffsetToStartPosition();
                }
                mActivePointerId = INVALID_POINTER;
                return false;
            }
        }

        return true;
    }

    //暂停刷新后的动画，回到顶部
    private void animateOffsetToStartPosition() {
        mFromOffSetTop = mCurrentOffsetTop;
        mFromDragPercent = mCurrentDragPercent;
        long animationDuration = Math.abs((long) (MAX_OFFSET_ANIMATION_DURATION * mFromDragPercent));

        mAnimateToStartPosition.reset();
        mAnimateToStartPosition.setDuration(animationDuration);
        mAnimateToStartPosition.setInterpolator(mDecelerateInterpolator);
        mAnimateToStartPosition.setAnimationListener(mToStartListener);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToStartPosition);
    }

    //开始刷新后的动画，例如target要弹到合适的位置
    private void animateOffsetToCorrectPosition() {
        mFromOffSetTop = mCurrentOffsetTop;
        mFromDragPercent = mCurrentDragPercent;

        mAnimateToCorrectPosition.reset();
        mAnimateToCorrectPosition.setDuration(MAX_OFFSET_ANIMATION_DURATION);
        mAnimateToCorrectPosition.setInterpolator(mDecelerateInterpolator);
        mRefreshView.clearAnimation();
        mRefreshView.startAnimation(mAnimateToCorrectPosition);

        if (mRefreshing) {
            mSoupRefreshView.start();//刷新动画开始
            if (mNotify) {
                if (mListener != null) {
                    mListener.onRefresh();//刷新时回调
                }
            }
        } else {
            mSoupRefreshView.stop();
            animateOffsetToStartPosition();
        }
        mCurrentOffsetTop = mTarget.getTop();
        mTarget.setPadding(mTargetPaddingLeft, mTargetPaddingTop, mTargetPaddingRight, mTotalDragDistance);
    }

    private void moveToStart(float interpolatedTime) {
        int targetTop = mFromOffSetTop - (int) (mFromOffSetTop * interpolatedTime);
        float targetPercent = mFromDragPercent * (1.0f - interpolatedTime);
        int offset = targetTop - mTarget.getTop();

        mCurrentDragPercent = targetPercent;
        mSoupRefreshView.setPercent(mCurrentDragPercent, true);//要重绘，收起的动画
        mTarget.setPadding(mTargetPaddingLeft, mTargetPaddingTop, mTargetPaddingRight, mTargetPaddingBottom + targetTop);
        setTargetOffsetTop(offset, false);
    }

    public void setRefreshing(boolean refreshing) {
        if (mRefreshing != refreshing) {
            setRefreshing(refreshing, false /* notify */);
        }
    }

    private void setRefreshing(boolean refreshing, final boolean notify) {
        if (mRefreshing != refreshing) {
            mNotify = notify;
            ensureTarget();//
            mRefreshing = refreshing;
            if (mRefreshing) {
                mSoupRefreshView.setPercent(1f, true);//设置刷新动画全部显示
                animateOffsetToCorrectPosition();
            } else {
                animateOffsetToStartPosition();
            }
        }
    }

    //多点触控时，当其中一个触点抬起时，先判断是否为当前活跃的触点
    //否则不处理
    //是则重置index，如果index为0，则置为1，不为0，置为1
    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == mActivePointerId) {
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
        }
    }

    private float getMotionEventY(MotionEvent ev, int activePointerId) {
        final int index = MotionEventCompat.findPointerIndex(ev, activePointerId);
        if (index < 0) {
            return -1;
        }
        return MotionEventCompat.getY(ev, index);
    }

    //位移
    private void setTargetOffsetTop(int offset, boolean requiresUpdate) {
        mTarget.offsetTopAndBottom(offset);//正数向下移动，负数向上移动
        mSoupRefreshView.offsetTopAndBottom(offset);
        mCurrentOffsetTop = mTarget.getTop();
        if (requiresUpdate && android.os.Build.VERSION.SDK_INT < 11) {
            invalidate();
        }
    }

    //判断mTarget是否滑动到顶，返回true就是还没到顶
    private boolean canChildScrollUp() {
        if (android.os.Build.VERSION.SDK_INT < 14) {
            if (mTarget instanceof AbsListView) {
                final AbsListView absListView = (AbsListView) mTarget;
                return absListView.getChildCount() > 0
                        && (absListView.getFirstVisiblePosition() > 0 || absListView.getChildAt(0)
                        .getTop() < absListView.getPaddingTop());
            } else {
                //getScrollY() > 0,上滑
                //getScrollY() < 0,下拉
                return mTarget.getScrollY() > 0;
            }
        } else {
            //当direction>0时，判断是否可以下滑，当direction<0时，判断是否可以上滑
            return ViewCompat.canScrollVertically(mTarget, -1);
        }
    }


    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

        ensureTarget();
        if (mTarget == null)
            return;

        int height = getMeasuredHeight();
        int width = getMeasuredWidth();
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getPaddingRight();
        int bottom = getPaddingBottom();

        mTarget.layout(left, top + mCurrentOffsetTop, left + width - right, top + height - bottom + mCurrentOffsetTop);
        mRefreshView.layout(left, top, left + width - right, top + height - bottom);
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        mListener = listener;
    }

    public interface OnRefreshListener {
        void onRefresh();
    }

}
