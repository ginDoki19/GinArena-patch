package app.template.extension.onedm;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Process;
import android.util.Log;
import com.aspsine.multithreaddownload.util.AdblockerListSource;
import idm.internet.download.manager.userscript.UserScript;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/**
 * Background Cloud Sync & Pre-Compiled Asset Manager for 1DM 20.0 (30257).
 *
 * Syncs pre-compiled AdBlocker binary engines (abnfp.bin, abnhp.bin, abcfp.bin)
 * and the mobile-patched Bypass All Shortlinks (.1dmus.gz) bundle from the
 * GinArena-patch 'onedm-cache' GitHub release with:
 *   - Full SHA-256 + binary format v20 header verification
 *   - Redirect-safe HTTPS transport (rejects HTTP downgrade redirects)
 *   - Multi-process & SIGKILL-safe fsynced JSON state (CheckAppVersion calls
 *     Process.killProcess(myPid()), which kills SharedPreferences.apply() queues
 *     and leaves MODE_PRIVATE SharedPreferences caches stale across processes)
 *   - TrayContentProvider ab_rebuild_wanted / ab_rebuild_done synchronization
 *   - Cross-process idm.userscript.SCRIPTS_CHANGED + ACTION_REFRESH_ADBLOCK broadcasts
 *   - Automatic fallback to 1DM's Local Turbo parser whenever custom filter lists are used
 */
@SuppressWarnings("unused")
public final class OnedmCloudSync {

    private static final String TAG = "GinArena1DMSync";

    public static final String DEFAULT_MANIFEST_URL =
        "https://github.com/ginDoki19/GinArena-patch/releases/download/onedm-cache/manifest.json";

    public static final String BYPASS_SCRIPT_ID = "bde72f0c-54aa-3f79-b2c6-9d96f479e580";

    private static final String STATE_FILE_NAME = "ginarena_cloud_sync_state.json";
    private static final String LOCK_FILE_NAME = "ginarena_cloud_sync.lock";

    private static final String KEY_LAST_CHECK_MS = "last_manifest_check_ms";
    private static final String KEY_AB_PACK_SHA256 = "ab_pack_sha256";
    private static final String KEY_AB_ABNFP_LEN = "ab_abnfp_len";
    private static final String KEY_AB_ABCFP_LEN = "ab_abcfp_len";
    private static final String KEY_US_BYPASS_SHA256 = "us_bypass_sha256";
    private static final String KEY_US_BYPASS_SEEDED = "us_bypass_seeded";

    private static final int EXPECTED_ADBLOCK_FORMAT_VERSION = 20;
    private static final long BACKGROUND_COOLDOWN_MS = 12L * 60L * 60L * 1000L; // 12 hours

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_REDIRECT_HOPS = 5;

    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final long MAX_STATE_FILE_BYTES = 64L * 1024L;
    private static final long MAX_ADBLOCK_ZIP_BYTES = 80L * 1024L * 1024L;
    private static final long MAX_ADBLOCK_BIN_ENTRY_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_USERSCRIPT_GZ_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_USERSCRIPT_DECODED_BYTES = 16L * 1024L * 1024L;

    private static final Object IN_PROCESS_LOCK = new Object();

    private static final String[] ADBLOCK_BIN_NAMES = new String[] {
        "abnfp.bin", "abnhp.bin", "abcfp.bin"
    };

    private static final Set<String> DEFAULT_ENABLED_UUIDS = createDefaultUuidSet();

    private static volatile String manifestUrl = DEFAULT_MANIFEST_URL;

    private OnedmCloudSync() {}

