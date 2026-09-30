package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressLint("StaticFieldLeak")
public class TelegramMediaSession {

    private static volatile TelegramMediaSession instance;

    public static TelegramMediaSession getInstance(Context context) {
        if (instance == null) {
            synchronized (TelegramMediaSession.class) {
                if (instance == null) {
                    instance = new TelegramMediaSession(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @Nullable
    public static TelegramMediaSession peekInstance() {
        return instance;
    }

    // Android Auto shows its now-playing bar for the session exposed by MusicBrowserService,
    // so mirror the player service session state here.
    private static MediaMetadataCompat lastPlayerMetadata;
    private static PlaybackStateCompat lastPlayerState;

    public static void mirrorPlayerMetadata(MediaMetadataCompat metadata) {
        lastPlayerMetadata = metadata;
        TelegramMediaSession current = instance;
        if (current != null) {
            current.session.setMetadata(metadata);
        }
    }

    public static void mirrorPlayerState(PlaybackStateCompat state) {
        lastPlayerState = state;
        TelegramMediaSession current = instance;
        if (current != null) {
            current.session.setPlaybackState(state);
        }
    }

    public static void clearPlayerState() {
        lastPlayerMetadata = null;
        lastPlayerState = null;
        TelegramMediaSession current = instance;
        if (current != null) {
            current.session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                    .setActions(current.getAvailableActions())
                    .build());
        }
    }

    private static final String SESSION_TAG = "TelegramMediaSession";
    private static final String MEDIA_ID_ROOT = "__ROOT__";
    private static final String MEDIA_ID_CHAT_PREFIX = "__CHAT_";

    private static final String SLOT_RESERVATION_SKIP_TO_NEXT = "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT";
    private static final String SLOT_RESERVATION_SKIP_TO_PREV = "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS";
    private static final String SLOT_RESERVATION_QUEUE = "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_QUEUE";

    private static final String CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    private static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final int CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1;
    private static final int CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2;

    private final Context appContext;
    private final MediaSessionCompat session;

    private int currentAccount;
    private long lastSelectedDialog;

    private boolean chatsLoaded;
    private boolean loadingChats;
    private int audioCatalogRevision;
    private final ArrayList<Runnable> pendingCatalogCallbacks = new ArrayList<>();
    private final ArrayList<Long> dialogs = new ArrayList<>();
    private final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
    private final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<MessageObject>> audioObjects = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<MediaSessionCompat.QueueItem>> audioQueues = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Runnable>> pendingAudioLoads = new LongSparseArray<>();
    private final LongSparseArray<Integer> audioCounts = new LongSparseArray<>();

    private Paint roundPaint;
    private RectF bitmapRect;

    private TelegramMediaSession(Context appContext) {
        this.appContext = appContext;
        this.currentAccount = UserConfig.selectedAccount;
        this.lastSelectedDialog = AndroidUtilities.getPrefIntOrLong(MessagesController.getNotificationsSettings(currentAccount), "auto_lastSelectedDialog", 0);

        session = new MediaSessionCompat(appContext, SESSION_TAG);
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new SessionCallback());

        Intent activityIntent = new Intent(appContext, LaunchActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                appContext, 99, activityIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session.setSessionActivity(pi);

        Bundle extras = new Bundle();
        extras.putBoolean(SLOT_RESERVATION_QUEUE, true);
        extras.putBoolean(SLOT_RESERVATION_SKIP_TO_PREV, true);
        extras.putBoolean(SLOT_RESERVATION_SKIP_TO_NEXT, true);
        session.setExtras(extras);

        session.setActive(true);

        PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                .setActions(getAvailableActions());
        session.setPlaybackState(pb.build());
        if (lastPlayerMetadata != null) {
            session.setMetadata(lastPlayerMetadata);
        }
        if (lastPlayerState != null) {
            session.setPlaybackState(lastPlayerState);
        }

        updateRepeatMode();
        updateShuffleMode();

        NotificationCenter.getGlobalInstance().addObserver(
                (id, account, args) -> {
                    if (id == NotificationCenter.activeAccountChanged) {
                        AndroidUtilities.runOnUIThread(this::onAccountSwitched);
                    }
                }, NotificationCenter.activeAccountChanged);
    }

    private void onAccountSwitched() {
        currentAccount = UserConfig.selectedAccount;
        audioCatalogRevision++;
        lastSelectedDialog = AndroidUtilities.getPrefIntOrLong(
                MessagesController.getNotificationsSettings(currentAccount), "auto_lastSelectedDialog", 0);
        chatsLoaded = false;
        loadingChats = false;
        pendingCatalogCallbacks.clear();
        dialogs.clear();
        users.clear();
        chats.clear();
        audioObjects.clear();
        audioQueues.clear();
        pendingAudioLoads.clear();
        audioCounts.clear();
        try {
            session.setQueue(null);
            session.setQueueTitle(null);
        } catch (Throwable ignored) {
        }
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    public ArrayList<Long> getAudioDialogsSortedByVisibleOrder() {
        ArrayList<Long> sorted = new ArrayList<>(dialogs);
        ArrayList<TLRPC.Dialog> all = MessagesController.getInstance(currentAccount).getAllDialogs();
        final java.util.HashMap<Long, Integer> rank = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            TLRPC.Dialog d = all.get(i);
            if (d != null) rank.put(d.id, i);
        }
        java.util.Collections.sort(sorted, (a, b) -> {
            Integer ra = rank.get(a);
            Integer rb = rank.get(b);
            if (ra == null && rb == null) return Long.compare(a, b);
            if (ra == null) return 1;
            if (rb == null) return -1;
            return Integer.compare(ra, rb);
        });
        return sorted;
    }

    public MediaSessionCompat getSession() {
        return session;
    }

    public MediaSessionCompat.Token getSessionToken() {
        return session.getSessionToken();
    }

    public android.media.session.MediaSession.Token getFrameworkSessionToken() {
        return (android.media.session.MediaSession.Token) session.getSessionToken().getToken();
    }

    public void release() {
        if (session != null) {
            session.release();
        }
    }

    public Bundle buildRootHints() {
        Bundle rootExtras = new Bundle();
        rootExtras.putBoolean(CONTENT_STYLE_SUPPORTED, true);
        rootExtras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE);
        rootExtras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
        return rootExtras;
    }

    public boolean isPasscodeLocked() {
        final int uptime = (int) (SystemClock.elapsedRealtime() / 1000);
        return SharedConfig.passcodeHash.length() > 0 && (
                SharedConfig.appLocked
                        || SharedConfig.autoLockIn != 0 && SharedConfig.lastPauseTime != 0 && (SharedConfig.lastPauseTime + SharedConfig.autoLockIn) <= uptime
                        || uptime + 5 < SharedConfig.lastPauseTime
        );
    }

    public interface BrowseChildrenCallback {
        void onResult(List<MediaBrowser.MediaItem> items);
    }

    public boolean isChatsLoaded() {
        return chatsLoaded;
    }

    public ArrayList<Long> getAudioDialogs() {
        return dialogs;
    }

    public TLRPC.User getMusicUser(long userId) {
        return users.get(userId);
    }

    public TLRPC.Chat getMusicChat(long chatId) {
        return chats.get(chatId);
    }

    public ArrayList<MessageObject> getAudioMessages(long dialogId) {
        return audioObjects.get(dialogId);
    }

    public int getAudioMessageCount(long dialogId) {
        Integer count = audioCounts.get(dialogId);
        return count != null ? count : 0;
    }

    public void loadAudioMessages(long dialogId, Runnable onLoaded) {
        if (audioObjects.get(dialogId) != null) {
            if (onLoaded != null) {
                AndroidUtilities.runOnUIThread(onLoaded);
            }
            return;
        }
        loadAudioForDialog(dialogId, onLoaded);
    }

    public void refreshAudioCatalog(Runnable onLoaded) {
        refreshAudioData(0, onLoaded);
    }

    public void refreshAudioData(long dialogId, Runnable onLoaded) {
        AndroidUtilities.runOnUIThread(() -> {
            audioCatalogRevision++;
            chatsLoaded = false;
            dialogs.clear();
            users.clear();
            chats.clear();
            audioObjects.clear();
            audioQueues.clear();
            audioCounts.clear();
            if (dialogId != 0 && onLoaded != null) {
                pendingCatalogCallbacks.add(() -> loadAudioForDialog(dialogId, onLoaded));
            } else if (onLoaded != null) {
                pendingCatalogCallbacks.add(onLoaded);
            }
            loadChats();
        });
    }

    public static boolean hasPlayableAudio(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (MessageObject message : messages) {
            if (isCarPlayable(message)) {
                return true;
            }
        }
        return false;
    }

    private static final String PREF_SHOW_VIDEOS = "car_show_videos";

    public static boolean isShowVideosEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_SHOW_VIDEOS, false);
    }

    public void setShowVideosEnabled(boolean enabled, Runnable onLoaded) {
        if (enabled == isShowVideosEnabled()) {
            if (onLoaded != null) {
                AndroidUtilities.runOnUIThread(onLoaded);
            }
            return;
        }
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_SHOW_VIDEOS, enabled).apply();
        refreshAudioCatalog(onLoaded);
    }

