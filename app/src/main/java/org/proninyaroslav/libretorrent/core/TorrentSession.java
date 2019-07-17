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

package org.proninyaroslav.libretorrent.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import org.apache.commons.io.FileUtils;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.Pair;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TcpEndpoint;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.ListenFailedAlert;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.PortmapErrorAlert;
import org.libtorrent4j.alerts.SessionErrorAlert;
import org.libtorrent4j.alerts.TorrentAlert;
import org.libtorrent4j.alerts.TorrentRemovedAlert;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.bdecode_node;
import org.libtorrent4j.swig.byte_vector;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.entry;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.int_vector;
import org.libtorrent4j.swig.ip_filter;
import org.libtorrent4j.swig.libtorrent;
import org.libtorrent4j.swig.session_params;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.sha1_hash;
import org.libtorrent4j.swig.string_vector;
import org.libtorrent4j.swig.tcp_endpoint_vector;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle;
import org.libtorrent4j.swig.torrent_info;
import org.proninyaroslav.libretorrent.MainApplication;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.entity.FastResume;
import org.proninyaroslav.libretorrent.core.entity.Torrent;
import org.proninyaroslav.libretorrent.core.exceptions.DecodeException;
import org.proninyaroslav.libretorrent.core.exceptions.TorrentAlreadyExistsException;
import org.proninyaroslav.libretorrent.core.storage.TorrentRepository;
import org.proninyaroslav.libretorrent.core.utils.Utils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.NonNull;

import io.reactivex.disposables.CompositeDisposable;

public class TorrentSession extends SessionManager
{
    @SuppressWarnings("unused")
    private static final String TAG = TorrentSession.class.getSimpleName();

    private static final int[] INNER_LISTENER_TYPES = new int[] {
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.SESSION_ERROR.swig(),
            AlertType.PORTMAP_ERROR.swig(),
            AlertType.LISTEN_FAILED.swig()
    };

    private Context appContext;
    private InnerListener innerListener;
    private ConcurrentLinkedQueue<TorrentEngineListener> listeners = new ConcurrentLinkedQueue<>();
    private Settings settings = new Settings();
    private Queue<LoadTorrentTask> restoreTorrentsQueue = new LinkedList<>();
    private ExecutorService loadTorrentsExec;
    private HashMap<String, TorrentDownload> torrentTasks = new HashMap<>();
    /* Wait list for non added magnets */
    private ArrayList<String> magnets = new ArrayList<>();
    private ConcurrentHashMap<String, byte[]> loadedMagnets = new ConcurrentHashMap<>();
    private ArrayList<String> addTorrentsList = new ArrayList<>();
    private ReentrantLock syncMagnet = new ReentrantLock();
    private CompositeDisposable disposables = new CompositeDisposable();
    private TorrentRepository repo;

    /* Base unit in KiB. Used for create torrent */
    public static final int[] pieceSize = {0, 16, 32, 64, 128, 256, 512,
            1024, 2048, 4096, 8192, 16384, 32768};
    public static final String PEER_FINGERPRINT = "Lr"; /* called peer id */
    public static final String USER_AGENT = "LibreTorrent %s";

    public static class Settings
    {
        public static final int DEFAULT_CACHE_SIZE = 256;
        public static final int DEFAULT_ACTIVE_DOWNLOADS = 4;
        public static final int DEFAULT_ACTIVE_SEEDS = 4;
        public static final int DEFAULT_MAX_PEER_LIST_SIZE = 200;
        public static final int DEFAULT_TICK_INTERVAL = 1000;
        public static final int DEFAULT_INACTIVITY_TIMEOUT = 60;
        public static final int MIN_CONNECTIONS_LIMIT = 2;
        public static final int DEFAULT_CONNECTIONS_LIMIT = 200;
        public static final int DEFAULT_CONNECTIONS_LIMIT_PER_TORRENT = 40;
        public static final int DEFAULT_UPLOADS_LIMIT_PER_TORRENT = 4;
        public static final int DEFAULT_ACTIVE_LIMIT = 6;
        public static final int MAX_PORT_NUMBER = 50256;
        public static final int MIN_PORT_NUMBER = 40256;
        public static final int DEFAULT_DOWNLOAD_RATE_LIMIT = 0;
        public static final int DEFAULT_UPLOAD_RATE_LIMIT = 0;
        public static final boolean DEFAULT_DHT_ENABLED = true;
        public static final boolean DEFAULT_LSD_ENABLED = true;
        public static final boolean DEFAULT_UTP_ENABLED = true;
        public static final boolean DEFAULT_UPNP_ENABLED = true;
        public static final boolean DEFAULT_NATPMP_ENABLED = true;
        public static final boolean DEFAULT_ENCRYPT_IN_CONNECTIONS = true;
        public static final boolean DEFAULT_ENCRYPT_OUT_CONNECTIONS = true;
        public static final int DEFAULT_ENCRYPT_MODE = settings_pack.enc_policy.pe_enabled.swigValue();
        public static final boolean DEFAULT_AUTO_MANAGED = false;
        public static final String DEFAULT_INETADDRESS = "0.0.0.0";
        public static final int DEFAULT_PORT_RANGE_FIRST = MIN_PORT_NUMBER;
        public static final int DEFAULT_PORT_RANGE_SECOND = MAX_PORT_NUMBER;

