package com.mokee.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.mokee.launcher.preference.PreferencesProvider;

public class PreviewLayout extends FrameLayout
    implements View.OnClickListener, View.OnLongClickListener,
        LauncherTransitionable, DragSource, DropTarget {
    private static final int DEFAULT_CELL_COUNT_X = 3;
    private static final int DEFAULT_CELL_COUNT_Y = 3;
    private static final int REORDER_ANIMATION_DURATION = 150;
    private static final int MAX_SCREEN = 9;
    public static final String TAG = "PreviewLayout";
    private Drawable mBackgroundDrawable;
    private int mCellCountX;
    private int mCellCountY;
    private CellLayout mContent;
    private DragLayer mDragLayer;
    private DragController mDragController;
    private Launcher mLauncher;
    private Workspace mWorkspace;
    private float mTransitionProgress = 0f;
    private boolean mIsSwitchingState = false;
    private int[] mTargetCell = new int[2];
    private int[] mPreviousTargetCell = new int[2];
    private int[] mEmptyCell = new int[2];
    int[] mTempXY = new int[2];
    View mCurrentDragView;
    boolean mDragInProgress = false;
    private Alarm mReorderAlarm = new Alarm();
    private Object mLock = new Object();

    public PreviewLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PreviewLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Workspace, defStyle, 0);
        mCellCountX = a.getInt(0, DEFAULT_CELL_COUNT_X);
        mCellCountY = a.getInt(1, DEFAULT_CELL_COUNT_Y);
    }

    /**
     * Add a homescreen to the workspace
     * @param index location in workspace to add the screen at
     */
    private void addScreen(int index) {
        mWorkspace.addScreen(index);
        mLauncher.hideDockDivider();
        mWorkspace.setOnLongClickListener(this.mLauncher);
        mWorkspace.invalidate();
        snapDrawingCacheToImageViews();
        PreferencesProvider.Interface.Homescreen.setNumberHomescreens(getContext(),
                mWorkspace.getPageCount());
    }

    /**
     * Delete the home screen at the given index
     * @param index
     */
    private void removeWorkspaceScreen(int index) {
        mWorkspace.removeScreen(index);
        mLauncher.hideDockDivider();
        snapDrawingCacheToImageViews();
        mWorkspace.invalidate();
        PreferencesProvider.Interface.Homescreen.setNumberHomescreens(getContext(),
                mWorkspace.getChildCount());
    }

    /**
     * Change the default homescreen
     * @param index
     */
    private void setWorkspaceDefaultScreen(int index) {
        mWorkspace.setDefaultScreenTo(index);
        invalidate();
        PreferencesProvider.Interface.Homescreen.setDefaultHomescreen(getContext(),
                index + 1);
    }

    /**
     * Return to workspace and display the homescreen at index
     * @param index
     */
    private void snapToScreen(int index) {
        mWorkspace.setCurrentPage(index);
        mLauncher.showWorkspace(true);
    }

    /**
     * Update the preview layouts
     * @param index default homescreen index
     */
    private void updatePreviews(int index) {
        for (int i = 0; i < mContent.getShortcutsAndWidgets().getChildCount(); i++) {
            ViewGroup viewGroup = (ViewGroup)mContent.getChildAt(i % 3, i / 3);
            int childIndex = (int)((ItemInfo)viewGroup.getTag()).id;
            ImageView image = (ImageView)viewGroup.findViewById(R.id.home_button);
            if (childIndex == index)
                image.setSelected(true);
            else
                image.setSelected(false);
        }
        invalidate();
    }

    /**
     * Add the "add homescreen" view at the given index
     * @param index
     */
    public void addPlusWorkspaceButtonAt(int index) {
        ViewGroup layout = (ViewGroup)mLauncher.getLayoutInflater().inflate(R.layout.preview_boxed, null);
        CellLayout.LayoutParams params = new CellLayout.LayoutParams(index % 3, index / 3, 1, 1);
        layout.setLayoutParams(params);
        ItemInfo info = new ItemInfo();
        info.cellX = index % 3;
        info.cellY = index / 3;
        info.id = index;
        layout.setTag(info);
        ((ImageView)layout.findViewById(R.id.delete_button)).setVisibility(View.GONE);
        ((ImageView)layout.findViewById(R.id.home_button)).setVisibility(View.GONE);
        ImageView image = (ImageView)layout.findViewById(R.id.preview_screen);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setOnClickListener(this);
        image.setImageResource(R.drawable.preview_new_screen);
        mContent.addViewToCellLayout(layout, -1, 0, params, true);
    }

    /**
     * Add a preview view to the cell layout
     * @param bitmap snapshot of the homescreen
     * @param cellX x location in layout to place this view
     * @param cellY y location in layout to place this view
     * @param iscellLayoutEmpty false if the homescreen has children
     * @param isDefault true if this is the current default homescreen
     * @param isCurrent true if this is the current homescreen
     */
    public void addPreviewBitmap(Bitmap bitmap, int cellX, int cellY, boolean iscellLayoutEmpty, boolean isDefault,
                                 boolean isCurrent ) {
        Log.v(TAG, "addPreviewBitmap----" + cellX + cellY);
        ViewGroup layout = (ViewGroup)mLauncher.getLayoutInflater().inflate(R.layout.preview_boxed, null);
        //layout.setBackgroundDrawable(mBackgroundDrawable);
        if (isCurrent)
            layout.setBackgroundResource(R.drawable.preview_bg_p);
        else
            layout.setBackgroundResource(R.drawable.preview_bg_n);
        CellLayout.LayoutParams params = new CellLayout.LayoutParams(cellX, cellY, 1, 1);
        layout.setLayoutParams(params);
        ImageView image = (ImageView)layout.findViewById(R.id.delete_button);

        if (!iscellLayoutEmpty) {
            // don't let this screen be deleted since it has content
            image.setVisibility(View.GONE);
            image = (ImageView)layout.findViewById(R.id.preview_screen);
            image.setClickable(false);
            if (bitmap != null)
                image.setImageBitmap(bitmap);
            image.setOnClickListener(this);
            image.setOnLongClickListener(this);

            image = (ImageView)layout.findViewById(R.id.home_button);
            image.setOnClickListener(this);
            if (isDefault)
                image.setSelected(true);
            else
                image.setSelected(false);
        } else {
            image.setOnClickListener(this);
            image = (ImageView)layout.findViewById(R.id.preview_screen);
            image.setOnClickListener(this);
            image.setOnLongClickListener(this);
            image = (ImageView)layout.findViewById(R.id.home_button);
            image.setOnClickListener(this);
            if (isDefault)
                image.setSelected(true);
            else
                image.setSelected(false);
        }

        ItemInfo info = new ItemInfo();
        info.cellX = cellX;
        info.cellY = cellY;
        info.id = cellX + cellY * 3;
        layout.setTag(info);
        layout.setOnClickListener(this);
        layout.setOnLongClickListener(this);

        this.mContent.addViewToCellLayout(layout, -1, 0, params, true);
    }


    public int getCellHeight() {
        return mContent.getCellHeight();
    }

    public int getCellWidth() {
        return mContent.getCellWidth();
    }

    public View getPreviewChildAt(int index) {
        return mContent.getChildAt(index % 3, index / 3);
    }

    public void loadBackgroundResource() {
        mBackgroundDrawable = getContext().getResources().getDrawable(R.drawable.preview_bg);
    }

    public void onClick(View v) {
        View view = null;
        if (!(v instanceof RelativeLayout))
            view = (View)v.getParent();
        else
            view = v;
        ItemInfo i = (ItemInfo)view.getTag();
        switch (v.getId()) {
            case R.id.home_button:
                updatePreviews((int)i.id);
                setWorkspaceDefaultScreen((int)i.id);
                break;
            case R.id.delete_button:
                removeWorkspaceScreen((int)i.id);
                break;
            case R.id.preview_screen:
                int j = mWorkspace.getPageCount();
                if (j < 9 && (int)i.id == j) {
                    Log.v(TAG, "add new screen");
                    addScreen((int)i.id);
                } else
                    snapToScreen((int)i.id);
                break;
            default:
                j = mLauncher.getWorkspace().getPageCount();
				if (i == null)
				return;
                if ((j < 9) && ((int)i.id == j)) {
                    Log.v(TAG, "add new screen");
                    addScreen((int)i.id);
                }
                break;
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (!(v instanceof RelativeLayout))
            v = (View)v.getParent();
        beginDrag(v, this);
        mCurrentDragView = v;

        ItemInfo info = (ItemInfo)v.getTag();
        mEmptyCell[0] = info.cellX;
        mEmptyCell[1] = info.cellY;
        mContent.removeView(mCurrentDragView);
        mDragInProgress = true;
        return true;
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        if (mCellCountX < 0)
            mCellCountX = 3;
        if (mCellCountY < 0)
            mCellCountY = 3;
        mDragLayer = (DragLayer)findViewById(R.id.preview_drag_layer);
        mContent = ((CellLayout)mDragLayer.findViewById(R.id.preview));
        mContent.setCellGaps(-1, -1);
        mContent.setGridSize(mCellCountX, mCellCountY);
    }

    public void removeAllViews() {
        if (mContent != null)
            mContent.removeAllViews();
    }

    public void setPreviewCellCenterPoint(int index, int[] res) {
        mContent.cellToCenterPoint(index % 3, index / 3, res);
    }

    public void setup(Launcher launcher) {
        mLauncher = launcher;
        mWorkspace = mLauncher.getWorkspace();

        mDragController = new DragController(launcher);
        final DragController dragController = mDragController;
        mDragLayer.setup(launcher, dragController);

        dragController.setScrollView(mDragLayer);
        dragController.setMoveTarget(this);
        dragController.addDropTarget(this);
    }

    /**
     * Adds children to mContent by retrieving a bitmap image of the children
     * CellLayouts in mWorkspace
     */
    public void snapDrawingCacheToImageViews() {
        removeAllViews();
        int cellLayoutCount = mWorkspace.getPageCount();
        loadBackgroundResource();
        for (int j = 0; j < cellLayoutCount; j++) {
            // if a CellLayout has children then grab a preview using
            // getDrawingCache()
            View cl = mWorkspace.getChildAt(j);
            int childCount = ((CellLayout)cl).getShortcutsAndWidgets().getChildCount();
            Bitmap bitmap = null;
            if (childCount > 0) {
                bitmap = cl.getDrawingCache();
            }

            int m = j % 3;
            int n = j / 3;
            boolean isCellLayoutEmpty = false;
            boolean isDefaultHomescreen = false;
            boolean isCurrent = false;
            if (childCount == 0)
                isCellLayoutEmpty = true;

            if (j == mWorkspace.getDefaultHomescreen())
                isDefaultHomescreen = true;

            if (j == mWorkspace.getCurrentPage())
                isCurrent = true;

            addPreviewBitmap(bitmap, m, n, isCellLayoutEmpty, isDefaultHomescreen, isCurrent);
        }

        // place the "add homescreen" view in the first open spot
        addPlusWorkspaceButtonAt(cellLayoutCount);
    }

    @Override
    public void onLauncherTransitionPrepare(Launcher l, boolean animated, boolean toWorkspace) {
        mIsSwitchingState = true;
        if (toWorkspace) {
            // Going from Previews -> Workspace
            mWorkspace.setVisibility(View.VISIBLE);
        } else {
            mContent.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLauncherTransitionStart(Launcher l, boolean animated, boolean toWorkspace) {
    }

    @Override
    public void onLauncherTransitionStep(Launcher l, float t) {
        mTransitionProgress = t;
    }

    @Override
    public void onLauncherTransitionEnd(Launcher l, boolean animated, boolean toWorkspace) {
        mIsSwitchingState = false;
        if (toWorkspace) {
            mContent.setVisibility(View.INVISIBLE);
        } else {
            // Going from Workspace -> Previews
            // Dismiss the workspace cling and show the all apps cling (if not already shown)
            l.dismissWorkspaceCling(null);
            mWorkspace.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public View getContent() {
        return this;
    }

    @Override
    public boolean supportsFlingToDelete() {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void onFlingToDeleteCompleted() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean isFlingToDelete, boolean success) {
        mDragInProgress = false;
        mCurrentDragView = null;
    }

    @Override
    public boolean isDropEnabled() {
        return true;
    }

    @Override
    public void onDrop(DragObject d) {
        mReorderAlarm.cancelAlarm();
        boolean reorderHomescreens = true;
        ItemInfo item = (ItemInfo) mCurrentDragView.getTag();
        int from = (int)item.id;
        int to = from;
        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) mCurrentDragView.getLayoutParams();
        if (item.cellX != mEmptyCell[0] || item.cellY != mEmptyCell[1]) {
            reorderHomescreens = true;
            to = mEmptyCell[0] + mEmptyCell[1] * 3;
        }
        item.cellX = lp.cellX = mEmptyCell[0];
        item.cellX = lp.cellY = mEmptyCell[1];
        mContent.addViewToCellLayout(mCurrentDragView, -1, (int)item.id, lp, true);
        if (d.dragView.hasDrawn()) {
            mDragLayer.animateViewIntoPosition(d.dragView, mCurrentDragView);
        } else {
            d.deferDragViewCleanupPostAnimation = false;
            mCurrentDragView.setVisibility(VISIBLE);
        }
        d.deferDragViewCleanupPostAnimation = false;
        if (reorderHomescreens) {
            mWorkspace.moveScreen(from, to);
            snapDrawingCacheToImageViews();
            mWorkspace.invalidate();
        }
    }

    @Override
    public void onDragEnter(DragObject dragObject) {
        mPreviousTargetCell[0] = -1;
        mPreviousTargetCell[1] = -1;
    }

    @Override
    public void onDragOver(DragObject d) {
        float[] r = getDragViewVisualCenter(d.x, d.y, d.xOffset, d.yOffset, d.dragView, null);
        mTargetCell = mContent.findNearestArea((int) r[0], (int) r[1], 1, 1, mTargetCell);
        // don't allow it to be dragged and inserted in the add workspace spot
        if ((mTargetCell[0] + mTargetCell[1] * 3) >= mWorkspace.getPageCount())
            return;

        if (mTargetCell[0] != mPreviousTargetCell[0] || mTargetCell[1] != mPreviousTargetCell[1]) {
            mReorderAlarm.cancelAlarm();
            mReorderAlarm.setOnAlarmListener(mReorderAlarmListener);
            mReorderAlarm.setAlarm(10);
            mPreviousTargetCell[0] = mTargetCell[0];
            mPreviousTargetCell[1] = mTargetCell[1];
        }
    }

    // This is used to compute the visual center of the dragView. The idea is that
    // the visual center represents the user's interpretation of where the item is, and hence
    // is the appropriate point to use when determining drop location.
    private float[] getDragViewVisualCenter(int x, int y, int xOffset, int yOffset,
                                            DragView dragView, float[] recycle) {
        float res[];
        if (recycle == null) {
            res = new float[2];
        } else {
            res = recycle;
        }

        // These represent the visual top and left of drag view if a dragRect was provided.
        // If a dragRect was not provided, then they correspond to the actual view left and
        // top, as the dragRect is in that case taken to be the entire dragView.
        // R.dimen.dragViewOffsetY.
        int left = x - xOffset;
        int top = y - yOffset;

        // In order to find the visual center, we shift by half the dragRect
        res[0] = left + dragView.getDragRegion().width() / 2;
        res[1] = top + dragView.getDragRegion().height() / 2;

        return res;
    }

    @Override
    public void onDragExit(DragObject dragObject) {
    }

    @Override
    public void onFlingToDelete(DragObject dragObject, int x, int y, PointF vec) {
    }

    @Override
    public DropTarget getDropTargetDelegate(DragObject dragObject) {
        return null;
    }

    @Override
    public boolean acceptDrop(DragObject dragObject) {
        return true;
    }

    @Override
    public void getLocationInDragLayer(int[] loc) {
        mDragLayer.getLocationInDragLayer(this, loc);
    }

    OnAlarmListener mReorderAlarmListener = new OnAlarmListener() {
        public void onAlarm(Alarm alarm) {
            realTimeReorder(mEmptyCell, mTargetCell);
        }
    };

    public void beginDrag(View child, DragSource source) {
        Resources r = getResources();

        // The drag bitmap follows the touch point around on the screen
        child.setDrawingCacheEnabled(true);
        child.buildDrawingCache();
        final Bitmap b = child.getDrawingCache();

        final int bmpWidth = b.getWidth();
        final int bmpHeight = b.getHeight();

        mDragLayer.getLocationInDragLayer(child, mTempXY);
        int dragLayerX =
                Math.round(mTempXY[0] - (bmpWidth - child.getScaleX() * child.getWidth()) / 2);
        int dragLayerY =
                Math.round(mTempXY[1] - (bmpHeight - child.getScaleY() * child.getHeight()) / 2);

        Point dragVisualizeOffset = null;
        Rect dragRect = null;
        mDragController.startDrag(b, dragLayerX, dragLayerY, source, child.getTag(),
                DragController.DRAG_ACTION_MOVE, dragVisualizeOffset, dragRect, child.getScaleX());
        b.recycle();
    }

    boolean readingOrderGreaterThan(int[] v1, int[] v2) {
        if (v1[1] > v2[1] || (v1[1] == v2[1] && v1[0] > v2[0])) {
            return true;
        } else {
            return false;
        }
    }

    private void realTimeReorder(int[] empty, int[] target) {
        boolean wrap;
        int startX;
        int endX;
        int startY;
        int delay = 0;
        float delayAmount = 30;
        if (readingOrderGreaterThan(target, empty)) {
            wrap = empty[0] >= mContent.getCountX() - 1;
            startY = wrap ? empty[1] + 1 : empty[1];
            for (int y = startY; y <= target[1]; y++) {
                startX = y == empty[1] ? empty[0] + 1 : 0;
                endX = y < target[1] ? mContent.getCountX() - 1 : target[0];
                for (int x = startX; x <= endX; x++) {
                    View v = mContent.getChildAt(x,y);
                    if (mContent.animateChildToPosition(v, empty[0], empty[1],
                            REORDER_ANIMATION_DURATION, delay, true, true)) {
                        empty[0] = x;
                        empty[1] = y;
                        delay += delayAmount;
                        delayAmount *= 0.9;
                    }
                }
            }
        } else {
            wrap = empty[0] == 0;
            startY = wrap ? empty[1] - 1 : empty[1];
            for (int y = startY; y >= target[1]; y--) {
                startX = y == empty[1] ? empty[0] - 1 : mContent.getCountX() - 1;
                endX = y > target[1] ? 0 : target[0];
                for (int x = startX; x >= endX; x--) {
                    View v = mContent.getChildAt(x,y);
                    if (mContent.animateChildToPosition(v, empty[0], empty[1],
                            REORDER_ANIMATION_DURATION, delay, true, true)) {
                        empty[0] = x;
                        empty[1] = y;
                        delay += delayAmount;
                        delayAmount *= 0.9;
                    }
                }
            }
        }
    }
}
