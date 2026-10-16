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
import android.util.Log;
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
            current.session.setPlaybackState(current.withFavoriteAction(state));
            current.rememberPlayState();
        }
    }

    /** Adds a star button to the car's now-playing screen for the playing item. */
    private PlaybackStateCompat withFavoriteAction(PlaybackStateCompat state) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (state == null || playing == null || !isCarPlayable(playing)) {
            return state;
        }
        boolean favorite = isFavorite(playing.getDialogId(), playing.getId());
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder(state);
        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_FAVORITE,
                LocaleController.getString(favorite ? R.string.CarRemoveFavorite : R.string.CarAddFavorite),
                favorite ? R.drawable.ic_rating_star_filled : R.drawable.ic_rating_star).build());
        return builder.build();
    }

    @Nullable
    private ArrayList<CarPlaybackStore.Favorite> favorites;
    @Nullable
    private ArrayList<CarPlaybackStore.Favorite> recents;
    private int listsAccount = -1;

    private ArrayList<CarPlaybackStore.Favorite> getStoredList(String prefix) {
        if (listsAccount != currentAccount) {
            favorites = null;
            recents = null;
            listsAccount = currentAccount;
        }
        if (MEDIA_ID_RECENT_PREFIX.equals(prefix)) {
            if (recents == null) {
                recents = CarPlaybackStore.loadList(CarPlaybackStore.RECENT, currentAccount);
            }
            return recents;
        }
        if (favorites == null) {
            favorites = CarPlaybackStore.loadList(CarPlaybackStore.FAVORITES, currentAccount);
        }
        return favorites;
    }

    private ArrayList<CarPlaybackStore.Favorite> getFavorites() {
        return getStoredList(MEDIA_ID_FAVORITE_PREFIX);
    }

    private static int indexOf(ArrayList<CarPlaybackStore.Favorite> list, long dialogId, int messageId) {
        for (int i = 0; i < list.size(); i++) {
            CarPlaybackStore.Favorite favorite = list.get(i);
            if (favorite.dialogId == dialogId && favorite.messageId == messageId) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFavorite(long dialogId, int messageId) {
        return indexOf(getFavorites(), dialogId, messageId) >= 0;
    }

    private void toggleFavorite(MessageObject message) {
        ArrayList<CarPlaybackStore.Favorite> list = getFavorites();
        int index = indexOf(list, message.getDialogId(), message.getId());
        if (index >= 0) {
            list.remove(index);
        } else {
            list.add(0, CarPlaybackStore.createFavorite(message));
        }
        CarPlaybackStore.saveList(CarPlaybackStore.FAVORITES, currentAccount, list);
        notifyBrowseTree(MEDIA_ID_FAVORITES);
        notifyBrowseTree(MEDIA_ID_RECENT);
        notifyDialogChanged(message.getDialogId(), true);
        if (lastPlayerState != null) {
            session.setPlaybackState(withFavoriteAction(lastPlayerState));
        }
    }

    private void clearRecent() {
        ArrayList<CarPlaybackStore.Favorite> list = getStoredList(MEDIA_ID_RECENT_PREFIX);
        list.clear();
        CarPlaybackStore.saveList(CarPlaybackStore.RECENT, currentAccount, list);
        notifyBrowseTree(MEDIA_ID_RECENT);
    }

    /** Moves an item to the top of the recently played list. */
    private void recordRecent(MessageObject message) {
        if (message == null || message.getId() <= 0 || !isCarPlayable(message)) {
            return;
        }
        ArrayList<CarPlaybackStore.Favorite> list = getStoredList(MEDIA_ID_RECENT_PREFIX);
        int index = indexOf(list, message.getDialogId(), message.getId());
        if (index == 0) {
            return;
        }
        if (index > 0) {
            list.remove(index);
        }
        list.add(0, CarPlaybackStore.createFavorite(message));
        while (list.size() > CarPlaybackStore.MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        CarPlaybackStore.saveList(CarPlaybackStore.RECENT, currentAccount, list);
        notifyBrowseTree(MEDIA_ID_RECENT);
    }

    /**
     * Plays an item of the favorites or recent list. The list is snapshotted so skipping keeps
     * the original order even though playing reorders the recent list.
     */
    private void playFromStoredList(String prefix, long dialogId, int messageId) {
        ArrayList<CarPlaybackStore.Favorite> list = getStoredList(prefix);
        int index = indexOf(list, dialogId, messageId);
        if (index < 0) {
            reportPlaybackError("Android Auto: item not found");
            return;
        }
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (CarPlaybackStore.Favorite favorite : list) {
            messages.add(favorite.message);
        }
        playingSnapshot = messages;
        playingSnapshotQueue = createAudioQueue(messages, prefix);
        playingSnapshotTitle = LocaleController.getString(MEDIA_ID_RECENT_PREFIX.equals(prefix)
                ? R.string.CarTabRecent : R.string.CarTabFavorites);
        playSnapshotItem(index);
    }

    private void playSnapshotItem(int index) {
        playSnapshotItem(index, false);
    }

    private void playSnapshotItem(int index, boolean fromStart) {
        if (playingSnapshot == null || index < 0 || index >= playingSnapshot.size()) {
            return;
        }
        ArrayList<MessageObject> messages = playingSnapshot;
        startPlayback(messages, index, playingSnapshotQueue, playingSnapshotTitle, fromStart);
        playingSnapshot = messages;
    }

    private int indexInSnapshot(MessageObject message) {
        if (playingSnapshot == null || message == null) {
            return -1;
        }
        for (int i = 0; i < playingSnapshot.size(); i++) {
            MessageObject item = playingSnapshot.get(i);
            if (item.getDialogId() == message.getDialogId() && item.getId() == message.getId()) {
                return i;
            }
        }
        return -1;
    }

    /** Favorites or recent list being played, or null when playing from a chat. */
    @Nullable
    private ArrayList<MessageObject> playingSnapshot;
    @Nullable
    private ArrayList<MediaSessionCompat.QueueItem> playingSnapshotQueue;
    private String playingSnapshotTitle;
    private long lastPositionSave;

    private void savePlayingPosition(boolean force) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !isCarPlayable(playing)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastPositionSave < POSITION_SAVE_INTERVAL_MS) {
            return;
        }
        lastPositionSave = now;
        CarPlaybackStore.savePosition(playing.currentAccount, playing.getDialogId(), playing.getId(),
                playing.audioProgress, playing.getDuration());
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
            current.showLastPlayed();
        }
    }

    /**
     * Shows the most recently played item as paused, so the car's media card offers to resume
     * it instead of just "Tap to open". Pressing play resumes it from the saved position.
     */
    private void showLastPlayed() {
        if (MediaController.getInstance().getPlayingMessageObject() != null) {
            return;
        }
        ArrayList<CarPlaybackStore.Favorite> list = getStoredList(MEDIA_ID_RECENT_PREFIX);
        if (list.isEmpty() || list.get(0).message == null) {
            return;
        }
        CarPlaybackStore.Favorite last = list.get(0);
        MessageObject message = last.message;
        long durationMs = (long) (message.getDuration() * 1000);
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                        MEDIA_ID_RECENT_PREFIX + last.dialogId + "_" + last.messageId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getAudioTitle(message))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, message.getMusicAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, message.getMusicAuthor())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        Bitmap cover = getNowPlayingCover(message);
        if (cover == null) {
            boolean video = isCarVideo(message);
            cover = getFallbackCover(video);
            String uri = getFallbackCoverUri(video).toString();
            meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uri)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uri);
        }
        if (cover != null) {
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        }
        session.setMetadata(meta.build());
        float progress = CarPlaybackStore.getPosition(currentAccount, last.dialogId, last.messageId);
        long position = progress > 0 ? (long) (progress * durationMs) : 0;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, position, 0f)
                .setActions(getAvailableActions() | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .build());
    }

    /** Resumes the most recently played item; returns false when there is none. */
    private boolean resumeLastPlayed() {
        ArrayList<CarPlaybackStore.Favorite> list = getStoredList(MEDIA_ID_RECENT_PREFIX);
        if (list.isEmpty() || list.get(0).message == null) {
            return false;
        }
        CarPlaybackStore.Favorite last = list.get(0);
        playFromStoredList(MEDIA_ID_RECENT_PREFIX, last.dialogId, last.messageId);
        return true;
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
    private static final String MEDIA_ID_FAVORITES = "__FAVORITES__";
    private static final String MEDIA_ID_FAVORITE_PREFIX = "fav_";
    private static final String MEDIA_ID_RECENT = "__RECENT__";
    private static final String MEDIA_ID_RECENT_PREFIX = "rec_";
    private static final String MEDIA_ID_CLEAR_RECENT = "__CLEAR_RECENT__";
    /** Root offered to system media resumption (BrowserRoot.EXTRA_RECENT): just the last item. */
    public static final String MEDIA_ID_RESUME = "__RESUME__";
    private static final String CUSTOM_ACTION_FAVORITE = "org.telegram.messenger.CAR_FAVORITE";
    private static final long POSITION_SAVE_INTERVAL_MS = 2000;
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
    private final Set<Long> pinnedDialogs = new HashSet<>();
    /** Cloud search hits that are not part of a loaded chat list yet, keyed by did_messageId. */
    private final HashMap<String, MessageObject> cloudSearchMessages = new HashMap<>();
    private static final String PINNED_ORDER = "CASE WHEN folder_id = 0 THEN pinned ELSE 0 END DESC, date DESC";
    private static final int CLOUD_SEARCH_LIMIT = 50;
    private static final long CLOUD_SEARCH_TIMEOUT_MS = 6000;
    private static final int CLOUD_AUDIO_PAGE_SIZE = 100;
    private final LongSparseArray<CloudAudioLoad> cloudAudioLoads = new LongSparseArray<>();
    private final Set<Integer> cloudRequestIds = new HashSet<>();
    /** Dialogs whose photo/video shared media has been merged into their playable list. */
    private final Set<Long> videoLoadedDialogs = new HashSet<>();
    private final LongSparseArray<ArrayList<Runnable>> pendingVideoLoads = new LongSparseArray<>();
    /** Chats found by the background photo/video scan; round videos are found by the main chat scan. */
    private final Set<Long> photoVideoDialogs = new HashSet<>();
    private boolean videoDialogsLoaded;
    private boolean scanningVideoDialogs;
    private boolean videoRescanRequested;
    /** Chats whose only playable audio is files; the music/voice count query cannot find them. */
    private final Set<Long> fileAudioDialogs = new HashSet<>();
    private boolean fileDialogsScanned;
    private boolean scanningFileDialogs;
    private final ArrayList<PendingBrowseRequest> pendingVideoBrowseRequests = new ArrayList<>();
    private boolean chatsReloadRequested;
    private final LongSparseArray<Integer> discoveredChannelCounts = new LongSparseArray<>();
    /** Bumped on every deletion so in-flight database reads cannot republish deleted messages. */
    private int deletionGeneration;
    private boolean channelRediscoveryRequested;
    private final LongSparseArray<Runnable> pendingDialogNotifies = new LongSparseArray<>();
    private static final long DIALOG_NOTIFY_INTERVAL_MS = 1500;
    private static final long CATALOG_REVALIDATE_DELAY_MS = 1000;
    private long pendingPlayDialog;
    private final Runnable pendingPlayTimeout = () -> {
        if (pendingPlayDialog != 0) {
            pendingPlayDialog = 0;
            reportPlaybackError("Android Auto: timed out waiting for audio");
        }
    };

    private void reportPlaybackError(String message) {
        FileLog.e(message);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0)
                .setErrorMessage(message)
                .setActions(getAvailableActions())
                .build());
    }

    private void cancelPendingPlay() {
        pendingPlayDialog = 0;
        AndroidUtilities.cancelRunOnUIThread(pendingPlayTimeout);
    }

    private static final class CloudAudioLoad {
        int filterIndex;
        int offsetId;
        boolean loading;
        boolean firstPageDone;
        boolean retryScheduled;
        /** Pages are fetched until the chat has this many items; browsing deeper raises it. */
        int target = CLOUD_AUDIO_PAGE_SIZE;
        final ArrayList<Runnable> waiters = new ArrayList<>();
        /** Server totals for the music and round/voice filters, -1 while unknown. */
        final int[] serverCounts = {-1, -1};
        boolean countsRequested;
        /** Consecutive server pages of files without any audio file. */
        int emptyFilePages;

        boolean exhausted() {
            return filterIndex >= CLOUD_FILTER_COUNT;
        }
    }

    // Cloud passes per chat: music, round and voice, then files (only audio files are kept).
    private static final int CLOUD_FILTER_FILES = 2;
    private static final int CLOUD_FILTER_COUNT = 3;
    // Chats with many documents are not paged forever looking for audio files.
    private static final int MAX_EMPTY_FILE_PAGES = 10;

    private static final int BROWSE_PAGE_SIZE = 25;
    private static final String PAGE_SEPARATOR = "@";
    private static final String CAR_LOG_TAG = "TgCar";

    private static final long CLOUD_WAIT_TIMEOUT_MS = 8000;
    private static final int PREFETCH_DISTANCE = 5;


    private void cancelCloudAudioRequests() {
        for (int requestId : cloudRequestIds) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
        }
        cloudRequestIds.clear();
        for (int i = 0; i < cloudAudioLoads.size(); i++) {
            ArrayList<Runnable> waiters = new ArrayList<>(cloudAudioLoads.valueAt(i).waiters);
            cloudAudioLoads.valueAt(i).waiters.clear();
            for (Runnable waiter : waiters) {
                waiter.run();
            }
        }
        cloudAudioLoads.clear();
        cloudSearchMessages.clear();
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
        } else {
            showLastPlayed();
        }
        try {
            appContext.getContentResolver().registerContentObserver(CAR_CONNECTION_URI, true,
                    new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            checkCarConnection();
                        }
                    });
        } catch (Exception e) {
            FileLog.e(e);
        }
        checkCarConnection();

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
        cancelPendingPlay();
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
        pinnedDialogs.clear();
        videoLoadedDialogs.clear();
        pendingVideoLoads.clear();
        photoVideoDialogs.clear();
        videoDialogsLoaded = false;
        scanningVideoDialogs = false;
        videoRescanRequested = false;
        fileAudioDialogs.clear();
        fileDialogsScanned = false;
        scanningFileDialogs = false;
        chatsReloadRequested = false;
        discoveredChannelCounts.clear();
        channelRediscoveryRequested = false;
        sentSignatures.clear();
        favorites = null;
        recents = null;
        playingSnapshot = null;
        for (int i = 0; i < pendingDialogNotifies.size(); i++) {
            AndroidUtilities.cancelRunOnUIThread(pendingDialogNotifies.valueAt(i));
        }
        pendingDialogNotifies.clear();
        AndroidUtilities.cancelRunOnUIThread(catalogRevalidate);
        CarArtworkProvider.clearSources();
        ArrayList<PendingBrowseRequest> videoRequests = new ArrayList<>(pendingVideoBrowseRequests);
        pendingVideoBrowseRequests.clear();
        for (PendingBrowseRequest request : videoRequests) {
            request.callback.onResult(new ArrayList<>());
        }
        try {
            session.setQueue(null);
            session.setQueueTitle(null);
        } catch (Throwable ignored) {
        }
        if (!pendingBrowseRequests.isEmpty()) {
            loadChats();
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
        cancelPendingPlay();
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
                String types = String.format(Locale.US, "%d, %d, %d, %d", MediaDataController.MEDIA_AUDIO, MediaDataController.MEDIA_MUSIC, MediaDataController.MEDIA_PHOTOVIDEO, MediaDataController.MEDIA_FILE);
                SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized("SELECT data, mid, uid, type FROM media_v4 WHERE uid != 0 AND mid > 0 AND type IN (" + types + ") ORDER BY date DESC, mid DESC");
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
                    boolean file = cursor.intValue(3) == MediaDataController.MEDIA_FILE;
                    if (file ? isCarAudioDocument(messageObject) : isCarPlayable(messageObject)) {
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
            MessageObject cloudMessage = cloudSearchMessages.get(dialogId + "_" + messageId);
            if (cloudMessage != null) {
                messages.add(cloudMessage);
                audioQueues.put(dialogId, createAudioQueue(dialogId, messages));
                searchIndex = null;
                searchIndexRevision = -1;
                playAudio(dialogId, messages.indexOf(cloudMessage));
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
        loadAudioForDialog(dialogId, onLoaded != null ? guarded(onLoaded) : null);
    }

    /** Drops a continuation if the account or catalog changed while it was waiting. */
    private Runnable guarded(Runnable action) {
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        return () -> {
            if (account == currentAccount && revision == audioCatalogRevision) {
                action.run();
            }
        };
    }

    public void refreshAudioCatalog(Runnable onLoaded) {
        AndroidUtilities.runOnUIThread(() -> {
            if (onLoaded != null) {
                pendingCatalogCallbacks.add(onLoaded);
            }
            if (loadingChats) {
                chatsReloadRequested = true;
                return;
            }
            loadChats();
        });
    }

    private final Runnable catalogRevalidate = () -> refreshAudioCatalog(null);

    /**
     * Reloads the chat list in the background while the current lists keep being served.
     * A video rescan is only needed when the car reconnects, because it walks all shared media.
     */
    public void revalidateCatalog(boolean rescanVideos) {
        AndroidUtilities.runOnUIThread(() -> {
            if (rescanVideos) {
                requestVideoRescan();
                dropCachedChatContents();
            }
            AndroidUtilities.cancelRunOnUIThread(catalogRevalidate);
            catalogRevalidate.run();
        });
    }

    /**
     * Observers are detached while the car is away, so cached chat contents may be stale on
     * reconnect. They are dropped and re-read lazily; the playing chat is reloaded in place.
     */
    private void dropCachedChatContents() {
        channelRediscoveryRequested = true;
        deletionGeneration++;
        for (int i = audioObjects.size() - 1; i >= 0; i--) {
            long did = audioObjects.keyAt(i);
            if (did == lastSelectedDialog || did == pendingPlayDialog || pendingAudioLoads.get(did) != null) {
                continue;
            }
            audioObjects.removeAt(i);
            audioQueues.remove(did);
            videoLoadedDialogs.remove(did);
        }
        for (int i = cloudAudioLoads.size() - 1; i >= 0; i--) {
            if (!cloudAudioLoads.valueAt(i).loading) {
                cloudAudioLoads.removeAt(i);
            }
        }
        invalidateSearchIndex();
        if (lastSelectedDialog != 0 && audioObjects.get(lastSelectedDialog) != null) {
            reloadDialog(lastSelectedDialog);
        }
    }

    private void scheduleCatalogRevalidate() {
        AndroidUtilities.cancelRunOnUIThread(catalogRevalidate);
        AndroidUtilities.runOnUIThread(catalogRevalidate, CATALOG_REVALIDATE_DELAY_MS);
    }

    private void requestVideoRescan() {
        if (videoDialogsLoaded) {
            videoRescanRequested = true;
        }
    }

    private void invalidateSearchIndex() {
        searchIndex = null;
        searchIndexRevision = -1;
    }

    /** Notifies the car that a chat's items changed, at most once per interval unless immediate. */
    private void notifyDialogChanged(long did, boolean immediate) {
        Runnable pending = pendingDialogNotifies.get(did);
        if (pending != null) {
            if (!immediate) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(pending);
            pendingDialogNotifies.remove(did);
        }
        Runnable flush = () -> {
            pendingDialogNotifies.remove(did);
            if (lastSelectedDialog == did && audioQueues.get(did) != null) {
                session.setQueue(audioQueues.get(did));
            }
            notifyBrowseTree(MEDIA_ID_CHAT_PREFIX + did);
            notifyBrowseTree(MEDIA_ID_VIDEO_CHAT_PREFIX + did);
        };
        if (immediate) {
            flush.run();
        } else {
            pendingDialogNotifies.put(did, flush);
            AndroidUtilities.runOnUIThread(flush, DIALOG_NOTIFY_INTERVAL_MS);
        }
    }

    private void onNewMessages(long did, ArrayList<MessageObject> incoming) {
        if (!chatsLoaded || DialogObject.isEncryptedDialog(did)) {
            return;
        }
        ArrayList<MessageObject> playable = new ArrayList<>();
        boolean hasAudio = false;
        boolean hasVideo = false;
        for (MessageObject message : incoming) {
            if (message.getId() > 0 && isCarPlayable(message)) {
                playable.add(message);
                if (isCarVideo(message)) {
                    hasVideo = true;
                } else {
                    hasAudio = true;
                }
            }
        }
        if (playable.isEmpty()) {
            return;
        }
        invalidateSearchIndex();
        ArrayList<MessageObject> messages = audioObjects.get(did);
        if (messages != null) {
            Set<Integer> known = new HashSet<>();
            for (MessageObject message : messages) {
                known.add(message.getId());
            }
            boolean changed = false;
            for (MessageObject message : playable) {
                boolean videoReady = !isCarVideo(message) || message.isRoundVideo() || videoLoadedDialogs.contains(did);
                if (videoReady && known.add(message.getId())) {
                    messages.add(0, message);
                    changed = true;
                }
            }
            if (changed) {
                audioQueues.put(did, createAudioQueue(did, messages));
                notifyDialogChanged(did, true);
            }
        }
        if (hasAudio && !dialogs.contains(did)) {
            dialogs.add(did);
            sortAudioDialogs();
            notifyBrowseTree(MEDIA_ID_LIBRARY);
        }
        if (hasVideo && videoDialogsLoaded && photoVideoDialogs.add(did)) {
            rebuildVideoDialogs();
            notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
        }
    }

    /** Removes deleted messages from loaded lists; dialogId 0 means every non-channel chat. */
    private void onMessagesDeleted(long dialogId, Set<Integer> ids, int maxId) {
        deletionGeneration++;
        requestVideoRescan();
        for (int i = 0; i < audioObjects.size(); i++) {
            long did = audioObjects.keyAt(i);
            if (dialogId != 0 ? did != dialogId : isChannelDialog(did)) {
                continue;
            }
            ArrayList<MessageObject> messages = audioObjects.valueAt(i);
            boolean changed = false;
            for (int a = messages.size() - 1; a >= 0; a--) {
                int id = messages.get(a).getId();
                if (ids != null ? ids.contains(id) : id <= maxId) {
                    messages.remove(a);
                    changed = true;
                }
            }
            if (changed) {
                audioQueues.put(did, createAudioQueue(did, messages));
                invalidateSearchIndex();
                notifyDialogChanged(did, true);
            }
        }
        scheduleCatalogRevalidate();
    }

    private boolean isChannelDialog(long did) {
        if (!DialogObject.isChatDialog(did)) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
        if (chat == null) {
            chat = chats.get(-did);
        }
        return ChatObject.isChannel(chat);
    }

    private void onDialogRemoved(long did) {
        deletionGeneration++;
        if (audioObjects.get(did) != null) {
            audioObjects.put(did, new ArrayList<>());
            audioQueues.put(did, new ArrayList<>());
            notifyDialogChanged(did, true);
        }
        invalidateSearchIndex();
        if (dialogs.remove(did)) {
            notifyBrowseTree(MEDIA_ID_LIBRARY);
        }
        photoVideoDialogs.remove(did);
        fileAudioDialogs.remove(did);
        if (videoDialogs.remove(did)) {
            notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
        }
        scheduleCatalogRevalidate();
    }


    public interface BrowseTreeListener {
        void onChildrenChanged(String parentMediaId);
    }

    @Nullable
    private static BrowseTreeListener browseTreeListener;
    private int observedAccount = -1;
    private final NotificationCenter.NotificationCenterDelegate messageObserver = (id, account, args) -> {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            savePlayingPosition(false);
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            savePlayingPosition(true);
        } else if (id == NotificationCenter.messagePlayingDidStart) {
            if (args.length > 0 && args[0] instanceof MessageObject) {
                recordRecent((MessageObject) args[0]);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 1 && args[0] instanceof Long && args[1] instanceof ArrayList
                    && (args.length <= 2 || !Boolean.TRUE.equals(args[2]))) {
                @SuppressWarnings("unchecked")
                ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
                onNewMessages((Long) args[0], messages);
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            if (args.length > 1 && args[0] instanceof ArrayList && args[1] instanceof Long
                    && (args.length <= 2 || !Boolean.TRUE.equals(args[2]))) {
                Set<Integer> ids = new HashSet<>();
                for (Object object : (ArrayList<?>) args[0]) {
                    if (object instanceof Integer) {
                        ids.add((Integer) object);
                    }
                }
                long channelId = (Long) args[1];
                onMessagesDeleted(channelId != 0 ? -channelId : 0, ids, 0);
            }
        } else if (id == NotificationCenter.historyCleared) {
            if (args.length > 1 && args[0] instanceof Long && args[1] instanceof Integer) {
                onMessagesDeleted((Long) args[0], null, (Integer) args[1]);
            }
        } else if (id == NotificationCenter.dialogDeleted) {
            if (args.length > 0 && args[0] instanceof Long) {
                onDialogRemoved((Long) args[0]);
            }
        } else if (id == NotificationCenter.removeAllMessagesFromDialog) {
            // Also posted when Telegram resyncs a chat, so reload it instead of emptying it.
            if (args.length > 0 && args[0] instanceof Long) {
                reloadDialog((Long) args[0]);
            }
        } else if (id == NotificationCenter.dialogsNeedReload) {
            applyLiveDialogOrder();
        } else if (id == NotificationCenter.updateInterfaces) {
            int mask = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            int chatMask = MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_AVATAR
                    | MessagesController.UPDATE_MASK_CHAT_NAME | MessagesController.UPDATE_MASK_CHAT_AVATAR
                    | MessagesController.UPDATE_MASK_CHAT;
            if ((mask & chatMask) != 0) {
                scheduleChatListRefresh();
            }
        }
    };

    private final Runnable chatListRefresh = () -> {
        notifyBrowseTree(MEDIA_ID_LIBRARY);
        notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
    };

    /** Coalesces bursts of name and photo updates into one browse refresh. */
    private void scheduleChatListRefresh() {
        if (!chatsLoaded) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(chatListRefresh);
        AndroidUtilities.runOnUIThread(chatListRefresh, 300);
    }

    /** Mirrors Telegram's in-memory chat list so pin and reorder changes show up immediately. */
    private void applyLiveDialogOrder() {
        if (!chatsLoaded) {
            return;
        }
        ArrayList<TLRPC.Dialog> live = MessagesController.getInstance(currentAccount).getDialogs(0);
        if (live.isEmpty()) {
            return;
        }
        Set<Long> livePinned = new HashSet<>();
        LongSparseArray<Integer> liveOrder = new LongSparseArray<>();
        for (int i = 0; i < live.size(); i++) {
            TLRPC.Dialog dialog = live.get(i);
            liveOrder.put(dialog.id, i);
            if (dialog.pinned) {
                livePinned.add(dialog.id);
            }
        }
        LongSparseArray<Integer> newOrder = new LongSparseArray<>();
        for (int i = 0; i < live.size(); i++) {
            newOrder.put(live.get(i).id, i);
        }
        for (int i = 0; i < dialogOrder.size(); i++) {
            long did = dialogOrder.keyAt(i);
            if (liveOrder.get(did) == null) {
                newOrder.put(did, live.size() + dialogOrder.valueAt(i));
            }
        }
        ArrayList<Long> oldDialogs = new ArrayList<>(dialogs);
        ArrayList<Long> oldVideoDialogs = new ArrayList<>(videoDialogs);
        boolean pinsChanged = !livePinned.equals(pinnedDialogs);
        dialogOrder.clear();
        for (int i = 0; i < newOrder.size(); i++) {
            dialogOrder.put(newOrder.keyAt(i), newOrder.valueAt(i));
        }
        pinnedDialogs.clear();
        pinnedDialogs.addAll(livePinned);
        sortAudioDialogs();
        sortByDialogOrder(videoDialogs);
        if (pinsChanged || !oldDialogs.equals(dialogs)) {
            notifyBrowseTree(MEDIA_ID_LIBRARY);
        }
        if (pinsChanged || !oldVideoDialogs.equals(videoDialogs)) {
            notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
        }
    }

    /** Registers the connected media browser so the audio tree refreshes as messages change. */
    public void setBrowseTreeListener(@Nullable BrowseTreeListener listener) {
        browseTreeListener = listener;
        if (listener == null) {
            // A pause caused by the car disconnecting must not be remembered as the user's choice.
            AndroidUtilities.cancelRunOnUIThread(savePausedState);
            carSessionRestored = false;
        }
        updateMessageObservers();
    }

    /**
     * Pauses are stored with a delay: disconnecting from the car pauses playback too, and that
     * pause is dropped when the car goes away first, so the next connection resumes playing.
     */
    private static final long PAUSE_STATE_DELAY_MS = 5000;
    private final Runnable savePausedState = () -> {
        boolean connected = isCarConnected();
        Log.d(CAR_LOG_TAG, "paused state: car connected=" + connected);
        if (connected) {
            CarPlaybackStore.setWasPlaying(currentAccount, false);
        }
    };

    private static final Uri CAR_CONNECTION_URI = Uri.parse("content://androidx.car.app.connection");

    /** Asks Android Auto whether a car is connected, like androidx.car.app.connection.CarConnection. */
    private boolean isCarConnected() {
        try (android.database.Cursor cursor = appContext.getContentResolver().query(CAR_CONNECTION_URI,
                new String[]{"CarConnectionState"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) != 0;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }
    private boolean carSessionRestored;
    @Nullable
    private Boolean lastRememberedPaused;
    private boolean carConnected;
    private long carConnectedAt;
    /** Set on car connect when playback was paused last time; swallows Android Auto's autoplay. */
    private boolean blockAutoplay;
    private int autoplayBlocked;
    // Android Auto retries its autoplay a few seconds later when playback did not start.
    private static final long AUTOPLAY_WINDOW_MS = 12000;
    private static final int MAX_AUTOPLAY_RETRIES = 3;

    private void checkCarConnection() {
        boolean connected = isCarConnected();
        if (connected == carConnected) {
            return;
        }
        carConnected = connected;
        Log.d(CAR_LOG_TAG, "car connected=" + connected);
        if (connected) {
            carConnectedAt = SystemClock.elapsedRealtime();
            restoreCarSession();
        } else {
            AndroidUtilities.cancelRunOnUIThread(savePausedState);
            carSessionRestored = false;
            blockAutoplay = false;
        }
    }

    /**
     * Android Auto's "start media automatically" option plays the last media app on connect.
     * When playback was paused at the end of the last drive, that first automatic play is
     * ignored so the item stays paused.
     */
    private boolean consumeAutoplay() {
        if (!blockAutoplay) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - carConnectedAt > AUTOPLAY_WINDOW_MS || ++autoplayBlocked > MAX_AUTOPLAY_RETRIES) {
            blockAutoplay = false;
            return false;
        }
        Log.d(CAR_LOG_TAG, "ignoring automatic play on connect; last state was paused");
        if (MediaController.getInstance().getPlayingMessageObject() == null) {
            showLastPlayed();
        } else if (lastPlayerState != null) {
            session.setPlaybackState(withFavoriteAction(lastPlayerState));
        }
        return true;
    }

    private void rememberPlayState() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        boolean paused = MediaController.getInstance().isMessagePaused();
        if (Boolean.valueOf(paused).equals(lastRememberedPaused)) {
            return;
        }
        lastRememberedPaused = paused;
        AndroidUtilities.cancelRunOnUIThread(savePausedState);
        if (paused) {
            AndroidUtilities.runOnUIThread(savePausedState, PAUSE_STATE_DELAY_MS);
        } else {
            CarPlaybackStore.setWasPlaying(currentAccount, true);
        }
    }

    /**
     * Called when the car connects: resumes the last item if it was playing at the end of the
     * previous drive, otherwise shows it paused on the media card.
     */
    public void restoreCarSession() {
        if (carSessionRestored) {
            return;
        }
        carSessionRestored = true;
        boolean wasPlaying = CarPlaybackStore.wasPlaying(currentAccount);
        blockAutoplay = !wasPlaying;
        autoplayBlocked = 0;
        carConnectedAt = SystemClock.elapsedRealtime();
        Log.d(CAR_LOG_TAG, "restoring car session, was playing=" + wasPlaying);
        if (MediaController.getInstance().getPlayingMessageObject() != null) {
            return;
        }
        if (wasPlaying) {
            AndroidUtilities.runOnUIThread(() -> {
                if (MediaController.getInstance().getPlayingMessageObject() == null && !resumeLastPlayed()) {
                    showLastPlayed();
                }
            }, 1000);
        } else {
            showLastPlayed();
        }
    }

    private void updateMessageObservers() {
        int target = browseTreeListener != null ? currentAccount : -1;
        if (target == observedAccount) {
            return;
        }
        int[] events = {NotificationCenter.didReceiveNewMessages, NotificationCenter.messagesDeleted, NotificationCenter.historyCleared,
                NotificationCenter.dialogsNeedReload, NotificationCenter.updateInterfaces, NotificationCenter.dialogDeleted,
                NotificationCenter.removeAllMessagesFromDialog, NotificationCenter.messagePlayingProgressDidChanged,
                NotificationCenter.messagePlayingPlayStateChanged, NotificationCenter.messagePlayingDidStart};
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
        notifyIfChanged(parentMediaId);
        // Pages of a list are separate browse nodes; refresh the ones the car has opened.
        TelegramMediaSession instance = peekInstance();
        if (instance != null) {
            String pagePrefix = parentMediaId + PAGE_SEPARATOR;
            for (String sent : new ArrayList<>(instance.sentSignatures.keySet())) {
                if (sent.startsWith(pagePrefix)) {
                    notifyIfChanged(sent);
                }
            }
        }
    }

    /**
     * The car reloads a list and resets its scroll position on every change notification, so a
     * list is only refreshed when the rows it was last given actually differ.
     */
    private static void notifyIfChanged(String parentMediaId) {
        BrowseTreeListener listener = browseTreeListener;
        if (listener == null) {
            return;
        }
        TelegramMediaSession instance = peekInstance();
        if (instance != null) {
            String sent = instance.sentSignatures.get(parentMediaId);
            if (sent != null && sent.equals(signatureOf(instance.loadChildrenSync(parentMediaId)))) {
                return;
            }
        }
        listener.onChildrenChanged(parentMediaId);
    }

    private final HashMap<String, String> sentSignatures = new HashMap<>();

    private static String signatureOf(@Nullable List<MediaBrowser.MediaItem> items) {
        if (items == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (MediaBrowser.MediaItem item : items) {
            android.media.MediaDescription description = item.getDescription();
            builder.append(description.getMediaId()).append('\u0001')
                    .append(description.getTitle()).append('\u0001')
                    .append(description.getSubtitle()).append('\u0001')
                    .append(description.getIconUri()).append('\u0002');
        }
        return builder.toString();
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
        return isCarAudioDocument(messageObject);
    }

    // Formats the car player (ExoPlayer) decodes without extensions.
    private static final Set<String> CAR_AUDIO_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mka", "weba"));
    private static final Set<String> CAR_AUDIO_MIME_TYPES = new HashSet<>(java.util.Arrays.asList(
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg", "audio/mp4", "audio/m4a", "audio/x-m4a",
            "audio/aac", "audio/x-aac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/flac",
            "audio/x-flac", "audio/ogg", "audio/opus", "audio/amr", "audio/x-matroska", "audio/webm"));

    /**
     * Audio sent as a plain file (no audio attribute): Telegram shows it as a document and opens
     * it in an external player, but the car player can play it.
     */
    public static boolean isCarAudioDocument(MessageObject messageObject) {
        if (messageObject == null || !messageObject.isDocument() || messageObject.isRoundVideo()
                || messageObject.isSecretMedia()) {
            return false;
        }
        TLRPC.Document document = messageObject.getDocument();
        String name = FileLoader.getDocumentFileName(document);
        int dot = name != null ? name.lastIndexOf('.') : -1;
        if (dot >= 0 && CAR_AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.US))) {
            return true;
        }
        return document.mime_type != null && CAR_AUDIO_MIME_TYPES.contains(document.mime_type.toLowerCase(Locale.US));
    }

    private static int getCarKind(MessageObject messageObject) {
        return messageObject.isMusic() ? 0 : isCarVideo(messageObject) ? 2 : isCarAudioDocument(messageObject) ? 3 : 1;
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
        loadBrowseChildren(MEDIA_ID_LIBRARY, items -> {
            if (items == null) {
                reportPlaybackError("Android Auto: could not load the audio catalog");
            } else if (onLoaded != null) {
                onLoaded.run();
            }
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

    /**
     * The car shows a spinner until a list is answered and does not retry by itself, so a list
     * that is still loading (e.g. while Telegram's database is busy right after start) is
     * answered with what is known after this delay and refreshed once the real result arrives.
     */
    private static final long BROWSE_ANSWER_TIMEOUT_MS = 4000;

    public void loadBrowseChildren(String parentMediaId, BrowseChildrenCallback callback) {
        final long start = SystemClock.elapsedRealtime();
        final boolean[] answered = new boolean[1];
        Runnable timeout = () -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            List<MediaBrowser.MediaItem> items = loadChildrenSync(parentMediaId);
            Log.w(CAR_LOG_TAG, "browse " + parentMediaId + " timed out, answering " + items.size() + " known items");
            sentSignatures.put(parentMediaId, signatureOf(items));
            callback.onResult(items);
        };
        AndroidUtilities.runOnUIThread(timeout, BROWSE_ANSWER_TIMEOUT_MS);
        loadBrowseChildrenInternal(parentMediaId, items -> {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (answered[0]) {
                // The car already got a provisional list; refresh it if the real one differs.
                notifyIfChanged(parentMediaId);
                return;
            }
            answered[0] = true;
            if (items != null) {
                sentSignatures.put(parentMediaId, signatureOf(items));
            } else {
                sentSignatures.remove(parentMediaId);
            }
            if (BuildVars.LOGS_ENABLED) {
                Log.d(CAR_LOG_TAG, "browse " + parentMediaId + " answered " + (items != null ? items.size() : -1)
                        + " items in " + (SystemClock.elapsedRealtime() - start) + "ms");
            }
            callback.onResult(items);
        });
    }

    private void loadBrowseChildrenInternal(String parentMediaId, BrowseChildrenCallback callback) {
        if (MEDIA_ID_ROOT.equals(parentMediaId) || MEDIA_ID_RESUME.equals(parentMediaId) || parentMediaId.startsWith(MEDIA_ID_FAVORITES)
                || parentMediaId.startsWith(MEDIA_ID_RECENT)) {
            callback.onResult(loadChildrenSync(parentMediaId));
            return;
        }
        if (!chatsLoaded) {
            pendingBrowseRequests.add(new PendingBrowseRequest(parentMediaId, callback));
            loadChats();
            return;
        }

        long did = getDialogIdFromMediaId(parentMediaId);
        if (did != 0 && audioObjects.get(did) == null) {
            loadAudioForDialog(did, () -> {
                if (audioObjects.get(did) == null) {
                    callback.onResult(loadChildrenSync(parentMediaId));
                } else {
                    loadBrowseChildren(parentMediaId, callback);
                }
            });
            return;
        }
        if (did != 0 && parentMediaId.startsWith(MEDIA_ID_VIDEO_CHAT_PREFIX) && !videoLoadedDialogs.contains(did)) {
            loadVideosForDialog(did, () -> callback.onResult(loadChildrenSync(parentMediaId)));
            return;
        }
        if (parentMediaId.startsWith(MEDIA_ID_VIDEO_LIBRARY) && !videoDialogsLoaded) {
            pendingVideoBrowseRequests.add(new PendingBrowseRequest(parentMediaId, callback));
            scanVideoDialogs();
            return;
        }

        if (did != 0) {
            int offset = getPageOffset(parentMediaId);
            if (!parentMediaId.startsWith(MEDIA_ID_VIDEO_CHAT_PREFIX)) {
                // One extra item tells whether an "older" folder is needed.
                ensureCloudItems(did, offset + BROWSE_PAGE_SIZE + 1,
                        () -> callback.onResult(loadChildrenSync(parentMediaId)),
                        () -> callback.onResult(new ArrayList<>()));
                return;
            }
        }
        callback.onResult(loadChildrenSync(parentMediaId));
    }

    /**
     * Fetches server pages until the chat holds at least {@code needed} items, the server is
     * exhausted, or a timeout passes. Browsing is never blocked longer than the timeout.
     */
    private void ensureCloudItems(long did, int needed, Runnable onReady, @Nullable Runnable onCancelled) {
        ArrayList<MessageObject> messages = audioObjects.get(did);
        if (messages == null || DialogObject.isEncryptedDialog(did)) {
            onReady.run();
            return;
        }
        CloudAudioLoad load = cloudAudioLoads.get(did);
        if (load == null) {
            load = new CloudAudioLoad();
            cloudAudioLoads.put(did, load);
        }
        load.target = Math.max(load.target, needed);
        if (load.exhausted() || load.firstPageDone && messages.size() >= needed) {
            onReady.run();
            loadCloudAudio(did);
            return;
        }
        final CloudAudioLoad state = load;
        final int account = currentAccount;
        final class Waiter implements Runnable {
            private boolean done;

            @Override
            public void run() {
                if (done) {
                    return;
                }
                done = true;
                state.waiters.remove(this);
                AndroidUtilities.cancelRunOnUIThread(this);
                if (account == currentAccount) {
                    onReady.run();
                } else if (onCancelled != null) {
                    onCancelled.run();
                }
            }
        }
        Waiter waiter = new Waiter();
        state.waiters.add(waiter);
        AndroidUtilities.runOnUIThread(waiter, CLOUD_WAIT_TIMEOUT_MS);
        loadCloudAudio(did);
    }

    /** Orders chats like Telegram's chat list: pinned chats first, then by last message date. */
    private static void sortByChatListOrder(MessagesStorage messagesStorage, ArrayList<Long> dialogIds) {
        if (dialogIds.size() < 2) {
            return;
        }
        HashMap<Long, Integer> order = new HashMap<>();
        try {
            SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT did FROM dialogs WHERE did IN (%s) ORDER BY " + PINNED_ORDER, TextUtils.join(",", dialogIds)));
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
            LongSparseArray<Integer> loadedAudioCounts = new LongSparseArray<>();
            LongSparseArray<TLRPC.User> loadedUsers = new LongSparseArray<>();
            LongSparseArray<TLRPC.Chat> loadedChats = new LongSparseArray<>();
            LongSparseArray<Integer> loadedDialogOrder = new LongSparseArray<>();
            ArrayList<Long> channelDialogs = new ArrayList<>();
            Set<Long> loadedPinned = new HashSet<>();
            boolean loadSucceeded = false;
            try {
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                // Counting rows avoids deserializing every voice message; videos are found later by scanVideoDialogs.
                SQLiteCursor cursor = messagesStorage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT uid, COUNT(*) FROM media_v4 WHERE uid != 0 AND mid > 0 AND type IN (%d, %d) GROUP BY uid",
                        MediaDataController.MEDIA_MUSIC, MediaDataController.MEDIA_AUDIO));
                while (cursor.next()) {
                    long dialogId = cursor.longValue(0);
                    if (DialogObject.isEncryptedDialog(dialogId)) {
                        continue;
                    }
                    loadedDialogs.add(dialogId);
                    loadedAudioCounts.put(dialogId, cursor.intValue(1));
                    if (DialogObject.isUserDialog(dialogId)) {
                        usersToLoad.add(dialogId);
                    } else {
                        chatsToLoad.add(-dialogId);
                    }
                }
                cursor.dispose();

                sortByChatListOrder(messagesStorage, loadedDialogs);

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
                cursor = messagesStorage.getDatabase().queryFinalized("SELECT did, pinned, folder_id FROM dialogs ORDER BY " + PINNED_ORDER);
                ArrayList<Long> channelIds = new ArrayList<>();
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    loadedDialogOrder.put(did, loadedDialogOrder.size());
                    if (cursor.intValue(1) > 0 && cursor.intValue(2) == 0) {
                        loadedPinned.add(did);
                    }
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
                loadSucceeded = true;
            } catch (Exception e) {
                FileLog.e(e);
            }

            final boolean succeeded = loadSucceeded;
            AndroidUtilities.runOnUIThread(() -> {
                if (account != currentAccount) {
                    return;
                }
                if (revision != audioCatalogRevision) {
                    loadingChats = false;
                    loadChats();
                    return;
                }
                if (!succeeded) {
                    loadingChats = false;
                    ArrayList<PendingBrowseRequest> requests = new ArrayList<>(pendingBrowseRequests);
                    pendingBrowseRequests.clear();
                    for (PendingBrowseRequest request : requests) {
                        if (chatsLoaded) {
                            loadBrowseChildren(request.parentMediaId, request.callback);
                        } else {
                            request.callback.onResult(null);
                        }
                    }
                    ArrayList<Runnable> callbacks = new ArrayList<>(pendingCatalogCallbacks);
                    pendingCatalogCallbacks.clear();
                    for (Runnable callback : callbacks) {
                        callback.run();
                    }
                    return;
                }
                ArrayList<Long> oldDialogs = new ArrayList<>(dialogs);
                ArrayList<Long> oldVideoDialogs = new ArrayList<>(videoDialogs);
                Set<Long> oldPinned = new HashSet<>(pinnedDialogs);
                boolean firstLoad = !chatsLoaded;
                dialogs.clear();
                dialogs.addAll(loadedDialogs);
                audioCounts.clear();
                for (int i = 0; i < loadedAudioCounts.size(); i++) {
                    audioCounts.put(loadedAudioCounts.keyAt(i), loadedAudioCounts.valueAt(i));
                }
                // Keep channels found through server counters so a reload does not hide them until rediscovered.
                for (long channelDid : channelDialogs) {
                    Integer discovered = discoveredChannelCounts.get(channelDid);
                    if (discovered != null && discovered > 0 && !dialogs.contains(channelDid)) {
                        dialogs.add(channelDid);
                        audioCounts.put(channelDid, discovered);
                    }
                }
                for (long fileDid : fileAudioDialogs) {
                    if (!dialogs.contains(fileDid)) {
                        dialogs.add(fileDid);
                    }
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
                pinnedDialogs.clear();
                pinnedDialogs.addAll(loadedPinned);
                sortAudioDialogs();
                sortByDialogOrder(videoDialogs);
                applyLiveDialogOrder();
                ArrayList<TLRPC.User> cachedUsers = new ArrayList<>();
                for (int i = 0; i < loadedUsers.size(); i++) {
                    cachedUsers.add(loadedUsers.valueAt(i));
                }
                MessagesController.getInstance(account).putUsers(cachedUsers, true);
                MessagesController.getInstance(account).putChats(toChatList(loadedChats), true);
                boolean rediscover = channelRediscoveryRequested;
                channelRediscoveryRequested = false;
                discoverChannelAudio(channelDialogs, 0, account, revision, rediscover);
                boolean pinsChanged = !oldPinned.equals(pinnedDialogs);
                if (firstLoad || pinsChanged || !oldDialogs.equals(dialogs)) {
                    notifyBrowseTree(MEDIA_ID_LIBRARY);
                }
                if (videoDialogsLoaded && (pinsChanged || !oldVideoDialogs.equals(videoDialogs))) {
                    notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
                }
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
                if (!videoDialogsLoaded || videoRescanRequested) {
                    scanVideoDialogs();
                }
                scanFileDialogs();
                if (chatsReloadRequested) {
                    chatsReloadRequested = false;
                    loadChats();
                }
            });
        });
    }

    private void rebuildVideoDialogs() {
        videoDialogs.clear();
        videoDialogs.addAll(photoVideoDialogs);
        sortByDialogOrder(videoDialogs);
    }

    /** Finds chats that only have audio files, one chat per storage task, once per catalog load. */
    private void scanFileDialogs() {
        if (scanningFileDialogs || fileDialogsScanned) {
            return;
        }
        scanningFileDialogs = true;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        final Set<Long> known = new HashSet<>(dialogs);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<Long> candidates = new ArrayList<>();
            try {
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT DISTINCT uid FROM media_v4 WHERE uid != 0 AND mid > 0 AND type = %d",
                        MediaDataController.MEDIA_FILE));
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    if (!DialogObject.isEncryptedDialog(did) && !known.contains(did)) {
                        candidates.add(did);
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            scanFileDialogsStep(storage, account, revision, candidates, 0, new HashSet<>());
        });
    }

    private void scanFileDialogsStep(MessagesStorage storage, int account, int revision, ArrayList<Long> candidates,
                                     int index, Set<Long> found) {
        if (index < candidates.size()) {
            long did = candidates.get(index);
            if (!readCarMessages(storage, account, did, FILE_TYPES, false, true).isEmpty()) {
                found.add(did);
            }
            storage.getStorageQueue().postRunnable(() -> scanFileDialogsStep(storage, account, revision, candidates, index + 1, found));
            return;
        }
        LongSparseArray<TLRPC.User> loadedUsers = new LongSparseArray<>();
        LongSparseArray<TLRPC.Chat> loadedChats = new LongSparseArray<>();
        readPeers(storage, found, loadedUsers, loadedChats);
        AndroidUtilities.runOnUIThread(() -> {
            if (account != currentAccount || revision != audioCatalogRevision) {
                return;
            }
            scanningFileDialogs = false;
            fileDialogsScanned = true;
            applyPeers(account, loadedUsers, loadedChats);
            fileAudioDialogs.addAll(found);
            boolean changed = false;
            for (long did : found) {
                if (!dialogs.contains(did)) {
                    dialogs.add(did);
                    changed = true;
                }
            }
            if (changed) {
                sortAudioDialogs();
                if (lastSelectedDialog == 0) {
                    lastSelectedDialog = dialogs.get(0);
                }
                notifyBrowseTree(MEDIA_ID_LIBRARY);
            }
        });
    }

    private static void readPeers(MessagesStorage storage, Set<Long> dialogIds,
                                  LongSparseArray<TLRPC.User> loadedUsers, LongSparseArray<TLRPC.Chat> loadedChats) {
        try {
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            for (long did : dialogIds) {
                if (DialogObject.isUserDialog(did)) {
                    usersToLoad.add(did);
                } else {
                    chatsToLoad.add(-did);
                }
            }
            if (!usersToLoad.isEmpty()) {
                ArrayList<TLRPC.User> result = new ArrayList<>();
                storage.getUsersInternal(usersToLoad, result);
                for (TLRPC.User user : result) {
                    loadedUsers.put(user.id, user);
                }
            }
            if (!chatsToLoad.isEmpty()) {
                ArrayList<TLRPC.Chat> result = new ArrayList<>();
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), result);
                for (TLRPC.Chat chat : result) {
                    loadedChats.put(chat.id, chat);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyPeers(int account, LongSparseArray<TLRPC.User> loadedUsers, LongSparseArray<TLRPC.Chat> loadedChats) {
        ArrayList<TLRPC.User> userList = new ArrayList<>();
        for (int i = 0; i < loadedUsers.size(); i++) {
            users.put(loadedUsers.keyAt(i), loadedUsers.valueAt(i));
            userList.add(loadedUsers.valueAt(i));
        }
        for (int i = 0; i < loadedChats.size(); i++) {
            chats.put(loadedChats.keyAt(i), loadedChats.valueAt(i));
        }
        MessagesController.getInstance(account).putUsers(userList, true);
        MessagesController.getInstance(account).putChats(toChatList(loadedChats), true);
    }

    /**
     * Finds chats with playable videos one chat per storage task, so opening a chat is not queued
     * behind a scan of every cached photo.
     */
    private void scanVideoDialogs() {
        if (scanningVideoDialogs) {
            videoRescanRequested = true;
            return;
        }
        scanningVideoDialogs = true;
        videoRescanRequested = false;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<Long> candidates = new ArrayList<>();
            try {
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT DISTINCT uid FROM media_v4 WHERE uid != 0 AND mid > 0 AND type IN (%d, %d)",
                        MediaDataController.MEDIA_PHOTOVIDEO, MediaDataController.MEDIA_AUDIO));
                while (cursor.next()) {
                    long did = cursor.longValue(0);
                    if (!DialogObject.isEncryptedDialog(did)) {
                        candidates.add(did);
                    }
                }
                cursor.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            scanVideoDialogsStep(storage, account, revision, candidates, 0, new HashSet<>());
        });
    }

    private void scanVideoDialogsStep(MessagesStorage storage, int account, int revision, ArrayList<Long> candidates,
                                      int index, Set<Long> found) {
        if (index < candidates.size()) {
            long did = candidates.get(index);
            if (!readCarMessages(storage, account, did, VIDEO_TYPES, true, true).isEmpty()) {
                found.add(did);
            }
            storage.getStorageQueue().postRunnable(() -> scanVideoDialogsStep(storage, account, revision, candidates, index + 1, found));
            return;
        }
        LongSparseArray<TLRPC.User> loadedUsers = new LongSparseArray<>();
        LongSparseArray<TLRPC.Chat> loadedChats = new LongSparseArray<>();
        readPeers(storage, found, loadedUsers, loadedChats);
        AndroidUtilities.runOnUIThread(() -> {
            if (account != currentAccount || revision != audioCatalogRevision) {
                return;
            }
            scanningVideoDialogs = false;
            applyPeers(account, loadedUsers, loadedChats);
            ArrayList<Long> oldVideoDialogs = new ArrayList<>(videoDialogs);
            boolean firstScan = !videoDialogsLoaded;
            photoVideoDialogs.clear();
            photoVideoDialogs.addAll(found);
            rebuildVideoDialogs();
            videoDialogsLoaded = true;
            if (firstScan || !oldVideoDialogs.equals(videoDialogs)) {
                notifyBrowseTree(MEDIA_ID_VIDEO_LIBRARY);
            }
            ArrayList<PendingBrowseRequest> requests = new ArrayList<>(pendingVideoBrowseRequests);
            pendingVideoBrowseRequests.clear();
            for (PendingBrowseRequest request : requests) {
                request.callback.onResult(loadChildrenSync(request.parentMediaId));
            }
            if (videoRescanRequested) {
                scanVideoDialogs();
            }
        });
    }

    /**
     * Reads playable messages of one shared-media type for a chat. With firstOnly it stops at the
     * first match, which is enough to decide whether a chat belongs in a list.
     */
    private static final String AUDIO_TYPES = MediaDataController.MEDIA_MUSIC + "," + MediaDataController.MEDIA_AUDIO
            + "," + MediaDataController.MEDIA_FILE;
    private static final String FILE_TYPES = String.valueOf(MediaDataController.MEDIA_FILE);
    private static final String PHOTOVIDEO_TYPES = String.valueOf(MediaDataController.MEDIA_PHOTOVIDEO);
    private static final String VIDEO_TYPES = MediaDataController.MEDIA_PHOTOVIDEO + "," + MediaDataController.MEDIA_AUDIO;

    private static ArrayList<MessageObject> readCarMessages(MessagesStorage storage, int account, long did, String types,
                                                            boolean videoOnly, boolean firstOnly) {
        ArrayList<MessageObject> result = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT data, mid, type FROM media_v4 WHERE uid = %d AND mid > 0 AND type IN (%s) ORDER BY date DESC, mid DESC", did, types));
            long clientUserId = UserConfig.getInstance(account).clientUserId;
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
                message.readAttachPath(data, clientUserId);
                data.reuse();
                if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                    continue;
                }
                message.id = cursor.intValue(1);
                message.dialog_id = did;
                MessageObject messageObject = new MessageObject(account, message, false, false);
                // Files are only audio files; a video sent as a file is not listed.
                boolean file = cursor.intValue(2) == MediaDataController.MEDIA_FILE;
                if (file ? isCarAudioDocument(messageObject)
                        : isCarPlayable(messageObject) && (!videoOnly || isCarVideo(messageObject))) {
                    result.add(messageObject);
                    if (firstOnly) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /** Loads a chat's videos only when its Videos list is opened. */
    private void loadVideosForDialog(long did, Runnable onLoaded) {
        if (videoLoadedDialogs.contains(did)) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }
        ArrayList<Runnable> callbacks = pendingVideoLoads.get(did);
        if (callbacks != null) {
            if (onLoaded != null) {
                callbacks.add(onLoaded);
            }
            return;
        }
        final ArrayList<Runnable> waiting = new ArrayList<>();
        if (onLoaded != null) {
            waiting.add(onLoaded);
        }
        pendingVideoLoads.put(did, waiting);
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        final int generation = deletionGeneration;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> videos = readCarMessages(storage, account, did, PHOTOVIDEO_TYPES, true, false);
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<MessageObject> messages = audioObjects.get(did);
                if (account == currentAccount && revision == audioCatalogRevision && messages != null) {
                    pendingVideoLoads.remove(did);
                    videoLoadedDialogs.add(did);
                    if (generation != deletionGeneration) {
                        reloadDialog(did);
                    } else if (mergeMessages(messages, videos)) {
                        audioQueues.put(did, createAudioQueue(did, messages));
                        invalidateSearchIndex();
                        notifyDialogChanged(did, false);
                    }
                }
                for (Runnable callback : waiting) {
                    callback.run();
                }
            });
        });
    }

    private static boolean mergeMessages(ArrayList<MessageObject> target, ArrayList<MessageObject> source) {
        Set<Integer> known = new HashSet<>();
        for (MessageObject message : target) {
            known.add(message.getId());
        }
        boolean changed = false;
        for (MessageObject message : source) {
            if (known.add(message.getId())) {
                target.add(message);
                changed = true;
            }
        }
        return changed;
    }

    /** Re-reads a loaded chat from the database and swaps its list in place. */
    private void reloadDialog(long did) {
        if (audioObjects.get(did) == null || pendingAudioLoads.get(did) != null) {
            scheduleCatalogRevalidate();
            return;
        }
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        final boolean withVideos = videoLoadedDialogs.contains(did);
        final int generation = deletionGeneration;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> loaded = readDialogAudio(storage, account, did);
            if (withVideos) {
                loaded.addAll(readCarMessages(storage, account, did, PHOTOVIDEO_TYPES, true, false));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (account != currentAccount || revision != audioCatalogRevision || audioObjects.get(did) == null) {
                    return;
                }
                if (generation != deletionGeneration) {
                    reloadDialog(did);
                    return;
                }
                audioObjects.put(did, loaded);
                audioQueues.put(did, createAudioQueue(did, loaded));
                invalidateSearchIndex();
                notifyDialogChanged(did, true);
                scheduleCatalogRevalidate();
            });
        });
    }

    private static ArrayList<MessageObject> readDialogAudio(MessagesStorage storage, int account, long did) {
        return readCarMessages(storage, account, did, AUDIO_TYPES, false, false);
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

        readAudioForDialog(did, callbacks);
    }

    private void readAudioForDialog(long did, ArrayList<Runnable> waiting) {
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        final int generation = deletionGeneration;
        MessagesStorage messagesStorage = MessagesStorage.getInstance(account);
        messagesStorage.getStorageQueue().postRunnable(() -> {
            ArrayList<MessageObject> arrayList = readDialogAudio(messagesStorage, account, did);
            ArrayList<MediaSessionCompat.QueueItem> queueList = createAudioQueue(did, arrayList);
            AndroidUtilities.runOnUIThread(() -> {
                boolean current = account == currentAccount && revision == audioCatalogRevision;
                if (current && generation != deletionGeneration) {
                    readAudioForDialog(did, waiting);
                    return;
                }
                if (current) {
                    audioObjects.put(did, arrayList);
                    audioQueues.put(did, queueList);
                    pendingAudioLoads.remove(did);
                    if (did == lastSelectedDialog) {
                        applyQueueFor(did);
                    }
                }
                // Always answer waiting browse requests so Android Auto never stays on a spinner.
                for (int i = 0; i < waiting.size(); i++) {
                    waiting.get(i).run();
                }
                if (current) {
                    loadCloudAudio(did);
                }
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
    private void discoverChannelAudio(ArrayList<Long> channelDialogs, int index, int account, int revision, boolean force) {
        if (account != currentAccount || revision != audioCatalogRevision || index >= channelDialogs.size()) {
            return;
        }
        long did = channelDialogs.get(index);
        if (!force && discoveredChannelCounts.get(did) != null) {
            discoverChannelAudio(channelDialogs, index + 1, account, revision, force);
            return;
        }
        TLRPC.TL_messages_getSearchCounters request = new TLRPC.TL_messages_getSearchCounters();
        request.peer = MessagesController.getInstance(account).getInputPeer(did);
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) {
            FileLog.e("Android Auto: missing channel peer " + did);
            discoverChannelAudio(channelDialogs, index + 1, account, revision, force);
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
                        discoveredChannelCounts.put(did, count);
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
                    // Spaced out so a reconnect does not trigger FLOOD_WAIT for the whole account.
                    AndroidUtilities.runOnUIThread(() -> discoverChannelAudio(channelDialogs, index + 1, account, revision, force), 300);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    private void sortAudioDialogs() {
        sortByDialogOrder(dialogs);
    }

    private void sortByDialogOrder(ArrayList<Long> list) {
        Collections.sort(list, (a, b) -> {
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
        ArrayList<MessageObject> current = audioObjects.get(did);
        if (load.loading || load.retryScheduled) {
            return;
        }
        if (load.exhausted() || load.firstPageDone && current.size() >= load.target) {
            flushCloudWaiters(load);
            return;
        }
        final CloudAudioLoad state = load;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        requestCloudCounts(did, state);
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = MessagesController.getInstance(account).getInputPeer(did);
        if (request.peer == null || request.peer instanceof TLRPC.TL_inputPeerEmpty) {
            FileLog.e("Android Auto: missing audio peer " + did);
            state.filterIndex = CLOUD_FILTER_COUNT;
            flushCloudWaiters(state);
            return;
        }
        request.q = "";
        request.filter = state.filterIndex == 0 ? new TLRPC.TL_inputMessagesFilterMusic()
                : state.filterIndex == CLOUD_FILTER_FILES ? new TLRPC.TL_inputMessagesFilterDocument()
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
                    if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                        int seconds = Utilities.parseInt(error.text);
                        state.retryScheduled = true;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (account == currentAccount && revision == audioCatalogRevision) {
                                state.retryScheduled = false;
                                loadCloudAudio(did);
                            }
                        }, Math.max(1, seconds) * 1000L + 500);
                        return;
                    }
                    if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                        FileLog.e("Android Auto: audio page failed for " + did + ": "
                                + (error != null ? error.text : "invalid response"));
                        flushCloudWaiters(state);
                        if (pendingPlayDialog == did) {
                            cancelPendingPlay();
                            reportPlaybackError("Android Auto: could not load audio");
                        }
                        return;
                    }
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    boolean files = state.filterIndex == CLOUD_FILTER_FILES;
                    if (state.offsetId == 0 && !files) {
                        state.serverCounts[state.filterIndex] = Math.max(result.count, result.messages.size());
                    }
                    MessagesController controller = MessagesController.getInstance(account);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    MessagesStorage.getInstance(account).putUsersAndChats(result.users, result.chats, true, true);
                    ArrayList<MessageObject> messages = audioObjects.get(did);
                    if (messages == null) {
                        flushCloudWaiters(state);
                        return;
                    }
                    state.firstPageDone = true;
                    boolean wasEmpty = messages.isEmpty();
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
                                state.filterIndex == 0 ? MediaDataController.MEDIA_MUSIC
                                        : files ? MediaDataController.MEDIA_FILE : MediaDataController.MEDIA_AUDIO,
                                result.messages, state.offsetId, 0, serverPageEmpty);
                    }
                    boolean changed = false;
                    for (TLRPC.Message message : result.messages) {
                        message.dialog_id = did;
                        MessageObject object = new MessageObject(account, message, false, false);
                        boolean playable = files ? isCarAudioDocument(object) : isCarPlayable(object);
                        if (playable && knownIds.add(message.id)) {
                            messages.add(object);
                            changed = true;
                        }
                    }
                    if (changed) {
                        sortByDateDesc(messages);
                        audioQueues.put(did, createAudioQueue(did, messages));
                        invalidateSearchIndex();
                        notifyDialogChanged(did, wasEmpty);
                    }
                    if (files) {
                        state.emptyFilePages = changed ? 0 : state.emptyFilePages + 1;
                    }
                    // A short page need not be the last one; continue until the server is exhausted.
                    if (serverPageEmpty || nextOffset == Integer.MAX_VALUE
                            || state.offsetId != 0 && nextOffset >= state.offsetId
                            || files && state.emptyFilePages >= MAX_EMPTY_FILE_PAGES) {
                        state.filterIndex++;
                        state.offsetId = 0;
                    } else {
                        state.offsetId = nextOffset;
                    }
                    if (pendingPlayDialog == did) {
                        if (!messages.isEmpty()) {
                            cancelPendingPlay();
                            playAudio(did, 0);
                        } else if (state.exhausted()) {
                            cancelPendingPlay();
                            reportPlaybackError("Android Auto: no playable audio in this chat");
                        }
                    }
                    loadCloudAudio(did);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    /** One cheap request that tells how many pages a chat has before they are downloaded. */
    private void requestCloudCounts(long did, CloudAudioLoad state) {
        if (state.countsRequested) {
            return;
        }
        TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(did);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            return;
        }
        state.countsRequested = true;
        final int account = currentAccount;
        final int revision = audioCatalogRevision;
        TLRPC.TL_messages_getSearchCounters request = new TLRPC.TL_messages_getSearchCounters();
        request.peer = peer;
        request.filters.add(new TLRPC.TL_inputMessagesFilterMusic());
        request.filters.add(new TLRPC.TL_inputMessagesFilterRoundVoice());
        final int[] requestId = new int[1];
        requestId[0] = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (account != currentAccount || revision != audioCatalogRevision) {
                        return;
                    }
                    cloudRequestIds.remove(requestId[0]);
                    if (!(response instanceof Vector)) {
                        state.countsRequested = false;
                        return;
                    }
                    for (Object object : ((Vector) response).objects) {
                        if (!(object instanceof TLRPC.TL_messages_searchCounter)) {
                            continue;
                        }
                        TLRPC.TL_messages_searchCounter counter = (TLRPC.TL_messages_searchCounter) object;
                        int index = counter.filter instanceof TLRPC.TL_inputMessagesFilterMusic ? 0
                                : counter.filter instanceof TLRPC.TL_inputMessagesFilterRoundVoice ? 1 : -1;
                        if (index >= 0 && state.serverCounts[index] < 0) {
                            state.serverCounts[index] = counter.count;
                        }
                    }
                    notifyDialogChanged(did, false);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    /** Number of audio items a chat has, including ones not downloaded from the server yet. */
    private int getAudioTotal(long did, int loaded) {
        CloudAudioLoad load = cloudAudioLoads.get(did);
        if (load == null || load.exhausted()) {
            return loaded;
        }
        int server = Math.max(load.serverCounts[0], 0) + Math.max(load.serverCounts[1], 0);
        if (load.serverCounts[0] < 0 && load.serverCounts[1] < 0) {
            // Totals unknown: offer one more page while the server may still have items.
            return loaded >= BROWSE_PAGE_SIZE ? loaded + 1 : loaded;
        }
        int total = Math.max(loaded, server);
        // Audio files have no server count; keep offering a further page while they are scanned.
        if (load.filterIndex == CLOUD_FILTER_FILES && loaded >= total && loaded >= BROWSE_PAGE_SIZE) {
            total = loaded + 1;
        }
        return total;
    }

    private static void flushCloudWaiters(CloudAudioLoad state) {
        ArrayList<Runnable> waiters = new ArrayList<>(state.waiters);
        state.waiters.clear();
        for (Runnable waiter : waiters) {
            waiter.run();
        }
    }

    private static void sortByDateDesc(ArrayList<MessageObject> messages) {
        Collections.sort(messages, (a, b) -> {
            int byDate = Integer.compare(b.messageOwner.date, a.messageOwner.date);
            return byDate != 0 ? byDate : Integer.compare(b.getId(), a.getId());
        });
    }

    /** Chat lists stay newest first so numbered pages keep their items while more pages load. */
    private static ArrayList<MediaSessionCompat.QueueItem> createAudioQueue(long did, ArrayList<MessageObject> messages) {
        sortByDateDesc(messages);
        return createAudioQueue(messages, MEDIA_ID_MESSAGE_PREFIX);
    }

    private static ArrayList<MediaSessionCompat.QueueItem> createAudioQueue(ArrayList<MessageObject> messages, String prefix) {
        ArrayList<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
        for (MessageObject message : messages) {
            MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                    .setMediaId(prefix + message.getDialogId() + "_" + message.getId())
                    .setTitle(getAudioTitle(message))
                    .setSubtitle(withDuration(message.getMusicAuthor(), (int) message.getDuration()))
                    .build();
            // Favorites mix chats, so their queue ids are positions rather than message ids.
            long queueId = MEDIA_ID_MESSAGE_PREFIX.equals(prefix) ? message.getId() : queue.size();
            queue.add(new MediaSessionCompat.QueueItem(description, queueId));
        }
        return queue;
    }

    private void playAudio(long dialogId, int index) {
        playAudio(dialogId, index, false);
    }

    private void playAudio(long dialogId, int index, boolean fromStart) {
        ArrayList<MessageObject> messages = audioObjects.get(dialogId);
        if (messages == null) {
            loadAudioForDialog(dialogId, guarded(() -> playAudio(dialogId, index, fromStart)));
            return;
        }
        if (index < 0 || index >= messages.size()) {
            if (index == 0 && messages.isEmpty()) {
                CloudAudioLoad load = cloudAudioLoads.get(dialogId);
                if (load == null || !load.exhausted()) {
                    cancelPendingPlay();
                    pendingPlayDialog = dialogId;
                    AndroidUtilities.runOnUIThread(pendingPlayTimeout, 15000);
                    loadCloudAudio(dialogId);
                    return;
                }
            }
            reportPlaybackError("Android Auto: no playable audio at the requested position");
            return;
        }

        if (index >= messages.size() - PREFETCH_DISTANCE) {
            ensureCloudItems(dialogId, messages.size() + CLOUD_AUDIO_PAGE_SIZE, () -> {}, null);
        }
        lastSelectedDialog = dialogId;
        MessagesController.getNotificationsSettings(currentAccount).edit()
                .putLong("auto_lastSelectedDialog", dialogId).apply();
        playingSnapshot = null;
        startPlayback(messages, index, audioQueues.get(dialogId), getDialogTitle(dialogId), fromStart);
    }

    private void startPlayback(ArrayList<MessageObject> messages, int index,
                               @Nullable ArrayList<MediaSessionCompat.QueueItem> queue, String queueTitle) {
        startPlayback(messages, index, queue, queueTitle, false);
    }

    /**
     * Plays one item of a list, resuming from where it was last left. With fromStart (next or
     * previous pressed) it starts at the beginning instead.
     */
    private void startPlayback(ArrayList<MessageObject> messages, int index,
                               @Nullable ArrayList<MediaSessionCompat.QueueItem> queue, String queueTitle,
                               boolean fromStart) {
        cancelPendingPlay();
        savePlayingPosition(true);
        MessageObject selected = messages.get(index);
        if (fromStart) {
            // 0 also overrides Telegram's own saved position for long voice and music files.
            selected.resetPlayingProgress();
            selected.forceSeekTo = 0;
        } else {
            float resume = CarPlaybackStore.getPosition(currentAccount, selected.getDialogId(), selected.getId());
            selected.forceSeekTo = resume > 0 ? resume : -1;
        }
        if (!isCarVideo(selected) && !FileLoader.getInstance(currentAccount).getPathToMessage(selected.messageOwner).exists()
                && (TextUtils.isEmpty(selected.messageOwner.attachPath)
                || !new File(selected.messageOwner.attachPath).exists())) {
            FileLoader.getInstance(currentAccount).loadFile(selected.getDocument(), selected,
                    FileLoader.PRIORITY_HIGH, selected.shouldEncryptPhotoOrVideo() ? 2 : 0);
        }
        MediaController controller = MediaController.getInstance();
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
        } else if (selected.isVoice() || isCarVideo(selected) || isCarAudioDocument(selected)) {
            // Voice messages, videos and audio files use the car queue; videos play as audio only.
            controller.setAudioOnlyVideos(playlist);
            controller.setVoiceMessagesPlaylist(playlist, false);
            controller.playMessage(selected);
        } else {
            return;
        }

        if (queue != null) {
            session.setQueue(queue);
        }
        session.setQueueTitle(queueTitle);
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
                savePlayingPosition(true);
                next.resetPlayingProgress();
                next.forceSeekTo = 0;
                controller.playMessage(next);
            }
            return;
        }
        int snapshotIndex = indexInSnapshot(playing);
        if (snapshotIndex >= 0 && !(SharedConfig.shuffleMusic && playing.isMusic())) {
            int size = playingSnapshot.size();
            playSnapshotItem((snapshotIndex + direction + size) % size, true);
            return;
        }
        ArrayList<MessageObject> messages = audioObjects.get(playing.getDialogId());
        int index = messages != null ? messages.indexOf(playing) : -1;
        if (index < 0 || SharedConfig.shuffleMusic && playing.isMusic()) {
            savePlayingPosition(true);
            controller.setStartNextFromBeginning(true);
            if (direction > 0) {
                controller.playNextMessage();
            } else {
                controller.playPreviousMessage();
            }
            controller.setStartNextFromBeginning(false);
            return;
        }
        int nextIndex = (index + direction + messages.size()) % messages.size();
        playAudio(playing.getDialogId(), nextIndex, true);
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
        String id = parentMediaId.substring(prefix.length());
        int separator = id.indexOf(PAGE_SEPARATOR);
        if (separator >= 0) {
            id = id.substring(0, separator);
        }
        try {
            return Long.parseLong(id);
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Item offset of a paged list; page ids look like "<list id>@<offset>". */
    private static int getPageOffset(String parentMediaId) {
        int separator = parentMediaId != null ? parentMediaId.indexOf(PAGE_SEPARATOR) : -1;
        if (separator < 0) {
            return 0;
        }
        Integer offset = Utilities.parseInt(parentMediaId.substring(separator + 1));
        return offset != null && offset > 0 ? offset : 0;
    }

    private List<MediaBrowser.MediaItem> loadChildrenSync(String parentMediaId) {
        List<MediaBrowser.MediaItem> mediaItems = new ArrayList<>();
        if (MEDIA_ID_ROOT.equals(parentMediaId)) {
            mediaItems.add(buildTab(MEDIA_ID_LIBRARY, R.string.CarTabAudio));
            mediaItems.add(buildTab(MEDIA_ID_VIDEO_LIBRARY, R.string.CarTabVideos));
            mediaItems.add(buildTab(MEDIA_ID_FAVORITES, R.string.CarTabFavorites));
            mediaItems.add(buildTab(MEDIA_ID_RECENT, R.string.CarTabRecent));
        } else if (MEDIA_ID_RESUME.equals(parentMediaId)) {
            ArrayList<CarPlaybackStore.Favorite> list = getStoredList(MEDIA_ID_RECENT_PREFIX);
            if (!list.isEmpty() && list.get(0).message != null) {
                CarPlaybackStore.Favorite item = list.get(0);
                mediaItems.add(buildPlayableItem(MEDIA_ID_RECENT_PREFIX + item.dialogId + "_" + item.messageId, item.message, null));
            }
        } else if (parentMediaId.startsWith(MEDIA_ID_FAVORITES) || parentMediaId.startsWith(MEDIA_ID_RECENT)) {
            String prefix = parentMediaId.startsWith(MEDIA_ID_RECENT) ? MEDIA_ID_RECENT_PREFIX : MEDIA_ID_FAVORITE_PREFIX;
            ArrayList<CarPlaybackStore.Favorite> list = getStoredList(prefix);
            if (MEDIA_ID_RECENT_PREFIX.equals(prefix) && !list.isEmpty() && getPageOffset(parentMediaId) == 0) {
                mediaItems.add(new MediaBrowser.MediaItem(new android.media.MediaDescription.Builder()
                        .setMediaId(MEDIA_ID_CLEAR_RECENT)
                        .setTitle(LocaleController.getString(R.string.CarClearRecent))
                        .build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            appendPage(mediaItems, parentMediaId, list.size(), list.size(), index -> {
                CarPlaybackStore.Favorite item = list.get(index);
                return buildPlayableItem(prefix + item.dialogId + "_" + item.messageId, item.message, null);
            }, null, true);
        } else if (parentMediaId.startsWith(MEDIA_ID_LIBRARY) || parentMediaId.startsWith(MEDIA_ID_VIDEO_LIBRARY)) {
            boolean videos = parentMediaId.startsWith(MEDIA_ID_VIDEO_LIBRARY);
            ArrayList<Long> list = videos ? videoDialogs : dialogs;
            String prefix = videos ? MEDIA_ID_VIDEO_CHAT_PREFIX : MEDIA_ID_CHAT_PREFIX;
            appendPage(mediaItems, parentMediaId, list.size(), list.size(),
                    index -> buildChatItem(prefix, list.get(index), null), null, true);
        } else if (getDialogIdFromMediaId(parentMediaId) != 0) {
            long did = getDialogIdFromMediaId(parentMediaId);
            boolean videos = parentMediaId.startsWith(MEDIA_ID_VIDEO_CHAT_PREFIX);
            ArrayList<MessageObject> arrayList = audioObjects.get(did);
            if (arrayList != null) {
                ArrayList<MessageObject> items = new ArrayList<>();
                for (MessageObject messageObject : arrayList) {
                    if (isCarVideo(messageObject) == videos) {
                        items.add(messageObject);
                    }
                }
                int total = videos ? items.size() : getAudioTotal(did, items.size());
                appendPage(mediaItems, parentMediaId, total, items.size(), index -> {
                    MessageObject messageObject = items.get(index);
                    return buildPlayableItem(MEDIA_ID_MESSAGE_PREFIX + did + "_" + messageObject.getId(), messageObject, null);
                }, getFallbackCoverUri(videos), false);
            }
        }
        return mediaItems;
    }

    private static MediaBrowser.MediaItem buildTab(String mediaId, int titleRes) {
        return new MediaBrowser.MediaItem(new android.media.MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(LocaleController.getString(titleRes))
                .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private interface ItemAt {
        MediaBrowser.MediaItem get(int index);
    }

    /**
     * Adds one page of a list. Android Auto cannot be scrolled by an app, so long lists are split
     * into short pages.
     *
     * @param pageIndex true lists every page at the end so any page is one level below the list;
     *                  false adds a single "next page" row at the top and the bottom instead.
     */
    private void appendPage(List<MediaBrowser.MediaItem> out, String parentMediaId, int total, int available,
                            ItemAt itemAt, @Nullable Uri pageIcon, boolean pageIndex) {
        int separator = parentMediaId.indexOf(PAGE_SEPARATOR);
        String base = separator >= 0 ? parentMediaId.substring(0, separator) : parentMediaId;
        int offset = Math.min(getPageOffset(parentMediaId), Math.max(0, available - 1)) / BROWSE_PAGE_SIZE * BROWSE_PAGE_SIZE;
        int end = Math.min(offset + BROWSE_PAGE_SIZE, available);
        int count = Math.max(total, available);
        if (!pageIndex) {
            MediaBrowser.MediaItem nextPage = null;
            if (count > offset + BROWSE_PAGE_SIZE) {
                int next = offset + BROWSE_PAGE_SIZE;
                nextPage = buildPageRow(base + PAGE_SEPARATOR + next,
                        LocaleController.formatString(R.string.CarNextPage, next / BROWSE_PAGE_SIZE + 1),
                        next, count, pageIcon);
            }
            if (nextPage != null && end - offset > 1) {
                out.add(nextPage);
            }
            for (int i = offset; i < end; i++) {
                out.add(itemAt.get(i));
            }
            if (nextPage != null) {
                out.add(nextPage);
            }
            return;
        }
        for (int i = offset; i < end; i++) {
            out.add(itemAt.get(i));
        }
        int pages = (count + BROWSE_PAGE_SIZE - 1) / BROWSE_PAGE_SIZE;
        if (pages <= 1) {
            return;
        }
        int current = offset / BROWSE_PAGE_SIZE;
        for (int page = 0; page < pages; page++) {
            if (page == current) {
                continue;
            }
            int pageOffset = page * BROWSE_PAGE_SIZE;
            out.add(buildPageRow(page == 0 ? base : base + PAGE_SEPARATOR + pageOffset,
                    LocaleController.formatString(R.string.CarPageNumber, page + 1), pageOffset, count, pageIcon));
        }
    }

    @Nullable
    private static MediaBrowser.MediaItem buildPageRow(String mediaId, String title, int offset, int count, @Nullable Uri icon) {
        if (mediaId == null) {
            return null;
        }
        android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(LocaleController.formatString(R.string.CarPageRange, offset + 1,
                        Math.min(offset + BROWSE_PAGE_SIZE, count)));
        if (icon != null) {
            builder.setIconUri(icon);
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE);
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
        String title = getDialogTitle(dialogId);
        builder.setTitle(pinnedDialogs.contains(dialogId) ? "\uD83D\uDCCC " + title : title);
        boolean savedMessages = dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
        Bitmap specialAvatar = savedMessages ? getSpecialAvatar(org.telegram.ui.Components.AvatarDrawable.AVATAR_TYPE_SAVED)
                : UserObject.isReplyUser(dialogId) ? getSpecialAvatar(org.telegram.ui.Components.AvatarDrawable.AVATAR_TYPE_REPLIES) : null;
        if (specialAvatar != null) {
            builder.setIconBitmap(specialAvatar);
        } else {
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            if (DialogObject.isUserDialog(dialogId)) {
                user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user == null) {
                    user = users.get(dialogId);
                }
            } else {
                chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat == null) {
                    chat = chats.get(-dialogId);
                }
            }
            Uri avatarUri = CarArtworkProvider.getAvatarUri(currentAccount, dialogId, user, chat);
            builder.setIconUri(avatarUri != null ? avatarUri : getFallbackCoverUri(false));
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
        int hours = durationSeconds / 3600;
        int minutes = durationSeconds / 60 % 60;
        int seconds = durationSeconds % 60;
        String duration = hours > 0
                ? String.format(Locale.US, "(%02d:%02d:%02d)", hours, minutes, seconds)
                : String.format(Locale.US, "(%02d:%02d)", minutes, seconds);
        return TextUtils.isEmpty(subtitle) ? duration : duration + " \u00B7 " + subtitle;
    }

    private MediaBrowser.MediaItem buildPlayableItem(String mediaId, MessageObject messageObject, @Nullable Bundle extras) {
        String title = getAudioTitle(messageObject);
        if (isFavorite(messageObject.getDialogId(), messageObject.getId())) {
            title = "\u2B50 " + title;
        }
        android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        float position = CarPlaybackStore.getPosition(currentAccount, messageObject.getDialogId(), messageObject.getId());
        if (position > 0) {
            // Shows a partially-played indicator on hosts that support it.
            extras = extras != null ? new Bundle(extras) : new Bundle();
            extras.putInt(EXTRA_PLAYBACK_STATUS, PLAYBACK_STATUS_PARTIALLY_PLAYED);
            extras.putDouble(EXTRA_COMPLETION_PERCENTAGE, position);
        }
        String author = messageObject.getMusicAuthor();
        String videoLabel = getVideoLabel(messageObject);
        if (videoLabel != null) {
            author = TextUtils.isEmpty(author) ? videoLabel : videoLabel + " \u00B7 " + author;
        }
        builder.setSubtitle(withDuration(author, (int) messageObject.getDuration()));
        boolean video = isCarVideo(messageObject);
        Uri cover = CarArtworkProvider.getCoverUri(messageObject, video);
        builder.setIconUri(cover != null ? cover : getFallbackCoverUri(video));
        if (extras != null) {
            builder.setExtras(extras);
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    /** Media browser search: returns playable items across all chats. */
    private static final String EXTRA_PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS";
    private static final String EXTRA_COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE";
    private static final int PLAYBACK_STATUS_PARTIALLY_PLAYED = 1;
    private static final String CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static Bundle groupExtras(String title) {
        Bundle extras = new Bundle();
        extras.putString(CONTENT_STYLE_GROUP_TITLE_HINT, title);
        return extras;
    }

    /** Media browser search: matching chats, local audio, then audio found on Telegram's servers. */
    public void searchBrowseItems(String query, BrowseChildrenCallback callback) {
        String normalized = normalizeQuery(query);
        final ArrayList<AudioSearchResult>[] localResults = new ArrayList[1];
        final ArrayList<MessageObject>[] cloudResults = new ArrayList[1];
        final boolean[] delivered = new boolean[1];
        final Runnable[] deliver = new Runnable[1];
        Runnable timeout = () -> {
            if (cloudResults[0] == null) {
                cloudResults[0] = new ArrayList<>();
                deliver[0].run();
            }
        };
        deliver[0] = () -> {
            if (delivered[0] || localResults[0] == null || cloudResults[0] == null) {
                return;
            }
            delivered[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            callback.onResult(buildSearchItems(normalized, localResults[0], cloudResults[0]));
        };
        searchCloudAudio(query, results -> {
            if (cloudResults[0] == null) {
                cloudResults[0] = results;
                deliver[0].run();
            }
        });
        AndroidUtilities.runOnUIThread(timeout, CLOUD_SEARCH_TIMEOUT_MS);
        searchAudio(0, query, results -> {
            localResults[0] = results;
            deliver[0].run();
        });
    }

    private interface CloudSearchCallback {
        void onResults(ArrayList<MessageObject> results);
    }

    /** Finds music on the server across all chats, including files that were never downloaded. */
    private void searchCloudAudio(@Nullable String query, CloudSearchCallback callback) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) {
            callback.onResults(new ArrayList<>());
            return;
        }
        final int account = currentAccount;
        TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = q;
        request.filter = new TLRPC.TL_inputMessagesFilterMusic();
        request.offset_peer = new TLRPC.TL_inputPeerEmpty();
        request.limit = CLOUD_SEARCH_LIMIT;
        final int[] requestId = new int[1];
        requestId[0] = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    cloudRequestIds.remove(requestId[0]);
                    ArrayList<MessageObject> results = new ArrayList<>();
                    if (account != currentAccount) {
                        callback.onResults(results);
                        return;
                    }
                    if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                        FileLog.e("Android Auto: cloud audio search failed: "
                                + (error != null ? error.text : "invalid response"));
                        callback.onResults(results);
                        return;
                    }
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    MessagesController controller = MessagesController.getInstance(account);
                    controller.putUsers(result.users, false);
                    controller.putChats(result.chats, false);
                    MessagesStorage.getInstance(account).putUsersAndChats(result.users, result.chats, true, true);
                    if (cloudSearchMessages.size() > 500) {
                        cloudSearchMessages.clear();
                    }
                    for (TLRPC.Message message : result.messages) {
                        long did = MessageObject.getDialogId(message);
                        if (message.id <= 0 || did == 0 || DialogObject.isEncryptedDialog(did)) {
                            continue;
                        }
                        message.dialog_id = did;
                        MessageObject object = new MessageObject(account, message, false, true);
                        if (isCarPlayable(object) && !isCarVideo(object)) {
                            results.add(object);
                            cloudSearchMessages.put(did + "_" + message.id, object);
                        }
                    }
                    callback.onResults(results);
                }));
        cloudRequestIds.add(requestId[0]);
    }

    private List<MediaBrowser.MediaItem> buildSearchItems(String normalized, ArrayList<AudioSearchResult> results,
                                                         ArrayList<MessageObject> cloudResults) {
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
        Set<String> seen = new HashSet<>();
        for (AudioSearchResult result : results) {
            seen.add(result.dialogId + "_" + result.messageId);
            items.add(buildSearchResultItem(result, otherExtras));
        }
        for (MessageObject message : cloudResults) {
            if (items.size() >= MAX_SEARCH_RESULTS + CLOUD_SEARCH_LIMIT) {
                break;
            }
            AudioSearchResult result = toSearchResult(message);
            if (seen.add(result.dialogId + "_" + result.messageId)) {
                items.add(buildSearchResultItem(result, otherExtras));
            }
        }
        return items;
    }

    private MediaBrowser.MediaItem buildSearchResultItem(AudioSearchResult result, Bundle extras) {
        android.media.MediaDescription.Builder builder = new android.media.MediaDescription.Builder()
                .setMediaId(MEDIA_ID_MESSAGE_PREFIX + result.dialogId + "_" + result.messageId)
                .setTitle(result.title)
                .setExtras(extras);
        String subtitle = result.author;
        String chat = getDialogTitle(result.dialogId);
        subtitle = subtitle.isEmpty() ? chat : subtitle + " \u00B7 " + chat;
        if (result.videoLabel != null) {
            subtitle = subtitle.isEmpty() ? result.videoLabel : result.videoLabel + " \u00B7 " + subtitle;
        }
        builder.setSubtitle(withDuration(subtitle, result.durationSeconds));
        builder.setIconUri(getFallbackCoverUri(result.videoLabel != null));
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
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

    static Bitmap addVideoBadge(Bitmap source) {
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

    static Bitmap decodeCover(@Nullable File file, @Nullable byte[] bytes, int sizePx) {
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
            if (playing.isMusic() || playing.isVoice() || isCarVideo(playing) || isCarAudioDocument(playing)) {
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
            if (consumeAutoplay()) {
                return;
            }
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject == null) {
                if (resumeLastPlayed()) {
                    return;
                }
                ensureLoaded(() -> {
                    if (lastSelectedDialog != 0) {
                        playAudio(lastSelectedDialog, 0);
                    } else {
                        reportPlaybackError("Android Auto: no audio chat available");
                    }
                });
            } else {
                MediaController.getInstance().playMessage(messageObject);
            }
        }

        @Override
        public void onPause() {
            cancelPendingPlay();
            MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
        }

        @Override
        public void onSkipToNext() {
            blockAutoplay = false;
            skipToAdjacentAudio(1);
        }

        @Override
        public void onSkipToPrevious() {
            blockAutoplay = false;
            skipToAdjacentAudio(-1);
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            blockAutoplay = false;
            if (playingSnapshot != null) {
                playSnapshotItem((int) queueId);
                return;
            }
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
            if (MediaController.getInstance().getPlayingMessageObject() == null) {
                showLastPlayed();
            }
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            if (MEDIA_ID_CLEAR_RECENT.equals(mediaId)) {
                return;
            }
            onPlayFromMediaId(mediaId, extras);
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            onPlayFromSearch(query, extras);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            blockAutoplay = false;
            if (TextUtils.isEmpty(mediaId)) return;
            if (MEDIA_ID_CLEAR_RECENT.equals(mediaId)) {
                clearRecent();
                return;
            }
            String listPrefix = mediaId.startsWith(MEDIA_ID_FAVORITE_PREFIX) ? MEDIA_ID_FAVORITE_PREFIX
                    : mediaId.startsWith(MEDIA_ID_RECENT_PREFIX) ? MEDIA_ID_RECENT_PREFIX : null;
            if (listPrefix != null) {
                String[] parts = mediaId.substring(listPrefix.length()).split("_");
                if (parts.length == 2) {
                    try {
                        playFromStoredList(listPrefix, Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                return;
            }
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
            cancelPendingPlay();
            // session stays alive; let MusicPlayerService handle notification + service teardown
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (CUSTOM_ACTION_FAVORITE.equals(action)) {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing != null && isCarPlayable(playing)) {
                    toggleFavorite(playing);
                }
                return;
            }
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
