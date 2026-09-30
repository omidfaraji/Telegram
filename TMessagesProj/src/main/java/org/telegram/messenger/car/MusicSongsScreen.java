package org.telegram.messenger.car;

import android.graphics.Bitmap;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

public class MusicSongsScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 50;

    private final long dialogId;
    private final String title;
    private final int startIndex;
    private boolean audioLoadRequested;
    private int currentAccount;
    private boolean coverRefreshScheduled;

    public MusicSongsScreen(@NonNull CarContext carContext, long dialogId, String title) {
        this(carContext, dialogId, title, 0);
    }

    public MusicSongsScreen(@NonNull CarContext carContext, long dialogId, String title, int startIndex) {
        super(carContext);
        this.dialogId = dialogId;
        this.title = title != null ? title : "";
        this.startIndex = Math.max(0, startIndex);
        currentAccount = UserConfig.selectedAccount;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        addAccountObservers();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        removeAccountObservers();
    }

    private void addAccountObservers() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.addObserver(this, NotificationCenter.historyCleared);
        notificationCenter.addObserver(this, NotificationCenter.fileLoaded);
    }

    private void removeAccountObservers() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        notificationCenter.removeObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.removeObserver(this, NotificationCenter.historyCleared);
        notificationCenter.removeObserver(this, NotificationCenter.fileLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            removeAccountObservers();
            currentAccount = UserConfig.selectedAccount;
            addAccountObservers();
            getScreenManager().pop();
            return;
        } else if (id == NotificationCenter.didReceiveNewMessages
                && args.length > 1
                && args[0] instanceof Long
                && (Long) args[0] == dialogId
                && args[1] instanceof ArrayList
                && (args.length <= 2 || !Boolean.TRUE.equals(args[2]))) {
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (TelegramMediaSession.hasPlayableAudio(messages)) {
                TelegramMediaSession.getInstance(getCarContext().getApplicationContext())
                        .refreshAudioData(dialogId, this::invalidate);
            }
            return;
        } else if (id == NotificationCenter.fileLoaded) {
            scheduleCoverRefresh();
            return;
        } else if (id == NotificationCenter.messagesDeleted || id == NotificationCenter.historyCleared) {
            TelegramMediaSession.getInstance(getCarContext().getApplicationContext())
                    .refreshAudioData(dialogId, this::invalidate);
            audioLoadRequested = true;
            return;
        }
        invalidate();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
        String headerTitle = title.isEmpty() ? " " : title;

        ArrayList<MessageObject> audioMessages = session.getAudioMessages(dialogId);
        if (audioMessages == null) {
            if (!audioLoadRequested) {
                audioLoadRequested = true;
                session.loadAudioMessages(dialogId, this::invalidate);
            }
            return new ListTemplate.Builder()
                    .setTitle(headerTitle)
                    .setHeaderAction(Action.BACK)
                    .setLoading(true)
                    .build();
        }
        if (audioMessages.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoAudioFiles))
                    .setTitle(headerTitle)
                    .setHeaderAction(Action.BACK)
                    .build();
        }

        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        long playingId = playing != null ? playing.getId() : 0;
        long playingDialog = playing != null ? playing.getDialogId() : 0;

        ItemList.Builder list = new ItemList.Builder();
        int endIndex = Math.min(audioMessages.size(), startIndex + PAGE_SIZE);
        for (int i = startIndex; i < endIndex; i++) {
            MessageObject mo = audioMessages.get(i);
            if (mo == null) continue;
            String songTitle = TelegramMediaSession.getAudioTitle(mo);
            String author = mo.getMusicAuthor();
            String videoLabel = TelegramMediaSession.getVideoLabel(mo);

            Row.Builder row = new Row.Builder()
                    .setTitle(songTitle != null ? songTitle : " ");
            String details = author;
            if (videoLabel != null) {
                details = author != null && !author.isEmpty() ? videoLabel + " \u00B7 " + author : videoLabel;
            }
            if (details != null && !details.isEmpty()) {
                row.addText(details);
            }
            boolean isCurrent = playing != null
                    && playingDialog == mo.getDialogId()
                    && playingId == mo.getId();
            Bitmap cover = session.getAudioCover(mo);
            IconCompat icon;
            if (cover != null) {
                icon = IconCompat.createWithBitmap(cover);
            } else {
                icon = IconCompat.createWithResource(getCarContext(),
                        isCurrent ? R.drawable.ic_player
                                : videoLabel != null ? R.drawable.msg_video : R.drawable.filled_widget_music);
            }
            row.setImage(new CarIcon.Builder(icon).build(), Row.IMAGE_TYPE_LARGE);
            if (isCurrent && cover != null) {
                row.setTitle("\u25B6 " + (songTitle != null ? songTitle : ""));
            }
            final int index = i;
            row.setOnClickListener(() -> playAtIndex(index));
            list.addItem(row.build());
        }
        if (startIndex > 0) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString(R.string.PreviousAudioFiles))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(new MusicSongsScreen(
                            getCarContext(), dialogId, title, Math.max(0, startIndex - PAGE_SIZE))))
                    .build());
        }
        if (endIndex < audioMessages.size()) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString(R.string.MoreAudioFiles))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(new MusicSongsScreen(
                            getCarContext(), dialogId, title, endIndex)))
                    .build());
        }

        return new ListTemplate.Builder()
                .setTitle(headerTitle)
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private void scheduleCoverRefresh() {
        if (coverRefreshScheduled) {
            return;
        }
        coverRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            coverRefreshScheduled = false;
            invalidate();
        }, 1000);
    }

    private void playAtIndex(int index) {
        try {
            TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
            MediaControllerCompat controller = session.getSession().getController();
            controller.getTransportControls().playFromMediaId(dialogId + "_" + index, null);
        } catch (Throwable ignored) {
        }
    }
}
