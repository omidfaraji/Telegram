package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Map;

/**
 * Android Auto favorites, recently played items and resume positions. Lists keep a serialized copy
 * of each message so they can be shown without the chat being cached or loaded.
 */
public final class CarPlaybackStore {

    public static final String FAVORITES = "car_favorites";
    public static final String RECENT = "car_recent";
    public static final int MAX_RECENT = 200;
    private static final String POSITIONS_PREFS = "car_positions";
    private static final int MAX_POSITIONS = 2000;
    /** Positions this close to the end count as finished, so the item restarts next time. */
    private static final float FINISHED_PROGRESS = 0.98f;
    private static final double FINISHED_REMAINING_SECONDS = 10;

    public static final class Favorite {
        public final long dialogId;
        public final int messageId;
        public final long addedAt;
        public final MessageObject message;

        Favorite(long dialogId, int messageId, long addedAt, MessageObject message) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.addedAt = addedAt;
            this.message = message;
        }
    }

    private CarPlaybackStore() {
    }

    private static SharedPreferences prefs(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    /** A stored list of an account, most recently added first. */
    public static ArrayList<Favorite> loadList(String list, int account) {
        ArrayList<Favorite> result = new ArrayList<>();
        String json = prefs(list).getString("list_" + account, null);
        if (json == null) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                MessageObject message = deserialize(account, entry.getLong("d"), entry.getString("b"));
                if (message != null) {
                    result.add(new Favorite(entry.getLong("d"), entry.getInt("m"), entry.getLong("t"), message));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static void saveList(String list, int account, ArrayList<Favorite> favorites) {
        JSONArray array = new JSONArray();
        try {
            for (Favorite favorite : favorites) {
                String data = serialize(favorite.message.messageOwner);
                if (data == null) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                entry.put("d", favorite.dialogId);
                entry.put("m", favorite.messageId);
                entry.put("t", favorite.addedAt);
                entry.put("b", data);
                array.put(entry);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        String value = array.toString();
        Utilities.globalQueue.postRunnable(() -> prefs(list).edit().putString("list_" + account, value).apply());
    }

    public static Favorite createFavorite(MessageObject message) {
        return new Favorite(message.getDialogId(), message.getId(), System.currentTimeMillis(), message);
    }

    @Nullable
    private static String serialize(TLRPC.Message message) {
        try {
            SerializedData data = new SerializedData(message.getObjectSize());
            message.serializeToStream(data);
            String result = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
            data.cleanup();
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @Nullable
    private static MessageObject deserialize(int account, long dialogId, String base64) {
        try {
            SerializedData data = new SerializedData(Base64.decode(base64, Base64.NO_WRAP));
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            if (message == null) {
                return null;
            }
            message.dialog_id = dialogId;
            return new MessageObject(account, message, false, false);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Saved progress in 0..1, or -1 when the item should start from the beginning. */
    private static final String STATE_PREFS = "car_state";

    /** Whether playback was running when the car was last used, to restore it on reconnect. */
    public static boolean wasPlaying(int account) {
        return prefs(STATE_PREFS).getBoolean("playing_" + account, false);
    }

    public static void setWasPlaying(int account, boolean playing) {
        prefs(STATE_PREFS).edit().putBoolean("playing_" + account, playing).apply();
    }

    public static float getPosition(int account, long dialogId, int messageId) {
        float progress = prefs(POSITIONS_PREFS).getFloat(account + "_" + key(dialogId, messageId), -1);
        return progress > 0 && progress < FINISHED_PROGRESS ? progress : -1;
    }

    public static void savePosition(int account, long dialogId, int messageId, float progress, double durationSeconds) {
        SharedPreferences preferences = prefs(POSITIONS_PREFS);
        String key = account + "_" + key(dialogId, messageId);
        SharedPreferences.Editor editor = preferences.edit();
        boolean nearEnd = durationSeconds > 0 && (1 - progress) * durationSeconds < FINISHED_REMAINING_SECONDS;
        if (progress <= 0 || progress >= FINISHED_PROGRESS || nearEnd) {
            editor.remove(key);
        } else {
            editor.putFloat(key, progress);
            Map<String, ?> all = preferences.getAll();
            if (all.size() > MAX_POSITIONS) {
                // Rarely hit; dropping arbitrary old entries keeps the file small.
                int toRemove = all.size() - MAX_POSITIONS / 2;
                for (String existing : all.keySet()) {
                    if (toRemove-- <= 0) {
                        break;
                    }
                    if (!existing.equals(key)) {
                        editor.remove(existing);
                    }
                }
            }
        }
        editor.apply();
    }
}