    private static Set<String> createDefaultUuidSet() {
        // Stock 1DM 20.0 (30257) 28 default-enabled filter list UUIDs in Li/hv6;
        // (excludes disabled UUID11, UUID12, UUID14, UUID18, UUID19, UUID33, and
        // ignores "MFSU" manual rules which compile separately into mManualEngine).
        String[] uuids = new String[] {
            "UUID1", "UUID2", "UUID3", "UUID4", "UUID5",
            "UUID6", "UUID7", "UUID8", "UUID9", "UUID10",
            "UUID13", "UUID15", "UUID16", "UUID17",
            "UUID20", "UUID21", "UUID22", "UUID23", "UUID24",
            "UUID25", "UUID26", "UUID27", "UUID28", "UUID29",
            "UUID30", "UUID31", "UUID32", "UUID34"
        };
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(uuids)));
    }

    // -------------------------------------------------------------------------
    // Patch-configured option flags & dynamic 1DM bridge methods.
    // Bodies are rewritten at patch-apply time by AdblockAndUserscriptPatch
    // using only incoming parameter registers (p0..p3).
    // -------------------------------------------------------------------------

    public static boolean isCloudAdblockEnabled() {
        return Boolean.parseBoolean("true");
    }

    public static boolean isBypassUserscriptEnabled() {
        return Boolean.parseBoolean("true");
    }

    private static ArrayList<AdblockerListSource> bridgeGetAllAdblockSources(Context ctx, AtomicInteger counter) {
        return null;
    }

    private static boolean bridgeValidateAdblockBins(Context ctx) {
        return false;
    }

    private static void bridgeRecordAdblockUpdatedTimestamp(Context ctx, long timestampMs) {
    }

    private static long bridgeGetTrayLong(Context ctx, String key, long defaultValue) {
        return defaultValue;
    }

    private static boolean bridgePutTrayLong(Context ctx, long value, String key) {
        return false;
    }

    private static UserScript bridgeGetUserscriptById(Context ctx, String scriptId) {
        return null;
    }

    private static boolean bridgeImportUserscript(Context ctx, List<UserScript> scripts) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Public entry points invoked by patched 1DM methods.
    // -------------------------------------------------------------------------

    /**
     * Hooked at index 0 of CheckAppVersion's extra_parse_ab_list rebuild worker.
     * When AdBlock triggers an initial rebuild on a fresh install because abnfp.bin
     * does not exist yet (!hasValidAdblockBins), syncs the 28-list Cloud Pack and
     * Bypass All Shortlinks immediately instead of compiling only builtin UUID1,
     * and records ab_rebuild_done in Tray so CheckAppVersion never schedules a
     * spurious retry that would overwrite the 28-list Cloud Pack.
     * When bins already exist (user manually edited/rebuilt filters), returns false
     * so 1DM's Local Turbo rebuild runs normally.
     */
    public static boolean onAdblockRebuildHook(Context context) {
        if (context == null) return false;
        try {
            Context appCtx = safeAppContext(context);
            if (hasValidAdblockBins(appCtx)) {
                return false;
            }
            syncFromCloud(appCtx, false);
            boolean handled = isCloudAdblockEnabled()
                && !hasCustomFilterSelection(appCtx)
                && hasValidAdblockBins(appCtx);
            if (handled) {
                markAdblockRebuildDone(appCtx, System.currentTimeMillis());
            }
            return handled;
        } catch (Throwable t) {
            Log.w(TAG, "onAdblockRebuildHook fallback to local rebuild", t);
            return false;
        }
    }

    /**
     * Hooked at index 0 of CheckAppVersion's background AdBlocker update method.
     *
     * @return true when Cloud Pre-Built AdBlocker binaries are active and valid
     *         for the default 28 filter set (caller skips local compilation);
     *         false when Local Turbo / custom filter selection should run.
     */
    public static boolean onAdblockCheckHook(Context context) {
        if (context == null) return false;
        try {
            Context appCtx = safeAppContext(context);
            syncFromCloud(appCtx, false);
            if (!isCloudAdblockEnabled()) {
                return false;
            }
            if (hasCustomFilterSelection(appCtx)) {
                return false;
            }
            return hasValidAdblockBins(appCtx);
        } catch (Throwable t) {
            Log.w(TAG, "onAdblockCheckHook fallback to local engine", t);
            return false;
        }
    }

    /**
     * Hooked at index 0 of CheckAppVersion's background Userscript update method.
     * Ensures Bypass All Shortlinks is seeded even when 0 userscripts exist yet.
     */
    public static void onUserscriptCheckHook(Context context) {
        if (context == null) return;
        try {
            syncFromCloud(safeAppContext(context), false);
        } catch (Throwable t) {
            Log.w(TAG, "onUserscriptCheckHook failed cleanly", t);
        }
    }

    /**
     * Hooked at index 0 of kh7's per-script update method so Bypass All Shortlinks
     * never fetches raw unpatched JS from Codeberg.
     */
    public static boolean shouldInterceptUserscriptUpdate(UserScript script) {
        try {
            return isBypassUserscriptEnabled()
                && script != null
                && BYPASS_SCRIPT_ID.equals(script.getScriptId());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs a 0-second-cooldown manifest check for Bypass All Shortlinks when
     * invoked by manual "Check for update" (mf7) or background kh7 update.
     * Returns null when already up to date, or the updated pre-compiled UserScript.
     */
    public static UserScript fetchUpdatedBypassUserscript(Context context, UserScript existing) {
        if (context == null || existing == null) return null;
        Context appCtx = safeAppContext(context);
        if (!isNetworkAvailable(appCtx)) return null;

        synchronized (IN_PROCESS_LOCK) {
            RandomAccessFile lockRaf = null;
            FileLock procLock = null;
            try {
                File filesDir = appCtx.getFilesDir();
                if (filesDir == null) return null;
                if (!filesDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    filesDir.mkdirs();
                }

                lockRaf = new RandomAccessFile(new File(filesDir, LOCK_FILE_NAME), "rw");
                procLock = acquireFileLockWithRetry(lockRaf);

                JSONObject state = readSyncStateLocked(filesDir);
                JSONObject manifest = fetchManifestJson();
                long now = System.currentTimeMillis();
                state.put(KEY_LAST_CHECK_MS, now);
                writeSyncStateLocked(filesDir, state);

                String remoteSha = normalizeSha256(manifest.optString("userscriptSha256", ""));
                String remoteAsset = sanitizeAssetName(manifest.optString("userscriptAsset", ""));
                String remoteVer = manifest.optString("userscriptVersion", "");
                long remoteSize = manifest.optLong("userscriptSize", -1L);
                if (remoteSha == null || remoteAsset == null) {
                    return null;
                }

                String savedSha = state.optString(KEY_US_BYPASS_SHA256, "");
                String currentVer = existing.getVersion();
                if (remoteSha.equalsIgnoreCase(savedSha)
                    && (remoteVer.isEmpty() || remoteVer.equals(currentVer))) {
                    return null; // Up to date -> 1DM displays "Script is up to date"
                }

                UserScript updated = downloadAndDecodeUserscript(filesDir, remoteAsset, remoteSha, remoteSize);
                if (updated == null) {
                    return null;
                }
                updated.setEnabled(existing.isEnabled());
                updated.setSyntaxChecked(true);

                state.put(KEY_US_BYPASS_SHA256, remoteSha);
                state.put(KEY_US_BYPASS_SEEDED, true);
                writeSyncStateLocked(filesDir, state);

                // If content hashes match what is already installed, treat as up to date.
                if (existing.getScriptHash() != null
                    && existing.getScriptHash().equals(updated.getScriptHash())
                    && existing.getSourceContentHash() != null
                    && existing.getSourceContentHash().equals(updated.getSourceContentHash())) {
                    return null;
                }

                return updated;
            } catch (Throwable t) {
                Log.w(TAG, "fetchUpdatedBypassUserscript failed cleanly", t);
                return null;
            } finally {
                closeLockQuietly(procLock, lockRaf);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core background sync engine
    // -------------------------------------------------------------------------

    public static void syncFromCloud(Context appCtx, boolean forceImmediate) {
        boolean wantCloudAb = isCloudAdblockEnabled();
        boolean wantBypassUs = isBypassUserscriptEnabled();
        if (!wantCloudAb && !wantBypassUs) {
            return;
        }
        if (!isNetworkAvailable(appCtx)) {
            return;
        }

        synchronized (IN_PROCESS_LOCK) {
            RandomAccessFile lockRaf = null;
            FileLock procLock = null;
            try {
                File filesDir = appCtx.getFilesDir();
                if (filesDir == null) return;
                if (!filesDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    filesDir.mkdirs();
                }

                lockRaf = new RandomAccessFile(new File(filesDir, LOCK_FILE_NAME), "rw");
                procLock = acquireFileLockWithRetry(lockRaf);

                JSONObject state = readSyncStateLocked(filesDir);
                boolean customFilters = wantCloudAb && hasCustomFilterSelection(appCtx);
                if (customFilters && !state.optString(KEY_AB_PACK_SHA256, "").isEmpty()) {
                    // User switched to custom filters; clear saved Cloud SHA so restoring
                    // the default 28 filter set later immediately re-syncs the Cloud Pack.
                    state.put(KEY_AB_PACK_SHA256, "");
                    state.put(KEY_AB_ABNFP_LEN, -1L);
                    state.put(KEY_AB_ABCFP_LEN, -1L);
                    writeSyncStateLocked(filesDir, state);
                }

                boolean binsValid = wantCloudAb && !customFilters && hasValidAdblockBins(appCtx);
                boolean binsMatchCloudRecord = binsValid && doLocalBinsMatchRecordedCloudPack(filesDir, state);
                boolean needInitialAb = wantCloudAb && !customFilters && (!binsValid || !binsMatchCloudRecord);
                boolean needInitialUs = wantBypassUs && !state.optBoolean(KEY_US_BYPASS_SEEDED, false);

                long now = System.currentTimeMillis();
                long lastCheck = state.optLong(KEY_LAST_CHECK_MS, 0L);
                if (!forceImmediate && !needInitialAb && !needInitialUs) {
                    if (lastCheck > 0L && now >= lastCheck && (now - lastCheck) < BACKGROUND_COOLDOWN_MS) {
                        return;
                    }
                }

                JSONObject manifest = fetchManifestJson();
                state.put(KEY_LAST_CHECK_MS, now);
                writeSyncStateLocked(filesDir, state);

                if (wantCloudAb && !customFilters) {
                    syncAdblockPackIfNeeded(appCtx, filesDir, state, manifest, needInitialAb);
                }

                if (wantBypassUs) {
                    syncBypassUserscriptIfNeeded(appCtx, filesDir, state, manifest);
                }
            } catch (Throwable t) {
                Log.w(TAG, "syncFromCloud aborted cleanly", t);
            } finally {
                closeLockQuietly(procLock, lockRaf);
            }
        }
    }

    private static void syncAdblockPackIfNeeded(
        Context appCtx,
        File filesDir,
        JSONObject state,
        JSONObject manifest,
        boolean binsMissingOrMismatched
    ) {
        try {
            int formatVersion = manifest.optInt("adblockFormatVersion", -1);
            if (formatVersion != EXPECTED_ADBLOCK_FORMAT_VERSION) {
                Log.w(TAG, "Skipping cloud adblock pack: formatVersion=" + formatVersion);
                return;
            }

            String remoteSha = normalizeSha256(manifest.optString("adblockSha256", ""));
            String remoteAsset = sanitizeAssetName(manifest.optString("adblockAsset", ""));
            long remoteSize = manifest.optLong("adblockSize", -1L);
            if (remoteSha == null || remoteAsset == null) {
                return;
            }

            String savedSha = state.optString(KEY_AB_PACK_SHA256, "");
            if (!binsMissingOrMismatched && remoteSha.equalsIgnoreCase(savedSha)) {
                markAdblockRebuildDone(appCtx, System.currentTimeMillis());
                return;
            }

            String assetUrl = resolveSiblingAssetUrl(remoteAsset);
            File tmpZip = new File(filesDir, "onedm_adblock_pack.zip.tmp");
            try {
                downloadAndVerifySha256(assetUrl, tmpZip, remoteSha, remoteSize, MAX_ADBLOCK_ZIP_BYTES);
                extractAndSwapAdblockBinsAtomically(filesDir, tmpZip);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
            }

            if (hasValidAdblockBins(appCtx)) {
                long updatedAt = System.currentTimeMillis();
                state.put(KEY_AB_PACK_SHA256, remoteSha);
                state.put(KEY_AB_ABNFP_LEN, new File(filesDir, "abnfp.bin").length());
                state.put(KEY_AB_ABCFP_LEN, new File(filesDir, "abcfp.bin").length());
                writeSyncStateLocked(filesDir, state);

                try {
                    bridgeRecordAdblockUpdatedTimestamp(appCtx, updatedAt);
                } catch (Throwable ignored) {
                }
                markAdblockRebuildDone(appCtx, updatedAt);
                broadcastAdblockRefresh(appCtx);
                Log.i(TAG, "Cloud AdBlocker pack synced: " + remoteSha.substring(0, 12));
            }
        } catch (Throwable t) {
            Log.w(TAG, "AdBlocker pack sync failed; keeping existing state", t);
        }
    }

    private static boolean doLocalBinsMatchRecordedCloudPack(File filesDir, JSONObject state) {
        String savedSha = state.optString(KEY_AB_PACK_SHA256, "");
        if (savedSha.isEmpty()) {
            return false;
        }
        long expectedAbnfp = state.optLong(KEY_AB_ABNFP_LEN, -1L);
        long expectedAbcfp = state.optLong(KEY_AB_ABCFP_LEN, -1L);
        if (expectedAbnfp <= 0L || expectedAbcfp <= 0L) {
            return true;
        }
        File abnfp = new File(filesDir, "abnfp.bin");
        File abcfp = new File(filesDir, "abcfp.bin");
        return abnfp.length() == expectedAbnfp && abcfp.length() == expectedAbcfp;
    }

    private static void markAdblockRebuildDone(Context appCtx, long timestampMs) {
        try {
            long wanted = bridgeGetTrayLong(appCtx, "ab_rebuild_wanted", 0L);
            long doneValue = Math.max(wanted, timestampMs);
            if (doneValue > 0L) {
                bridgePutTrayLong(appCtx, doneValue, "ab_rebuild_done");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Checks that all 3 compiled binary filter engines exist on disk, start with
     * big-endian format version 20, AND pass 1DM's stale-version check (j12).
     * Note: j12's stale-version method alone returns true when files do not exist yet!
     */
    static boolean hasValidAdblockBins(Context appCtx) {
        if (appCtx == null) return false;
        try {
            File filesDir = appCtx.getFilesDir();
            if (filesDir == null) return false;
            for (int i = 0; i < ADBLOCK_BIN_NAMES.length; i++) {
                File f = new File(filesDir, ADBLOCK_BIN_NAMES[i]);
                if (!f.isFile() || f.length() < 8L) {
                    return false;
                }
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                try {
                    int version = raf.readInt();
                    if (version != EXPECTED_ADBLOCK_FORMAT_VERSION) {
                        return false;
                    }
                } finally {
                    raf.close();
                }
            }
            return bridgeValidateAdblockBins(appCtx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void extractAndSwapAdblockBinsAtomically(File filesDir, File verifiedZip) throws Exception {
        Set<String> allowed = new HashSet<String>(Arrays.asList(ADBLOCK_BIN_NAMES));
        Set<String> extracted = new HashSet<String>();
        byte[] buf = new byte[32 * 1024];

        try {
            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(verifiedZip)));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    String name = entry.getName();
                    if (!allowed.contains(name)) {
                        throw new IOException("Unexpected entry in adblock pack: " + name);
                    }
                    File tmpBin = new File(filesDir, name + ".cloud.tmp");
                    long total = 0L;
                    byte[] header = new byte[4];
                    int headerRead = 0;
                    FileOutputStream fos = new FileOutputStream(tmpBin);
                    BufferedOutputStream bos = new BufferedOutputStream(fos, 32 * 1024);
                    try {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            total += n;
                            if (total > MAX_ADBLOCK_BIN_ENTRY_BYTES) {
                                throw new IOException("Entry exceeds max size: " + name);
                            }
                            if (headerRead < 4) {
                                int copy = Math.min(4 - headerRead, n);
                                System.arraycopy(buf, 0, header, headerRead, copy);
                                headerRead += copy;
                            }
                            bos.write(buf, 0, n);
                        }
                        bos.flush();
                        try {
                            fos.getFD().sync();
                        } catch (Throwable ignored) {
                        }
                    } finally {
                        bos.close();
                    }
                    int magic = (headerRead == 4)
                        ? (((header[0] & 0xFF) << 24)
                            | ((header[1] & 0xFF) << 16)
                            | ((header[2] & 0xFF) << 8)
                            | (header[3] & 0xFF))
                        : -1;
                    if (magic != EXPECTED_ADBLOCK_FORMAT_VERSION) {
                        throw new IOException("Invalid binary magic " + magic + " in " + name);
                    }
                    extracted.add(name);
                    zis.closeEntry();
                }
            } finally {
                zis.close();
            }

            if (extracted.size() != ADBLOCK_BIN_NAMES.length) {
                throw new IOException("Incomplete adblock pack: extracted=" + extracted);
            }

            // Acquire 1DM's cross-process AdBlock compile lock before swapping files.
            RandomAccessFile abLockRaf = new RandomAccessFile(new File(filesDir, "ab_filter_compile.lock"), "rw");
            FileLock abLock = null;
            try {
                abLock = acquireFileLockWithRetry(abLockRaf);
                for (String binName : ADBLOCK_BIN_NAMES) {
                    File tmpBin = new File(filesDir, binName + ".cloud.tmp");
                    File dstBin = new File(filesDir, binName);
                    if (!tmpBin.renameTo(dstBin)) {
                        copyFileAndSync(tmpBin, dstBin);
                        //noinspection ResultOfMethodCallIgnored
                        tmpBin.delete();
                    }
                }
            } finally {
                closeLockQuietly(abLock, abLockRaf);
            }
        } finally {
            for (String binName : ADBLOCK_BIN_NAMES) {
                File leftover = new File(filesDir, binName + ".cloud.tmp");
                if (leftover.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    leftover.delete();
                }
            }
        }
    }

    private static void syncBypassUserscriptIfNeeded(
        Context appCtx,
        File filesDir,
        JSONObject state,
        JSONObject manifest
    ) {
        try {
            String remoteSha = normalizeSha256(manifest.optString("userscriptSha256", ""));
            String remoteAsset = sanitizeAssetName(manifest.optString("userscriptAsset", ""));
            long remoteSize = manifest.optLong("userscriptSize", -1L);
            if (remoteSha == null || remoteAsset == null) {
                return;
            }

            boolean seededBefore = state.optBoolean(KEY_US_BYPASS_SEEDED, false);
            UserScript existing = bridgeGetUserscriptById(appCtx, BYPASS_SCRIPT_ID);

            // Respect user deletion: if we seeded once and the user later removed
            // the userscript in 1DM's manager, never silently re-install it.
            if (seededBefore && existing == null) {
                return;
            }

            String savedSha = state.optString(KEY_US_BYPASS_SHA256, "");
            if (existing != null && remoteSha.equalsIgnoreCase(savedSha)) {
                if (!seededBefore) {
                    state.put(KEY_US_BYPASS_SEEDED, true);
                    writeSyncStateLocked(filesDir, state);
                }
                return;
            }

            UserScript decoded = downloadAndDecodeUserscript(filesDir, remoteAsset, remoteSha, remoteSize);
            if (decoded == null) {
                return;
            }

            boolean enableState = (existing == null) || existing.isEnabled();
            decoded.setEnabled(enableState);
            decoded.setSyntaxChecked(true);

            boolean imported = bridgeImportUserscript(appCtx, Collections.singletonList(decoded));
            if (imported) {
                state.put(KEY_US_BYPASS_SEEDED, true);
                state.put(KEY_US_BYPASS_SHA256, remoteSha);
                writeSyncStateLocked(filesDir, state);
                broadcastUserscriptsChanged(appCtx);
                Log.i(TAG, "Bypass All Shortlinks synced: " + decoded.getVersion());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Userscript cloud sync failed cleanly", t);
        }
    }

    private static UserScript downloadAndDecodeUserscript(
        File filesDir,
        String remoteAsset,
        String remoteSha,
        long remoteSize
    ) throws Exception {
        String assetUrl = resolveSiblingAssetUrl(remoteAsset);
        File tmpGz = new File(filesDir, "onedm_bypass_us.gz.tmp");
        try {
            downloadAndVerifySha256(assetUrl, tmpGz, remoteSha, remoteSize, MAX_USERSCRIPT_GZ_BYTES);
            String exported = gunzipUtf8Bounded(tmpGz, MAX_USERSCRIPT_DECODED_BYTES).trim();
            if (exported.isEmpty()) {
                throw new IOException("Empty .1dmus payload");
            }
            UserScript decoded = UserScript.from(exported);
            if (decoded == null || !BYPASS_SCRIPT_ID.equals(decoded.getScriptId())) {
                throw new IOException("Decoded .1dmus scriptId mismatch");
            }
            decoded.setSyntaxChecked(true);
            return decoded;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpGz.delete();
        }
    }

    // -------------------------------------------------------------------------
    // Multi-process & SIGKILL-safe fsynced JSON state persistence
    // (Must only be called while holding IN_PROCESS_LOCK + LOCK_FILE_NAME FileLock)
    // -------------------------------------------------------------------------

    private static JSONObject readSyncStateLocked(File filesDir) {
        File stateFile = new File(filesDir, STATE_FILE_NAME);
        if (!stateFile.isFile()) {
            return new JSONObject();
        }
        try {
            long len = stateFile.length();
            if (len <= 0L || len > MAX_STATE_FILE_BYTES) {
                return new JSONObject();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) len);
            FileInputStream fis = new FileInputStream(stateFile);
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
            } finally {
                fis.close();
            }
            String raw = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(raw);
        } catch (Throwable t) {
            Log.w(TAG, "Resetting unreadable cloud sync state file", t);
            return new JSONObject();
        }
    }

    private static void writeSyncStateLocked(File filesDir, JSONObject state) {
        File stateFile = new File(filesDir, STATE_FILE_NAME);
        File tmpFile = new File(filesDir, STATE_FILE_NAME + ".tmp");
        try {
            byte[] payload = state.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream fos = new FileOutputStream(tmpFile);
            try {
                fos.write(payload);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                }
            } finally {
                fos.close();
            }
            if (!tmpFile.renameTo(stateFile)) {
                copyFileAndSync(tmpFile, stateFile);
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to persist cloud sync state", t);
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
        }
    }

    private static FileLock acquireFileLockWithRetry(RandomAccessFile raf) throws IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return raf.getChannel().lock();
            } catch (OverlappingFileLockException e) {
                if (attempt == 49) {
                    throw new IOException("Timed out waiting for in-process FileLock", e);
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for FileLock", ie);
                }
            }
        }
        throw new IOException("Unable to acquire FileLock");
    }

    // -------------------------------------------------------------------------
    // Custom filter selection check (ignores "MFSU" manual rules)
    // -------------------------------------------------------------------------

    public static boolean hasCustomFilterSelection(Context appCtx) {
        try {
            ArrayList<AdblockerListSource> sources = bridgeGetAllAdblockSources(appCtx, new AtomicInteger(0));
            if (sources == null || sources.isEmpty()) {
                return false;
            }
            Set<String> enabled = new HashSet<String>();
            for (int i = 0; i < sources.size(); i++) {
                AdblockerListSource src = sources.get(i);
                if (src == null) continue;
                String uuid = src.getUuid();
                if (uuid == null || AdblockerListSource.MANUAL_FILTER_SOURCE_UUID.equals(uuid)) {
                    // Manual user rules ("MFSU") compile into mManualEngine separately
                    // from abnfp.bin / abnhp.bin / abcfp.bin, so they work alongside Cloud Sync!
                    continue;
                }
                if (src.isEnabled()) {
                    enabled.add(uuid);
                }
            }
            return !DEFAULT_ENABLED_UUIDS.equals(enabled);
        } catch (Throwable t) {
            Log.w(TAG, "hasCustomFilterSelection check failed", t);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Redirect-safe HTTPS + SHA-256 helpers
    // -------------------------------------------------------------------------

    private static JSONObject fetchManifestJson() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        HttpURLConnection conn = openRedirectSafeConnection(manifestUrl);
        try {
            InputStream is = new BufferedInputStream(conn.getInputStream(), 8192);
            try {
                byte[] buf = new byte[4096];
                long total = 0L;
                int n;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_MANIFEST_BYTES) {
                        throw new IOException("Manifest exceeds size cap");
                    }
                    bos.write(buf, 0, n);
                }
            } finally {
                is.close();
            }
        } finally {
            conn.disconnect();
        }
        String jsonText = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        return new JSONObject(jsonText);
    }

    private static void downloadAndVerifySha256(
        String urlStr,
        File targetTmp,
        String expectedSha256,
        long expectedSize,
        long maxBytes
    ) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn = openRedirectSafeConnection(urlStr);
        long total = 0L;
        try {
            InputStream is = new BufferedInputStream(conn.getInputStream(), 32 * 1024);
            try {
                FileOutputStream fos = new FileOutputStream(targetTmp);
                BufferedOutputStream bos = new BufferedOutputStream(fos, 32 * 1024);
                try {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) {
                            throw new IOException("Download exceeds maxBytes: " + total);
                        }
                        md.update(buf, 0, n);
                        bos.write(buf, 0, n);
                    }
                    bos.flush();
                    try {
                        fos.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                } finally {
                    bos.close();
                }
            } finally {
                is.close();
            }
        } finally {
            conn.disconnect();
        }

        if (expectedSize > 0L && total != expectedSize) {
            //noinspection ResultOfMethodCallIgnored
            targetTmp.delete();
            throw new IOException("Size mismatch: expected=" + expectedSize + " actual=" + total);
        }

        String actualSha = toHexLower(md.digest());
        if (!actualSha.equalsIgnoreCase(expectedSha256)) {
            //noinspection ResultOfMethodCallIgnored
            targetTmp.delete();
            throw new IOException("SHA-256 mismatch: expected=" + expectedSha256 + " actual=" + actualSha);
        }
    }

    private static HttpURLConnection openRedirectSafeConnection(String startUrl) throws IOException {
        boolean allowLoopbackHttp = isLoopbackTestUrl(manifestUrl);
        URL current = new URL(startUrl);
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            validateScheme(current, allowLoopbackHttp);
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "GinArena-1DM-Sync/1.0");
            conn.setRequestProperty("Accept-Encoding", "identity");

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = new URL(current, location);
                continue;
            }
            if (code != 200) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " for " + current);
            }
            return conn;
        }
        throw new IOException("Exceeded max redirects (" + MAX_REDIRECT_HOPS + ")");
    }

    private static void validateScheme(URL url, boolean allowLoopbackHttp) throws IOException {
        String protocol = url.getProtocol();
        if ("https".equalsIgnoreCase(protocol)) {
            return;
        }
        if (allowLoopbackHttp && "http".equalsIgnoreCase(protocol) && "127.0.0.1".equals(url.getHost())) {
            return;
        }
        throw new IOException("Refusing non-HTTPS URL: " + protocol + "://" + url.getHost());
    }

    private static boolean isLoopbackTestUrl(String url) {
        return url != null && url.startsWith("http://127.0.0.1:");
    }

    private static String resolveSiblingAssetUrl(String sanitizedAssetName) throws Exception {
        return new URL(new URL(manifestUrl), sanitizedAssetName).toString();
    }

    private static String sanitizeAssetName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128 || trimmed.contains("..")) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '-';
            if (!ok) return null;
        }
        return trimmed;
    }

    private static String normalizeSha256(String sha) {
        if (sha == null) return null;
        String trimmed = sha.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() != 64) return null;
        for (int i = 0; i < 64; i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return null;
        }
        return trimmed;
    }

    private static String gunzipUtf8Bounded(File gzFile, long maxDecodedBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
        GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(new FileInputStream(gzFile), 16 * 1024));
        try {
            byte[] buf = new byte[16 * 1024];
            long total = 0L;
            int n;
            while ((n = gis.read(buf)) != -1) {
                total += n;
                if (total > maxDecodedBytes) {
                    throw new IOException("Gunzip payload exceeds limit: " + total);
                }
                bos.write(buf, 0, n);
            }
        } finally {
            gis.close();
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void copyFileAndSync(File src, File dst) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src), 32 * 1024);
        try {
            FileOutputStream fos = new FileOutputStream(dst);
            BufferedOutputStream bos = new BufferedOutputStream(fos, 32 * 1024);
            try {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = bis.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                bos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                }
            } finally {
                bos.close();
            }
        } finally {
            bis.close();
        }
    }

    private static String toHexLower(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }

    private static void broadcastAdblockRefresh(Context appCtx) {
        try {
            Intent intent = new Intent("intent.action.ACTION_REFRESH_ADBLOCK");
            intent.setPackage(appCtx.getPackageName());
            appCtx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to broadcast ACTION_REFRESH_ADBLOCK", t);
        }
    }

    /**
     * Matches 1DM's kh7 batch-update broadcast so any running Browser/UI process
     * reloads userscripts.db into its live ug7 cache immediately (ug7's receiver
     * ignores broadcasts where sender_pid == Process.myPid()).
     */
    private static void broadcastUserscriptsChanged(Context appCtx) {
        try {
            Intent intent = new Intent("idm.userscript.SCRIPTS_CHANGED");
            intent.setPackage(appCtx.getPackageName());
            intent.putExtra("sender_pid", Process.myPid());
            appCtx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to broadcast SCRIPTS_CHANGED", t);
        }
    }

    private static boolean isNetworkAvailable(Context appCtx) {
        try {
            Object svc = appCtx.getSystemService("connectivity");
            if (svc instanceof ConnectivityManager) {
                NetworkInfo info = ((ConnectivityManager) svc).getActiveNetworkInfo();
                return info != null && info.isConnectedOrConnecting();
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private static Context safeAppContext(Context ctx) {
        Context app = ctx.getApplicationContext();
        return app != null ? app : ctx;
    }

    private static void closeLockQuietly(FileLock lock, RandomAccessFile raf) {
        if (lock != null) {
            try {
                lock.release();
            } catch (Throwable ignored) {
            }
        }
        if (raf != null) {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Test-only hook for loopback HTTP verification against 127.0.0.1. */
    static void setManifestUrlForTest(String url) {
        manifestUrl = (url == null || url.isEmpty()) ? DEFAULT_MANIFEST_URL : url;
    }
}
