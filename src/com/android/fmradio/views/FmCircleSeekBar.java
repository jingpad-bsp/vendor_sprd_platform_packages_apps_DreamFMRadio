package com.android.fmradio.views;
/**
 * @author bourne.wang
 */
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.fmradio.FmUtils;

/**
 * The Class CircularSeekBar.
 * For FM 2.0 new main UI.
 */
public class FmCircleSeekBar extends View {
    private String TAG = "FmCircleSeekBar";
    private Context mContext;
    /** touch point angle*/
    private float mAngle;
    /** angle to freq*/
    private String mFrequency;
    /** circle view width */
    private int width;
    /** circle view height */
    private int height;
    /** percent of the whole circle, total 100*/
    private float mProgress;
    /** circle x point*/
    private float cx;
    /** circle y point */
    private float cy;
    /**seek cursor point x coordinate;*/
    private float markPointX;
    /**seek cursor point x coordinate;*/
    private float markPointY;
    /**line circle line amount*/
    private int ScaleBarNum = 120;
    /**line circle line length, unit: dp*/
    private int ScaleBarHeight = 12;
    /**the angle of two adjacent lines.*/
    private int stepAngle = 360/ScaleBarNum;
    /**the amount of lines that should be highlight.odd number looks fine.*/
    private int lightSize = 19;
    /**the line height array.*/
    private static int lightHeight[] = {0,1,2,3,4,5,6,7,8,8,8,7,6,5,4,3,2,1,0};
    /**the line color for highlight lines*/
    //private static String lightColor[] = {"#4DFFFFFF","#66FFFFFF","#66FFFFFF","#80FFFFFF","#99FFFFFF","#B3FFFFFF","#B3FFFFFF","#CCFFFFFF","#CCFFFFFF","#FFFFFFFF","#CCFFFFFF","#CCFFFFFF","#B3FFFFFF","#B3FFFFFF","#99FFFFFF","#80FFFFFF","#66FFFFFF","#66FFFFFF","#4DFFFFFF"};
    private static String lightColor[] = {"#52acb3","#6bb8be","#6bb8be","#84c4c9","#9dd0d4","#b5dbde","#b5dbde","#cee7e9","#cee7e9","#FFFFFF","#cee7e9","#cee7e9","#b5dbde","#b5dbde","#9dd0d4","#84c4c9","#6bb8be","#6bb8be","#52acb3"};
    private static String circleColor ="#4DFFFFFF";
    /**for press scope.unit: dp*/
    private int pressedScope = 30;
    /** marker cursor size*/
    private int cursorSize = 15;
    /** line circle radius */
    private float Radius;
    /** inner circle radius */
    private float circleRadius;
    /** cursor path circle radius*/
    private float cursorRadius;
    /** whether cursor pressed */
    private boolean IS_PRESSED = false;
    /** whether fm is powerup*/
    private boolean mIsPowerUp = true;
    /**Paint for draw*/
    private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**cursor position change listener, activity registered this listener */
    private OnSeekChangeListener mListener = new OnSeekChangeListener() {
        @Override
        public void onProgressChange(FmCircleSeekBar view, String newFreqText, boolean fresh) {
        }
    };

    public FmCircleSeekBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    public FmCircleSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public FmCircleSeekBar(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // View size, unit: px
        width = getMeasuredWidth(); // Get View Width
        height =getMeasuredHeight();// Get View Height
        int size = (width > height) ? height : width; // Choose the smaller
        //The whole circle center position (cx,cy);
        cx = width / 2; // Center X for circle
        cy = height / 2; // Center Y for circle
        // line circle radius: min(width,height)/2 * 80%
        Radius = size / 2 * 80 / 100; // Radius of the outer circle
        circleRadius = Radius - 10;
        cursorRadius = circleRadius - cursorSize;
        //view: width:750, height:750 // 250*3， Radius-300 ;  unit : px
        markPointX = getXByProgress(mProgress);// Initial locatino of the marker X coordinate
        markPointY = getYByProgress(mProgress);// Initial locatino of the marker Y coordinate
    }