        public int cacheSize = DEFAULT_CACHE_SIZE;
        public int activeDownloads = DEFAULT_ACTIVE_DOWNLOADS;
        public int activeSeeds = DEFAULT_ACTIVE_SEEDS;
        public int maxPeerListSize = DEFAULT_MAX_PEER_LIST_SIZE;
        public int tickInterval = DEFAULT_TICK_INTERVAL;
        public int inactivityTimeout = DEFAULT_INACTIVITY_TIMEOUT;
        public int connectionsLimit = DEFAULT_CONNECTIONS_LIMIT;
        public int connectionsLimitPerTorrent = DEFAULT_CONNECTIONS_LIMIT_PER_TORRENT;
        public int uploadsLimitPerTorrent = DEFAULT_UPLOADS_LIMIT_PER_TORRENT;
        public int activeLimit = DEFAULT_ACTIVE_LIMIT;
        public int portRangeFirst = DEFAULT_PORT_RANGE_FIRST;
        public int portRangeSecond = DEFAULT_PORT_RANGE_SECOND;
        public int downloadRateLimit = DEFAULT_DOWNLOAD_RATE_LIMIT;
        public int uploadRateLimit = DEFAULT_UPLOAD_RATE_LIMIT;
        public boolean dhtEnabled = DEFAULT_DHT_ENABLED;
        public boolean lsdEnabled = DEFAULT_LSD_ENABLED;
        public boolean utpEnabled = DEFAULT_UTP_ENABLED;
        public boolean upnpEnabled = DEFAULT_UPNP_ENABLED;
        public boolean natPmpEnabled = DEFAULT_NATPMP_ENABLED;
        public boolean encryptInConnections = DEFAULT_ENCRYPT_IN_CONNECTIONS;
        public boolean encryptOutConnections = DEFAULT_ENCRYPT_OUT_CONNECTIONS;
        public int encryptMode = DEFAULT_ENCRYPT_MODE;
        public boolean autoManaged = DEFAULT_AUTO_MANAGED;
        public String inetAddress = DEFAULT_INETADDRESS;
    }

    public TorrentSession(@NonNull Context appContext)
    {
        this.appContext = appContext;
        repo = ((MainApplication)appContext).getTorrentRepository();
        innerListener = new InnerListener();
        loadTorrentsExec = Executors.newCachedThreadPool();
    }

