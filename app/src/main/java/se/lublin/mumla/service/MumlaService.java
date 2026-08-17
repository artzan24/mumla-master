/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;
import android.net.wifi.WifiManager;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    private static final String CHANNEL_ID = "mumla_foreground_service_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 101;

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;
    private static final int BASE_RECONNECT_DELAY = 3000; // Mulai dari 3 detik
    private static final int MAX_RECONNECT_DELAY = 60000;
    private boolean mUserRequestedDisconnect = false;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** CPU WakeLock agar koneksi tidak terputus saat aplikasi di-minimize/layar mati */
    private PowerManager.WakeLock mCpuWakeLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private String mLastKnownUsername = "Unknown";

    //private static final int RECONNECT_DELAY_MS = 5000;

    private String mCurrentNrp = "Unknown";

    // === VARIABEL UNTUK TOLERANSI SINYAL & AUTO-RECONNECT ===
    private final Handler mReconnectHandler = new Handler(Looper.getMainLooper());
    private int mReconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 24; // ~2 menit toleransi sinyal
    private boolean mIsRetryingConnection = false;
    private boolean mIsForegroundStarted = false;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    private void acquireLocks() {
        // 1. Mencegah CPU tertidur (WakeLock)
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla::KeepAliveWakeLock");
            wakeLock.acquire(10 * 60 * 60 * 1000L); // Tahan selama max 10 jam (atau atur sesuai kebutuhan)
        }

        // 2. Mencegah WiFi terputus saat standby (WifiLock)
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null && (wifiLock == null || !wifiLock.isHeld())) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mumla::KeepAliveWifiLock");
            wifiLock.acquire();
        }
    }

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            mUserRequestedDisconnect = false;

            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";

            try {
                mNotification = MumlaConnectionNotification.create(MumlaService.this,
                        getString(R.string.mumlaConnecting) + tor,
                        MumlaService.this);
                // Hanya tampilkan jika service foreground sudah aktif normal
                if (mIsForegroundStarted) {
                    mNotification.show();
                }
            } catch (Exception e) {
                Log.w(TAG, "Gagal memperbarui notifikasi koneksi di background: " + e.getMessage());
            }

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            mIsRetryingConnection = false;
            mReconnectAttempts = 0;
            mReconnectHandler.removeCallbacksAndMessages(null);

            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }

            acquireCpuWakeLock();

            if (mBackgroundSyncHandler != null && mBackgroundSyncRunnable != null) {
                mBackgroundSyncHandler.removeCallbacks(mBackgroundSyncRunnable);
                mBackgroundSyncHandler.post(mBackgroundSyncRunnable);
                Log.d(TAG, "🚀 Background sync dipicu dari onConnected()");
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            Log.w(TAG, "⚠️ Sinyal drop/terputus: " + (e != null ? e.getMessage() : "Koneksi terganggu"));

            acquireCpuWakeLock();

            if (mNotification != null) {
                mNotification.hide();
                mNotification = null;
            }

            // Jika ini murni karena user menolak/disconnect, jangan jalankan auto-reconnect
            if (mUserRequestedDisconnect) {
                Log.d(TAG, "🛑 Disconnect manual oleh user, mengabaikan auto-reconnect.");
                return; // Langsung hentikan eksekusi di sini!
            }

            // Jika bukan karena user (misal jaringan putus), baru jalankan auto-reconnect
            triggerAutoReconnect(e);
        }

        // (CATATAN: Method disconnect() telah DIHAPUS dari sini karena salah tempat)

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null && user.getTexture() == null) {
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) return;

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened());
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    String href = anchor.attr("href");
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                    user.getTalkState() == TalkState.TALKING &&
                    mPTTSoundEnabled) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
            }
        }
    };

    /**
     * Auto-reconnect loop aman dari Android 12+ Background Restrictions
     */
    private void triggerAutoReconnect(final HumlaException lastException) {
        if (mIsRetryingConnection || isConnectionEstablished()) {
            return;
        }

        mIsRetryingConnection = true;
        mReconnectAttempts = 0;

        // PEGANG WAKE LOCK AGAR TIDAK TERBEKU SAAT STANDBY
        acquireCpuWakeLock();

        mReconnectHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isConnectionEstablished()) {
                    Log.d(TAG, "✅ Reconnect berhasil!");
                    mIsRetryingConnection = false;
                    mReconnectAttempts = 0;
                    releaseCpuWakeLock(); // Lepas jika sudah sukses
                    return;
                }

                if (mReconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    long delay = (long) (BASE_RECONNECT_DELAY * Math.pow(2, mReconnectAttempts));
                    if (delay > MAX_RECONNECT_DELAY) delay = MAX_RECONNECT_DELAY;

                    mReconnectAttempts++;
                    Log.d(TAG, "🔄 Percobaan " + mReconnectAttempts + " - Menunggu " + (delay/1000) + " detik...");

                    try {
                        reconnect();
                    } catch (Throwable ex) {
                        Log.w(TAG, "Error saat mencoba reconnect: " + ex.getMessage());
                    }

                    // Gunakan postDelayed biasa atau Alarm jika ingin lebih tangguh di background
                    mReconnectHandler.postDelayed(this, delay);
                } else {
                    Log.e(TAG, "❌ Maksimal percobaan tercapai. Berhenti mencoba.");
                    mIsRetryingConnection = false;
                    releaseCpuWakeLock(); // Lepas wake lock

                    if (mReconnectNotification != null) {
                        mReconnectNotification.hide();
                        mReconnectNotification = null;
                    }

                    // Matikan total service dan aplikasi
                    MumlaService.this.disconnect();
                    stopSelf();
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        acquireLocks();
        // Aktifkan Foreground Service HANYA SEKALI saat service hidup
        startForegroundServiceWithNotification();
        registerObserver(mObserver);

        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        if (mReconnectHandler != null) {
            mReconnectHandler.removeCallbacksAndMessages(null);
        }

        releaseCpuWakeLock();

        if (mBackgroundSyncHandler != null && mBackgroundSyncRunnable != null) {
            mBackgroundSyncHandler.removeCallbacks(mBackgroundSyncRunnable);
        }

        stopForeground(true);
        mIsForegroundStarted = false;

        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        unregisterObserver(mObserver);
        if(mTTS != null) mTTS.shutdown();
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Restart service jika user menghapus aplikasi dari recent apps
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartServicePI = PendingIntent.getService(
                getApplicationContext(), 1, restartService,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        android.app.AlarmManager alarmService = (android.app.AlarmManager)getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, restartServicePI);

        super.onTaskRemoved(rootIntent);
    }

    private final Handler mBackgroundSyncHandler = new Handler(Looper.getMainLooper());

    private int mConnectionFailCount = 0; // Tambahkan variabel counter di atas

    private final Runnable mBackgroundSyncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (isConnectionEstablished()) {
                    // Reset counter jika koneksi normal kembali
                    mConnectionFailCount = 0;

                    String currentUsername = "Unknown";
                    String activeChannelName = "Lobby Utama";

                    try {
                        if (getSessionUser() != null) {
                            currentUsername = getSessionUser().getName();
                            if (getSessionUser().getChannel() != null) {
                                activeChannelName = getSessionUser().getChannel().getName();
                            }
                        }
                    } catch (Exception ignored) {}

                    se.lublin.mumla.helper.RealtimeStatusSync.sendStatus(
                            MumlaService.this,
                            currentUsername,
                            "online",
                            activeChannelName
                    );

                } else {
                    // Jika koneksi terputus / tidak ada internet
                    mConnectionFailCount++;

                    // Misalkan dicek tiap 5 detik, jika 8 kali gagal (= ~40 detik)
                    if (mConnectionFailCount >= 6) {
                        // Panggil fungsi untuk memunculkan peringatan ke layar
                        showNetworkWarningOnUI();

                        // Reset counter agar dialog tidak muncul terus-menerus setiap detik
                        mConnectionFailCount = 0;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error sync: " + e.getMessage());
            }

            mBackgroundSyncHandler.postDelayed(this, 5000);
        }
    };

    private void showNetworkWarningOnUI() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                // Pilihan 1: Menggunakan Toast panjang
                android.widget.Toast.makeText(
                        MumlaService.this,
                        "⚠️ Koneksi internet bermasalah! Sinkronisasi status terganggu.",
                        android.widget.Toast.LENGTH_LONG
                ).show();

                // Pilihan 2: Jika ingin menggunakan Broadcast agar Activity bisa menampilkan Dialog khusus
                Intent intent = new Intent("ACTION_NETWORK_LOST");
                sendBroadcast(intent);
            }
        });
    }

    @Override
    public void onConnectionSynchronized() {
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        IntentFilter filter = new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK);
        ContextCompat.registerReceiver(
                this,
                mTalkReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
        );

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }

        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }

        mBackgroundSyncHandler.removeCallbacks(mBackgroundSyncRunnable);
        mBackgroundSyncHandler.post(mBackgroundSyncRunnable);
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);

        if (!mIsRetryingConnection) {
            releaseCpuWakeLock();
            sendOfflineStatus();
        }

        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {}

        if (mBackgroundSyncHandler != null) {
            mBackgroundSyncHandler.removeCallbacks(mBackgroundSyncRunnable);
        }

        mChannelOverlay.hide();
        mHotCorner.setShown(false);
        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    private void sendOfflineStatus() {
        try {
            if (mLastKnownUsername == null || mLastKnownUsername.equals("Unknown")) {
                try {
                    if (getSessionUser() != null) {
                        mLastKnownUsername = getSessionUser().getName();
                    }
                } catch (Exception ignored) {}
            }

            se.lublin.mumla.helper.RealtimeStatusSync.sendStatus(
                    this,
                    mLastKnownUsername,
                    "offline",
                    "-"
            );

            Log.d(TAG, "🔴 Status offline terkirim untuk NRP: " + mLastKnownUsername);
        } catch (Exception e) {
            Log.e(TAG, "Gagal kirim status offline: " + e.getMessage());
        }
    }

    @Override
    public void disconnect() {
        // 1. Tandai sebagai aksi manual
        mUserRequestedDisconnect = true;

        // 2. Langsung hentikan/batalkan semua antrean handler auto-reconnect yang sedang menunggu!
        if (mReconnectHandler != null) {
            mReconnectHandler.removeCallbacksAndMessages(null);
        }

        // 3. Matikan status retry
        mIsRetryingConnection = false;
        mReconnectAttempts = 0;

        // 4. Sembunyikan notifikasi reconnect jika kebetulan sedang muncul
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        // 5. Panggil fungsi asli pemutus koneksi
        super.disconnect();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_INPUT_METHOD:
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                        AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    private void startForegroundServiceWithNotification() {
        if (mIsForegroundStarted) return;

        // Pastikan channel notifikasi sudah ada (Penting untuk Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Layanan Koneksi Mumble",
                    NotificationManager.IMPORTANCE_LOW
            );
            manager.createNotificationChannel(channel);
        }

        // PendingIntent dengan FLAG_IMMUTABLE/MUTABLE disesuaikan
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent notificationIntent = new Intent(this, MumlaActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mumla Terhubung")
                .setContentText("Aplikasi berjalan di background")
                .setSmallIcon(R.drawable.tik_polri_android)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14 memerlukan tipe foreground service
                startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            mIsForegroundStarted = true;
        } catch (Exception e) {
            // Jika gagal karena background restriction, aplikasi tetap harus jalan
            Log.e(TAG, "Gagal startForeground: " + e.getMessage());
        }
    }

    private void acquireCpuWakeLock() {
        if (mCpuWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mCpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mumla:CpuWakeLock");
                mCpuWakeLock.acquire();
                Log.d(TAG, "🔒 CPU WakeLock Diaktifkan");
            }
        }
    }

    private void releaseCpuWakeLock() {
        if (mCpuWakeLock != null && mCpuWakeLock.isHeld()) {
            mCpuWakeLock.release();
            mCpuWakeLock = null;
            Log.d(TAG, "🔓 CPU WakeLock Dilepas");
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        // Cek apakah ada internet sebelum memaksa reconnect
        if (!isNetworkAvailable()) {
            Log.w(TAG, "⚠️ Internet mati, membatalkan percobaan reconnect agar tidak macet.");
            return;
        }

        if (isConnectionEstablished() || isReconnecting()) {
            Log.d(TAG, "🧹 Membersihkan koneksi lama sebelum memulai koneksi baru...");
            super.disconnect();
        }

        connect();
    }

    private boolean isNetworkAvailable() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            android.net.NetworkCapabilities capabilities = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                if (network != null) {
                    capabilities = cm.getNetworkCapabilities(network);
                }
            }
            return capabilities != null && (
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            );
        }
        return true; // Default jika gagal cek, biarkan sistem yang tangani
    }

    @Override
    public void cancelReconnect() {
        mIsRetryingConnection = false;
        mReconnectAttempts = 0;

        // Lepas WakeLock agar CPU tidak kerja terus
        releaseCpuWakeLock();

        if (mReconnectHandler != null) {
            mReconnectHandler.removeCallbacksAndMessages(null);
        }

        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        // Putus koneksi bersih
        super.disconnect();

        // Kirim status disconnect ke observer agar List Channel merespons & menutup dialog
        if (mObserver != null) {
            mObserver.onDisconnected(null);
        }

        Log.d(TAG, "🛑 Auto-reconnect dibatalkan manual, kembali ke status bersih.");
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    @Override
    public void onTalkKeyDown() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                setTalkingState(true);
            }
        }
    }

    @Override
    public void onTalkKeyUp() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking());
            } else if (isTalking()) {
                setTalkingState(false);
            }
        }
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}