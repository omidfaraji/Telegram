package org.telegram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves Android Auto list artwork on demand, so the car host only loads images for visible rows
 * instead of receiving every bitmap with the browse result.
 */
public class CarArtworkProvider extends ContentProvider {

    private static final String PATH = "art";
    private static final int ARTWORK_SIZE_PX = 256;
    private static final long DOWNLOAD_WAIT_MS = 8000;
    private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_-]+");

    private static final class Source {
        final int account;
        final ImageLocation location;
        final Object parent;
        @Nullable
        final byte[] bytes;
        final Object attach;
        final boolean video;
        final boolean avatar;

        Source(int account, ImageLocation location, Object parent, @Nullable byte[] bytes, Object attach, boolean video, boolean avatar) {
            this.account = account;
            this.location = location;
            this.parent = parent;
            this.bytes = bytes;
            this.attach = attach;
            this.video = video;
            this.avatar = avatar;
        }
    }

    private static final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private static volatile String authority;

    public static String getAuthority() {
        if (authority == null) {
            authority = ApplicationLoader.getApplicationId() + ".car_artwork";
        }
        return authority;
    }

    /** Prefix granted to the connected car host so it can read every artwork URI. */
    public static Uri getBaseUri() {
        return Uri.parse("content://" + getAuthority() + "/" + PATH);
    }

    private static Uri buildUri(String key) {
        return getBaseUri().buildUpon().appendPath(key).build();
    }

    /** Returns a lazily rendered cover URI, or null when the media has no cover thumbnail. */
    @Nullable
    public static Uri getCoverUri(MessageObject messageObject, boolean video) {
        TLRPC.Document document = messageObject != null ? messageObject.getDocument() : null;
        if (document == null || document.thumbs == null || document.thumbs.isEmpty()) {
            return null;
        }
        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320, false, null, true);
        if (thumb == null) {
            return null;
        }
        String key = "c" + messageObject.currentAccount + "_" + document.id + (video ? "v" : "");
        if (!sources.containsKey(key)) {
            sources.put(key, new Source(messageObject.currentAccount, ImageLocation.getForDocument(thumb, document),
                    messageObject, thumb.bytes != null && thumb.bytes.length > 0 ? thumb.bytes : null, thumb, video, false));
        }
        return buildUri(key);
    }

    /** Returns a lazily rendered round avatar URI for a user or chat photo. */
    @Nullable
    public static Uri getAvatarUri(int account, long dialogId, @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat) {
        ImageLocation location;
        TLRPC.FileLocation small;
        long photoId;
        Object parent;
        if (user != null && user.photo != null && user.photo.photo_small != null
                && !(user.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
            location = ImageLocation.getForUser(user, ImageLocation.TYPE_SMALL);
            small = user.photo.photo_small;
            photoId = user.photo.photo_id;
            parent = user;
        } else if (chat != null && chat.photo != null && chat.photo.photo_small != null
                && !(chat.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
            location = ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL);
            small = chat.photo.photo_small;
            photoId = chat.photo.photo_id;
            parent = chat;
        } else {
            return getLetterAvatarUri(account, dialogId, user, chat);
        }
        if (location == null) {
            return getLetterAvatarUri(account, dialogId, user, chat);
        }
        String key = avatarKey(account, dialogId, photoId != 0 ? String.valueOf(photoId) : small.volume_id + "_" + small.local_id);
        if (!sources.containsKey(key)) {
            sources.put(key, new Source(account, location, parent, null, small, false, true));
        }
        return buildUri(key);
    }

    @Nullable
    private static Uri getLetterAvatarUri(int account, long dialogId, @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat) {
        Object parent = user != null ? user : chat;
        if (parent == null) {
            return null;
        }
        String name = user != null ? UserObject.getUserName(user) : chat.title;
        String key = avatarKey(account, dialogId, "l" + Integer.toHexString(name != null ? name.hashCode() : 0));
        if (!sources.containsKey(key)) {
            sources.put(key, new Source(account, null, parent, null, null, false, true));
        }
        return buildUri(key);
    }

    private static String avatarKey(int account, long dialogId, String suffix) {
        String key = "a" + account + "_" + (dialogId < 0 ? "n" + -dialogId : String.valueOf(dialogId)) + "_" + suffix;
        return key.replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    }

    public static void clearSources() {
        sources.clear();
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            Utilities.globalQueue.postRunnable(() -> pruneCache(context));
        }
        return true;
    }

    private static File getCacheDir(Context context) {
        File dir = new File(context.getCacheDir(), "car_artwork");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void pruneCache(Context context) {
        File[] files = getCacheDir(context).listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (now - file.lastModified() > CACHE_MAX_AGE_MS) {
                file.delete();
            }
        }
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context context = getContext();
        String key = uri.getLastPathSegment();
        if (context == null || key == null || !KEY_PATTERN.matcher(key).matches() || !mode.equals("r")) {
            throw new FileNotFoundException(uri.toString());
        }
        // Only artwork registered by the current browse session is served; this also stops URIs
        // from a previous account resolving after an account switch.
        Source source = sources.get(key);
        if (source == null) {
            throw new FileNotFoundException(uri.toString());
        }
        File dir = getCacheDir(context);
        File out = new File(dir, key + ".png");
        if (!out.exists() || out.length() == 0) {
            Bitmap bitmap = null;
            try {
                bitmap = render(source);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            boolean written = bitmap != null && write(bitmap, out);
            if (!written && source.avatar && source.location != null) {
                // Photo not available yet: serve initials once without caching them under the photo key.
                Bitmap letters = renderLetters(source.parent);
                File temporary = new File(dir, key + "." + SystemClock.elapsedRealtimeNanos() + ".letters");
                if (letters != null && write(letters, temporary)) {
                    ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY);
                    temporary.delete();
                    return descriptor;
                }
            }
            if (!written) {
                boolean video = !key.startsWith("a") && key.endsWith("v");
                out = getFallback(context, dir, video ? R.drawable.car_video_cover : R.drawable.car_audio_cover);
                if (out == null) {
                    throw new FileNotFoundException(uri.toString());
                }
            }
        }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static final int[] LETTER_COLORS = {
            0xFFE56555, 0xFFF28C48, 0xFF8E85EE, 0xFF76C84D, 0xFF5FBED5, 0xFF549CDD, 0xFFF2749A
    };

    /** Draws an initials avatar without Theme resources, which may not exist in a headless process. */
    @Nullable
    private static Bitmap renderLetters(Object parent) {
        long id;
        String first;
        String last;
        if (parent instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) parent;
            id = user.id;
            first = user.first_name;
            last = user.last_name;
        } else if (parent instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) parent;
            id = chat.id;
            first = chat.title;
            last = null;
        } else {
            return null;
        }
        StringBuilder initials = new StringBuilder();
        appendInitial(initials, first);
        if (last != null) {
            appendInitial(initials, last);
        } else if (first != null) {
            int space = first.trim().lastIndexOf(' ');
            if (space > 0) {
                appendInitial(initials, first.trim().substring(space + 1));
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(LETTER_COLORS[(int) (Math.abs(id) % LETTER_COLORS.length)]);
        canvas.drawOval(new RectF(0, 0, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX), circle);
        if (initials.length() > 0) {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(ARTWORK_SIZE_PX * 0.4f);
            text.setFakeBoldText(true);
            float y = ARTWORK_SIZE_PX / 2f - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(initials.toString().toUpperCase(java.util.Locale.ROOT), ARTWORK_SIZE_PX / 2f, y, text);
        }
        return bitmap;
    }

    private static void appendInitial(StringBuilder out, @Nullable String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (!trimmed.isEmpty()) {
            out.appendCodePoint(trimmed.codePointAt(0));
        }
    }

    @Nullable
    private static Bitmap render(Source source) {
        if (source.location == null) {
            return renderLetters(source.parent);
        }
        Bitmap square;
        if (source.bytes != null) {
            square = TelegramMediaSession.decodeCover(null, source.bytes, ARTWORK_SIZE_PX);
        } else {
            File path = FileLoader.getInstance(source.account).getPathToAttach((org.telegram.tgnet.TLObject) source.attach, true);
            if (path == null) {
                return null;
            }
            if (!path.exists()) {
                AndroidUtilities.runOnUIThread(() -> FileLoader.getInstance(source.account).loadFile(
                        source.location, source.parent, "jpg", FileLoader.PRIORITY_HIGH, 1));
                long deadline = SystemClock.elapsedRealtime() + DOWNLOAD_WAIT_MS;
                while (!path.exists() && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100);
                }
                if (!path.exists()) {
                    return null;
                }
            }
            square = TelegramMediaSession.decodeCover(path, null, ARTWORK_SIZE_PX);
        }
        if (square == null) {
            return null;
        }
        if (source.avatar) {
            return toRound(square);
        }
        return source.video ? TelegramMediaSession.addVideoBadge(square) : square;
    }

    private static Bitmap toRound(Bitmap square) {
        Bitmap result = Bitmap.createBitmap(square.getWidth(), square.getHeight(), Bitmap.Config.ARGB_8888);
        result.eraseColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(result).drawOval(new RectF(0, 0, square.getWidth(), square.getHeight()), paint);
        square.recycle();
        return result;
    }

    @Nullable
    private static synchronized File getFallback(Context context, File dir, int resId) {
        File out = new File(dir, "fallback_" + resId + ".png");
        if (out.exists() && out.length() > 0) {
            return out;
        }
        try {
            Drawable drawable = context.getDrawable(resId);
            if (drawable == null) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX);
            drawable.draw(new Canvas(bitmap));
            return write(bitmap, out) ? out : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean write(Bitmap bitmap, File out) {
        File tmp = new File(out.getParentFile(), out.getName() + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (Throwable e) {
            FileLog.e(e);
            tmp.delete();
            return false;
        } finally {
            bitmap.recycle();
        }
        return tmp.renameTo(out) || out.exists();
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/png";
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
