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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
            current.session.setMetadata(current.withCoverArt(metadata));
        }
    }

    /** Adds the Telegram cover thumbnail when the audio file has no embedded album art. */
    private MediaMetadataCompat withCoverArt(MediaMetadataCompat metadata) {
        if (metadata == null || metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) != null) {
            return metadata;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        Bitmap cover = playing != null ? getNowPlayingCover(playing) : null;
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder(metadata);
        if (cover == null) {
            boolean video = playing != null && isCarVideo(playing);
            cover = getFallbackCover(video);
            String uri = getFallbackCoverUri(video).toString();
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri);
        }
        if (cover != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        }
        return builder.build();
    }

    private static final int FALLBACK_COVER_SIZE_PX = 320;
    private final Bitmap[] fallbackCovers = new Bitmap[2];

    /** High resolution placeholder art for audio without a cover photo. */
    private Uri getFallbackCoverUri(boolean video) {
        return Uri.parse("android.resource://" + appContext.getPackageName() + "/drawable/"
                + (video ? "car_video_cover" : "car_audio_cover"));
    }

    @Nullable
    private Bitmap getFallbackCover(boolean video) {
        int index = video ? 1 : 0;
        if (fallbackCovers[index] == null) {
            try {
                Bitmap decoded = BitmapFactory.decodeResource(appContext.getResources(),
                        video ? R.drawable.car_video_cover : R.drawable.car_audio_cover);
                if (decoded != null) {
                    fallbackCovers[index] = cropAndScale(decoded, FALLBACK_COVER_SIZE_PX, true);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return fallbackCovers[index];
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

    private static final String SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";
    // Android Auto shows browsable root children as tabs: one for audio chats, one for video chats.
    private static final String MEDIA_ID_LIBRARY = "__LIBRARY__";
    private static final String MEDIA_ID_VIDEO_LIBRARY = "__VIDEOS__";
    private static final String MEDIA_ID_VIDEO_CHAT_PREFIX = "__VCHAT_";
    private static final String MEDIA_ID_MESSAGE_PREFIX = "msg_";
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
    private final ArrayList<Long> videoDialogs = new ArrayList<>();
    private final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
    private final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<MessageObject>> audioObjects = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<MediaSessionCompat.QueueItem>> audioQueues = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Runnable>> pendingAudioLoads = new LongSparseArray<>();
    private final LongSparseArray<Integer> audioCounts = new LongSparseArray<>();
    private final LongSparseArray<Integer> dialogOrder = new LongSparseArray<>();
    private static final int CLOUD_AUDIO_PAGE_SIZE = 100;
    private final LongSparseArray<CloudAudioLoad> cloudAudioLoads = new LongSparseArray<>();
    private final Set<Integer> cloudRequestIds = new HashSet<>();

    private static final class CloudAudioLoad {
        int filterIndex;
        int offsetId;
        boolean loading;
    }

    private void cancelCloudAudioRequests() {
        for (int requestId : cloudRequestIds) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
        }
        cloudRequestIds.clear();
        cloudAudioLoads.clear();
    }

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
        cancelCloudAudioRequests();
        currentAccount = UserConfig.selectedAccount;
        updateMessageObservers();
        audioCatalogRevision++;
        lastSelectedDialog = AndroidUtilities.getPrefIntOrLong(
                MessagesController.getNotificationsSettings(currentAccount), "auto_lastSelectedDialog", 0);
        chatsLoaded = false;
        loadingChats = false;
        pendingCatalogCallbacks.clear();
        dialogs.clear();
        videoDialogs.clear();
        users.clear();
        chats.clear();
        audioObjects.clear();
        audioQueues.clear();
        pendingAudioLoads.clear();
        audioCounts.clear();
        dialogOrder.clear();
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

    // Kept for the unregistered upstream Car App screens.
    public ArrayList<Long> getMusicDialogsSortedByVisibleOrder() {
        return getAudioDialogsSortedByVisibleOrder();
    }

    public ArrayList<MessageObject> getMusicMessages(long dialogId) {
        return getAudioMessages(dialogId);
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
        cancelCloudAudioRequests();
        if (session != null) {
            session.release();
        }
    }

    public Bundle buildRootHints() {
        Bundle rootExtras = new Bundle();
        rootExtras.putBoolean(SEARCH_SUPPORTED, true);
        rootExtras.putBoolean(CONTENT_STYLE_SUPPORTED, true);
        rootExtras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
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

    /** Chat name as shown in Telegram's chat list, including Saved Messages and Replies. */
    @NonNull
    public String getDialogTitle(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                return LocaleController.getString(R.string.SavedMessages);
            }
            if (UserObject.isReplyUser(dialogId)) {
                return LocaleController.getString(R.string.RepliesTitle);
            }
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user == null) {
                user = users.get(dialogId);
            }
            if (UserObject.isUserSelf(user)) {
                return LocaleController.getString(R.string.SavedMessages);
            }
            return UserObject.getUserName(user);
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        if (chat == null) {
            chat = chats.get(-dialogId);
        }
        return chat != null && !TextUtils.isEmpty(chat.title) ? chat.title : LocaleController.getString(R.string.HiddenName);
    }

    /** Lightweight search entry so global search does not keep every MessageObject in memory. */
    public static final class AudioSearchResult {
        public final long dialogId;
        public final int messageId;
        public final String title;
        public final String author;
        public final int durationSeconds;
        @Nullable
        public final String videoLabel;
        final String searchText;

        AudioSearchResult(long dialogId, int messageId, String title, String author, @Nullable String videoLabel, int durationSeconds) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.title = title != null ? title : "";
            this.author = author != null ? author : "";
            this.videoLabel = videoLabel;
            this.durationSeconds = durationSeconds;
            this.searchText = (this.title + "\n" + this.author).toLowerCase(Locale.ROOT);
        }
    }

    public interface AudioSearchCallback {
        void onResults(ArrayList<AudioSearchResult> results);
    }

    private static final int MAX_SEARCH_RESULTS = 100;
    @Nullable
    private ArrayList<AudioSearchResult> searchIndex;
    private int searchIndexRevision = -1;
    private boolean buildingSearchIndex;
    private final ArrayList<Runnable> pendingSearchIndexCallbacks = new ArrayList<>();

    public static boolean matchesQuery(@Nullable String text, @NonNull String normalizedQuery) {
        return normalizedQuery.isEmpty() || text != null && text.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    public static String normalizeQuery(@Nullable String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /** Searches audio in one chat when dialogId is not 0, otherwise across all chats. */
    public void searchAudio(long dialogId, @Nullable String query, AudioSearchCallback callback) {
        String normalized = normalizeQuery(query);
        if (dialogId != 0) {
            loadAudioMessages(dialogId, () -> {
                ArrayList<AudioSearchResult> results = new ArrayList<>();
                ArrayList<MessageObject> messages = audioObjects.get(dialogId);
                if (messages != null && !normalized.isEmpty()) {
                    for (MessageObject message : messages) {
                        AudioSearchResult result = toSearchResult(message);
                        if (result.searchText.contains(normalized)) {
                            results.add(result);
                            if (results.size() >= MAX_SEARCH_RESULTS) {
                                break;
                            }
                        }
                    }
                }
                callback.onResults(results);
            });
            return;
        }
        ensureSearchIndex(() -> {
            ArrayList<AudioSearchResult> results = new ArrayList<>();
            if (searchIndex != null && !normalized.isEmpty()) {
                for (AudioSearchResult entry : searchIndex) {
                    if (entry.searchText.contains(normalized)) {
                        results.add(entry);
                        if (results.size() >= MAX_SEARCH_RESULTS) {
                            break;
                        }
                    }
                }
            }
            callback.onResults(results);
        });
    }

    private static AudioSearchResult toSearchResult(MessageObject message) {
        return new AudioSearchResult(message.getDialogId(), message.getId(), getAudioTitle(message),
                message.getMusicAuthor(), getVideoLabel(message), (int) message.getDuration());
    }

    private void ensureSearchIndex(Runnable onReady) {
        if (searchIndex != null && searchIndexRevision == audioCatalogRevision) {
            AndroidUtilities.runOnUIThread(onReady);
            return;
        }
        pendingSearchIndexCallbacks.add(onReady);
        if (buildingSearchIndex) {
            return;
        }
        buildingSearchIndex = true;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
        messagesStorage.getStorageQueue().postRunnable(() -> {
            ArrayList<AudioSearchResult> entries = new ArrayList<>();
            try {
                String types = String.format(Locale.US, "%d, %d, %d", MediaDataController.MEDIA_AUDIO, MediaDataController.MEDIA_MUSIC, MediaDataController.MEDIA_PHOTOVIDEO);
                SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized("SELECT data, mid, uid FROM media_v4 WHERE uid != 0 AND mid > 0 AND type IN (" + types + ") ORDER BY date DESC, mid DESC");
                while (cursor.next()) {
                    long did = cursor.longValue(2);
                    if (DialogObject.isEncryptedDialog(did)) {
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
                    if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                        continue;
                    }
                    message.id = cursor.intValue(1);
                    message.dialog_id = did;
                    MessageObject messageObject = new MessageObject(account, message, false, false);
                    if (isCarPlayable(messageObject)) {
                        entries.add(toSearchResult(messageObject));
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                buildingSearchIndex = false;
                if (account == currentAccount) {
                    Set<String> indexed = new HashSet<>();
                    for (AudioSearchResult entry : entries) {
                        indexed.add(entry.dialogId + "_" + entry.messageId);
                    }
                    for (int i = 0; i < audioObjects.size(); i++) {
                        for (MessageObject message : audioObjects.valueAt(i)) {
                            if (indexed.add(message.getDialogId() + "_" + message.getId())) {
                                entries.add(toSearchResult(message));
                            }
                        }
                    }
                    searchIndex = entries;
                    searchIndexRevision = revision;
                }
                ArrayList<Runnable> callbacks = new ArrayList<>(pendingSearchIndexCallbacks);
                pendingSearchIndexCallbacks.clear();
                for (Runnable callback : callbacks) {
                    callback.run();
                }
            });
        });
    }

    /** Plays a message by id, loading its chat audio first when needed. */
    public void playAudioMessage(long dialogId, int messageId) {
        loadAudioMessages(dialogId, () -> {
            ArrayList<MessageObject> messages = audioObjects.get(dialogId);
            if (messages == null) {
                return;
            }
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getId() == messageId) {
                    playAudio(dialogId, i);
                    return;
                }
            }
        });
    }

    public void togglePlayPause() {
        MediaController controller = MediaController.getInstance();
        MessageObject playing = controller.getPlayingMessageObject();
        if (playing == null) {
            session.getController().getTransportControls().play();
        } else if (controller.isMessagePaused()) {
            controller.playMessage(playing);
        } else {
            controller.pauseMessage(playing);
        }
    }

    public void skipToNext() {
        skipToAdjacentAudio(1);
    }

    public void skipToPrevious() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && playing.audioProgressSec > 3) {
            MediaController.getInstance().seekToProgress(playing, 0);
            return;
        }
        skipToAdjacentAudio(-1);
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
            loadCloudAudio(dialogId);
            return;
        }
        loadAudioForDialog(dialogId, onLoaded);
    }

    public void refreshAudioCatalog(Runnable onLoaded) {
        refreshAudioData(0, onLoaded);
    }

    public void refreshAudioData(long dialogId, Runnable onLoaded) {
        AndroidUtilities.runOnUIThread(() -> {
            cancelCloudAudioRequests();
            audioCatalogRevision++;
            chatsLoaded = false;
            dialogs.clear();
            videoDialogs.clear();
            users.clear();
            chats.clear();
            audioObjects.clear();
            audioQueues.clear();
            audioCounts.clear();
            for (int i = 0; i < pendingAudioLoads.size(); i++) {
                long pendingDialog = pendingAudioLoads.keyAt(i);
                ArrayList<Runnable> callbacks = pendingAudioLoads.valueAt(i);
                pendingCatalogCallbacks.add(() -> loadAudioForDialog(pendingDialog, () -> {
                    for (Runnable callback : callbacks) {
                        callback.run();
                    }
                }));
            }
            pendingAudioLoads.clear();
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

    public interface BrowseTreeListener {
        void onChildrenChanged(String parentMediaId);
    }

    @Nullable
    private static BrowseTreeListener browseTreeListener;
    private int observedAccount = -1;
    private final NotificationCenter.NotificationCenterDelegate messageObserver = (id, account, args) -> {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 1 && args[0] instanceof Long && args[1] instanceof ArrayList
                    && (args.length <= 2 || !Boolean.TRUE.equals(args[2]))) {
                @SuppressWarnings("unchecked")
                ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
                if (hasPlayableAudio(messages)) {
                    long dialogId = (Long) args[0];
                    refreshAudioData(dialogId, () -> {
                        notifyBrowseTree(MEDIA_ID_CHAT_PREFIX + dialogId);
                        notifyBrowseTree(MEDIA_ID_VIDEO_CHAT_PREFIX + dialogId);
                    });
                }
            }
        } else {
            refreshAudioCatalog(null);
        }
    };

    /** Registers the connected media browser so the audio tree refreshes as messages change. */
    public void setBrowseTreeListener(@Nullable BrowseTreeListener listener) {
        browseTreeListener = listener;
        updateMessageObservers();
    }

    private void updateMessageObservers() {
        int target = browseTreeListener != null ? currentAccount : -1;
        if (target == observedAccount) {
            return;
        }
        int[] events = {NotificationCenter.didReceiveNewMessages, NotificationCenter.messagesDeleted, NotificationCenter.historyCleared};
        if (observedAccount >= 0) {
            for (int event : events) {
                NotificationCenter.getInstance(observedAccount).removeObserver(messageObserver, event);
            }
        }
        observedAccount = target;
        if (target >= 0) {
            for (int event : events) {
                NotificationCenter.getInstance(target).addObserver(messageObserver, event);
            }
        }
    }

    private static void notifyBrowseTree(String parentMediaId) {
        BrowseTreeListener listener = browseTreeListener;
        if (listener != null) {
            listener.onChildrenChanged(parentMediaId);
        }
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
        if (messageObject.isRoundVideo()) {
            return !messageObject.isRoundOnce() && !messageObject.needDrawBluredPreview();
        }
        if (messageObject.isVideo()) {
            return !messageObject.needDrawBluredPreview() && !messageObject.isSecretMedia();
        }
        return false;
    }

    private static int getCarKind(MessageObject messageObject) {
        return messageObject.isMusic() ? 0 : isCarVideo(messageObject) ? 2 : 1;
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
        if (did != 0) {
            loadCloudAudio(did);
        }
    }

    /** Orders chats like Telegram's chat list: pinned chats first, then by last message date. */
    private static void sortByChatListOrder(MessagesStorage messagesStorage, ArrayList<Long> dialogIds) {
        if (dialogIds.size() < 2) {
            return;
        }
        HashMap<Long, Integer> order = new HashMap<>();
        try {
            SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT did FROM dialogs WHERE did IN (%s) ORDER BY pinned DESC, date DESC", TextUtils.join(",", dialogIds)));
            while (cursor.next()) {
                order.put(cursor.longValue(0), order.size());
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        Collections.sort(dialogIds, (a, b) -> {
            Integer oa = order.get(a);
            Integer ob = order.get(b);
            return Integer.compare(oa != null ? oa : Integer.MAX_VALUE, ob != null ? ob : Integer.MAX_VALUE);
        });
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
            ArrayList<Long> loadedVideoDialogs = new ArrayList<>();
            Set<Long> loadedVideoDialogIds = new HashSet<>();
            Set<Long> loadedDialogIds = new HashSet<>();
            LongSparseArray<Integer> loadedAudioCounts = new LongSparseArray<>();
            LongSparseArray<TLRPC.User> loadedUsers = new LongSparseArray<>();
            LongSparseArray<TLRPC.Chat> loadedChats = new LongSparseArray<>();
            LongSparseArray<Integer> loadedDialogOrder = new LongSparseArray<>();
            ArrayList<Long> channelDialogs = new ArrayList<>();
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
                    if (isCarVideo(messageObject)) {
                        if (loadedVideoDialogIds.add(dialogId)) {
                            loadedVideoDialogs.add(dialogId);
                            if (!loadedDialogIds.contains(dialogId)) {
                                if (DialogObject.isUserDialog(dialogId)) {
                                    usersToLoad.add(dialogId);
                                } else {
                                    chatsToLoad.add(-dialogId);
                                }
                            }
                        }
                        continue;
                    }
                    Integer voiceCount = voiceCounts.get(dialogId);
                    voiceCounts.put(dialogId, voiceCount == null ? 1 : voiceCount + 1);
                    if (loadedDialogIds.add(dialogId)) {
                        loadedDialogs.add(dialogId);
                        if (loadedVideoDialogIds.contains(dialogId)) {
                            // Already queued for loading by a video in the same chat.
                        } else if (DialogObject.isUserDialog(dialogId)) {
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

                sortByChatListOrder(messagesStorage, loadedDialogs);
                sortByChatListOrder(messagesStorage, loadedVideoDialogs);

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
                cursor = messagesStorage.getDatabase().queryFinalized("SELECT did FROM dialogs ORDER BY pinned DESC, date DESC");
                ArrayList<Long> channelIds = new ArrayList<>();
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    loadedDialogOrder.put(did, loadedDialogOrder.size());
                    if (DialogObject.isChatDialog(did)) {
                        channelIds.add(-did);
                    }
                }
                cursor.dispose();
                if (!channelIds.isEmpty()) {
                    ArrayList<TLRPC.Chat> channelChats = new ArrayList<>();
                    messagesStorage.getChatsInternal(TextUtils.join(",", channelIds), channelChats);
                    for (TLRPC.Chat chat : channelChats) {
                        if (ChatObject.isChannel(chat) && !ChatObject.isNotInChat(chat)) {
                            loadedChats.put(chat.id, chat);
                            channelDialogs.add(-chat.id);
                        }
                    }
                    sortByChatListOrder(messagesStorage, channelDialogs);
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
                videoDialogs.clear();
                videoDialogs.addAll(loadedVideoDialogs);
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
                dialogOrder.clear();
                for (int i = 0; i < loadedDialogOrder.size(); i++) {
                    dialogOrder.put(loadedDialogOrder.keyAt(i), loadedDialogOrder.valueAt(i));
                }
                ArrayList<TLRPC.User> cachedUsers = new ArrayList<>();
                for (int i = 0; i < loadedUsers.size(); i++) {
                    cachedUsers.add(loadedUsers.valueAt(i));
                }
                MessagesController.getInstance(account).putUsers(cachedUsers, true);
                MessagesController.getInstance(account).putChats(toChatList(loadedChats), true);
                discoverChannelAudio(channelDialogs, 0, account, revision);
                notifyBrowseTree(MEDIA_ID_LIBRARY);
                notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
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
        final int revision = audioCatalogRevision;
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
                queueList.addAll(createAudioQueue(did, arrayList));
            } catch (Exception e) {
                FileLog.e(e);
            }

            AndroidUtilities.runOnUIThread(() -> {
                if (account != currentAccount || revision != audioCatalogRevision) {
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
                loadCloudAudio(did);
            });
        });
    }

    private static ArrayList<TLRPC.Chat> toChatList(LongSparseArray<TLRPC.Chat> source) {
        ArrayList<TLRPC.Chat> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            result.add(source.valueAt(i));
        }
        return result;
    }

    /** Discover channels with audio even when no shared-media history is cached on the phone. */
    private void discoverChannelAudio(ArrayList<Long> channelDialogs, int index, int account, int revision) {
        if (account != currentAccount || revision != audioCatalogRevision || index >= channelDialogs.size()) {
            return;
        }
        long did = channelDialogs.get(index);
        TLRPC.TL_messages_getSearchCounters request = new TLRPC.TL_messages_getSearchCounters();
        request.peer = MessagesController.getInstance(account).getInputPeer(did);
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) {
            FileLog.e("Android Auto: missing channel peer " + did);
            discoverChannelAudio(channelDialogs, index + 1, account, revision);
            return;
        }
        request.filters.add(new TLRPC.TL_inputMessagesFilterMusic());
        request.filters.add(new TLRPC.TL_inputMessagesFilterVoice());
        final int[] requestId = new int[1];
        requestId[0] = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (account != currentAccount || revision != audioCatalogRevision) {
                        return;
                    }
                    cloudRequestIds.remove(requestId[0]);
                    if (error != null || !(response instanceof Vector)) {
                        FileLog.e("Android Auto: channel audio discovery failed for " + did + ": "
                                + (error != null ? error.text : "invalid response"));
                    } else {
                        int count = 0;
                        for (Object object : ((Vector) response).objects) {
                            if (object instanceof TLRPC.TL_messages_searchCounter) {
                                count += ((TLRPC.TL_messages_searchCounter) object).count;
                            }
                        }
                        audioCounts.put(did, count);
                        if (count > 0 && !dialogs.contains(did)) {
                            dialogs.add(did);
                            sortAudioDialogs();
                            if (lastSelectedDialog == 0) {
                                lastSelectedDialog = did;
                            }
                            notifyBrowseTree(MEDIA_ID_LIBRARY);
                        }
                    }
                    discoverChannelAudio(channelDialogs, index + 1, account, revision);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    private void sortAudioDialogs() {
        Collections.sort(dialogs, (a, b) -> {
            Integer oa = dialogOrder.get(a);
            Integer ob = dialogOrder.get(b);
            return Integer.compare(oa != null ? oa : Integer.MAX_VALUE, ob != null ? ob : Integer.MAX_VALUE);
        });
    }

    /** Page message metadata, not media files. Cached rows remain usable while pages arrive. */
    private void loadCloudAudio(long did) {
        if (DialogObject.isEncryptedDialog(did) || audioObjects.get(did) == null) {
            return;
        }
        CloudAudioLoad load = cloudAudioLoads.get(did);
        if (load == null) {
            load = new CloudAudioLoad();
            cloudAudioLoads.put(did, load);
        }
        if (load.loading || load.filterIndex >= 2) {
            return;
        }
        final CloudAudioLoad state = load;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = MessagesController.getInstance(account).getInputPeer(did);
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) {
            FileLog.e("Android Auto: missing audio peer " + did);
            return;
        }
        request.q = "";
        request.filter = state.filterIndex == 0 ? new TLRPC.TL_inputMessagesFilterMusic()
                : new TLRPC.TL_inputMessagesFilterRoundVoice();
        request.limit = CLOUD_AUDIO_PAGE_SIZE;
        request.offset_id = state.offsetId;
        state.loading = true;
        final int[] requestId = new int[1];
        requestId[0] = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (account != currentAccount || revision != audioCatalogRevision) {
                        return;
                    }
                    cloudRequestIds.remove(requestId[0]);
                    state.loading = false;
                    if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                        FileLog.e("Android Auto: audio page failed for " + did + ": "
                                + (error != null ? error.text : "invalid response"));
                        return;
                    }
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    MessagesController controller = MessagesController.getInstance(account);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    MessagesStorage.getInstance(account).putUsersAndChats(result.users, result.chats, true, true);
                    ArrayList<MessageObject> messages = audioObjects.get(did);
                    Set<Integer> knownIds = new HashSet<>();
                    for (MessageObject message : messages) {
                        knownIds.add(message.getId());
                    }
                    int nextOffset = state.offsetId == 0 ? Integer.MAX_VALUE : state.offsetId;
                    boolean serverPageEmpty = result.messages.isEmpty();
                    for (TLRPC.Message message : result.messages) {
                        if (message.id > 0) {
                            nextOffset = Math.min(nextOffset, message.id);
                        }
                    }
                    controller.removeDeletedMessagesFromArray(did, result.messages);
                    if (serverPageEmpty || !result.messages.isEmpty()) {
                        MediaDataController.getInstance(account).putMediaDatabase(did, 0,
                                state.filterIndex == 0 ? MediaDataController.MEDIA_MUSIC : MediaDataController.MEDIA_AUDIO,
                                result.messages, state.offsetId, 0, serverPageEmpty);
                    }
                    boolean changed = false;
                    for (TLRPC.Message message : result.messages) {
                        message.dialog_id = did;
                        MessageObject object = new MessageObject(account, message, false, true);
                        if (isCarPlayable(object) && knownIds.add(message.id)) {
                            messages.add(object);
                            changed = true;
                        }
                    }
                    if (changed) {
                        audioQueues.put(did, createAudioQueue(did, messages));
                        if (lastSelectedDialog == did) {
                            session.setQueue(audioQueues.get(did));
                        }
                        searchIndex = null;
                        searchIndexRevision = -1;
                        notifyBrowseTree(MEDIA_ID_CHAT_PREFIX + did);
                        notifyBrowseTree(MEDIA_ID_VIDEO_CHAT_PREFIX + did);
                    }
                    // A short page need not be the last one; continue until the server is exhausted.
                    if (serverPageEmpty || nextOffset == Integer.MAX_VALUE
                            || state.offsetId != 0 && nextOffset >= state.offsetId) {
                        state.filterIndex++;
                        state.offsetId = 0;
                    } else {
                        state.offsetId = nextOffset;
                    }
                    loadCloudAudio(did);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    private static ArrayList<MediaSessionCompat.QueueItem> createAudioQueue(long did, ArrayList<MessageObject> messages) {
        // Alphabetical order lets Android Auto group items by letter and show its A-Z jump.
        final java.text.Collator collator = java.text.Collator.getInstance();
        collator.setStrength(java.text.Collator.PRIMARY);
        Collections.sort(messages, (a, b) -> {
            String ta = getAudioTitle(a);
            String tb = getAudioTitle(b);
            return collator.compare(ta != null ? ta.trim() : "", tb != null ? tb.trim() : "");
        });
        ArrayList<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
        for (MessageObject message : messages) {
            MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                    .setMediaId(MEDIA_ID_MESSAGE_PREFIX + did + "_" + message.getId())
                    .setTitle(getAudioTitle(message))
                    .setSubtitle(withDuration(message.getMusicAuthor(), (int) message.getDuration()))
                    .build();
            queue.add(new MediaSessionCompat.QueueItem(description, message.getId()));
        }
        return queue;
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
        if (!isCarVideo(selected) && !FileLoader.getInstance(currentAccount).getPathToMessage(selected.messageOwner).exists()
                && (TextUtils.isEmpty(selected.messageOwner.attachPath)
                || !new File(selected.messageOwner.attachPath).exists())) {
            FileLoader.getInstance(currentAccount).loadFile(selected.getDocument(), selected,
                    FileLoader.PRIORITY_HIGH, selected.shouldEncryptPhotoOrVideo() ? 2 : 0);
        }
        MediaController controller = MediaController.getInstance();
        lastSelectedDialog = dialogId;
        MessagesController.getNotificationsSettings(currentAccount).edit()
                .putLong("auto_lastSelectedDialog", dialogId).apply();

        ArrayList<MessageObject> playlist = new ArrayList<>();
        for (MessageObject message : messages) {
            if (getCarKind(selected) == getCarKind(message)) {
                playlist.add(message);
            }
        }
        if (selected.isMusic()) {
            controller.setAudioOnlyVideos(null);
            controller.setPlaylist(playlist, selected, 0, false, null);
            controller.setForceLoopCurrentPlaylist(false);
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
        session.setQueueTitle(getDialogTitle(dialogId));
    }

    private void skipToAdjacentAudio(int direction) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        MediaController controller = MediaController.getInstance();
        if (controller.isInCarVoiceQueue(playing)) {
            MessageObject next = controller.getCarVoiceQueueNext(playing, direction, false);
            if (next != null) {
                next.resetPlayingProgress();
                controller.playMessage(next);
            }
            return;
        }
        ArrayList<MessageObject> messages = audioObjects.get(playing.getDialogId());
        int index = messages != null ? messages.indexOf(playing) : -1;
        if (index < 0 || SharedConfig.shuffleMusic && playing.isMusic()) {
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
        String prefix;
        if (parentMediaId != null && parentMediaId.startsWith(MEDIA_ID_CHAT_PREFIX)) {
            prefix = MEDIA_ID_CHAT_PREFIX;
        } else if (parentMediaId != null && parentMediaId.startsWith(MEDIA_ID_VIDEO_CHAT_PREFIX)) {
            prefix = MEDIA_ID_VIDEO_CHAT_PREFIX;
        } else {
            return 0;
        }
        try {
            return Long.parseLong(parentMediaId.substring(prefix.length()));
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private List<MediaBrowser.MediaItem> loadChildrenSync(String parentMediaId) {
        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();
        if (MEDIA_ID_ROOT.equals(parentMediaId)) {
            mediaItems.add(new MediaBrowser.MediaItem(new android.media.MediaDescription.Builder()
                    .setMediaId(MEDIA_ID_LIBRARY)
                    .setTitle(LocaleController.getString(R.string.CarTabAudio))
                    .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
            mediaItems.add(new MediaBrowser.MediaItem(new android.media.MediaDescription.Builder()
                    .setMediaId(MEDIA_ID_VIDEO_LIBRARY)
                    .setTitle(LocaleController.getString(R.string.CarTabVideos))
                    .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
        } else if (MEDIA_ID_LIBRARY.equals(parentMediaId)) {
            for (int a = 0; a < dialogs.size(); a++) {
                mediaItems.add(buildChatItem(MEDIA_ID_CHAT_PREFIX, dialogs.get(a), null));
            }
        } else if (MEDIA_ID_VIDEO_LIBRARY.equals(parentMediaId)) {
            for (int a = 0; a < videoDialogs.size(); a++) {
                mediaItems.add(buildChatItem(MEDIA_ID_VIDEO_CHAT_PREFIX, videoDialogs.get(a), null));
            }
        } else if (getDialogIdFromMediaId(parentMediaId) != 0) {
            long did = getDialogIdFromMediaId(parentMediaId);
            boolean videos = parentMediaId.startsWith(MEDIA_ID_VIDEO_CHAT_PREFIX);
            ArrayList<MessageObject> arrayList = audioObjects.get(did);
            if (arrayList != null) {
                for (int a = 0; a < arrayList.size(); a++) {
                    MessageObject messageObject = arrayList.get(a);
                    if (isCarVideo(messageObject) == videos) {
                        mediaItems.add(buildPlayableItem(MEDIA_ID_MESSAGE_PREFIX + did + "_" + messageObject.getId(), messageObject, null));
                    }
                }
            }
        }
        return mediaItems;
    }

    private final android.util.SparseArray<Bitmap> specialAvatars = new android.util.SparseArray<>();

    /** Telegram's own round avatar for Saved Messages and Replies. */
    @Nullable
    private Bitmap getSpecialAvatar(int avatarType) {
        Bitmap bitmap = specialAvatars.get(avatarType);
        if (bitmap == null) {
            try {
                int size = 256;
                bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                org.telegram.ui.Components.AvatarDrawable drawable = new org.telegram.ui.Components.AvatarDrawable();
                drawable.setAvatarType(avatarType);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(new Canvas(bitmap));
                specialAvatars.put(avatarType, bitmap);
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
        return bitmap;
    }

    private MediaBrowser.MediaItem buildChatItem(String prefix, long dialogId, @Nullable String subtitle) {
        return buildChatItem(prefix, dialogId, subtitle, null);
    }

    private MediaBrowser.MediaItem buildChatItem(String prefix, long dialogId, @Nullable String subtitle, @Nullable Bundle extras) {
        android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                .setMediaId(prefix + dialogId);
        builder.setTitle(getDialogTitle(dialogId));
        TLRPC.FileLocation avatar = null;
        boolean savedMessages = dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
        Bitmap specialAvatar = savedMessages ? getSpecialAvatar(org.telegram.ui.Components.AvatarDrawable.AVATAR_TYPE_SAVED)
                : UserObject.isReplyUser(dialogId) ? getSpecialAvatar(org.telegram.ui.Components.AvatarDrawable.AVATAR_TYPE_REPLIES) : null;
        if (specialAvatar != null) {
            builder.setIconBitmap(specialAvatar);
        } else if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user == null) {
                user = users.get(dialogId);
            }
            if (user != null && user.photo != null && !(user.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                avatar = user.photo.photo_small;
            }
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat == null) {
                chat = chats.get(-dialogId);
            }
            if (chat != null && chat.photo != null && !(chat.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                avatar = chat.photo.photo_small;
            }
        }
        Bitmap bitmap = null;
        if (avatar != null) {
            bitmap = createRoundBitmap(FileLoader.getInstance(currentAccount).getPathToAttach(avatar, true));
            if (bitmap != null) {
                builder.setIconBitmap(bitmap);
            }
        }
        if (specialAvatar == null && (avatar == null || bitmap == null)) {
            builder.setIconUri(Uri.parse("android.resource://" + appContext.getPackageName() + "/drawable/contact_blue"));
        }
        if (subtitle != null) {
            builder.setSubtitle(subtitle);
        }
        if (extras != null) {
            builder.setExtras(extras);
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private static String withDuration(@Nullable String subtitle, int durationSeconds) {
        if (durationSeconds <= 0) {
            return subtitle;
        }
        String duration = AndroidUtilities.formatShortDuration(durationSeconds);
        return TextUtils.isEmpty(subtitle) ? duration : duration + " \u00B7 " + subtitle;
    }

    private MediaBrowser.MediaItem buildPlayableItem(String mediaId, MessageObject messageObject, @Nullable Bundle extras) {
        android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(getAudioTitle(messageObject));
        String author = messageObject.getMusicAuthor();
        String videoLabel = getVideoLabel(messageObject);
        if (videoLabel != null) {
            author = TextUtils.isEmpty(author) ? videoLabel : videoLabel + " \u00B7 " + author;
        }
        builder.setSubtitle(withDuration(author, (int) messageObject.getDuration()));
        Bitmap cover = getAudioCover(messageObject);
        if (cover != null) {
            builder.setIconBitmap(cover);
        } else {
            builder.setIconUri(getFallbackCoverUri(isCarVideo(messageObject)));
        }
        if (extras != null) {
            builder.setExtras(extras);
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    /** Media browser search: returns playable items across all chats. */
    private static final String CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static Bundle groupExtras(String title) {
        Bundle extras = new Bundle();
        extras.putString(CONTENT_STYLE_GROUP_TITLE_HINT, title);
        return extras;
    }

    /** Media browser search: matching chats, then matching audio from all chats. */
    public void searchBrowseItems(String query, BrowseChildrenCallback callback) {
        String normalized = normalizeQuery(query);
        searchAudio(0, query, results -> {
            List<MediaBrowser.MediaItem> items = new ArrayList<>();
            if (!normalized.isEmpty()) {
                Bundle extras = groupExtras(LocaleController.getString(R.string.CarSearchChats));
                for (int a = 0; a < dialogs.size(); a++) {
                    long dialogId = dialogs.get(a);
                    if (matchesQuery(getDialogTitle(dialogId), normalized)) {
                        items.add(buildChatItem(MEDIA_ID_CHAT_PREFIX, dialogId, LocaleController.getString(R.string.CarTabAudio), extras));
                    }
                }
                for (int a = 0; a < videoDialogs.size(); a++) {
                    long dialogId = videoDialogs.get(a);
                    if (matchesQuery(getDialogTitle(dialogId), normalized)) {
                        items.add(buildChatItem(MEDIA_ID_VIDEO_CHAT_PREFIX, dialogId, LocaleController.getString(R.string.CarTabVideos), extras));
                    }
                }
            }
            Bundle otherExtras = groupExtras(LocaleController.getString(R.string.CarSearchAllAudio));
            for (AudioSearchResult result : results) {
                android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                        .setMediaId(MEDIA_ID_MESSAGE_PREFIX + result.dialogId + "_" + result.messageId)
                        .setTitle(result.title)
                        .setExtras(otherExtras);
                String subtitle = result.author;
                String chat = getDialogTitle(result.dialogId);
                subtitle = subtitle.isEmpty() ? chat : subtitle + " \u00B7 " + chat;
                if (result.videoLabel != null) {
                    subtitle = subtitle.isEmpty() ? result.videoLabel : result.videoLabel + " \u00B7 " + subtitle;
                }
                builder.setSubtitle(withDuration(subtitle, result.durationSeconds));
                builder.setIconUri(getFallbackCoverUri(result.videoLabel != null));
                items.add(new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            callback.onResult(items);
        });
    }

    private void applyQueueFor(long did) {
        if (did == 0) return;
        ArrayList<MessageObject> arrayList = audioObjects.get(did);
        ArrayList<MediaSessionCompat.QueueItem> queueList = audioQueues.get(did);
        if (arrayList == null || arrayList.isEmpty() || queueList == null) return;
        session.setQueue(queueList);
        session.setQueueTitle(getDialogTitle(did));
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

    private long nowPlayingCoverKey;
    private Bitmap nowPlayingCover;

    /** Larger cover for the now-playing screen: embedded album art first, then the file thumbnail. */
    @Nullable
    public Bitmap getNowPlayingCover(MessageObject messageObject) {
        TLRPC.Document document = messageObject != null ? messageObject.getDocument() : null;
        if (document == null) {
            return null;
        }
        AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        Bitmap embedded = audioInfo != null ? audioInfo.getCover() : null;
        long key = embedded != null ? -document.id : document.id;
        if (nowPlayingCover != null && nowPlayingCoverKey == key) {
            return nowPlayingCover;
        }
        Bitmap bitmap = null;
        try {
            if (embedded != null && !embedded.isRecycled()) {
                bitmap = cropAndScale(embedded, NOW_PLAYING_COVER_SIZE_PX, false);
            } else if (document.thumbs != null && !document.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320, false, null, true);
                if (thumb != null) {
                    if (thumb.bytes != null && thumb.bytes.length > 0) {
                        bitmap = decodeCover(null, thumb.bytes, NOW_PLAYING_COVER_SIZE_PX);
                    } else {
                        File path = FileLoader.getInstance(messageObject.currentAccount).getPathToAttach(thumb, true);
                        if (path != null && path.exists()) {
                            bitmap = decodeCover(path, null, NOW_PLAYING_COVER_SIZE_PX);
                        } else {
                            getAudioCover(messageObject);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (bitmap != null) {
            nowPlayingCover = bitmap;
            nowPlayingCoverKey = key;
        }
        return bitmap;
    }

    private static final int NOW_PLAYING_COVER_SIZE_PX = 320;

    private static Bitmap decodeCover(@Nullable File file, @Nullable byte[] bytes) {
        return decodeCover(file, bytes, COVER_SIZE_PX);
    }

    private static Bitmap decodeCover(@Nullable File file, @Nullable byte[] bytes, int sizePx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (file != null) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
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
        return cropAndScale(decoded, sizePx, true);
    }

    private static Bitmap cropAndScale(Bitmap source, int sizePx, boolean recycleSource) {
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                (source.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true);
        if (recycleSource && square != source) {
            source.recycle();
        }
        if (scaled != square && square != source) {
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
            playAudioMessage(lastSelectedDialog, (int) queueId);
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
            if (mediaId.startsWith(MEDIA_ID_MESSAGE_PREFIX)) {
                String[] parts = mediaId.substring(MEDIA_ID_MESSAGE_PREFIX.length()).split("_");
                if (parts.length == 2) {
                    try {
                        playAudioMessage(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                return;
            }
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
            String q = normalizeQuery(query);
            for (int a = 0; a < dialogs.size(); a++) {
                long did = dialogs.get(a);
                if (matchesQuery(getDialogTitle(did), q)) {
                    onPlayFromMediaId(did + "_" + 0, null);
                    return;
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
            } else {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (org.telegram.ui.Components.AudioPlayerAlert.instance != null) {
                    org.telegram.ui.Components.AudioPlayerAlert.instance.updateRepeatButton();
                }
            });
            notifyPlayStateForNotificationRefresh();
        }
    }
}