    @Override
    protected void onDraw(Canvas canvas) {
        //translate circle center to view center.
        canvas.translate(cx, cy);
        //draw line circle.
        float cursorAngle = getAngle();
        mPaint.setStrokeWidth(4);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.parseColor(circleColor));
        // get the distance of line.
        float distance = Radius+convertDpToPx(ScaleBarHeight);
        for (int i = 0; i < ScaleBarNum; i++) {
            canvas.drawLine(0, Radius, 0, distance, mPaint);
            canvas.rotate(stepAngle);
        }
        if(mIsPowerUp) {
            //draw the highlight lines near the cursor point.
            float startAngle = 0;
            // attention the angle rotate.
            if (cursorAngle % stepAngle < (stepAngle / 2 + 1)) {
                // change to the nearliest line in line circle, on the left.
                startAngle = cursorAngle - (stepAngle * (lightSize / 2) + cursorAngle % stepAngle);
            } else {
                // change to the nearliest line in line circle, on the right.
                startAngle = cursorAngle - (stepAngle * (lightSize / 2 - 1) + cursorAngle % stepAngle);
            }
            // rotate the canvas to the right position to draw the highlight lines.
            canvas.rotate(startAngle);
            // draw the highlight lines with different height and color.
            for (int i = 0; i < lightSize; i++) {
                mPaint.setColor(Color.parseColor(lightColor[i]));
                canvas.drawLine(0, -Radius, 0, -distance - convertDpToPx(lightHeight[i]), mPaint);
                canvas.rotate(stepAngle);
            }
            // rotate the canvas to the original position and draw cursor point.
            canvas.rotate(-(stepAngle * lightSize) - startAngle);
            drawMarkerAtProgress(canvas);
        }
        super.onDraw(canvas);
    }

    /**
     * draw seek cursor point
     * (markPointX,markPointY), r = cursorSize
     * @param canvas
     */
    public void drawMarkerAtProgress(Canvas canvas) {
        mPaint.setStrokeWidth(4);
        mPaint.setColor(Color.WHITE);
        mPaint.setStyle(Paint.Style.FILL);
        // draw cusor point.
        canvas.drawCircle(markPointX-cx,markPointY-cy,cursorSize,mPaint);
        if (IS_PRESSED) {
            // draw inner circle.
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(Color.parseColor(circleColor));
            canvas.drawCircle(0,0,circleRadius,mPaint);

        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if(this.isEnabled()) {
        float x = event.getX();
        float y = event.getY();
        boolean up = false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                //To limit the pressed valid scope when screen touched.
                if (Math.abs(x - markPointX) < convertDpToPx(pressedScope) && Math.abs(y - markPointY) < convertDpToPx(pressedScope)) {
                    IS_PRESSED = true;
                    setAngle(getAngle(), false);
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (IS_PRESSED) {
                    float distance = (float) Math.sqrt(Math.pow((x - cx), 2) + Math.pow((y - cy), 2));
                    if (distance < circleRadius + 2 * convertDpToPx(pressedScope)) {
                        markPointX = (float) (cx + cursorRadius * Math.cos(Math.atan2(x - cx, cy - y) - (Math.PI / 2)));
                        markPointY = (float) (cy + cursorRadius * Math.sin(Math.atan2(x - cx, cy - y) - (Math.PI / 2)));
                        float angle = (float) ((float) ((Math.toDegrees(Math.atan2(x - cx, cy - y)) + 360.0)) % 360.0);
                        if (angle < 0) {
                            angle += 2 * Math.PI;
                        }
                        setAngle(angle, false);
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (IS_PRESSED) {
                    setAngle(getAngle(), true);
                }
                IS_PRESSED = false;
                invalidate();
                break;
        }
      }
        return true;
    }

    public float getAngle() {
        return mAngle;
    }

    public void setAngle(float angle,boolean fresh) {
        this.mAngle = angle;
        float progress = (((float) this.mAngle) / 360) * 100;
        if(!fresh){
            mFrequency = FmUtils.formatFreq(angle);
        }
        setProgress(progress,fresh);
    }

    public float getProgress() {
        return mProgress;
    }

    public void setProgress(float progress, boolean fresh) {
        this.mProgress = progress;
        if (!IS_PRESSED) {
            // TODO: may do something when action up
        }
        mListener.onProgressChange(this, mFrequency,fresh);
    }

    public float getXByProgress(float progress) {
        // (progress/100)*2*PI;
        // cx + cos(angle);
        float angle = (float) (2 * progress * Math.PI / 100);
        float x = (float) (cx + cursorRadius * Math.cos(angle - Math.PI / 2));
        return x;
    }

    public float getYByProgress(float progress) {
        //cy + sin(angle);
        float angle = (float) (2 * progress * Math.PI / 100);
        float y = (float) (cy + cursorRadius * Math.sin(angle - Math.PI / 2));
        return y;
    }

    /**
     * register cursor position change listener.
     * @param listener
     */
    public void setSeekBarChangeListener(OnSeekChangeListener listener) {
        mListener = listener;
    }


    /**
     * definition cursor position change listener.
     * @see OnSeekChangeEvent
     */
    public interface OnSeekChangeListener {
        /**
         * On progress change.
         * @param view
         * @param newProgress
         * @param fresh - wether fresh Ui for freq change.
         */
        public void onProgressChange(FmCircleSeekBar view, String newFreqText, boolean fresh);
    }

    /**
     * for main ui freq value change by dragging the cursor.
     *@param currentstationitem freq.
     */
    public void setStationValueBySeek(int freq, boolean isPowerUp) {
        //if (!IS_PRESSED) {
            float angle = (float)(freq - 87500) / FmUtils.freqScope * 360;
            setAngle(angle, false);

            markPointX = getXByProgress(mProgress);// Initial locatino of the marker X coordinate
            markPointY = getYByProgress(mProgress);// Initial locatino of the marker Y coordinate
            // when drag cursor and tune station by click next button, tune station treated as more important behaviors,
            // so will disable this drag function for once.
            IS_PRESSED = false;
            mIsPowerUp = isPowerUp;
            invalidate();
            Log.d(TAG, "setStationValueBySeek -- freq=" + freq + ", angle:" + angle);
       // }
    }
   public void setPressedStatus(boolean isPressed){
        IS_PRESSED = isPressed;
   }

    public boolean getPressedStatus(boolean isPressed){
        return IS_PRESSED ;
    }

    /** dp convert to px.*/
    public float convertDpToPx(float dpValue) {
        float fontScale = mContext.getResources().getDisplayMetrics().scaledDensity;
        return (dpValue * fontScale + 0.5f);
    }

}