    public void addListener(TorrentEngineListener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(TorrentEngineListener listener)
    {
        listeners.remove(listener);
    }

    public TorrentDownload getTask(String id)
    {
        return torrentTasks.get(id);
    }

    public Collection<TorrentDownload> getTasks()
    {
        return torrentTasks.values();
    }

    public void setSettings(@NonNull Settings settings)
    {
        this.settings = settings;
        applySettings(settings);
    }

    public Settings getSettings()
    {
        return settings;
    }

    public byte[] getLoadedMagnet(String hash)
    {
        return loadedMagnets.get(hash);
    }

    public void removeLoadedMagnet(String hash)
    {
        loadedMagnets.remove(hash);
    }

    public Torrent addTorrent(@NonNull AddTorrentParams params,
                              boolean removeFile) throws IOException, TorrentAlreadyExistsException, DecodeException
    {
        Torrent torrent = new Torrent(params.sha1hash, params.downloadPath, params.name,
                params.addPaused, System.currentTimeMillis());

        byte[] bencode = null;
        if (params.fromMagnet) {
            bencode = getLoadedMagnet(params.sha1hash);
            removeLoadedMagnet(params.sha1hash);
            if (bencode == null)
                torrent.setMagnetUri(params.source);
        }

        if (repo.getTorrentById(torrent.id) != null) {
            mergeTorrent(torrent.id, params, bencode);
            throw new TorrentAlreadyExistsException();
        }

        repo.addTorrent(torrent);

        if (!torrent.isDownloadingMetadata()) {
            /*
             * This is possible if the magnet data came after Torrent object
             * has already been created and nothing is known about the received data
             */
            if (params.filePriorities.isEmpty()) {
                ContentResolver contentResolver = appContext.getContentResolver();
                try (ParcelFileDescriptor outPfd = contentResolver.openFileDescriptor(Uri.parse(params.source), "r")) {
                    FileDescriptor outFd = outPfd.getFileDescriptor();
                    try (FileInputStream is = new FileInputStream(outFd)) {
                        TorrentMetaInfo info = new TorrentMetaInfo(is);
                        params.filePriorities = Collections.nCopies(info.fileCount, Priority.DEFAULT);
                    }
                } catch (FileNotFoundException e) {
                    /* Ignore */
                }
            }
        }

        try {
            download(torrent.id, params, bencode);

        } catch (Exception e) {
            repo.deleteTorrent(torrent);
            throw e;
        } finally {
            if (removeFile && !params.fromMagnet)
                org.proninyaroslav.libretorrent.core.utils.FileUtils.deleteFile(appContext, Uri.parse(params.source));
        }

        return torrent;
    }

    private void download(String id, AddTorrentParams params, byte[] bencode) throws IOException
    {
        if (magnets.contains(id))
            cancelFetchMagnet(id);

        /* TODO: SAF support */
        if (org.proninyaroslav.libretorrent.core.utils.FileUtils.isSAFPath(params.downloadPath))
            throw new IllegalArgumentException("SAF is not supported:" + params.downloadPath);

        Torrent torrent = repo.getTorrentById(id);
        if (torrent == null)
            throw new IOException("Torrent " + id + " is null");

        addTorrentsList.add(params.sha1hash);

        File saveDir = new File(params.downloadPath.getPath());
        if (torrent.isDownloadingMetadata()) {
            download(params.source, saveDir, params.addPaused);
            return;
        }

        TorrentDownload task = torrentTasks.get(torrent.id);
        if (task != null)
            task.remove(false);

        if (params.fromMagnet) {
            download(bencode,
                    saveDir,
                    params.filePriorities.toArray(new Priority[0]),
                    params.sequentialDownload,
                    params.addPaused,
                    null);
        } else {
            download(new TorrentInfo(new File(Uri.parse(params.source).getPath())),
                    saveDir,
                    params.filePriorities.toArray(new Priority[0]),
                    params.sequentialDownload,
                    params.addPaused,
                    null);
        }
    }

    public void deleteTorrent(@NonNull String id, boolean withFiles)
    {
        if (swig() == null)
            return;

        TorrentDownload task = getTask(id);
        if (task == null) {
            Torrent torrent = repo.getTorrentById(id);
            if (torrent != null)
                repo.deleteTorrent(torrent);

            notifyListeners((listener) ->
                    listener.onTorrentRemoved(id));
        } else {
            task.remove(withFiles);
        }
    }

    public void restoreTorrents()
    {
        if (swig() == null)
            return;

        for (Torrent torrent : repo.getAllTorrents()) {
            if (torrent == null || torrentTasks.containsKey(torrent.id))
                continue;

            /* TODO: SAF support */
            if (org.proninyaroslav.libretorrent.core.utils.FileUtils.isSAFPath(torrent.downloadPath))
                throw new IllegalArgumentException("SAF is not supported:" + torrent.downloadPath);

            LoadTorrentTask loadTask = new LoadTorrentTask(torrent.id);
            if (torrent.isDownloadingMetadata())
                loadTask.putMagnet(torrent.getMagnet(), new File(torrent.downloadPath.getPath()),
                        torrent.manuallyPaused);

            restoreTorrentsQueue.add(loadTask);
        }
        runNextLoadTorrentTask();
    }

    public org.libtorrent4j.AddTorrentParams parseMagnetUri(@NonNull String uri)
    {
        error_code ec = new error_code();
        add_torrent_params p = add_torrent_params.parse_magnet_uri(uri, ec);

        if (ec.value() != 0)
            throw new IllegalArgumentException(ec.message());

        return new org.libtorrent4j.AddTorrentParams(p);
    }

    public org.libtorrent4j.AddTorrentParams fetchMagnet(org.libtorrent4j.AddTorrentParams params) throws Exception
    {
        if (swig() == null)
            return null;

        add_torrent_params p = params.swig();

        p.set_disabled_storage();
        sha1_hash hash = p.getInfo_hash();
        String strHash = hash.to_hex();
        torrent_handle th = null;
        boolean add = false;

        try {
            syncMagnet.lock();

            try {
                th = swig().find_torrent(hash);
                if (th != null && th.is_valid()) {
                    torrent_info ti = th.torrent_file_ptr();
                    loadedMagnets.put(hash.to_hex(), createTorrent(p, ti));
                    notifyListeners((listener) ->
                            listener.onMagnetLoaded(strHash, ti != null ? new TorrentInfo(ti).bencode() : null));
                } else {
                    add = true;
                }

                if (add) {
                    if (TextUtils.isEmpty(p.getName()))
                        p.setName(strHash);
                    torrent_flags_t flags = p.getFlags();
                    flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv());
                    flags = flags.or_(TorrentFlags.UPLOAD_MODE);
                    flags = flags.or_(TorrentFlags.STOP_WHEN_READY);
                    p.setFlags(flags);

                    error_code ec = new error_code();
                    th = swig().add_torrent(p, ec);
                    th.resume();
                }

            } finally {
                syncMagnet.unlock();
            }

        } catch (Exception e) {
            if (add && th != null && th.is_valid())
                swig().remove_torrent(th);

            throw new Exception(e);
        }

        if (th.is_valid() && add)
            magnets.add(strHash);

        return new org.libtorrent4j.AddTorrentParams(p);
    }

