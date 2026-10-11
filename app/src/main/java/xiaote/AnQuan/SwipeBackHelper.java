package xiaote.AnQuan;

import android.app.Activity;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * 左滑返回辅助（空白处左滑回到上一页）
 */
public class SwipeBackHelper {

    public static void attach(Activity activity) {
        new SwipeBackHelper(activity);
    }

    private SwipeBackHelper(final Activity activity) {
        final GestureDetector detector = new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        // 左滑：起始X > 结束X 且 水平速度 > 200
                        if (e1 != null && e2 != null
                                && e1.getX() - e2.getX() > 80
                                && Math.abs(velocityX) > 200
                                && Math.abs(velocityY) < Math.abs(velocityX) * 2) {
                            activity.finish();
                            activity.overridePendingTransition(0, 0);
                            return true;
                        }
                        return false;
                    }
                });

        // 在 DecorView 上监听触摸，但不消费事件，不干扰子视图点击
        activity.getWindow().getDecorView().setOnTouchListener(
                new android.view.View.OnTouchListener() {
                    @Override
                    public boolean onTouch(android.view.View v, MotionEvent event) {
                        detector.onTouchEvent(event);
                        return false; // 不消费事件，按钮等正常点击
                    }
                });
    }
}