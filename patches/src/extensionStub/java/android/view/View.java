package android.view;
import android.content.Context;
import android.graphics.drawable.Drawable;
public class View {
    public Context getContext() { throw new RuntimeException(); }
    public int getId() { throw new RuntimeException(); }
    public Drawable getBackground() { throw new RuntimeException(); }
    public void setBackgroundColor(int color) {}
    public void setSystemUiVisibility(int visibility) {}
    public int getSystemUiVisibility() { throw new RuntimeException(); }
    public boolean post(Runnable action) { throw new RuntimeException(); }
    /** laid-out width in px; 0 before the first layout pass, same as the real View */
    public int getWidth() { throw new RuntimeException(); }
    /** laid-out height in px; 0 before the first layout pass */
    public int getHeight() { throw new RuntimeException(); }
    public void setOnLongClickListener(OnLongClickListener l) {}
    public void setLongClickable(boolean longClickable) {}
    public void setClickable(boolean clickable) {}
    public void setOnKeyListener(OnKeyListener l) {}
    public void setOnTouchListener(OnTouchListener l) {}
    public boolean requestFocus() { throw new RuntimeException(); }
    public boolean isFocused() { throw new RuntimeException(); }
    /** the view's parent (the grid a card sits in); null before it is attached */
    public ViewParent getParent() { throw new RuntimeException(); }
    /** top edge in px, relative to the parent; 0 before layout */
    public int getTop() { throw new RuntimeException(); }
    public interface OnLongClickListener {
        boolean onLongClick(View v);
    }
    public interface OnKeyListener {
        boolean onKey(View v, int keyCode, KeyEvent event);
    }
    public interface OnTouchListener {
        boolean onTouch(View v, MotionEvent event);
    }
}