    public static boolean isCarPlayable(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        if (messageObject.isMusic()) {
            return true;
        }
        if (messageObject.isVoice()) {
            return !messageObject.isVoiceOnce();
        }
        if (!isShowVideosEnabled()) {
            return false;
        }
        if (messageObject.isRoundVideo()) {
            return !messageObject.isRoundOnce() && !messageObject.needDrawBluredPreview();
        }
        if (messageObject.isVideo()) {
            return !messageObject.needDrawBluredPreview() && !messageObject.isSecretMedia();
        }
        return false;
    }

    public static boolean isCarVideo(MessageObject messageObject) {
        return messageObject != null && (messageObject.isRoundVideo() || messageObject.isVideo());
    }

    @Nullable
    public static String getVideoLabel(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        if (messageObject.isRoundVideo()) {
            return LocaleController.getString(R.string.AttachRound);
        }
        if (messageObject.isVideo()) {
            return LocaleController.getString(R.string.AttachVideo);
        }
        return null;
    }

    public static String getAudioTitle(MessageObject messageObject) {
        if (messageObject != null && !messageObject.isRoundVideo() && messageObject.isVideo()) {
            String text = messageObject.messageOwner != null ? messageObject.messageOwner.message : null;
            if (!TextUtils.isEmpty(text)) {
                text = text.trim();
                int newLine = text.indexOf('\n');
                if (newLine > 0) {
                    text = text.substring(0, newLine).trim();
                }
                if (text.length() > 80) {
                    text = text.substring(0, 80).trim() + "\u2026";
                }
                if (!text.isEmpty()) {
                    return text;
                }
            }
            return LocaleController.getString(R.string.AttachVideo) + ", "
                    + LocaleController.formatDateAudio(messageObject.messageOwner.date, true);
        }
        return messageObject != null ? messageObject.getMusicTitle() : null;
    }

