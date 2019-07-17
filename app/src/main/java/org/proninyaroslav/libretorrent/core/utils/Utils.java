/*
 * Copyright (C) 2016-2019 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.core.utils;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

import org.acra.ACRA;
import org.acra.ReportField;
import org.apache.commons.io.IOUtils;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.FileStorage;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.adapters.drawer.DrawerGroup;
import org.proninyaroslav.libretorrent.adapters.drawer.DrawerGroupItem;
import org.proninyaroslav.libretorrent.core.BencodeFileItem;
import org.proninyaroslav.libretorrent.core.HttpConnection;
import org.proninyaroslav.libretorrent.core.RealSystemFacade;
import org.proninyaroslav.libretorrent.core.SystemFacade;
import org.proninyaroslav.libretorrent.core.exceptions.FetchLinkException;
import org.proninyaroslav.libretorrent.core.filter.TorrentFilter;
import org.proninyaroslav.libretorrent.core.filter.TorrentFilterCollection;
import org.proninyaroslav.libretorrent.core.sorting.TorrentSorting;
import org.proninyaroslav.libretorrent.core.sorting.TorrentSortingComparator;
import org.proninyaroslav.libretorrent.receivers.old.BootReceiver;
import org.proninyaroslav.libretorrent.settings.SettingsManager;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/*
 * General utils.
 */

public class Utils
{
    public static final String INFINITY_SYMBOL = "\u221e";
    public static final String MAGNET_PREFIX = "magnet";
    public static final String HTTP_PREFIX = "http";
    public static final String HTTPS_PREFIX = "https";
    public static final String UDP_PREFIX = "udp";
    public static final String INFOHASH_PREFIX = "magnet:?xt=urn:btih:";
    public static final String FILE_PREFIX = "file";
    public static final String CONTENT_PREFIX = "content";
    public static final String TRACKER_URL_PATTERN =
            "^(https?|udp)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
    public static final String HASH_PATTERN = "\\b[0-9a-fA-F]{5,40}\\b";
    public static final String MIME_TORRENT = "application/x-bittorrent";

    private static SystemFacade systemFacade;

    public synchronized static SystemFacade getSystemFacade(@NonNull Context context)
    {
        if (systemFacade == null)
            systemFacade = new RealSystemFacade(context);

        return systemFacade;
    }

    @VisibleForTesting
    public synchronized static void setSystemFacade(@NonNull SystemFacade systemFacade)
    {
        Utils.systemFacade = systemFacade;
    }

    /*
     * Colorize the progress bar in the accent color (for pre-Lollipop).
     */

