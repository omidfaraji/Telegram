package org.telegram.messenger.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class AudioChatsScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final int RESERVED_ROWS = 2;

    private final int startIndex;
    private int currentAccount;
    private int visibleCount;
    private boolean loadRequested;

    public AudioChatsScreen(@NonNull CarContext carContext, int startIndex) {
        super(carContext);
        this.startIndex = Math.max(0, startIndex);
        visibleCount = CarListLimits.initialCount(carContext, RESERVED_ROWS);
        currentAccount = UserConfig.selectedAccount;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        addAccountObservers();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        removeAccountObservers();
    }

    private void addAccountObservers() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.addObserver(this, NotificationCenter.historyCleared);
    }

    private void removeAccountObservers() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        notificationCenter.removeObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.removeObserver(this, NotificationCenter.historyCleared);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            removeAccountObservers();
            currentAccount = UserConfig.selectedAccount;
            addAccountObservers();
            invalidate();
        } else if (id == NotificationCenter.didReceiveNewMessages
                && args.length > 1
                && args[0] instanceof Long
                && args[1] instanceof ArrayList
                && (args.length <= 2 || !Boolean.TRUE.equals(args[2]))) {
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (TelegramMediaSession.hasPlayableAudio(messages)) {
                TelegramMediaSession.getInstance(getCarContext().getApplicationContext())
                        .refreshAudioData((Long) args[0], this::invalidate);
            }
        } else if (id == NotificationCenter.messagesDeleted || id == NotificationCenter.historyCleared) {
            TelegramMediaSession.getInstance(getCarContext().getApplicationContext())
                    .refreshAudioCatalog(this::invalidate);
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
        if (!session.isChatsLoaded()) {
            if (!loadRequested) {
                loadRequested = true;
                session.ensureLoaded(() -> {
                    loadRequested = false;
                    invalidate();
                });
            }
            return new ListTemplate.Builder()
                    .setTitle(LocaleController.getString(R.string.AudioChats))
                    .setHeaderAction(Action.BACK)
                    .setActionStrip(buildVideoToggleStrip())
                    .setLoading(true)
                    .build();
        }
        ArrayList<Long> dialogs = session.getAudioDialogsSortedByVisibleOrder();
        ItemList.Builder list = new ItemList.Builder();
        if (dialogs.isEmpty()) {
            list.setNoItemsMessage(LocaleController.getString(R.string.NoAudioFiles));
            return new ListTemplate.Builder()
                    .setTitle(LocaleController.getString(R.string.AudioChats))
                    .setHeaderAction(Action.BACK)
                    .setActionStrip(buildVideoToggleStrip())
                    .setSingleList(list.build())
                    .build();
        }

        int maxCount = CarListLimits.maxCount(getCarContext(), RESERVED_ROWS);
        visibleCount = Math.min(visibleCount, maxCount);
        int endIndex = Math.min(dialogs.size(), startIndex + visibleCount);
        for (int i = startIndex; i < endIndex; i++) {
            long dialogId = dialogs.get(i);
            String title;
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = session.getMusicUser(dialogId);
                if (user == null) {
                    continue;
                }
                title = UserObject.isUserSelf(user)
                        ? LocaleController.getString(R.string.SavedMessages)
                        : ContactsController.formatName(user.first_name, user.last_name);
            } else {
                TLRPC.Chat chat = session.getMusicChat(-dialogId);
                if (chat == null) {
                    continue;
                }
                title = chat.title != null ? chat.title : "";
            }
            list.addItem(new Row.Builder()
                    .setTitle(title)
                    .addText(LocaleController.formatPluralString(
                            "AudioFiles", session.getAudioMessageCount(dialogId)))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(
                            new MusicSongsScreen(getCarContext(), dialogId, title)))
                    .build());
        }

        if (startIndex > 0) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString(R.string.PreviousAudioChats))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().pop())
                    .build());
        }
        if (endIndex < dialogs.size()) {
            Row.Builder more = new Row.Builder()
                    .setTitle(LocaleController.getString(R.string.MoreAudioChats));
            if (visibleCount < maxCount) {
                more.setOnClickListener(() -> {
                    visibleCount = Math.min(visibleCount + CarListLimits.LOAD_MORE_STEP, maxCount);
                    invalidate();
                });
            } else {
                more.setBrowsable(true)
                        .setOnClickListener(() -> getScreenManager().push(
                                new AudioChatsScreen(getCarContext(), endIndex)));
            }
            list.addItem(more.build());
        }

        return new ListTemplate.Builder()
                .setTitle(LocaleController.getString(R.string.AudioChats))
                .setHeaderAction(Action.BACK)
                .setActionStrip(buildVideoToggleStrip())
                .setSingleList(list.build())
                .build();
    }

    private ActionStrip buildVideoToggleStrip() {
        boolean enabled = TelegramMediaSession.isShowVideosEnabled();
        return new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setTitle(LocaleController.getString(enabled ? R.string.CarVideosOn : R.string.CarVideosOff))
                        .setOnClickListener(() -> TelegramMediaSession
                                .getInstance(getCarContext().getApplicationContext())
                                .setShowVideosEnabled(!enabled, this::invalidate))
                        .build())
                .build();
    }
}
