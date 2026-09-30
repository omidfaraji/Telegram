package org.telegram.messenger.car;

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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;

import java.util.ArrayList;

public class MusicSongsScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE_SIZE = 50;

    private final long dialogId;
    private final String title;
    private final int startIndex;
    private boolean audioLoadRequested;

    public MusicSongsScreen(@NonNull CarContext carContext, long dialogId, String title) {
        this(carContext, dialogId, title, 0);
    }

    public MusicSongsScreen(@NonNull CarContext carContext, long dialogId, String title, int startIndex) {
        super(carContext);
        this.dialogId = dialogId;
        this.title = title != null ? title : "";
        this.startIndex = Math.max(0, startIndex);
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            getScreenManager().pop();
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
            String songTitle = mo.getMusicTitle();
            String author = mo.getMusicAuthor();

            Row.Builder row = new Row.Builder()
                    .setTitle(songTitle != null ? songTitle : " ");
            if (author != null && !author.isEmpty()) {
                row.addText(author);
            }
            boolean isCurrent = playing != null
                    && playingDialog == mo.getDialogId()
                    && playingId == mo.getId();
            if (isCurrent) {
                row.setImage(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_player)).build());
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

    private void playAtIndex(int index) {
        try {
            TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
            MediaControllerCompat controller = session.getSession().getController();
            controller.getTransportControls().playFromMediaId(dialogId + "_" + index, null);
        } catch (Throwable ignored) {
        }
    }
}