    public void cancelFetchMagnet(@NonNull String infoHash)
    {
        if (swig() == null || !magnets.contains(infoHash))
            return;

        magnets.remove(infoHash);
        TorrentHandle th = find(new Sha1Hash(infoHash));
        if (th != null && th.isValid())
            remove(th, SessionHandle.DELETE_FILES);
    }

    private void mergeTorrent(String id, AddTorrentParams params, byte[] bencode)
    {
        if (swig() == null)
            return;

        TorrentDownload task = torrentTasks.get(id);
        if (task == null)
            return;

        TorrentInfo ti = null;
        try {
            ti = (bencode == null ?
                    new TorrentInfo(new File(Uri.parse(params.source).getPath())) :
                    new TorrentInfo(bencode));

        } catch (Exception e) {
            /* Ignore */
        }

        task.setSequentialDownload(params.sequentialDownload);
        if (ti != null) {
            task.addTrackers(ti.trackers());
            task.addWebSeeds(ti.webSeeds());
        }
        Priority[] priorities = new Priority[params.filePriorities.size()];
        task.prioritizeFiles(params.filePriorities.toArray(priorities));
        if (params.addPaused)
            task.pauseManually();
        else
            task.resumeManually();
    }

    public long getDownloadRate()
    {
        return stats().downloadRate();
    }

    public long getUploadRate()
    {
        return stats().uploadRate();
    }

    public long getTotalDownload()
    {
        return stats().totalDownload();
    }

    public long getTotalUpload()
    {
        return stats().totalUpload();
    }

    public int getDownloadRateLimit()
    {
        SettingsPack settingsPack = settings();

        return (settingsPack == null ? -1 : settingsPack.downloadRateLimit());
    }

    public int getUploadRateLimit()
    {
        SettingsPack settingsPack = settings();

        return (settingsPack == null ? -1 : settingsPack.uploadRateLimit());
    }

    public int getListenPort()
    {
        return (swig() == null ? -1 : swig().listen_port());
    }

    public void setPortRange(int portFirst, int portSecond)
    {
        if (swig() == null || portFirst == -1 || portSecond == -1)
            return;

        settings.portRangeFirst = portFirst;
        settings.portRangeSecond = portSecond;
        applySettings(settings);
    }

    public void setRandomPortRange()
    {
        Pair<Integer, Integer> portRange = getRandomRangePort();
        setPortRange(portRange.first, portRange.second);
    }

    /*
     * Get the first port in range [37000, 57000] and the second `first` + 10
     */

    private Pair<Integer, Integer> getRandomRangePort()
    {
        int port = 37000 + new Random().nextInt(20000);

        return new Pair<>(port , port + 10);
    }

    public void enableIpFilter(@NonNull Uri path)
    {
        if (swig() == null)
            return;

        IPFilterParser parser = new IPFilterParser(path);
        disposables.add(parser.parse(appContext)
                .subscribe((filter) -> {
                            if (swig() != null)
                                swig().set_ip_filter(filter);
                            notifyListeners((listener) ->
                                    listener.onIpFilterParsed(true));
                        },
                        (Throwable t) -> {
                            notifyListeners((listener) ->
                                    listener.onIpFilterParsed(false));
                        })
        );
    }

    public void disableIpFilter()
    {
        if (swig() == null)
            return;

        swig().set_ip_filter(new ip_filter());
    }

