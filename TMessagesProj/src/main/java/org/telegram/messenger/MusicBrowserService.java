/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.support.v4.media.MediaBrowserCompat;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media.MediaBrowserServiceCompat;

import java.util.List;

import javax.annotation.Nullable;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MusicBrowserService extends MediaBrowserServiceCompat {

    private static final String MEDIA_ID_ROOT = "__ROOT__";

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        TelegramMediaSession holder = TelegramMediaSession.getInstance(this);
        setSessionToken(holder.getSessionToken());
        holder.setBrowseTreeListener(this::notifyChildrenChanged);
        holder.revalidateCatalog(true);
    }

    @Override
    public void onDestroy() {
        TelegramMediaSession.getInstance(this).setBrowseTreeListener(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (clientPackageName == null) {
            return null;
        }
        boolean isSelf = Process.SYSTEM_UID == clientUid || Process.myUid() == clientUid;
        if (!isSelf && !PackageValidator.isKnownCaller(this, clientPackageName, clientUid)) {
            return null;
        }
        if (TelegramMediaSession.getInstance(this).isPasscodeLocked()) {
            android.util.Log.w("TgCar", "onGetRoot refused: passcode locked");
            return null;
        }
        if (!isSelf) {
            try {
                grantUriPermission(clientPackageName, CarArtworkProvider.getBaseUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (!isSelf && clientPackageName.startsWith("com.google.android.projection.gearhead")) {
            TelegramMediaSession.getInstance(this).restoreCarSession();
        }
        if (rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT)) {
            Bundle extras = new Bundle();
            extras.putBoolean(BrowserRoot.EXTRA_RECENT, true);
            return new BrowserRoot(TelegramMediaSession.MEDIA_ID_RESUME, extras);
        }
        return new BrowserRoot(MEDIA_ID_ROOT, TelegramMediaSession.getInstance(this).buildRootHints());
    }

    @Override
    public void onLoadChildren(@NonNull String parentMediaId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        TelegramMediaSession holder = TelegramMediaSession.getInstance(this);
        if (holder.isPasscodeLocked()) {
            Toast.makeText(getApplicationContext(), LocaleController.getString(R.string.EnterYourTelegramPasscode), Toast.LENGTH_LONG).show();
            result.sendResult(null);
            return;
        }
        result.detach();
        holder.loadBrowseChildren(parentMediaId, items -> result.sendResult(MediaBrowserCompat.MediaItem.fromMediaItemList(items)));
    }

    @Override
    public void onSearch(@NonNull String query, Bundle extras, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        TelegramMediaSession holder = TelegramMediaSession.getInstance(this);
        if (holder.isPasscodeLocked()) {
            result.sendResult(null);
            return;
        }
        result.detach();
        holder.searchBrowseItems(query, items -> result.sendResult(MediaBrowserCompat.MediaItem.fromMediaItemList(items)));
    }
}
