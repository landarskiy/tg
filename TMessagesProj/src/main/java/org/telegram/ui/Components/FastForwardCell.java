package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

public class FastForwardCell extends FrameLayout {

    private final int currentAccount = UserConfig.selectedAccount;
    private TLRPC.User user;
    private final BackupImageView imageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable() {
        @Override
        public void invalidateSelf() {
            super.invalidateSelf();
            imageView.invalidate();
        }
    };

    public FastForwardCell(Context context) {
        super(context);
        setWillNotDraw(false);

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(21));
        addView(imageView, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
    }

    public void setDialog(long uid) {
        if (DialogObject.isUserDialog(uid)) {
            user = MessagesController.getInstance(currentAccount).getUser(uid);
            invalidate();
            avatarDrawable.setInfo(currentAccount, user);
            if (UserObject.isReplyUser(user)) {
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
                imageView.setImage(null, null, avatarDrawable, user);
            } else if (UserObject.isUserSelf(user)) {
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                imageView.setImage(null, null, avatarDrawable, user);
            } else {
                imageView.setForUserOrChat(user, avatarDrawable);
            }
            imageView.setRoundRadius(dp(28));
        } else {
            user = null;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-uid);
            avatarDrawable.setInfo(currentAccount, chat);
            imageView.setForUserOrChat(chat, avatarDrawable);
            imageView.setRoundRadius(chat != null && chat.forum ? dp(16) : dp(28));
        }
    }
}