    public Bitmap getRoundedAvatar(File path) {
        return createRoundBitmap(path);
    }

    public void ensureLoaded(Runnable onLoaded) {
        if (chatsLoaded) {
            if (onLoaded != null) AndroidUtilities.runOnUIThread(onLoaded);
            return;
        }
        loadBrowseChildren(MEDIA_ID_ROOT, items -> {
            if (onLoaded != null) onLoaded.run();
        });
    }

    private static final class PendingBrowseRequest {
        final String parentMediaId;
        final BrowseChildrenCallback callback;

        PendingBrowseRequest(String parentMediaId, BrowseChildrenCallback callback) {
            this.parentMediaId = parentMediaId;
            this.callback = callback;
        }
    }

    private final ArrayList<PendingBrowseRequest> pendingBrowseRequests = new ArrayList<>();

    public void loadBrowseChildren(String parentMediaId, BrowseChildrenCallback callback) {
        if (!chatsLoaded) {
            pendingBrowseRequests.add(new PendingBrowseRequest(parentMediaId, callback));
            loadChats();
            return;
        }

        long did = getDialogIdFromMediaId(parentMediaId);
        if (did != 0 && audioObjects.get(did) == null) {
            loadAudioForDialog(did, () -> callback.onResult(loadChildrenSync(parentMediaId)));
            return;
        }

        callback.onResult(loadChildrenSync(parentMediaId));
    }