    public static void colorizeProgressBar(@NonNull Context context,
                                           @NonNull ProgressBar progress)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            progress.getProgressDrawable().setColorFilter(ContextCompat.getColor(context, R.color.accent),
                                                          android.graphics.PorterDuff.Mode.SRC_IN);
    }

    /*
     * Returns the list of BencodeFileItem objects, extracted from FileStorage.
     * The order of addition in the list corresponds to the order of indexes in libtorrent4j.FileStorage
     */

    public static ArrayList<BencodeFileItem> getFileList(@NonNull FileStorage storage)
    {
        ArrayList<BencodeFileItem> files = new ArrayList<>();
        for (int i = 0; i < storage.numFiles(); i++) {
            BencodeFileItem file = new BencodeFileItem(storage.filePath(i), i, storage.fileSize(i));
            files.add(file);
        }

        return files;
    }

    public static void setBackground(@NonNull View v,
                                     @NonNull Drawable d)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
            v.setBackgroundDrawable(d);
        else
            v.setBackground(d);
    }

    public static boolean checkConnectivity(@NonNull Context context)
    {
        SystemFacade systemFacade = getSystemFacade(context);
        NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnected() && isNetworkTypeAllowed(context);
    }

    public static boolean isNetworkTypeAllowed(@NonNull Context context)
    {
        SystemFacade systemFacade = getSystemFacade(context);

        SharedPreferences pref = SettingsManager.getInstance(context).getPreferences();
        boolean enableRoaming = pref.getBoolean(context.getString(R.string.pref_key_enable_roaming),
                                                SettingsManager.Default.enableRoaming);
        boolean unmeteredOnly = pref.getBoolean(context.getString(R.string.pref_key_umnetered_connections_only),
                                                SettingsManager.Default.unmeteredConnectionsOnly);

        boolean noUnmeteredOnly;
        boolean noRoaming;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
            /*
             * Use ConnectivityManager#isActiveNetworkMetered() instead of NetworkCapabilities#NET_CAPABILITY_NOT_METERED,
             * since Android detection VPN as metered, including on Android 9, oddly enough.
             * I think this is due to what VPN services doesn't use setUnderlyingNetworks() method.
             *
             * See for details: https://developer.android.com/about/versions/pie/android-9.0-changes-all#network-capabilities-vpn
             */
            boolean unmetered = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    !systemFacade.isActiveNetworkMetered();
            noUnmeteredOnly = !unmeteredOnly || unmetered;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                noRoaming = !enableRoaming || caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
            } else {
                NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
                noRoaming = netInfo != null && !(enableRoaming && netInfo.isRoaming());
            }

        } else {
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            if (netInfo == null) {
                noUnmeteredOnly = false;
                noRoaming = false;
            } else {
                noUnmeteredOnly = !unmeteredOnly || !systemFacade.isActiveNetworkMetered();
                noRoaming = !(enableRoaming && netInfo.isRoaming());
            }
        }

        return noUnmeteredOnly && noRoaming;
    }

    public static boolean isMetered(@NonNull Context context)
    {
        SystemFacade systemFacade = getSystemFacade(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
            return caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                                    systemFacade.isActiveNetworkMetered();
        } else {
            return systemFacade.isActiveNetworkMetered();
        }
    }

    public static boolean isRoaming(@NonNull Context context)
    {
        SystemFacade systemFacade = getSystemFacade(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NetworkCapabilities caps = systemFacade.getNetworkCapabilities();
            return caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        } else {
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            return netInfo != null && netInfo.isRoaming();
        }
    }

    /*
     * Returns the link as "(http[s]|ftp)://[www.]name.domain/...".
     */

    public static String normalizeURL(@NonNull String url)
    {
        url = IDN.toUnicode(url);

        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX))
            return HTTP_PREFIX + url;
        else
            return url;
    }

    /*
     * Returns the link as "magnet:?xt=urn:btih:hash".
     */

    public static String normalizeMagnetHash(@NonNull String hash)
    {
        return INFOHASH_PREFIX + hash;
    }

    /*
     * Don't use app context (its doesn't reload after configuration changes)
     */

    public static boolean isTwoPane(@NonNull Context context)
    {
        return context.getResources().getBoolean(R.bool.isTwoPane);
    }

    /*
     * Tablets (from 7"), notebooks, TVs
     *
     * Don't use app context (its doesn't reload after configuration changes)
     */

    public static boolean isLargeScreenDevice(@NonNull Context context)
    {
        return context.getResources().getBoolean(R.bool.isLargeScreenDevice);
    }

    /*
     * Returns true if link has the form "http[s][udp]://[www.]name.domain/...".
     *
     * Returns false if the link is not valid.
     */

    public static boolean isValidTrackerUrl(@NonNull String url)
    {
        if (TextUtils.isEmpty(url))
            return false;

        Pattern pattern = Pattern.compile(TRACKER_URL_PATTERN);
        Matcher matcher = pattern.matcher(url.trim());

        return matcher.matches();
    }

    public static boolean isHash(@NonNull String hash) {
        if (TextUtils.isEmpty(hash))
            return false;

        Pattern pattern = Pattern.compile(HASH_PATTERN);
        Matcher matcher = pattern.matcher(hash.trim());

        return matcher.matches();
    }

    /*
     * Return system text line separator (in android it '\n').
     */

    public static String getLineSeparator()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            return System.lineSeparator();
        else
            return System.getProperty("line.separator");
    }

    /*
     * Returns the first item from clipboard.
     */

    @Nullable
    public static String getClipboard(@NonNull Context context)
    {
        ClipboardManager clipboard = (ClipboardManager)context.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (clipboard == null)
            return null;

        if (!clipboard.hasPrimaryClip())
            return null;

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0)
            return null;

        CharSequence text = clip.getItemAt(0).getText();
        if (text == null)
            return null;

        return text.toString();
    }

    public static void reportError(@NonNull Throwable error,
                                   String comment)
    {
        if (comment != null)
            ACRA.getErrorReporter().putCustomData(ReportField.USER_COMMENT.toString(), comment);

        ACRA.getErrorReporter().handleSilentException(error);
    }

    public static int dpToPx(@NonNull Context context, float dp)
    {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics());
    }

    public static int getDefaultBatteryLowLevel()
    {
        return Resources.getSystem().getInteger(
                Resources.getSystem().getIdentifier("config_lowBatteryWarningLevel", "integer", "android"));
    }

    public static float getBatteryLevel(@NonNull Context context)
    {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null)
            return 50.0f;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        /* Error checking that probably isn't needed but I added just in case */
        if (level == -1 || scale == -1)
            return 50.0f;

        return ((float)level / (float)scale) * 100.0f;
    }

    public static boolean isBatteryCharging(@NonNull Context context)
    {
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null)
            return false;
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public static boolean isBatteryLow(@NonNull Context context)
    {
        return Utils.getBatteryLevel(context) <= Utils.getDefaultBatteryLowLevel();
    }

    public static boolean isBatteryBelowThreshold(@NonNull Context context, int threshold)
    {
        return Utils.getBatteryLevel(context) <= threshold;
    }

    public static int getThemePreference(@NonNull Context context)
    {
        return SettingsManager.getInstance(context)
                .getPreferences().getInt(context.getString(R.string.pref_key_theme),
                                         SettingsManager.Default.theme(context));
    }
    
    public static boolean isDarkTheme(@NonNull Context context)
    {
        return getThemePreference(context) == Integer.parseInt(context.getString(R.string.pref_theme_dark_value));
    }
    
    public static boolean isBlackTheme(@NonNull Context context)
    {
        return getThemePreference(context) == Integer.parseInt(context.getString(R.string.pref_theme_black_value));
    }

    public static int getAppTheme(@NonNull Context context)
    {
        int theme = getThemePreference(context);

        if (theme == Integer.parseInt(context.getString(R.string.pref_theme_light_value)))
            return R.style.AppTheme;
        else if (theme == Integer.parseInt(context.getString(R.string.pref_theme_dark_value)))
            return R.style.AppTheme_Dark;
        else if (theme == Integer.parseInt(context.getString(R.string.pref_theme_black_value)))
            return R.style.AppTheme_Black;

        return R.style.AppTheme;
    }

    public static int getTranslucentAppTheme(@NonNull Context appContext)
    {
        int theme = getThemePreference(appContext);

        if (theme == Integer.parseInt(appContext.getString(R.string.pref_theme_light_value)))
            return R.style.AppTheme_Translucent;
        else if (theme == Integer.parseInt(appContext.getString(R.string.pref_theme_dark_value)))
            return R.style.AppTheme_Translucent_Dark;
        else if (theme == Integer.parseInt(appContext.getString(R.string.pref_theme_black_value)))
            return R.style.AppTheme_Translucent_Black;

        return R.style.AppTheme_Translucent;
    }

    public static int getSettingsTheme(@NonNull Context context)
    {
        int theme = getThemePreference(context);

        if (theme == Integer.parseInt(context.getString(R.string.pref_theme_light_value)))
            return R.style.BaseTheme_Settings;
        else if (theme == Integer.parseInt(context.getString(R.string.pref_theme_dark_value)))
            return R.style.BaseTheme_Settings_Dark;
        else if (theme == Integer.parseInt(context.getString(R.string.pref_theme_black_value)))
            return R.style.BaseTheme_Settings_Black;

        return R.style.BaseTheme_Settings;
    }

    public static TorrentSorting getTorrentSorting(@NonNull Context context)
    {
        SharedPreferences pref = SettingsManager.getInstance(context).getPreferences();

        String column = pref.getString(context.getString(R.string.pref_key_sort_torrent_by),
                                       SettingsManager.Default.sortTorrentBy);
        String direction = pref.getString(context.getString(R.string.pref_key_sort_torrent_direction),
                                          SettingsManager.Default.sortTorrentDirection);

        return new TorrentSorting(TorrentSorting.SortingColumns.fromValue(column),
                TorrentSorting.Direction.fromValue(direction));
    }

    public static boolean checkStoragePermission(@NonNull Context context)
    {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * Migrate from Tray settings database to shared preferences.
     * TODO: delete after some releases
     */
    @Deprecated
    public static void migrateTray2SharedPreferences(@NonNull Context context)
    {
        final String TAG = "tray2shared";
        final String migrate_key = "tray2shared_migrated";
        SharedPreferences pref = SettingsManager.getInstance(context).getPreferences();

        if (pref.getBoolean(migrate_key, false))
            return;

        File dbFile = context.getDatabasePath("tray.db");
        if (dbFile == null || !dbFile.exists()) {
            Log.w(TAG, "Database not found");
            pref.edit().putBoolean(migrate_key, true).apply();

            return;
        }
        SQLiteDatabase db;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open database: " + Log.getStackTraceString(e));
            context.deleteDatabase("tray");
            pref.edit().putBoolean(migrate_key, true).apply();

            return;
        }
        Cursor c = db.query("TrayPreferences",
                            new String[]{"KEY", "VALUE"},
                            null,
                            null,
                            null,
                            null,
                            null);
        SharedPreferences.Editor edit = pref.edit();
        Log.i(TAG, "Start migrate");
        try {
            int key_i = c.getColumnIndex("KEY");
            int value_i = c.getColumnIndex("VALUE");
            while (c.moveToNext()) {
                String key = c.getString(key_i);
                String value = c.getString(value_i);

                if (value.equalsIgnoreCase("true")) {
                    edit.putBoolean(key, true);
                } else if (value.equalsIgnoreCase("false")) {
                    edit.putBoolean(key, false);
                } else {
                    try {
                        int number = Integer.parseInt(value);
                        edit.putInt(key, number);
                    } catch (NumberFormatException e) {
                        edit.putString(key, value);
                    }
                }
            }
            Log.i(TAG, "Migrate completed");

        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            c.close();
            context.deleteDatabase("tray.db");
            edit.putBoolean(migrate_key, true);
            edit.apply();
        }
    }

    /*
     * Workaround for start service in Android 8+ if app no started.
     * We have a window of time to get around to calling startForeground() before we get ANR,
     * if work is longer than a millisecond but less than a few seconds.
     */

    public static void startServiceBackground(@NonNull Context context, @NonNull Intent i)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(i);
        else
            context.startService(i);
    }

    public static void enableBootReceiver(@NonNull Context context, boolean enable)
    {
        SharedPreferences pref = SettingsManager.getInstance(context).getPreferences();
        boolean schedulingStart = pref.getBoolean(context.getString(R.string.pref_key_enable_scheduling_start),
                                                  SettingsManager.Default.enableSchedulingStart);
        boolean schedulingStop = pref.getBoolean(context.getString(R.string.pref_key_enable_scheduling_shutdown),
                                                 SettingsManager.Default.enableSchedulingShutdown);
        boolean autostart = pref.getBoolean(context.getString(R.string.pref_key_autostart),
                                            SettingsManager.Default.autostart);
        boolean autoRefreshFeeds = pref.getBoolean(context.getString(R.string.pref_key_feed_auto_refresh),
                                                   SettingsManager.Default.autoRefreshFeeds);
        int flag = (!(enable || schedulingStart || schedulingStop || autostart || autoRefreshFeeds) ?
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        ComponentName bootReceiver = new ComponentName(context, BootReceiver.class);
        context.getPackageManager()
                .setComponentEnabledSetting(bootReceiver, flag, PackageManager.DONT_KILL_APP);
    }

    public static void enableBootReceiverIfNeeded(@NonNull Context context)
    {
        SharedPreferences pref = SettingsManager.getInstance(context).getPreferences();
        boolean schedulingStart = pref.getBoolean(context.getString(R.string.pref_key_enable_scheduling_start),
                SettingsManager.Default.enableSchedulingStart);
        boolean schedulingStop = pref.getBoolean(context.getString(R.string.pref_key_enable_scheduling_shutdown),
                SettingsManager.Default.enableSchedulingShutdown);
        boolean autostart = pref.getBoolean(context.getString(R.string.pref_key_autostart),
                SettingsManager.Default.autostart);
        boolean autoRefreshFeeds = pref.getBoolean(context.getString(R.string.pref_key_feed_auto_refresh),
                                                   SettingsManager.Default.autoRefreshFeeds);
        int flag = (!(schedulingStart || schedulingStop || autostart || autoRefreshFeeds) ?
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        ComponentName bootReceiver = new ComponentName(context, BootReceiver.class);
        context.getPackageManager()
                .setComponentEnabledSetting(bootReceiver, flag, PackageManager.DONT_KILL_APP);
    }

    public static byte[] fetchHttpUrl(@NonNull Context context,
                                      @NonNull String url) throws FetchLinkException
    {
        byte[][] response = new byte[1][];

        if (!Utils.checkConnectivity(context))
            throw new FetchLinkException("No network connection");

        final ArrayList<Throwable> errorArray = new ArrayList<>(1);
        HttpConnection connection;
        try {
            connection = new HttpConnection(url);
        } catch (Exception e) {
            throw new FetchLinkException(e);
        }

        connection.setListener(new HttpConnection.Listener() {
            @Override
            public void onConnectionCreated(HttpURLConnection conn)
            {
                /* Nothing */
            }

            @Override
            public void onResponseHandle(HttpURLConnection conn, int code, String message)
            {
                if (code == HttpURLConnection.HTTP_OK) {
                    try {
                        response[0] = IOUtils.toByteArray(conn.getInputStream());

                    } catch (IOException e) {
                        errorArray.add(e);
                    }
                } else {
                    errorArray.add(new FetchLinkException("Failed to fetch link, response code: " + code));
                }
            }

            @Override
            public void onMovedPermanently(String newUrl)
            {
                /* Nothing */
            }

            @Override
            public void onIOException(IOException e)
            {
                errorArray.add(e);
            }

            @Override
            public void onTooManyRedirects()
            {
                errorArray.add(new FetchLinkException("Too many redirects"));
            }
        });
        connection.run();

        if (!errorArray.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (Throwable e : errorArray)
                s.append(e.getMessage().concat("\n"));

            throw new FetchLinkException(s.toString());
        }

        return response[0];
    }

    public static String getAppVersionName(@NonNull Context context)
    {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            /* Ignore */
        }

        return null;
    }

    /*
     * Without additional information (e.g -DEBUG)
     */

    public static String getAppVersionNumber(@NonNull String versionName)
    {
        int index = versionName.indexOf("-");
        if (index >= 0)
            versionName = versionName.substring(0, index);

        return versionName;
    }

    /*
     * Return version components in these format: [major, minor, revision]
     */

    public static int[] getVersionComponents(@NonNull String versionName)
    {
        int[] version = new int[3];

        /* Discard additional information */
        versionName = getAppVersionNumber(versionName);

        String[] components = versionName.split("\\.");
        if (components.length < 2)
            return version;

        try {
            version[0] = Integer.parseInt(components[0]);
            version[1] = Integer.parseInt(components[1]);
            if (components.length >= 3)
                version[2] = Integer.parseInt(components[2]);

        } catch (NumberFormatException e) {
            /* Ignore */
        }

        return version;
    }

    public static String makeSha1Hash(@NonNull String s)
    {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        messageDigest.update(s.getBytes(Charset.forName("UTF-8")));
        StringBuilder sha1 = new StringBuilder();
        for (byte b : messageDigest.digest()) {
            if ((0xff & b) < 0x10)
                sha1.append("0");
            sha1.append(Integer.toHexString(0xff & b));
        }

        return sha1.toString();
    }

    public static SSLContext getSSLContext() throws GeneralSecurityException
    {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore)null);

        TrustManager[] trustManagers = tmf.getTrustManagers();
        final X509TrustManager origTrustManager = (X509TrustManager)trustManagers[0];

        TrustManager[] wrappedTrustManagers = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return origTrustManager.getAcceptedIssuers();
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException
                    {
                        origTrustManager.checkClientTrusted(certs, authType);
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException
                    {
                        origTrustManager.checkServerTrusted(certs, authType);
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, wrappedTrustManagers, null);

        return sslContext;
    }

    public static String getErrorMsg(ErrorCode error)
    {
        return (error == null ? "" : error.message() + ", code " + error.value());
    }

    public static void showActionModeStatusBar(@NonNull Activity activity, boolean mode)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return;

        int color = (mode ? R.color.action_mode_dark : R.color.primary_dark);
        activity.getWindow().setStatusBarColor(ContextCompat.getColor(activity, color));
    }

    public static int getAttributeColor(@NonNull Context context, int attributeId)
    {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(attributeId, typedValue, true);
        int colorRes = typedValue.resourceId;
        int color = -1;
        try {
            color = context.getResources().getColor(colorRes);

        } catch (Resources.NotFoundException e) {
            return color;
        }

        return color;
    }

    /*
     * Return path to the current torrent download directory.
     * If the directory doesn't exist, the function creates it automatically
     */

    public static Uri getTorrentDownloadPath(@NonNull Context appContext)
    {
        SharedPreferences pref = SettingsManager.getInstance(appContext).getPreferences();
        String path = pref.getString(appContext.getString(R.string.pref_key_save_torrents_in),
                                     SettingsManager.Default.saveTorrentsIn);

        path = (TextUtils.isEmpty(path) ? FileUtils.getDefaultDownloadPath() : path);

        return (path == null ? null : Uri.parse(FileUtils.normalizeFilesystemPath(path)));
    }

    public static void setTextViewStyle(@NonNull Context context,
                                        @NonNull TextView textView,
                                        int resId)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            textView.setTextAppearance(context, resId);
        else
            textView.setTextAppearance(resId);
    }

    public static List<DrawerGroup> getNavigationDrawerItems(@NonNull Context context,
                                                             @NonNull SharedPreferences localPref)
    {
        Resources res = context.getResources();

        ArrayList<DrawerGroup> groups = new ArrayList<>();

        DrawerGroup status = new DrawerGroup(res.getInteger(R.integer.drawer_status_id),
                res.getString(R.string.drawer_status),
                localPref.getBoolean(res.getString(R.string.drawer_status_is_expanded), true));
        status.selectItem(localPref.getLong(res.getString(R.string.drawer_status_selected_item),
                                            DrawerGroup.DEFAULT_SELECTED_ID));

        DrawerGroup sorting = new DrawerGroup(res.getInteger(R.integer.drawer_sorting_id),
                res.getString(R.string.drawer_sorting),
                localPref.getBoolean(res.getString(R.string.drawer_sorting_is_expanded), false));
        sorting.selectItem(localPref.getLong(res.getString(R.string.drawer_sorting_selected_item),
                                             DrawerGroup.DEFAULT_SELECTED_ID));

        DrawerGroup dateAdded = new DrawerGroup(res.getInteger(R.integer.drawer_date_added_id),
                res.getString(R.string.drawer_date_added),
                localPref.getBoolean(res.getString(R.string.drawer_time_is_expanded), false));
        dateAdded.selectItem(localPref.getLong(res.getString(R.string.drawer_time_selected_item),
                                               DrawerGroup.DEFAULT_SELECTED_ID));

        status.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_status_all_id),
                R.drawable.ic_all_inclusive_grey600_24dp, res.getString(R.string.all)));
        status.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_status_downloading_id),
                R.drawable.ic_download_grey600_24dp, res.getString(R.string.drawer_status_downloading)));
        status.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_status_downloaded_id),
                R.drawable.ic_file_grey600_24dp, res.getString(R.string.drawer_status_downloaded)));
        status.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_status_downloading_metadata_id),
                R.drawable.ic_magnet_grey600_24dp, res.getString(R.string.drawer_status_downloading_metadata)));

        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_date_added_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_date_added)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_date_added_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_date_added)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_name_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_name)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_name_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_name)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_size_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_size)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_size_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_size)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_progress_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_progress)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_progress_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_progress)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_ETA_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_ETA)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_ETA_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_ETA)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_peers_asc_id),
                R.drawable.ic_sort_ascending_grey600_24dp, res.getString(R.string.drawer_sorting_peers)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_peers_desc_id),
                R.drawable.ic_sort_descending_grey600_24dp, res.getString(R.string.drawer_sorting_peers)));
        sorting.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_sorting_no_sorting_id),
                R.drawable.ic_sort_off_grey600_24dp, res.getString(R.string.drawer_sorting_no_sorting)));

        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_all_id),
                R.drawable.ic_all_inclusive_grey600_24dp, res.getString(R.string.all)));
        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_today_id),
                R.drawable.ic_calendar_today_grey600_24dp, res.getString(R.string.drawer_date_added_today)));
        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_yesterday_id),
                R.drawable.ic_calendar_yesterday_grey600_24dp, res.getString(R.string.drawer_date_added_yesterday)));
        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_week_id),
                R.drawable.ic_calendar_week_grey600_24dp, res.getString(R.string.drawer_date_added_week)));
        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_month_id),
                R.drawable.ic_calendar_month_grey600_24dp, res.getString(R.string.drawer_date_added_month)));
        dateAdded.items.add(new DrawerGroupItem(res.getInteger(R.integer.drawer_date_added_year_id),
                R.drawable.ic_calendar_year_grey600_24dp, res.getString(R.string.drawer_date_added_year)));

        groups.add(status);
        groups.add(sorting);
        groups.add(dateAdded);

        return groups;
    }

    public static TorrentSortingComparator getDrawerGroupItemSorting(@NonNull Context context,
                                                                     long itemId)
    {
        Resources res = context.getResources();
        if (itemId == res.getInteger(R.integer.drawer_sorting_no_sorting_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.none, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_name_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.name, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_name_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.name, TorrentSorting.Direction.DESC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_size_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.size, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_size_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.size, TorrentSorting.Direction.DESC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_date_added_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.dateAdded, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_date_added_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.dateAdded, TorrentSorting.Direction.DESC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_progress_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.progress, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_progress_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.progress, TorrentSorting.Direction.DESC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_ETA_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.ETA, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_ETA_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.ETA, TorrentSorting.Direction.DESC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_peers_asc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.peers, TorrentSorting.Direction.ASC));
        else if (itemId == res.getInteger(R.integer.drawer_sorting_peers_desc_id))
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.peers, TorrentSorting.Direction.DESC));
        else
            return new TorrentSortingComparator(new TorrentSorting(TorrentSorting.SortingColumns.none, TorrentSorting.Direction.ASC));
    }

    public static TorrentFilter getDrawerGroupStatusFilter(@NonNull Context context,
                                                           long itemId)
    {
        Resources res = context.getResources();
        if (itemId == res.getInteger(R.integer.drawer_status_all_id))
            return TorrentFilterCollection.all();
        else if (itemId == res.getInteger(R.integer.drawer_status_downloading_id))
            return TorrentFilterCollection.statusDownloading();
        else if (itemId == res.getInteger(R.integer.drawer_status_downloaded_id))
            return TorrentFilterCollection.statusDownloaded();
        else if (itemId == res.getInteger(R.integer.drawer_status_downloading_metadata_id))
            return TorrentFilterCollection.statusDownloadingMetadata();
        else
            return TorrentFilterCollection.all();
    }

    public static TorrentFilter getDrawerGroupDateAddedFilter(@NonNull Context context,
                                                              long itemId)
    {
        Resources res = context.getResources();
        if (itemId == res.getInteger(R.integer.drawer_date_added_all_id))
            return TorrentFilterCollection.all();
        else if (itemId == res.getInteger(R.integer.drawer_date_added_today_id))
            return TorrentFilterCollection.dateAddedToday();
        else if (itemId == res.getInteger(R.integer.drawer_date_added_yesterday_id))
            return TorrentFilterCollection.dateAddedYesterday();
        else if (itemId == res.getInteger(R.integer.drawer_date_added_week_id))
            return TorrentFilterCollection.dateAddedWeek();
        else if (itemId == res.getInteger(R.integer.drawer_date_added_month_id))
            return TorrentFilterCollection.dateAddedMonth();
        else if (itemId == res.getInteger(R.integer.drawer_date_added_year_id))
            return TorrentFilterCollection.dateAddedYear();
        else
            return TorrentFilterCollection.all();
    }
}