    public void setProxy(@NonNull ProxySettingsPack proxy)
    {
        if (swig() == null || proxy.getType() == ProxySettingsPack.ProxyType.NONE)
            return;

        SettingsPack sp = settings();
        settings_pack.proxy_type_t type = settings_pack.proxy_type_t.none;
        switch (proxy.getType()) {
            case SOCKS4:
                type = settings_pack.proxy_type_t.socks4;
                break;
            case SOCKS5:
                type = (TextUtils.isEmpty(proxy.getAddress()) ? settings_pack.proxy_type_t.socks5 :
                        settings_pack.proxy_type_t.socks5_pw);
                break;
            case HTTP:
                type = (TextUtils.isEmpty(proxy.getAddress()) ? settings_pack.proxy_type_t.http :
                        settings_pack.proxy_type_t.http_pw);
                break;
        }

        sp.setInteger(settings_pack.int_types.proxy_type.swigValue(), type.swigValue());
        sp.setInteger(settings_pack.int_types.proxy_port.swigValue(), proxy.getPort());
        sp.setString(settings_pack.string_types.proxy_hostname.swigValue(), proxy.getAddress());
        sp.setString(settings_pack.string_types.proxy_username.swigValue(), proxy.getLogin());
        sp.setString(settings_pack.string_types.proxy_password.swigValue(), proxy.getPassword());
        sp.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), proxy.isProxyPeersToo());

        applySettingsPack(sp);
    }

    public boolean isLSDEnabled()
    {
        return swig() != null && settings().broadcastLSD();
    }

    public void pauseAll()
    {
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.pause();
        }
    }

    public void resumeAll()
    {
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.resume();
        }
    }

    public void pauseAllManually()
    {
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.pauseManually();
        }
    }

    public void resumeAllManually()
    {
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.resumeManually();
        }
    }

    public void setMaxConnectionsPerTorrent(int connections)
    {
        settings.connectionsLimitPerTorrent = connections;
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.setMaxConnections(connections);
        }
    }

    public void setMaxUploadsPerTorrent(int uploads)
    {
        settings.uploadsLimitPerTorrent = uploads;
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.setMaxUploads(uploads);
        }
    }

    public void setAutoManaged(boolean autoManaged)
    {
        settings.autoManaged = autoManaged;
        for (TorrentDownload task : torrentTasks.values())
            task.setAutoManaged(autoManaged);
    }

    public ProxySettingsPack getProxy()
    {
        if (swig() == null)
            return null;

        ProxySettingsPack proxy = new ProxySettingsPack();
        SettingsPack sp = settings();
        if (sp == null)
            return null;

        ProxySettingsPack.ProxyType type;
        String swigType = sp.getString(settings_pack.int_types.proxy_type.swigValue());

        type = ProxySettingsPack.ProxyType.NONE;
        if (swigType.equals(settings_pack.proxy_type_t.socks4.toString()))
            type = ProxySettingsPack.ProxyType.SOCKS4;
        else if (swigType.equals(settings_pack.proxy_type_t.socks5.toString()))
            type = ProxySettingsPack.ProxyType.SOCKS5;
        else if (swigType.equals(settings_pack.proxy_type_t.http.toString()))
            type = ProxySettingsPack.ProxyType.HTTP;

        proxy.setType(type);
        proxy.setPort(sp.getInteger(settings_pack.int_types.proxy_port.swigValue()));
        proxy.setAddress(sp.getString(settings_pack.string_types.proxy_hostname.swigValue()));
        proxy.setLogin(sp.getString(settings_pack.string_types.proxy_username.swigValue()));
        proxy.setPassword(sp.getString(settings_pack.string_types.proxy_password.swigValue()));
        proxy.setProxyPeersToo(sp.getBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue()));

        return proxy;
    }

    public void disableProxy()
    {
        setProxy(new ProxySettingsPack());
    }

    public boolean isDHTEnabled()
    {
        SettingsPack settingsPack = settings();

        return settingsPack != null && settingsPack.enableDht();
    }

    public boolean isPeXEnabled()
    {
        /* PeX enabled by default in session_handle.session_flags_t::add_default_plugins */
        return true;
    }

    @Override
    public void start()
    {
        SessionParams params = loadSettings();
        settings_pack sp = params.settings().swig();
        sp.set_str(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), dhtBootstrapNodes());
        sp.set_bool(settings_pack.bool_types.enable_ip_notifier.swigValue(), false);
        sp.set_int(settings_pack.int_types.stop_tracker_timeout.swigValue(), 0);
        sp.set_int(settings_pack.int_types.alert_queue_size.swigValue(), 5000);
        sp.set_bool(settings_pack.bool_types.upnp_ignore_nonrouters.swigValue(), true);

        Pair<Integer, Integer> portRange = getRandomRangePort();
        settings.portRangeFirst = portRange.first;
        settings.portRangeSecond = portRange.second;
        sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), getIface());
        sp.set_int(settings_pack.int_types.max_retry_port_bind.swigValue(),
                settings.portRangeSecond - settings.portRangeFirst);

        String versionName = Utils.getAppVersionName(appContext);
        if (versionName != null) {
            int[] version = Utils.getVersionComponents(versionName);

            String fingerprint = libtorrent.generate_fingerprint(PEER_FINGERPRINT,
                    version[0], version[1], version[2], 0);
            sp.set_str(settings_pack.string_types.peer_fingerprint.swigValue(), fingerprint);

            String userAgent = String.format(USER_AGENT, Utils.getAppVersionNumber(versionName));
            sp.set_str(settings_pack.string_types.user_agent.swigValue(), userAgent);

            Log.i(TAG, "Peer fingerprint: " + sp.get_str(settings_pack.string_types.peer_fingerprint.swigValue()));
            Log.i(TAG, "User agent: " + sp.get_str(settings_pack.string_types.user_agent.swigValue()));
        }

        super.start(params);
    }

    @Override
    protected void onBeforeStart()
    {
        addListener(innerListener);
    }

    @Override
    protected void onAfterStart()
    {
        notifyListeners(TorrentEngineListener::onSessionStarted);
    }

    @Override
    protected void onBeforeStop()
    {
        saveAllResumeData();
        /* Handles must be destructed before the session is destructed */
        torrentTasks.clear();
        magnets.clear();
        loadedMagnets.clear();
        removeListener(innerListener);
        saveSettings();
    }

    @Override
    protected void onApplySettings(SettingsPack sp)
    {
        saveSettings();
    }

    private final class InnerListener implements AlertListener
    {
        @Override
        public int[] types()
        {
            return INNER_LISTENER_TYPES;
        }

        @Override
        public void alert(Alert<?> alert)
        {
            switch (alert.type()) {
                case ADD_TORRENT:
                    TorrentAlert<?> torrentAlert = (TorrentAlert<?>)alert;
                    TorrentHandle th = find(torrentAlert.handle().infoHash());
                    if (th == null)
                        break;
                    String hash = th.infoHash().toHex();
                    if (magnets.contains(hash))
                        break;
                    torrentTasks.put(hash, newTask(th, hash));
                    if (addTorrentsList.contains(hash))
                        notifyListeners((listener) ->
                                listener.onTorrentAdded(hash));
                    else
                        notifyListeners((listener) ->
                                listener.onTorrentLoaded(hash));
                    addTorrentsList.remove(hash);
                    runNextLoadTorrentTask();
                    break;
                case METADATA_RECEIVED:
                    handleMetadata(((MetadataReceivedAlert) alert));
                    break;
                case TORRENT_REMOVED:
                    torrentTasks.remove(((TorrentRemovedAlert)alert).infoHash().toHex());
                    break;
                default:
                    checkError(alert);
                    break;
            }
        }
    }

    private void checkError(Alert<?> alert)
    {
        notifyListeners((listener) -> {
            switch (alert.type()) {
                case SESSION_ERROR: {
                    SessionErrorAlert sessionErrorAlert = (SessionErrorAlert)alert;
                    ErrorCode error = sessionErrorAlert.error();
                    if (error.isError())
                        listener.onSessionError(Utils.getErrorMsg(error));
                    break;
                }
                case LISTEN_FAILED: {
                    ListenFailedAlert listenFailedAlert = (ListenFailedAlert)alert;
                    String fmt = appContext.getString(R.string.listen_failed_error);
                    listener.onSessionError(String.format(fmt,
                            listenFailedAlert.address(),
                            listenFailedAlert.port(),
                            listenFailedAlert.socketType(),
                            Utils.getErrorMsg(listenFailedAlert.error())));
                    break;
                }
                case PORTMAP_ERROR: {
                    PortmapErrorAlert portmapErrorAlert = (PortmapErrorAlert)alert;
                    ErrorCode error = portmapErrorAlert.error();
                    if (error.isError())
                        listener.onNatError(Utils.getErrorMsg(error));
                    break;
                }
            }
        });
    }

    private void handleMetadata(MetadataReceivedAlert metadataAlert)
    {
        if (swig() == null)
            return;

        TorrentHandle th = metadataAlert.handle();
        String hash = th.infoHash().toHex();
        if (!magnets.contains(hash))
            return;

        int size = metadataAlert.metadataSize();
        int maxSize = 2 * 1024 * 1024;
        byte[] bencode = null;
        if (0 < size && size <= maxSize)
            bencode = metadataAlert.torrentData(true);
        if (bencode != null)
            loadedMagnets.put(hash, bencode);
        remove(th, SessionHandle.DELETE_FILES);

        notifyListeners((listener) ->
                listener.onMagnetLoaded(hash, loadedMagnets.get(hash)));
    }

    private static String dhtBootstrapNodes()
    {
        StringBuilder sb = new StringBuilder();

        sb.append("dht.libtorrent.org:25401").append(",");
        sb.append("router.bittorrent.com:6881").append(",");
        sb.append("dht.transmissionbt.com:6881").append(",");
        /* For IPv6 DHT */
        sb.append("outer.silotis.us:6881");

        return sb.toString();
    }

    private SessionParams loadSettings()
    {
        try {
            String sessionPath = repo.getSessionFile(appContext);
            if (sessionPath == null)
                return new SessionParams(defaultSettingsPack());

            File sessionFile = new File(sessionPath);
            if (sessionFile.exists()) {
                byte[] data = FileUtils.readFileToByteArray(sessionFile);
                byte_vector buffer = Vectors.bytes2byte_vector(data);
                bdecode_node n = new bdecode_node();
                error_code ec = new error_code();
                int ret = bdecode_node.bdecode(buffer, n, ec);
                if (ret == 0) {
                    session_params params = libtorrent.read_session_params(n);
                    /* Prevents GC */
                    buffer.clear();

                    return new SessionParams(params);
                } else {
                    throw new IllegalArgumentException("Can't decode data: " + ec.message());
                }

            } else {
                return new SessionParams(defaultSettingsPack());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading session state: ");
            Log.e(TAG, Log.getStackTraceString(e));

            return new SessionParams(defaultSettingsPack());
        }
    }

    private void saveSettings()
    {
        if (swig() == null)
            return;

        try {
            repo.saveSession(appContext, saveState());

        } catch (Exception e) {
            Log.e(TAG, "Error saving session state: ");
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    private SettingsPack defaultSettingsPack()
    {
        SettingsPack sp = new SettingsPack();

        int maxQueuedDiskBytes = sp.maxQueuedDiskBytes();
        sp.maxQueuedDiskBytes(maxQueuedDiskBytes / 2);
        int sendBufferWatermark = sp.sendBufferWatermark();
        sp.sendBufferWatermark(sendBufferWatermark / 2);
        /* TODO: settings */
//        sp.seedingOutgoingConnections(false);
        sp.cacheSize(256);
        sp.tickInterval(1000);
        sp.inactivityTimeout(60);
        settingsToSettingsPack(settings, sp);

        return sp;
    }

    private void settingsToSettingsPack(Settings settings, SettingsPack sp)
    {
        sp.cacheSize(settings.cacheSize);
        sp.activeDownloads(settings.activeDownloads);
        sp.activeSeeds(settings.activeSeeds);
        sp.activeLimit(settings.activeLimit);
        sp.maxPeerlistSize(settings.maxPeerListSize);
        sp.tickInterval(settings.tickInterval);
        sp.inactivityTimeout(settings.inactivityTimeout);
        sp.connectionsLimit(settings.connectionsLimit);
        sp.setString(settings_pack.string_types.listen_interfaces.swigValue(), getIface());
        sp.setInteger(settings_pack.int_types.max_retry_port_bind.swigValue(),
                settings.portRangeSecond - settings.portRangeFirst);
        sp.enableDht(settings.dhtEnabled);
        sp.broadcastLSD(settings.lsdEnabled);
        sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), settings.utpEnabled);
        sp.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), settings.utpEnabled);
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), settings.upnpEnabled);
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), settings.natPmpEnabled);
        sp.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), settings.encryptMode);
        sp.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), settings.encryptMode);
        sp.uploadRateLimit(settings.uploadRateLimit);
        sp.downloadRateLimit(settings.downloadRateLimit);
    }

    private String getIface()
    {
        String iface = settings.inetAddress;
        if (iface.equals(Settings.DEFAULT_INETADDRESS)) {
            iface = "0.0.0.0:%1$d,[::]:%1$d";
        } else {
            /* IPv6 test */
            if (iface.contains(":")) {
                iface = "[" + iface + "]";
            }
            iface = iface + ":%1$d";
        }

        return String.format(iface, settings.portRangeFirst);
    }

    private void applySettingsPack(SettingsPack sp)
    {
        if (swig() == null)
            return;

        applySettings(sp);
        saveSettings();
    }

    private void applySettings(Settings settings)
    {
        if (swig() == null)
            return;

        SettingsPack sp = settings();
        settingsToSettingsPack(settings, sp);
        applySettingsPack(sp);
    }

    private void saveAllResumeData()
    {
        for (TorrentDownload task : torrentTasks.values()) {
            if (task == null)
                continue;
            task.saveResumeData(true);
        }
    }

    private byte[] createTorrent(add_torrent_params params, torrent_info ti)
    {
        create_torrent ct = new create_torrent(ti);

        string_vector v = params.get_url_seeds();
        int size = (int)v.size();
        for (int i = 0; i < size; i++)
            ct.add_url_seed(v.get(i));

        string_vector trackers = params.get_trackers();
        int_vector tiers = params.get_tracker_tiers();
        size = (int)trackers.size();
        for (int i = 0; i < size; i++)
            ct.add_tracker(trackers.get(i), tiers.get(i));

        entry e = ct.generate();
        return Vectors.byte_vector2bytes(e.bencode());
    }

    private TorrentDownload newTask(TorrentHandle th, String id)
    {
        TorrentDownload task = new TorrentDownload(appContext, this, listeners, id, th);
        task.setMaxConnections(settings.connectionsLimitPerTorrent);
        task.setMaxUploads(settings.uploadsLimitPerTorrent);

        return task;
    }

    private interface CallListener
    {
        void apply(TorrentEngineListener listener);
    }

    private void notifyListeners(@NonNull CallListener l)
    {
        for (TorrentEngineListener listener : listeners) {
            if (listener != null)
                l.apply(listener);
        }
    }

    private void runNextLoadTorrentTask() {
        LoadTorrentTask task = null;
        try {
            if (!restoreTorrentsQueue.isEmpty())
                task = restoreTorrentsQueue.poll();
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));

            return;
        }

        if (task != null)
            loadTorrentsExec.execute(task);
    }

    private final class LoadTorrentTask implements Runnable
    {
        private String torrentId;
        private File saveDir = null;
        private String magnetUri = null;
        private boolean isMagnet = false;
        private boolean magnetPaused = false;

        LoadTorrentTask(String torrentId)
        {
            this.torrentId = torrentId;
        }

        public void putMagnet(String magnetUri, File saveDir, boolean magnetPaused)
        {
            this.magnetUri = magnetUri;
            this.saveDir = saveDir;
            this.magnetPaused = magnetPaused;
            isMagnet = true;
        }

        @Override
        public void run()
        {
            try {
                if (torrentTasks.containsKey(torrentId))
                    return;

                if (isMagnet)
                    download(magnetUri, saveDir, magnetPaused);
                else
                    restoreDownload(torrentId);

            } catch (Exception e) {
                Log.e(TAG, "Unable to restore torrent from previous session: " + torrentId, e);
                Torrent torrent = repo.getTorrentById(torrentId);
                if (torrent != null) {
                    torrent.error = e.toString();
                    repo.updateTorrent(torrent);
                }

                notifyListeners((listener) ->
                        listener.onRestoreSessionError(torrentId));
            }
        }
    }

    private void download(byte[] bencode, File saveDir,
                          Priority[] priorities, boolean sequentialDownload,
                          boolean paused, List<TcpEndpoint> peers)
    {
        download(new TorrentInfo(bencode), saveDir, priorities, sequentialDownload, paused, peers);
    }

    private void download(TorrentInfo ti, File saveDir,
                          Priority[] priorities, boolean sequentialDownload,
                          boolean paused, List<TcpEndpoint> peers)
    {
        if (swig() == null)
            return;

        if (!ti.isValid())
            throw new IllegalArgumentException("Torrent info not valid");

        torrent_handle th = swig().find_torrent(ti.swig().info_hash());
        if (th != null && th.is_valid()) {
            /* Found a download with the same hash */
            return;
        }

        add_torrent_params p = add_torrent_params.create_instance();

        p.set_ti(ti.swig());
        if (saveDir != null)
            p.setSave_path(saveDir.getAbsolutePath());

        if (priorities != null) {
            if (ti.files().numFiles() != priorities.length)
                throw new IllegalArgumentException("Priorities count should be equals to the number of files");

            byte_vector v = new byte_vector();
            for (Priority priority : priorities)
                v.push_back((byte)priority.swig());

            p.set_file_priorities2(v);
        }

        if (peers != null && !peers.isEmpty()) {
            tcp_endpoint_vector v = new tcp_endpoint_vector();
            for (TcpEndpoint endp : peers)
                v.push_back(endp.swig());

            p.set_peers(v);
        }

        torrent_flags_t flags = p.getFlags();

        if (settings.autoManaged)
            flags = flags.or_(TorrentFlags.AUTO_MANAGED);
        else
            flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv());

        if (sequentialDownload)
            flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD);
        else
            flags = flags.and_(TorrentFlags.SEQUENTIAL_DOWNLOAD.inv());

        if (paused)
            flags = flags.or_(TorrentFlags.PAUSED);
        else
            flags = flags.and_(TorrentFlags.PAUSED.inv());

        p.setFlags(flags);

        swig().async_add_torrent(p);
    }

    public void download(String magnetUri, File saveDir, boolean paused)
    {
        if (swig() == null)
            return;

        error_code ec = new error_code();
        add_torrent_params p = add_torrent_params.parse_magnet_uri(magnetUri, ec);

        if (ec.value() != 0)
            throw new IllegalArgumentException(ec.message());

        sha1_hash info_hash = p.getInfo_hash();
        torrent_handle th = swig().find_torrent(info_hash);
        if (th != null && th.is_valid()) {
            /* Found a download with the same hash */
            return;
        }

        if (saveDir != null)
            p.setSave_path(saveDir.getAbsolutePath());

        torrent_flags_t flags = p.getFlags();

        flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv());
        if (paused)
            flags = flags.or_(TorrentFlags.PAUSED);
        else
            flags = flags.and_(TorrentFlags.PAUSED.inv());

        p.setFlags(flags);

        swig().async_add_torrent(p);
    }

    private void restoreDownload(String id) throws IOException
    {
        if (swig() == null)
            return;

        FastResume fastResume = repo.getFastResumeById(id);
        if (fastResume == null)
            throw new IOException("Fast resume data not found");

        error_code ec = new error_code();
        add_torrent_params p = add_torrent_params.read_resume_data(Vectors.bytes2byte_vector(fastResume.data), ec);
        if (ec.value() != 0)
            throw new IllegalArgumentException("Unable to read the resume data: " + ec.message());

        torrent_flags_t flags = p.getFlags();

        if (settings.autoManaged)
            flags = flags.or_(TorrentFlags.AUTO_MANAGED);
        else
            flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv());

        p.setFlags(flags);

        swig().async_add_torrent(p);
    }
}