    private void loadChats() {
        if (loadingChats) {
            return;
        }
        loadingChats = true;

        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
        messagesStorage.getStorageQueue().postRunnable(() -> {
            ArrayList<Long> loadedDialogs = new ArrayList<>();
            Set<Long> loadedDialogIds = new HashSet<>();
            LongSparseArray<Integer> loadedAudioCounts = new LongSparseArray<>();
            LongSparseArray<TLRPC.User> loadedUsers = new LongSparseArray<>();
            LongSparseArray<TLRPC.Chat> loadedChats = new LongSparseArray<>();
            try {
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT uid, COUNT(*) FROM media_v4 WHERE uid != 0 AND mid > 0 AND type = %d GROUP BY uid", MediaDataController.MEDIA_MUSIC));
                while (cursor.next()) {
                    long dialogId = cursor.longValue(0);
                    if (DialogObject.isEncryptedDialog(dialogId)) {
                        continue;
                    }
                    loadedDialogs.add(dialogId);
                    loadedDialogIds.add(dialogId);
                    loadedAudioCounts.put(dialogId, cursor.intValue(1));
                    if (DialogObject.isUserDialog(dialogId)) {
                        usersToLoad.add(dialogId);
                    } else {
                        chatsToLoad.add(-dialogId);
                    }
                }
                cursor.dispose();

                LongSparseArray<Integer> voiceCounts = new LongSparseArray<>();
                cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT data, mid, uid FROM media_v4 WHERE uid != 0 AND mid > 0 AND type IN (%d, %d)", MediaDataController.MEDIA_AUDIO, MediaDataController.MEDIA_PHOTOVIDEO));
                while (cursor.next()) {
                    long dialogId = cursor.longValue(2);
                    if (DialogObject.isEncryptedDialog(dialogId)) {
                        continue;
                    }
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message == null) {
                        data.reuse();
                        continue;
                    }
                    message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                    data.reuse();
                    if (message.media instanceof TLRPC.TL_messageMediaPhoto
                            || !MessageObject.isVoiceMessage(message)
                            && !MessageObject.isRoundVideoMessage(message)
                            && !MessageObject.isVideoMessage(message)) {
                        continue;
                    }

                    message.id = cursor.intValue(1);
                    message.dialog_id = dialogId;
                    MessageObject messageObject = new MessageObject(account, message, false, true);
                    if (!isCarPlayable(messageObject)) {
                        continue;
                    }
                    Integer voiceCount = voiceCounts.get(dialogId);
                    voiceCounts.put(dialogId, voiceCount == null ? 1 : voiceCount + 1);
                    if (loadedDialogIds.add(dialogId)) {
                        loadedDialogs.add(dialogId);
                        if (DialogObject.isUserDialog(dialogId)) {
                            usersToLoad.add(dialogId);
                        } else {
                            chatsToLoad.add(-dialogId);
                        }
                    }
                }
                cursor.dispose();
                for (int i = 0; i < voiceCounts.size(); i++) {
                    long dialogId = voiceCounts.keyAt(i);
                    Integer musicCount = loadedAudioCounts.get(dialogId);
                    loadedAudioCounts.put(dialogId, (musicCount != null ? musicCount : 0) + voiceCounts.valueAt(i));
                }

                if (!usersToLoad.isEmpty()) {
                    ArrayList<TLRPC.User> usersArrayList = new ArrayList<>();
                    messagesStorage.getUsersInternal(usersToLoad, usersArrayList);
                    for (TLRPC.User user : usersArrayList) {
                        loadedUsers.put(user.id, user);
                    }
                }
                if (!chatsToLoad.isEmpty()) {
                    ArrayList<TLRPC.Chat> chatsArrayList = new ArrayList<>();
                    messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), chatsArrayList);
                    for (TLRPC.Chat chat : chatsArrayList) {
                        loadedChats.put(chat.id, chat);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            AndroidUtilities.runOnUIThread(() -> {
                if (account != currentAccount) {
                    return;
                }
                if (revision != audioCatalogRevision) {
                    loadingChats = false;
                    loadChats();
                    return;
                }
                dialogs.clear();
                dialogs.addAll(loadedDialogs);
                audioCounts.clear();
                for (int i = 0; i < loadedAudioCounts.size(); i++) {
                    audioCounts.put(loadedAudioCounts.keyAt(i), loadedAudioCounts.valueAt(i));
                }
                users.clear();
                for (int i = 0; i < loadedUsers.size(); i++) {
                    users.put(loadedUsers.keyAt(i), loadedUsers.valueAt(i));
                }
                chats.clear();
                for (int i = 0; i < loadedChats.size(); i++) {
                    chats.put(loadedChats.keyAt(i), loadedChats.valueAt(i));
                }
                chatsLoaded = true;
                loadingChats = false;
                if (lastSelectedDialog == 0 && !dialogs.isEmpty()) {
                    lastSelectedDialog = dialogs.get(0);
                }

                ArrayList<PendingBrowseRequest> requests = new ArrayList<>(pendingBrowseRequests);
                pendingBrowseRequests.clear();
                for (int i = 0; i < requests.size(); i++) {
                    PendingBrowseRequest request = requests.get(i);
                    loadBrowseChildren(request.parentMediaId, request.callback);
                }
                ArrayList<Runnable> callbacks = new ArrayList<>(pendingCatalogCallbacks);
                pendingCatalogCallbacks.clear();
                for (Runnable callback : callbacks) {
                    callback.run();
                }
            });
        });
    }

