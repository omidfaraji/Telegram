package org.telegram.messenger.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
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

    private static final int PAGE_SIZE = 50;

    private final int startIndex;
    private int currentAccount;

    public AudioChatsScreen(@NonNull CarContext carContext, int startIndex) {
        super(carContext);
        this.startIndex = Math.max(0, startIndex);
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
        ArrayList<Long> dialogs = session.getAudioDialogsSortedByVisibleOrder();
        if (dialogs.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoAudioFiles))
                    .setTitle(LocaleController.getString(R.string.AudioChats))
                    .setHeaderAction(Action.BACK)
                    .build();
        }

        ItemList.Builder list = new ItemList.Builder();
        int endIndex = Math.min(dialogs.size(), startIndex + PAGE_SIZE);
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
                    .setOnClickListener(() -> getScreenManager().push(
                            new AudioChatsScreen(getCarContext(), Math.max(0, startIndex - PAGE_SIZE))))
                    .build());
        }
        if (endIndex < dialogs.size()) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString(R.string.MoreAudioChats))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(
                            new AudioChatsScreen(getCarContext(), endIndex)))
                    .build());
        }

        return new ListTemplate.Builder()
                .setTitle(LocaleController.getString(R.string.AudioChats))
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }
}
