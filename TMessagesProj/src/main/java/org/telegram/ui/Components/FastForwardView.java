package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FastForwardView extends FrameLayout {

    private static int STATE_EMPTY = 0;
    private static int STATE_EXPANDING = 1;
    private static int STATE_IDLE = 2;
    private static int STATE_COLLAPSSING = 3;
    private int state = STATE_EMPTY;


    private final Theme.ResourcesProvider resourcesProvider;
    private final RectF forwardButtonLocation = new RectF();
    private final RectF avatarsLayoutLocation = new RectF();

    private List<TLRPC.Dialog> dialogs = Collections.emptyList();
    private MessageObject pendingMessageObject;

    private float globalX;
    private float globalY;
    private final int avatarSizeDp = 42;
    private final float avatarSizePx = AndroidUtilities.dp(avatarSizeDp);
    private final int avatarsLayoutPaddingDp = 8;
    private final float avatarsLayoutPaddingPx = AndroidUtilities.dp(avatarsLayoutPaddingDp);
    private final int avatarMarginDp = 10;
    private final float avatarMarginPx = AndroidUtilities.dp(avatarMarginDp);
    private final int sideButtonSizeDp = 30;
    private final float sideButtonSizePx = AndroidUtilities.dp(sideButtonSizeDp);
    private final int avatarsLayoutMarginDp = 10;
    private final float avatarsLayoutMarginPx = AndroidUtilities.dp(avatarsLayoutMarginDp);
    private final int rootPaddingDp = 4;
    private final float rootPaddingPx = AndroidUtilities.dp(rootPaddingDp);
    private final int labelsLayoutMarginDp = 8;
    private final float labelsLayoutMarginPx = AndroidUtilities.dp(labelsLayoutMarginDp);
    private AnimatorSet currentAnimator;
    private FrameLayout avatarsLayout;
    private FrameLayout labelsLayout;
    private int selectedChildIndex = -1;
    private boolean pointerInsideAvatarsLayout = false;

    private final MultipointInterpolator inA = new MultipointInterpolator(
            new float[]{0f, 0.55f, 0.9f},
            new float[]{0f, 1.1f, 1f}
    );
    private final MultipointInterpolator inB = new MultipointInterpolator(
            new float[]{0.2f, 0.68f, 0.95f},
            new float[]{0f, 1.1f, 1f}
    );
    private final MultipointInterpolator inC = new MultipointInterpolator(
            new float[]{0.35f, 0.73f, 1f},
            new float[]{0f, 1.1f, 1f}
    );
    private final MultipointInterpolator[][] childScaleInt = new MultipointInterpolator[][]{
            {},
            {inA},
            {inA, inA},
            {inB, inA, inB},
            {inB, inA, inA, inB},
            {inC, inB, inA, inB, inC}
    };
    private final MultipointInterpolator parentWidthInterpolator = new MultipointInterpolator(
            new FastOutSlowInInterpolator(),
            new float[]{0f, 0.6f},
            new float[]{0f, 1f}
    );
    private final MultipointInterpolator parentHeightInterpolator = new MultipointInterpolator(
            new FastOutSlowInInterpolator(),
            new float[]{0f, 0.65f},
            new float[]{0f, 1f}
    );
    private final MultipointInterpolator parentScaleInterpolator = new MultipointInterpolator(
            new float[]{0.5f, 0.8f, 1f},
            new float[]{1f, 1.03f, 1f}
    );

    public FastForwardView(@NonNull Context context, @NonNull Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnimator();
        clearChildren();
    }

    private void cancelAnimator() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
    }

    private void clearChildren() {
        avatarsLayout = null;
        labelsLayout = null;
        removeAllViews();
        selectedChildIndex = -1;
        pointerInsideAvatarsLayout = false;
        state = STATE_EMPTY;
    }

    // Entry point
    public void show(MessageObject messageObject, float x, float y) {
        if (state != STATE_EMPTY) {
            return;
        }
        pendingMessageObject = messageObject;
        Context context = getContext();
        clearChildren();
        globalX = x;
        globalY = y;
        dialogs = fetchDialogs();
        if (dialogs.isEmpty()) {
            invalidate();
            return;
        }

        updateStartPoint();

        int bgColor = getThemedColor(Theme.key_chat_topPanelBackground);
        Drawable shadowBg = context.getResources().getDrawable(R.drawable.fast_forward_bg).mutate();
        shadowBg.setColorFilter(new PorterDuffColorFilter(bgColor, PorterDuff.Mode.MULTIPLY));
        float shadowSize = AndroidUtilities.dp(3);
        float bgHeight = shadowBg.getIntrinsicHeight() - shadowSize * 2;
        avatarsLayout = new FrameLayout(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                float scaleFactor = getMeasuredHeight() / bgHeight;
                int scaledWidth = (int) (getMeasuredWidth() / scaleFactor);
                canvas.save();
                canvas.translate(-shadowSize, -shadowSize);
                canvas.scale(scaleFactor, scaleFactor);
                shadowBg.setBounds(0, 0, Math.round(scaledWidth + shadowSize * 2), shadowBg.getIntrinsicHeight());
                shadowBg.draw(canvas);
                canvas.restore();
                super.onDraw(canvas);
            }
        };
        avatarsLayout.setVisibility(View.INVISIBLE);
        avatarsLayout.setWillNotDraw(false);
        labelsLayout = new FrameLayout(context);
        labelsLayout.setVisibility(View.INVISIBLE);
        labelsLayout.setClipChildren(false);
        for (TLRPC.Dialog dialog : dialogs) {
            FastForwardCell cell = new FastForwardCell(context);
            cell.setDialog(dialog.id);
            avatarsLayout.addView(cell, LayoutHelper.createFrame(avatarSizeDp, avatarSizeDp));
            labelsLayout.addView(createNameLabel(context, getLabel(dialog)), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        int count = dialogs.size();
        int width = avatarSizeDp * count + avatarsLayoutPaddingDp * 2;
        if (count > 0) {
            width += avatarMarginDp * (count - 1);
        }
        int height = avatarSizeDp + avatarsLayoutPaddingDp * 2;
        addView(avatarsLayout, LayoutHelper.createFrame(width, height));
        addView(labelsLayout, LayoutHelper.createFrame(width, LayoutHelper.WRAP_CONTENT));
        expand(avatarsLayout, labelsLayout);

        state = STATE_EXPANDING;
    }

    private CharSequence getLabel(TLRPC.Dialog dialog) {
        int currentAccount = UserConfig.selectedAccount;
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialog.id);
        if (UserObject.isUserSelf(user)) {
            return LocaleController.getString(R.string.SavedMessages);
        } else {
            if (user != null) {
                return ContactsController.formatName(user.first_name, null);
            } else {
                return "";
            }
        }
    }

    private void updateStartPoint() {
        int[] pos = new int[2];
        getLocationInWindow(pos);
        float x = globalX - pos[0];
        float y = globalY - pos[1];
        forwardButtonLocation.set(x, y, x + sideButtonSizePx, y + sideButtonSizePx);
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state == STATE_EMPTY || state == STATE_COLLAPSSING) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            collapse();
            return false;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (selectedChildIndex != -1 && dialogs.size() > selectedChildIndex) {
                sendInternal(pendingMessageObject, dialogs.get(selectedChildIndex));
            } else {
                collapse();
            }
            return false;
        } else if (state == STATE_IDLE) {
            updateAvatarsLayoutLocation();
            int index = findSelectedChildIndex(event.getX(), event.getY());
            boolean inside = avatarsLayoutLocation.contains(event.getX(), event.getY());

            if (inside != pointerInsideAvatarsLayout) {
                updateAllChildren(index, !inside);
            } else if (index != selectedChildIndex) {
                unselectChild(selectedChildIndex, !inside);
                selectChild(index);
            }
            selectedChildIndex = index;
            pointerInsideAvatarsLayout = inside;
        }
        invalidate();
        return true;
    }

    private void updateAvatarsLayoutLocation() {
        if (avatarsLayout == null) {
            avatarsLayoutLocation.set(0f, 0f, 0f, 0f);
            return;
        }
        avatarsLayoutLocation.set(0f, 0f, avatarsLayout.getMeasuredWidth(), avatarsLayout.getMeasuredHeight());
        avatarsLayoutLocation.offset(avatarsLayout.getTranslationX(), avatarsLayout.getTranslationY());
    }

    private int findSelectedChildIndex(float pX, float pY) {
        if (avatarsLayout == null) {
            return -1;
        }
        int childCount = avatarsLayout.getChildCount();
        if (childCount == 0) {
            return -1;
        }

        float childRadius = avatarSizePx / 2;
        for (int i = 0; i < childCount; i++) {
            View child = avatarsLayout.getChildAt(i);
            float cX = avatarsLayout.getX() + child.getX() + childRadius;
            float cY = avatarsLayout.getY() + child.getY() + childRadius;
            float dx = pX - cX;
            float dy = pY - cY;
            float distanceSquared = dx * dx + dy * dy;
            float radiusSquared = childRadius * childRadius;
            if (distanceSquared <= radiusSquared) {
                return i;
            }
        }
        return -1;
    }

    private void updateAllChildren(int selectedIndex, boolean fullAlpha) {
        if (avatarsLayout == null) {
            return;
        }
        for (int i = 0; i < avatarsLayout.getChildCount(); i++) {
            if (i == selectedIndex) {
                selectChild(i);
            } else {
                unselectChild(i, fullAlpha);
            }
        }
    }

    private void selectChild(int index) {
        changeChildState(index, true, true);
    }

    private void unselectChild(int index, boolean fullAlpha) {
        changeChildState(index, false, fullAlpha);
    }

    private void changeChildState(int index, boolean selected, boolean fullAlpha) {
        if (avatarsLayout == null || index == -1 || index >= avatarsLayout.getChildCount()) {
            return;
        }
        View avatar = avatarsLayout.getChildAt(index);
        View label = labelsLayout.getChildAt(index);
        AnimatorSet lastAnimator = (AnimatorSet) avatar.getTag();
        if (lastAnimator != null) {
            lastAnimator.cancel();
        }
        float endSelectorValue = selected ? 1.1f : 1f;
        float endAlphaValue = fullAlpha ? 1.0f : 0.7f;

        AnimatorSet animator = new AnimatorSet();
        avatar.setTag(animator);
        List<Animator> animators = new ArrayList<>();
        // Selector animator
        ValueAnimator selectorAnimator = ValueAnimator.ofFloat(avatar.getScaleX(), endSelectorValue);
        selectorAnimator.addUpdateListener(valueAnimator -> {
            float progress = (float) valueAnimator.getAnimatedValue();
            avatar.setScaleX(progress);
            avatar.setScaleY(progress);
            label.setAlpha((progress - 1f) * 10f);
            label.setScaleX(progress - 0.1f);
            label.setScaleY(progress - 0.1f);
        });
        animators.add(selectorAnimator);

        // Avatar alpha animator
        ValueAnimator avatarAlphaAnimator = ValueAnimator.ofFloat(avatar.getAlpha(), endAlphaValue);
        avatarAlphaAnimator.addUpdateListener(valueAnimator -> {
            float progress = (float) valueAnimator.getAnimatedValue();
            avatar.setAlpha(progress);
        });
        animators.add(avatarAlphaAnimator);

        animator.setDuration(100);
        animator.setInterpolator(new LinearInterpolator());
        animator.playTogether(animators);
        animator.start();
    }

    public List<TLRPC.Dialog> fetchDialogs() {
        int currentAccount = UserConfig.selectedAccount;
        List<TLRPC.Dialog> dialogs = new ArrayList<>();
        long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
        if (!MessagesController.getInstance(currentAccount).dialogsForward.isEmpty()) {
            TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogsForward.get(0);
            dialogs.add(dialog);
        }
        ArrayList<TLRPC.Dialog> archivedDialogs = new ArrayList<>();
        ArrayList<TLRPC.Dialog> allDialogs = MessagesController.getInstance(currentAccount).getAllDialogs();
        for (int a = 0; a < allDialogs.size(); a++) {
            TLRPC.Dialog dialog = allDialogs.get(a);
            if (!(dialog instanceof TLRPC.TL_dialog)) {
                continue;
            }
            if (dialog.id == selfUserId) {
                continue;
            }
            if (!DialogObject.isEncryptedDialog(dialog.id) && DialogObject.isUserDialog(dialog.id)) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialog.id);
                if (!user.bot) {
                    if (dialog.folder_id == 1) {
                        archivedDialogs.add(dialog);
                    } else {
                        dialogs.add(dialog);
                    }
                }
            }
            if (dialogs.size() >= 5) {
                break;
            }
        }
        dialogs.addAll(archivedDialogs);
        int maxCount = 5;
        if (dialogs.size() > maxCount) {
            dialogs = dialogs.subList(0, maxCount);
        }
        return dialogs;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private Paint getThemedPaint(String paintKey) {
        Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(paintKey) : null;
        return paint != null ? paint : Theme.getThemePaint(paintKey);
    }

    private void expand(FrameLayout avatars, FrameLayout labels) {
        cancelAnimator();
        ViewGroup.LayoutParams lp = avatars.getLayoutParams();
        int widthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
        int heightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
        avatars.measure(widthSpec, heightSpec);
        labels.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        placeAvatars(avatars, 0f);
        placeLabels(labels);
        final int avatarsW = avatars.getMeasuredWidth();
        final int avatarsH = avatars.getMeasuredHeight();
        final float minW = (int) sideButtonSizePx;
        final float minH = (int) sideButtonSizePx;
        currentAnimator = new AnimatorSet();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(valueAnimator -> {
            float animatedValue = (float) valueAnimator.getAnimatedValue();
            placeParentLayouts(avatars, labels, avatarsW, avatarsH, minW, minH, animatedValue);
            placeAvatars(avatars, animatedValue);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                avatars.setVisibility(View.VISIBLE);
                labels.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                state = STATE_IDLE;
            }
        });
        // 1000
        animator.setDuration(1_000);
        animator.setInterpolator(new LinearInterpolator());
        currentAnimator.play(animator);
        currentAnimator.start();
    }

    public boolean collapse() {
        return collapse(avatarsLayout, labelsLayout, false, 0, 0);
    }

    public boolean collapse(float cX, float cY) {
        return collapse(avatarsLayout, labelsLayout, true, cX, cY);
    }

    private boolean collapse(FrameLayout avatars, FrameLayout labels, boolean moveToTarget, float outCx, float outCy) {
        if (state == STATE_COLLAPSSING || state == STATE_EMPTY) {
            return false;
        }
        state = STATE_COLLAPSSING;
        bringToFront();
        if (avatars == null || labels == null) {
            return false;
        }
        cancelAnimator();
        final View selectedAvatarView;
        float selectedAvatarViewStartCx;
        float selectedAvatarViewStartCy;
        float fadeStartScale;
        if (selectedChildIndex != -1 && avatars.getChildCount() > selectedChildIndex && moveToTarget) {
            selectedAvatarView = avatars.getChildAt(selectedChildIndex);
            avatars.removeView(selectedAvatarView);
            selectedAvatarView.setTranslationX(selectedAvatarView.getTranslationX() + avatars.getTranslationX());
            selectedAvatarView.setTranslationY(selectedAvatarView.getTranslationY() + avatars.getTranslationY());
            selectedAvatarViewStartCx = selectedAvatarView.getTranslationX() + avatarSizePx / 2f;
            selectedAvatarViewStartCy = selectedAvatarView.getTranslationY() + avatarSizePx / 2f;
            fadeStartScale = selectedAvatarView.getScaleX();
            addView(selectedAvatarView);
        } else {
            selectedAvatarView = null;
            selectedAvatarViewStartCx = 0;
            selectedAvatarViewStartCy = 0;
            fadeStartScale = 0;
        }
        // Main view
        avatars.setPivotX(avatars.getMeasuredWidth() / 2f);
        avatars.setPivotY(avatars.getMeasuredHeight() / 2f);
        currentAnimator = new AnimatorSet();
        List<Animator> animators = new ArrayList<>();
        ValueAnimator fadeOutAnimator = ValueAnimator.ofFloat(0f, 1f);
        fadeOutAnimator.addUpdateListener(valueAnimator -> {
            float progress = (float) valueAnimator.getAnimatedValue();
            float scale = 0.7f + 0.3f * progress;
            avatars.setAlpha(progress);
            avatars.setScaleX(scale);
            avatars.setScaleY(scale);
            labels.setAlpha(progress * progress);
        });

        fadeOutAnimator.setDuration(300);
        fadeOutAnimator.setInterpolator(new ReverseInterpolator(new FastOutSlowInInterpolator()));
        animators.add(fadeOutAnimator);

        if (selectedAvatarView != null) {
            // Move
            ValueAnimator fadeOutMoveAnimator = ValueAnimator.ofFloat(0f, 1f);
            Interpolator yInterpolator = new OvershootInterpolator(1f);
            fadeOutMoveAnimator.addUpdateListener(valueAnimator -> {
                float progress = (float) valueAnimator.getAnimatedValue();
                float tx = outCx + (selectedAvatarViewStartCx - outCx) * progress;
                float ty = outCy + (selectedAvatarViewStartCy - outCy) * yInterpolator.getInterpolation(progress);
                float scale = Math.max(0.45f, fadeStartScale * progress);
                selectedAvatarView.setTranslationX(tx - avatarSizePx / 2f);
                selectedAvatarView.setTranslationY(ty - avatarSizePx / 2f);
                selectedAvatarView.setScaleX(scale);
                selectedAvatarView.setScaleY(scale);
            });
            fadeOutMoveAnimator.setDuration(300);
            fadeOutMoveAnimator.setInterpolator(new ReverseInterpolator(new FastOutSlowInInterpolator()));
            animators.add(fadeOutMoveAnimator);

            // Hide
            ValueAnimator fadeOutAlphaAnimator = ValueAnimator.ofFloat(0f, 1f);
            fadeOutAlphaAnimator.addUpdateListener(valueAnimator -> {
                float progress = (float) valueAnimator.getAnimatedValue();
                selectedAvatarView.setAlpha(progress);
            });
            fadeOutAlphaAnimator.setDuration(150);
            fadeOutAlphaAnimator.setInterpolator(new ReverseInterpolator(new LinearInterpolator()));
            fadeOutAlphaAnimator.setStartDelay(300);
            animators.add(fadeOutAlphaAnimator);
        }

        currentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                clearChildren();
            }
        });
        currentAnimator.playTogether(animators);
        currentAnimator.start();
        return true;
    }

    private void placeParentLayouts(FrameLayout avatars, FrameLayout labels, int targetWidth, int targetHeight, float minWidth, float minHeight, float progress) {
        int rootWidth = getMeasuredWidth();
        final float targetDeltaWidth = targetWidth - minWidth;
        final float targetDeltaHeight = targetHeight - minHeight;
        ViewGroup.LayoutParams layoutParams = avatars.getLayoutParams();
        float widthProgress = parentWidthInterpolator.interpolate(progress);
        float heightProgress = parentHeightInterpolator.interpolate(progress);
        layoutParams.width = (int) (minWidth + widthProgress * targetDeltaWidth);
        layoutParams.height = (int) (minHeight + heightProgress * targetDeltaHeight);
        avatars.setLayoutParams(layoutParams);
        float cX = forwardButtonLocation.centerX();
        float leftX = cX - targetWidth / 2f;
        float rightX = leftX + targetWidth;
        float shiftX = 0f;
        if (leftX < rootPaddingPx) {
            shiftX = rootPaddingPx - leftX;
        } else if (rightX > rootWidth - rootPaddingPx) {
            shiftX = (rootWidth - rootPaddingPx) - rightX;
        }
        leftX += shiftX;
        float translationX = leftX + (forwardButtonLocation.left - leftX) * (1 - widthProgress);
        avatars.setTranslationX(translationX);
        labels.setTranslationX(translationX);
        float targetTranslationY = heightProgress * (avatarsLayoutMarginPx + sideButtonSizePx);
        float avatarsTranslationY = forwardButtonLocation.top - (layoutParams.height - forwardButtonLocation.height()) - targetTranslationY;
        float labelsTranslationY = avatarsTranslationY - labels.getMeasuredHeight() - labelsLayoutMarginPx;
        if (labelsTranslationY < 0) {
            avatarsTranslationY -= labelsTranslationY;
            labelsTranslationY = 0;
        }
        avatars.setTranslationY(avatarsTranslationY);
        labels.setTranslationY(labelsTranslationY);

        // Scale (in the end)
        float parentScale = parentScaleInterpolator.interpolate(progress);
        avatars.setPivotX(layoutParams.width / 2f);
        avatars.setPivotY(layoutParams.height);
        avatars.setScaleX(parentScale);
        avatars.setScaleY(parentScale);
    }

    private void placeLabels(FrameLayout labels) {
        int childCount = labels.getChildCount();
        if (childCount == 0) {
            return;
        }
        int labelsW = labels.getMeasuredWidth();
        for (int i = 0; i < childCount; i++) {
            View child = labels.getChildAt(i);
            int childW = child.getMeasuredWidth();
            if (childW > labelsW) {
                child.setTranslationX((labelsW - childW) / 2f);
            } else {
                child.setTranslationX(avatarsLayoutPaddingPx + (avatarSizePx + avatarMarginPx) * i + (avatarSizePx - childW) / 2);
                if (child.getTranslationX() < 0) {
                    child.setTranslationX(0);
                } else if (child.getTranslationX() + childW > labelsW) {
                    child.setTranslationX(labelsW - childW);
                }
            }
        }
    }

    private void placeAvatars(FrameLayout avatars, float progress) {
        int childCount = avatars.getChildCount();
        if (childCount == 0) {
            return;
        }
        int targetMeasuredWidth = avatars.getMeasuredWidth();
        int targetMeasuredHeight = avatars.getMeasuredHeight();
        float startX;
        float dx;
        float y = (targetMeasuredHeight - avatarSizePx) / 2f;
        if (childCount > 1) {
            if (avatarSizePx + avatarsLayoutPaddingPx * 2 >= targetMeasuredWidth) {
                dx = 0f;
                startX = targetMeasuredWidth / 2f - avatarSizePx / 2f;
            } else {
                dx = avatarSizePx + (targetMeasuredWidth - avatarsLayoutPaddingPx * 2f - avatarSizePx * childCount) / (childCount - 1);
                startX = avatarsLayoutPaddingPx;
            }
        } else {
            dx = 0f;
            startX = Math.min(avatarsLayoutPaddingPx, targetMeasuredWidth / 2f);
        }
        boolean hasCenterView = childCount % 2 == 1;
        int centerViewIndex = childCount / 2;
        final MultipointInterpolator[] childScales = childScaleInt[childCount];
        for (int i = 0; i < childCount; i++) {
            MultipointInterpolator scaleInt = childScales[i];
            float childScaleProgress = scaleInt.interpolate(progress);
            View child = avatars.getChildAt(i);
            child.setTranslationX(startX + dx * i);
            child.setTranslationY(y);
            child.setPivotY(avatarSizePx / 2);
            if (i < centerViewIndex) {
                child.setPivotX(childScaleProgress * avatarSizePx / 2);
            } else {
                if (hasCenterView && i == centerViewIndex) {
                    child.setPivotX(avatarSizePx / 2);
                } else {
                    child.setPivotX(avatarSizePx - childScaleProgress * avatarSizePx / 2);
                }
            }

            child.setScaleX(childScaleProgress);
            child.setScaleY(childScaleProgress);
        }
    }

    protected void sendInternal(MessageObject messageObject, TLRPC.Dialog dialog) {
        int currentAccount = UserConfig.selectedAccount;
        if (messageObject == null || dialog == null) {
            return;
        }
        if (AlertsCreator.checkSlowMode(getContext(), currentAccount, dialog.id, false)) {
            return;
        }
        ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(messageObject);
        int result = SendMessagesHelper.getInstance(currentAccount).sendMessage(messages, dialog.id, true, false, true, 0);
        onMessageSent(result, messageObject, dialog);
    }

    public void onMessageSent(int result, MessageObject messageObject, TLRPC.Dialog dialog) {
    }

    public boolean hasGradientService() {
        return resourcesProvider != null ? resourcesProvider.hasGradientService() : Theme.hasGradientService();
    }

    private View createNameLabel(Context context, CharSequence label) {
        TextPaint textPaint = (TextPaint) getThemedPaint(Theme.key_paint_chatActionText);
        int maxWidth = (int) (avatarSizePx * 2 + avatarMarginPx + avatarsLayoutPaddingPx);
        int horizontalPadding = AndroidUtilities.dp2(8);
        int verticalPadding = AndroidUtilities.dp2(4);
        RectF bgRect = new RectF();
        View labelView = new BlurLabel(context, label, textPaint, maxWidth) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int widthWithPadding = (int) textLayout.getLineWidth(0) + horizontalPadding * 2;
                int heightWithPadding = textLayout.getHeight() + verticalPadding * 2;
                setMeasuredDimension(widthWithPadding, heightWithPadding);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                FastForwardView root = FastForwardView.this;
                Theme.applyServiceShaderMatrix(root.getMeasuredWidth(), root.getMeasuredHeight(), labelsLayout.getTranslationX() + getTranslationX(), labelsLayout.getTranslationY() + getTranslationX());
                Paint backgroundPaint = getThemedPaint(Theme.key_paint_chatActionBackground);
                float r = AndroidUtilities.dpf2(32);
                bgRect.set(0f, 0f, getMeasuredWidth(), getMeasuredHeight());
                canvas.drawRoundRect(bgRect, r, r, backgroundPaint);
                canvas.save();
                canvas.translate(horizontalPadding, verticalPadding);
                textLayout.draw(canvas);
                canvas.restore();
            }
        };
        labelView.setAlpha(0f);
        return labelView;
    }

    private static class BlurLabel extends View {

        public StaticLayout textLayout;

        public BlurLabel(Context context, CharSequence text, TextPaint textPaint, int maxWidth) {
            super(context);
            CharSequence displayText = Emoji.replaceEmoji(text, textPaint.getFontMetricsInt(), dp(10), false);
            textLayout = createLabelTextLayout(displayText, textPaint, maxWidth);
            NotificationCenter.getGlobalInstance().listenGlobal(this, NotificationCenter.emojiLoaded, args -> {
                CharSequence updatedDisplayText = Emoji.replaceEmoji(text, textPaint.getFontMetricsInt(), dp(10), false);
                textLayout = createLabelTextLayout(updatedDisplayText, textPaint, maxWidth);
                invalidate();
            });
        }

        private StaticLayout createLabelTextLayout(CharSequence text, TextPaint textPaint, int maxWidth) {
            StaticLayout textLayout = createTextLayout(text, textPaint, maxWidth);
            if (textLayout.getLineCount() > 1) {
                int lineEnd = textLayout.getLineEnd(0) - 1;
                textLayout = createTextLayout(
                        text.subSequence(0, lineEnd) + "…", textPaint, maxWidth
                );
            }
            if (textLayout.getLineCount() > 1) {
                int lineEnd = textLayout.getLineEnd(0);
                textLayout = createTextLayout(text.subSequence(0, lineEnd), textPaint, maxWidth);
            }
            return textLayout;
        }

        private StaticLayout createTextLayout(CharSequence text, TextPaint textPaint, int width) {
            return new StaticLayout(
                    text,
                    textPaint,
                    width,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.0f,
                    0f,
                    false
            );
        }
    }

    private final static class MultipointInterpolator {

        final Interpolator interpolator;
        final float[] keyPoints;
        final float[] keyValues;

        public MultipointInterpolator(float[] keyPoints, float[] keyValues) {
            this(new LinearInterpolator(), keyPoints, keyValues);
        }

        public MultipointInterpolator(Interpolator interpolator, float[] keyPoints, float[] keyValues) {
            this.interpolator = interpolator;
            this.keyPoints = keyPoints;
            this.keyValues = keyValues;
        }

        public float interpolate(float progress) {
            return interpolator.getInterpolation(interpolateInternal(progress));
        }

        private float interpolateInternal(float progress) {
            if (progress <= keyPoints[0]) {
                return keyValues[0];
            } else if (progress >= keyPoints[keyPoints.length - 1]) {
                return keyValues[keyValues.length - 1];
            }
            for (int i = 1; i < keyPoints.length; i++) {
                if (progress < keyPoints[i]) {
                    float t = (progress - keyPoints[i - 1]) / (keyPoints[i] - keyPoints[i - 1]);
                    return interpolate(keyValues[i - 1], keyValues[i], t);
                }
            }
            return keyValues[keyValues.length - 1];
        }

        private float interpolate(float startValue, float endValue, float t) {
            return startValue + t * (endValue - startValue);
        }
    }

    private static class ReverseInterpolator implements Interpolator {
        private final Interpolator direct;

        public ReverseInterpolator(Interpolator direct) {
            this.direct = direct;
        }

        @Override
        public float getInterpolation(float input) {
            return 1f - direct.getInterpolation(input);
        }
    }
}