    private void loadAudioForDialog(long did, Runnable onLoaded) {
        ArrayList<Runnable> callbacks = pendingAudioLoads.get(did);
        if (callbacks != null) {
            if (onLoaded != null) {
                callbacks.add(onLoaded);
            }
            return;
        }

        callbacks = new ArrayList<>();
        if (onLoaded != null) {
            callbacks.add(onLoaded);
        }
        pendingAudioLoads.put(did, callbacks);

        final int account = currentAccount;
        MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
        messagesStorage.getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> arrayList = new ArrayList<>();
            ArrayList<MediaSessionCompat.QueueItem> queueList = new ArrayList<>();
            try {
                SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US, "SELECT data, mid FROM media_v4 WHERE uid = %d AND mid > 0 AND type IN (%d, %d, %d) ORDER BY date DESC, mid DESC", did, MediaDataController.MEDIA_AUDIO, MediaDataController.MEDIA_MUSIC, MediaDataController.MEDIA_PHOTOVIDEO));
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message == null) {
                        data.reuse();
                        continue;
                    }
                    message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                    data.reuse();
                    if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                        continue;
                    }
                    message.id = cursor.intValue(1);
                    message.dialog_id = did;
                    MessageObject messageObject = new MessageObject(account, message, false, true);
                    if (!isCarPlayable(messageObject)) {
                        continue;
                    }
                    arrayList.add(messageObject);
                }
                cursor.dispose();
                Collections.reverse(arrayList);
                for (int i = 0; i < arrayList.size(); i++) {
                    MessageObject messageObject = arrayList.get(i);
                    MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                            .setMediaId(did + "_" + i)
                            .setTitle(getAudioTitle(messageObject))
                            .setSubtitle(messageObject.getMusicAuthor())
                            .build();
                    queueList.add(new MediaSessionCompat.QueueItem(description, i));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            AndroidUtilities.runOnUIThread(() -> {
                if (account != currentAccount) {
                    return;
                }
                audioObjects.put(did, arrayList);
                audioQueues.put(did, queueList);
                ArrayList<Runnable> loadedCallbacks = pendingAudioLoads.get(did);
                pendingAudioLoads.remove(did);
                if (did == lastSelectedDialog) {
                    applyQueueFor(did);
                }
                if (loadedCallbacks != null) {
                    for (int i = 0; i < loadedCallbacks.size(); i++) {
                        loadedCallbacks.get(i).run();
                    }
                }
            });
        });
    }

    private void playAudio(long dialogId, int index) {
        ArrayList<MessageObject> messages = audioObjects.get(dialogId);
        if (messages == null) {
            loadAudioForDialog(dialogId, () -> playAudio(dialogId, index));
            return;
        }
        if (index < 0 || index >= messages.size()) {
            return;
        }

        MessageObject selected = messages.get(index);
        MediaController controller = MediaController.getInstance();
        lastSelectedDialog = dialogId;
        MessagesController.getNotificationsSettings(currentAccount).edit()
                .putLong("auto_lastSelectedDialog", dialogId).apply();

        ArrayList<MessageObject> playlist = new ArrayList<>();
        for (MessageObject message : messages) {
            if (selected.isMusic() == message.isMusic()) {
                playlist.add(message);
            }
        }
        if (selected.isMusic()) {
            controller.setAudioOnlyVideos(null);
            controller.setPlaylist(playlist, selected, 0, false, null);
        } else if (selected.isVoice() || isCarVideo(selected)) {
            // Voice messages and videos share one queue; videos play as audio only.
            controller.setAudioOnlyVideos(playlist);
            controller.setVoiceMessagesPlaylist(playlist, false);
            controller.playMessage(selected);
        } else {
            return;
        }

        ArrayList<MediaSessionCompat.QueueItem> queue = audioQueues.get(dialogId);
        if (queue != null) {
            session.setQueue(queue);
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = users.get(dialogId);
            session.setQueueTitle(user != null
                    ? ContactsController.formatName(user.first_name, user.last_name)
                    : "DELETED USER");
        } else {
            TLRPC.Chat chat = chats.get(-dialogId);
            session.setQueueTitle(chat != null ? chat.title : "DELETED CHAT");
        }
    }

    private void skipToAdjacentAudio(int direction) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        ArrayList<MessageObject> messages = audioObjects.get(playing.getDialogId());
        int index = messages != null ? messages.indexOf(playing) : -1;
        if (index < 0) {
            if (direction > 0) {
                MediaController.getInstance().playNextMessage();
            } else {
                MediaController.getInstance().playPreviousMessage();
            }
            return;
        }
        int nextIndex = (index + direction + messages.size()) % messages.size();
        playAudio(playing.getDialogId(), nextIndex);
    }

    private long getDialogIdFromMediaId(String parentMediaId) {
        if (parentMediaId == null || !parentMediaId.startsWith(MEDIA_ID_CHAT_PREFIX)) {
            return 0;
        }
        try {
            return Long.parseLong(parentMediaId.substring(MEDIA_ID_CHAT_PREFIX.length()));
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private List<MediaBrowser.MediaItem> loadChildrenSync(String parentMediaId) {
        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();
        if (MEDIA_ID_ROOT.equals(parentMediaId)) {
            for (int a = 0; a < dialogs.size(); a++) {
                long dialogId = dialogs.get(a);
                android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                        .setMediaId(MEDIA_ID_CHAT_PREFIX + dialogId);
                TLRPC.FileLocation avatar = null;
                if (DialogObject.isUserDialog(dialogId)) {
                    TLRPC.User user = users.get(dialogId);
                    if (user != null) {
                        builder.setTitle(ContactsController.formatName(user.first_name, user.last_name));
                        if (user.photo != null && !(user.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                            avatar = user.photo.photo_small;
                        }
                    } else {
                        builder.setTitle("DELETED USER");
                    }
                } else {
                    TLRPC.Chat chat = chats.get(-dialogId);
                    if (chat != null) {
                        builder.setTitle(chat.title);
                        if (chat.photo != null && !(chat.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                            avatar = chat.photo.photo_small;
                        }
                    } else {
                        builder.setTitle("DELETED CHAT");
                    }
                }
                Bitmap bitmap = null;
                if (avatar != null) {
                    bitmap = createRoundBitmap(FileLoader.getInstance(currentAccount).getPathToAttach(avatar, true));
                    if (bitmap != null) {
                        builder.setIconBitmap(bitmap);
                    }
                }
                if (avatar == null || bitmap == null) {
                    builder.setIconUri(Uri.parse("android.resource://" + appContext.getPackageName() + "/drawable/contact_blue"));
                }
                mediaItems.add(new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
            }
        } else if (parentMediaId != null && parentMediaId.startsWith(MEDIA_ID_CHAT_PREFIX)) {
            long did = 0;
            try {
                did = Long.parseLong(parentMediaId.replace(MEDIA_ID_CHAT_PREFIX, ""));
            } catch (Exception e) {
                FileLog.e(e);
            }
            ArrayList<MessageObject> arrayList = audioObjects.get(did);
            if (arrayList != null) {
                for (int a = 0; a < arrayList.size(); a++) {
                    MessageObject messageObject = arrayList.get(a);
                    android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                            .setMediaId(did + "_" + a);
                    builder.setTitle(getAudioTitle(messageObject));
                    builder.setSubtitle(messageObject.getMusicAuthor());
                    String videoLabel = getVideoLabel(messageObject);
                    if (videoLabel != null) {
                        builder.setDescription(videoLabel);
                    }
                    Bitmap cover = getAudioCover(messageObject);
                    if (cover != null) {
                        builder.setIconBitmap(cover);
                    }
                    mediaItems.add(new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
                }
            }
        }
        return mediaItems;
    }

    private void applyQueueFor(long did) {
        if (did == 0) return;
        ArrayList<MessageObject> arrayList = audioObjects.get(did);
        ArrayList<MediaSessionCompat.QueueItem> queueList = audioQueues.get(did);
        if (arrayList == null || arrayList.isEmpty() || queueList == null) return;
        session.setQueue(queueList);
        if (DialogObject.isUserDialog(did)) {
            TLRPC.User user = users.get(did);
            session.setQueueTitle(user != null
                    ? ContactsController.formatName(user.first_name, user.last_name)
                    : "DELETED USER");
        } else {
            TLRPC.Chat chat = chats.get(-did);
            session.setQueueTitle(chat != null ? chat.title : "DELETED CHAT");
        }
        MessageObject messageObject = arrayList.get(0);
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long) (messageObject.getDuration() * 1000))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, messageObject.getMusicAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getAudioTitle(messageObject));
        session.setMetadata(mb.build());
    }

    private static final int COVER_SIZE_PX = 64;
    private final LruCache<Long, Bitmap> coverCache = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<Long> requestedCovers = new HashSet<>();

    /**
     * Returns the cover photo of an audio message, or null if it has none or it is not available yet.
     * Missing covers are downloaded; observe NotificationCenter.fileLoaded to refresh.
     */
    @Nullable
    public Bitmap getAudioCover(MessageObject messageObject) {
        TLRPC.Document document = messageObject != null ? messageObject.getDocument() : null;
        if (document == null || document.thumbs == null || document.thumbs.isEmpty()) {
            return null;
        }
        Bitmap cached = coverCache.get(document.id);
        if (cached != null) {
            return cached;
        }
        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320, false, null, true);
        if (thumb == null) {
            return null;
        }
        Bitmap bitmap = null;
        try {
            if (thumb.bytes != null && thumb.bytes.length > 0) {
                bitmap = decodeCover(null, thumb.bytes);
            } else {
                File path = FileLoader.getInstance(messageObject.currentAccount).getPathToAttach(thumb, true);
                if (path != null && path.exists()) {
                    bitmap = decodeCover(path, null);
                } else if (requestedCovers.add(document.id)) {
                    FileLoader.getInstance(messageObject.currentAccount).loadFile(
                            ImageLocation.getForDocument(thumb, document), messageObject, "jpg",
                            FileLoader.PRIORITY_NORMAL, 1);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (bitmap != null) {
            if (isCarVideo(messageObject)) {
                bitmap = addVideoBadge(bitmap);
            }
            coverCache.put(document.id, bitmap);
        }
        return bitmap;
    }

    private static Bitmap addVideoBadge(Bitmap source) {
        Bitmap result = source.isMutable() ? source : source.copy(Bitmap.Config.ARGB_8888, true);
        if (result == null) {
            return source;
        }
        Canvas canvas = new Canvas(result);
        float size = Math.min(result.getWidth(), result.getHeight());
        float radius = size * 0.2f;
        float cx = result.getWidth() - radius - size * 0.04f;
        float cy = result.getHeight() - radius - size * 0.04f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xC0000000);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.WHITE);
        float triangle = radius * 0.5f;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx - triangle * 0.6f, cy - triangle);
        path.lineTo(cx + triangle, cy);
        path.lineTo(cx - triangle * 0.6f, cy + triangle);
        path.close();
        canvas.drawPath(path, paint);
        if (result != source) {
            source.recycle();
        }
        return result;
    }

    private static Bitmap decodeCover(@Nullable File file, @Nullable byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (file != null) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= COVER_SIZE_PX && bounds.outHeight / (sample * 2) >= COVER_SIZE_PX) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = file != null
                ? BitmapFactory.decodeFile(file.getAbsolutePath(), options)
                : BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }
        // Keep covers small: the whole list is sent to the car host in one binder transaction.
        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        Bitmap square = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2,
                (decoded.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, COVER_SIZE_PX, COVER_SIZE_PX, true);
        if (square != decoded) {
            decoded.recycle();
        }
        if (scaled != square) {
            square.recycle();
        }
        return scaled;
    }

    public void publishMetadata(MessageObject messageObject, @Nullable AudioInfo audioInfo, @Nullable Bitmap albumArt) {
        if (messageObject == null) return;
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, messageObject.getMusicAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, messageObject.getMusicAuthor())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long) (messageObject.getDuration() * 1000))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getAudioTitle(messageObject))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
                        audioInfo != null && messageObject.isMusic() ? audioInfo.getAlbum() : null);
        if (albumArt != null && !albumArt.isRecycled()) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
        }
        session.setMetadata(meta.build());
    }

    public void publishPlaybackState(PlaybackStateCompat state) {
        session.setPlaybackState(state);
    }

    public long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_PREPARE
                | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null) {
            if (!MediaController.getInstance().isMessagePaused()) {
                actions |= PlaybackStateCompat.ACTION_PAUSE;
            }
            if (playing.isMusic() || playing.isVoice() || isCarVideo(playing)) {
                actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
            }
        }
        return actions;
    }

    public void updateRepeatMode() {
        int sessionRepeatMode;
        switch (SharedConfig.repeatMode) {
            case 1:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_ALL;
                break;
            case 2:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_ONE;
                break;
            default:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
                break;
        }
        session.setRepeatMode(sessionRepeatMode);
    }

    public void updateShuffleMode() {
        session.setShuffleMode(SharedConfig.shuffleMusic
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE);
    }

    private Bitmap createRoundBitmap(File path) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(path.toString(), options);
            if (bitmap != null) {
                Bitmap result = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                result.eraseColor(Color.TRANSPARENT);
                Canvas canvas = new Canvas(result);
                BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (roundPaint == null) {
                    roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bitmapRect = new RectF();
                }
                roundPaint.setShader(shader);
                bitmapRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                canvas.drawRoundRect(bitmapRect, bitmap.getWidth(), bitmap.getHeight(), roundPaint);
                return result;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private final class SessionCallback extends MediaSessionCompat.Callback {

        @Override
        public void onPlay() {
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject == null) {
                if (lastSelectedDialog != 0) {
                    playAudio(lastSelectedDialog, 0);
                }
            } else {
                MediaController.getInstance().playMessage(messageObject);
            }
        }

        @Override
        public void onPause() {
            MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
        }

        @Override
        public void onSkipToNext() {
            skipToAdjacentAudio(1);
        }

        @Override
        public void onSkipToPrevious() {
            skipToAdjacentAudio(-1);
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            playAudio(lastSelectedDialog, (int) queueId);
        }

        @Override
        public void onSeekTo(long pos) {
            MessageObject object = MediaController.getInstance().getPlayingMessageObject();
            if (object != null) {
                MediaController.getInstance().seekToProgress(object, (float) (pos / 1000.0 / object.getDuration()));
            }
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            int newMode;
            switch (repeatMode) {
                case PlaybackStateCompat.REPEAT_MODE_ONE:
                    newMode = 2;
                    break;
                case PlaybackStateCompat.REPEAT_MODE_ALL:
                case PlaybackStateCompat.REPEAT_MODE_GROUP:
                    newMode = 1;
                    break;
                default:
                    newMode = 0;
                    break;
            }
            SharedConfig.setRepeatMode(newMode);
            updateRepeatMode();
            notifyPlayStateForNotificationRefresh();
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            boolean shuffle = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    || shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP;
            if (shuffle != SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(shuffle ? 2 : 0);
            }
            updateShuffleMode();
            notifyPlayStateForNotificationRefresh();
        }

        private void notifyPlayStateForNotificationRefresh() {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, 0));
        }

        @Override
        public void onPrepare() {
            // No-op: nothing to prepare without a target. Hosts call prepareFromX with args.
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            onPlayFromMediaId(mediaId, extras);
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            onPlayFromSearch(query, extras);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            if (TextUtils.isEmpty(mediaId)) return;
            String[] args = mediaId.split("_");
            if (args.length != 2) return;
            try {
                long did = Long.parseLong(args[0]);
                int id = Integer.parseInt(args[1]);
                playAudio(did, id);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            if (query == null || query.length() == 0) return;
            String q = query.toLowerCase();
            for (int a = 0; a < dialogs.size(); a++) {
                long did = dialogs.get(a);
                if (DialogObject.isUserDialog(did)) {
                    TLRPC.User user = users.get(did);
                    if (user == null) continue;
                    String first = user.first_name != null ? user.first_name.toLowerCase() : null;
                    String last = user.last_name != null ? user.last_name.toLowerCase() : null;
                    if (first != null && first.contains(q) || last != null && last.contains(q)) {
                        onPlayFromMediaId(did + "_" + 0, null);
                        return;
                    }
                } else {
                    TLRPC.Chat chat = chats.get(-did);
                    if (chat == null) continue;
                    if (chat.title != null && chat.title.toLowerCase().contains(q)) {
                        onPlayFromMediaId(did + "_" + 0, null);
                        return;
                    }
                }
            }
        }

        @Override
        public void onStop() {
            // session stays alive; let MusicPlayerService handle notification + service teardown
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (MusicPlayerService.NOTIFY_REPEAT.equals(action)) {
                SharedConfig.setRepeatMode((SharedConfig.repeatMode + 1) % 3);
                updateRepeatMode();
            } else if (MusicPlayerService.NOTIFY_SHUFFLE.equals(action)) {
                MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
                updateShuffleMode();
            }
        }
    }
}
