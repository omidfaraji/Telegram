/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.TargetApi;
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
            return null;
        }
        return new BrowserRoot(MEDIA_ID_ROOT, TelegramMediaSession.getInstance(this).buildRootHints());
    }

    @Override
    public void onLoadChildren(@NonNull String parentMediaId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        TelegramMediaSession holder = TelegramMediaSession.getInstance(this);
        if (holder.isPasscodeLocked()) {
            Toast.makeText(getApplicationContext(), LocaleController.getString(R.string.EnterYourTelegramPasscode), Toast.LENGTH_LONG).show();
            stopSelf();
            result.detach();
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
