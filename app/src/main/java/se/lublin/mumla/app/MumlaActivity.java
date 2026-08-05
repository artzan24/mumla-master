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

package se.lublin.mumla.app;

import static androidx.core.content.ContentProviderCompat.requireContext;
import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.Server;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.MumbleURLParser;
import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.channel.AccessTokenFragment;
import se.lublin.mumla.channel.ChannelFragment;
import se.lublin.mumla.channel.ServerInfoFragment;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.db.PublicServer;
import se.lublin.mumla.helper.RealtimeStatusSync;
import se.lublin.mumla.model.Channel;
import se.lublin.mumla.preference.MumlaCertificateGenerateTask;
import se.lublin.mumla.preference.SettingsActivity;
import se.lublin.mumla.servers.FavouriteServerListFragment;
import se.lublin.mumla.servers.PublicServerListFragment;
import se.lublin.mumla.servers.ServerEditFragment;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.util.HumlaServiceFragment;
import se.lublin.mumla.util.HumlaServiceProvider;
import se.lublin.mumla.util.MumlaTrustStore;
import se.lublin.humla.IHumlaService;

import android.util.Log;
import android.widget.Toast;
import com.google.gson.Gson;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import se.lublin.mumla.model.LoginResponse;
import se.lublin.mumla.model.SessionManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

public class MumlaActivity extends AppCompatActivity implements ListView.OnItemClickListener,
        FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider, DatabaseProvider,
        SharedPreferences.OnSharedPreferenceChangeListener, DrawerAdapter.DrawerDataProvider,
        ServerEditFragment.ServerEditListener {
    private static final String TAG = MumlaActivity.class.getName();

    /**
     * If specified, the provided integer drawer fragment ID is shown when the activity is created.
     */
    public static final String EXTRA_DRAWER_FRAGMENT = "drawer_fragment";

    private IMumlaService mService;
    private MumlaDatabase mDatabase;
    private Settings mSettings;

    private ActionBarDrawerToggle mDrawerToggle;
    private DrawerLayout mDrawerLayout;
    private DrawerAdapter mDrawerAdapter;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 2;
    private Server mServerPendingPerm = null;
    private boolean mPermPostNotificationsAsked = false;

    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;
    private boolean mIsPttBlocked = false;

    /**
     * List of fragments to be notified about service state changes.
     */
    private final List<HumlaServiceFragment> mServiceFragments = new ArrayList<HumlaServiceFragment>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((MumlaService.MumlaBinder) service).getService();
            mService.setSuppressNotifications(true);
            mService.registerObserver(mObserver);
            mService.clearChatNotifications(); // Clear chat notifications on resume.
            mDrawerAdapter.notifyDataSetChanged();

            for (HumlaServiceFragment fragment : mServiceFragments)
                fragment.setServiceBound(true);

            // Re-show server list if we're showing a fragment that depends on the service.
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof HumlaServiceFragment &&
                    !mService.isConnected()) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            updateConnectionState(getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnected() {

            loadDrawerFragment(DrawerAdapter.ITEM_SERVER);
            // Paksa mode input audio langsung jadi PTT saat terkoneksi
            Settings settings = Settings.getInstance(MumlaActivity.this);
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);

            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onConnecting() {
            updateConnectionState(getService());
        }

        @Override
        public void onDisconnected(HumlaException e) {
            // Re-show server list if we're showing a fragment that depends on the service.
            if (getSupportFragmentManager().findFragmentById(R.id.content_frame) instanceof HumlaServiceFragment) {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
            mDrawerAdapter.notifyDataSetChanged();
            supportInvalidateOptionsMenu();

            updateConnectionState(getService());
        }

        @Override
        public void onTLSHandshakeFailed(X509Certificate[] chain) {
            if (chain.length == 0) {
                return;
            }
            final Server lastServer = getService().getTargetServer();
            try {
                final X509Certificate x509 = chain[0];

                // --- DI-BYPASS: Langsung simpan sertifikat ke trust store secara otomatis tanpa dialog ---
                String alias = lastServer.getHost();
                KeyStore trustStore = MumlaTrustStore.getTrustStore(MumlaActivity.this);
                trustStore.setCertificateEntry(alias, x509);
                MumlaTrustStore.saveTrustStore(MumlaActivity.this, trustStore);

                // Langsung sambungkan ulang ke server secara otomatis
                connectToServer(lastServer);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onPermissionDenied(String reason) {
            new MaterialAlertDialogBuilder(MumlaActivity.this)
                    .setTitle(R.string.perm_denied)
                    .setMessage(reason)
                    .show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        );
        mSettings = Settings.getInstance(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            // Mengaktifkan tombol home/kiri atas dan memaksa menjadi ikon Settings/Gear
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_settings);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mService != null && mService.isConnected()) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.disconnectSure, mService.getTargetServer().getName()))
                            .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                mService.disconnect();
                                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        setStayAwake(mSettings.shouldStayAwake());

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        mDatabase = new MumlaSQLiteDatabase(this);
        mDatabase.open();

        mDrawerLayout = findViewById(R.id.drawer_layout);

        ListView mDrawerList = findViewById(R.id.left_drawer);

        View headerView = getLayoutInflater().inflate(R.layout.list_drawer_headerlogo, mDrawerList, false);
        mDrawerList.addHeaderView(headerView, null, false);

        if (BuildConfig.FLAVOR.equals("foss")) {
            final int layoutResId = getResources().getIdentifier("list_drawer_headerdonate_foss", "xml", getPackageName());
            final int stringResId = getResources().getIdentifier("donate_link_foss", "string", getPackageName());
            if ((layoutResId != 0) && (stringResId != 0)) {
                View footerView = getLayoutInflater().inflate(layoutResId, mDrawerList, false);
                mDrawerList.addHeaderView(footerView, null, true);
                footerView.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(stringResId)));
                    startActivity(intent);
                    mDrawerLayout.closeDrawers();
                });
            }
        }

        mDrawerList.setOnItemClickListener(this);
        mDrawerAdapter = new DrawerAdapter(this, this);
        mDrawerList.setAdapter(mDrawerAdapter);

        // MENGUNCI DRAWER AGAR TIDAK BISA DIBUKA
        mDrawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        if (savedInstanceState == null) {
            if (getIntent() != null && getIntent().hasExtra(EXTRA_DRAWER_FRAGMENT)) {
                loadDrawerFragment(getIntent().getIntExtra(EXTRA_DRAWER_FRAGMENT,
                        DrawerAdapter.ITEM_FAVOURITES));
            } else {
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
            }
        }

        if (getIntent() != null &&
                Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            String url = getIntent().getDataString();
            try {
                Server server = MumbleURLParser.parseURL(url);
                DialogFragment fragment = ServerEditFragment.createServerEditDialog(
                        MumlaActivity.this, server, ServerEditFragment.Action.CONNECT_ACTION, true);
                fragment.show(getSupportFragmentManager(), "url_edit");
            } catch (MalformedURLException e) {
                Toast.makeText(this, getString(R.string.mumble_url_parse_failed), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        }

        setVolumeControlStream(mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);

        if (savedInstanceState == null) {
            if (mSettings.isFirstRun()) {
                showFirstRunGuide();
            } else {
                new StartupAction().execute(this);
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        //mDrawerToggle.syncState();
    }

    // Handler untuk sinkronisasi status realtime ke server
    /*private final Handler mSyncHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSyncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                // Cukup cek apakah mService tidak null dan terhubung
                if (mService != null && mService.isConnected()) {
                    String currentUsername = "";
                    String activeChannelName = "Lobby Utama";

                    // 1. Ambil Username/NRP dari SharedPreferences aplikasi Mumla
                    SharedPreferences prefs = getSharedPreferences("mumla_preferences", Context.MODE_PRIVATE);
                    currentUsername = prefs.getString("username", "");
                    if (currentUsername.isEmpty()) {
                        currentUsername = prefs.getString("pref_username", "");
                    }

                    // 2. Fallback jika SharedPreferences kosong (untuk pengujian)
                    if (currentUsername == null || currentUsername.isEmpty()) {
                        currentUsername = "87010203";
                    }

                    // 3. Ambil nama channel menggunakan method getActiveChannelName() yang sudah ada di MumlaActivity
                    try {
                        String detectedChannel = getActiveChannelName();
                        if (detectedChannel != null && !detectedChannel.isEmpty()) {
                            activeChannelName = detectedChannel;
                        }
                    } catch (Exception ignored) {}

                    // 4. Kirim data ke server
                    if (!currentUsername.isEmpty()) {
                        Log.d("MumlaSync", "Mengirim Status -> User: " + currentUsername + " | Channel: " + activeChannelName);
                        RealtimeStatusSync.sendStatus(MumlaActivity.this, currentUsername, "online", activeChannelName);
                    } else {
                        Log.w("MumlaSync", "Username/NRP masih kosong.");
                    }
                }
            } catch (Exception e) {
                Log.e("MumlaSync", "Error sync runnable: " + e.getMessage());
            }

            // Kirim ulang setiap 1 menit (60000 ms)
            mSyncHandler.postDelayed(this, 30000);
        }
    };*/

    /*private String getActiveChannelName() {
        try {
            if (mService != null && mService.isConnected()) {
                IHumlaSession session = mService.HumlaSession();
                if (session != null && session.getSessionChannel() != null) {
                    return session.getSessionChannel().getName();
                }
            }
        } catch (Exception e) {
            Log.e("MumlaActivity", "Error get active channel: " + e.getMessage());
        }
        return null;
    }*/

    @Override
    protected void onResume() {
        super.onResume();
        Intent connectIntent = new Intent(this, MumlaService.class);
        bindService(connectIntent, mConnection, 0);
        //mSyncHandler.post(mSyncRunnable);
        if (mDrawerLayout != null) {
            mDrawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Hentikan routine sinkronisasi realtime saat aplikasi di-pause/ditinggalkan
        /*if (mSyncHandler != null) {
            mSyncHandler.removeCallbacks(mSyncRunnable);
        }

        // Laporkan status offline ke server saat aplikasi ditutup/ditinggalkan
        try {
            String currentUsername = "";
            SharedPreferences prefs = getSharedPreferences("mumla_preferences", Context.MODE_PRIVATE);
            currentUsername = prefs.getString("username", "");

            if (!currentUsername.isEmpty()) {
                // Kirim 4 parameter lengkap: Context, username, status, keterangan
                RealtimeStatusSync.sendStatus(this, currentUsername, "offline", "Aplikasi Ditutup");
            }
        } catch (Exception e) {
            Log.e("MumlaSync", "Error send offline status: " + e.getMessage());
        }*/

        if (mErrorDialog != null)
            mErrorDialog.dismiss();
        if (mConnectingDialog != null)
            mConnectingDialog.dismiss();

        if (mService != null) {
            for (HumlaServiceFragment fragment : mServiceFragments) {
                fragment.setServiceBound(false);
            }
            mService.unregisterObserver(mObserver);
            mService.setSuppressNotifications(false);
        }
        unbindService(mConnection);
    }


    @Override
    protected void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        mDatabase.close();
        super.onDestroy();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem disconnectButton = menu.findItem(R.id.action_disconnect);
        if (disconnectButton != null) {
            disconnectButton.setVisible(mService != null && mService.isConnected());
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Intent intent = new Intent(this, se.lublin.mumla.preference.SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        if (item.getItemId() == R.id.action_disconnect) {
            if (mService != null && mService.isConnected()) {
                Server targetServer = mService.getTargetServer();
                String serverName = (targetServer != null && targetServer.getName() != null)
                        ? targetServer.getName()
                        : "Server";

                new MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.disconnectSure, serverName))
                        .setPositiveButton(R.string.confirm, (dialog, which) -> {
                            // Tambahkan pengaman null di sini agar tidak force close
                            if (mService != null) {
                                mService.disconnect();
                            }
                            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(@NotNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mDrawerToggle != null) {
            mDrawerToggle.onConfigurationChanged(newConfig);
        }
    }

    private boolean isChannelBusy() {
        if (mService == null || mService.getConnectionState() != se.lublin.humla.HumlaService.ConnectionState.CONNECTED) {
            return false;
        }
        try {
            int selfSession = mService.HumlaSession().getSessionId();
            java.util.List<? extends se.lublin.humla.model.IUser> users = mService.HumlaSession().getSessionChannel().getUsers();
            if (users != null) {
                for (se.lublin.humla.model.IUser user : users) {
                    if (user != null && user.getSession() != selfSession) {
                        switch (user.getTalkState()) {
                            case TALKING:
                            case SHOUTING:
                            case WHISPERING:
                                return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            if (event.getRepeatCount() > 0) {
                return true;
            }
            if (isChannelBusy()) {
                mIsPttBlocked = true;
                Toast.makeText(this, "Channel Sibuk", Toast.LENGTH_SHORT).show();
                return true;
            }
            mIsPttBlocked = false;
            mService.onTalkKeyDown();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
            if (mIsPttBlocked) {
                mIsPttBlocked = false;
                return true;
            }
            mService.onTalkKeyUp();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        mDrawerLayout.closeDrawers();
        loadDrawerFragment((int) id);
    }

    private void showFirstRunGuide() {
        if (mSettings.isUsingCertificate()) {
            mSettings.setFirstRun(false);
            return;
        }

        MumlaCertificateGenerateTask generateTask = new MumlaCertificateGenerateTask(MumlaActivity.this) {
            @Override
            protected void onPostExecute(DatabaseCertificate result) {
                super.onPostExecute(result);
                if (result != null) {
                    mSettings.setDefaultCertificateId(result.getId());
                }
            }
        };
        generateTask.execute();
        mSettings.setFirstRun(false);
    }

    private void loadDrawerFragment(int fragmentId) {
        // 1. Atur visibilitas toolbar secara dinamis berdasarkan halaman yang dipilih
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            if (fragmentId == DrawerAdapter.ITEM_FAVOURITES) {
                // Sembunyikan toolbar total khusus di halaman Favorites (gaya Fancy Mumble)
                toolbar.setVisibility(View.GONE);
            } else {
                // Tampilkan kembali toolbar untuk halaman Channel, Info, dll.
                toolbar.setVisibility(View.VISIBLE);
            }
        }

        Class<? extends Fragment> fragmentClass = null;
        Bundle args = new Bundle();
        switch (fragmentId) {
            case DrawerAdapter.ITEM_SERVER:
                fragmentClass = ChannelFragment.class;
                break;
            case DrawerAdapter.ITEM_INFO:
                fragmentClass = ServerInfoFragment.class;
                break;
            case DrawerAdapter.ITEM_ACCESS_TOKENS:
                fragmentClass = AccessTokenFragment.class;
                Server connectedServer = getService().getTargetServer();
                args.putLong("server", connectedServer.getId());
                args.putStringArrayList("access_tokens", (ArrayList<String>) mDatabase.getAccessTokens(connectedServer.getId()));
                break;
            case DrawerAdapter.ITEM_PINNED_CHANNELS:
                fragmentClass = ChannelFragment.class;
                args.putBoolean("pinned", true);
                break;
            case DrawerAdapter.ITEM_FAVOURITES:
                fragmentClass = FavouriteServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_PUBLIC:
                fragmentClass = PublicServerListFragment.class;
                break;
            case DrawerAdapter.ITEM_SETTINGS:
                Intent prefIntent = new Intent(this, SettingsActivity.class);
                startActivity(prefIntent);
                return;
            default:
                return;
        }

        Fragment fragment = Fragment.instantiate(this, fragmentClass.getName(), args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, fragment, fragmentClass.getName())
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();

        // Ubah judul toolbar hanya jika toolbar sedang ditampilkan
        if (toolbar != null && toolbar.getVisibility() == View.VISIBLE) {
            requireNonNull(getSupportActionBar()).setTitle(mDrawerAdapter.getItemWithId(fragmentId).title);
        }
    }

    /*public void connectToServer(final Server server) {
        mServerPendingPerm = server;
        connectToServerWithPerm();
    }*/

    public void connectToServer(final Server server) {
        final String inputNrp = server.getUsername();

        if (inputNrp == null || inputNrp.trim().isEmpty()) {
            Toast.makeText(this, "Silakan masukkan NRP pada kolom Username!", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Memvalidasi NRP ke server...", Toast.LENGTH_SHORT).show();

        String url = "https://mumble.tekkombali.com/api/login";
        String apiKey = "RAHASIA_RADIO_24101981"; // Ganti dengan X-API-KEY yang valid di CI4 Anda

        OkHttpClient client = new OkHttpClient();

        RequestBody formBody = new FormBody.Builder()
                .add("nrp", inputNrp.trim())
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-API-KEY", apiKey) // Menambahkan Header X-API-KEY
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("MUMBLE_LOGIN", "Koneksi ke server gagal: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Gagal terhubung ke server backend!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBodyString = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    // Jika sukses (200 OK)
                    Gson gson = new Gson();
                    LoginResponse loginData = gson.fromJson(responseBodyString, LoginResponse.class);

                    runOnUiThread(() -> {
                        if (loginData != null && loginData.isStatus()) {
                            String realname = loginData.getProfile().getRealname();
                            String kesatuan = loginData.getProfile().getKesatuan();
                            String nrpAsli = inputNrp.trim();

                            SessionManager sessionManager = new SessionManager(getApplicationContext());
                            sessionManager.createLoginSession(loginData.getProfile(), loginData.getAllowed_channels());

// ---> PERBAIKAN DI SINI <---
                            try {
                                List<Channel> channelObjects = loginData.getAllowed_channels();
                                StringBuilder channelIdsBuilder = new StringBuilder("1"); // Selalu sertakan ID 1 (lobby utama)

                                if (channelObjects != null) {
                                    for (Channel ch : channelObjects) {
                                        // Ubah string ID dari ch.getId() menjadi integer
                                        int channelId = Integer.parseInt(ch.getId());
                                        if (channelId != 1) { // Hindari duplikat angka 1
                                            channelIdsBuilder.append(",").append(channelId);
                                        }
                                    }
                                }

                                android.content.SharedPreferences prefs = getApplicationContext().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                                prefs.edit().putString("allowed_channels", channelIdsBuilder.toString()).apply();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            // ------------------------------------------------------------------------------------

                            server.setUsername(nrpAsli);
                            if (server.isSaved()) {
                                mDatabase.updateServer(server);
                            }

                            Toast.makeText(getApplicationContext(), "Selamat datang, " + realname + " (" + kesatuan + ")", Toast.LENGTH_LONG).show();

                            mServerPendingPerm = server;
                            connectToServerWithPerm();

                        } else {
                            String pesanError = loginData != null ? loginData.getMessage() : "NRP tidak terdaftar!";
                            showAccessDeniedDialog(pesanError);
                        }
                    });
                } else {
                    // Jika error dari server (Misal 401 Unauthorized, 404, dll)
                    String errorMessage = "Terjadi kesalahan pada server (" + response.code() + ")";
                    try {
                        // Parse menggunakan JSONObject bawaan Android agar fleksibel mengambil nested key
                        org.json.JSONObject jsonObject = new org.json.JSONObject(responseBodyString);
                        if (jsonObject.has("messages")) {
                            org.json.JSONObject messagesObj = jsonObject.getJSONObject("messages");
                            if (messagesObj.has("error")) {
                                errorMessage = messagesObj.getString("error");
                            }
                        } else if (jsonObject.has("message")) {
                            errorMessage = jsonObject.getString("message");
                        }
                    } catch (Exception e) {
                        // Jika gagal parsing JSON, gunakan isi body apa adanya jika ada
                        if (!responseBodyString.isEmpty()) {
                            errorMessage = responseBodyString;
                        }
                    }

                    final String finalErrorMessage = errorMessage;
                    runOnUiThread(() -> {
                        showAccessDeniedDialog(finalErrorMessage);
                    });
                }
            }
        });
    }

    // Helper method untuk menampilkan dialog akses ditolak agar rapi
    private void showAccessDeniedDialog(String message) {
        new MaterialAlertDialogBuilder(MumlaActivity.this)
                .setTitle("Akses Ditolak")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    public void connectToServerWithPerm() {
        if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MumlaActivity.this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !mPermPostNotificationsAsked) {
            if (ContextCompat.checkSelfPermission(MumlaActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MumlaActivity.this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
                return;
            }
        }

        if (mServerPendingPerm == null) {
            Log.w(TAG, "No pending server after getting permissions");
            return;
        }

        Server server = mServerPendingPerm;
        mServerPendingPerm = null;

        if (mService != null && mService.isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.reconnect_dialog_message)
                    .setPositiveButton(R.string.connect, (dialog, which) -> {
                        mService.registerObserver(new HumlaObserver() {
                            @Override
                            public void onDisconnected(HumlaException e) {
                                connectToServer(server);
                                mService.unregisterObserver(this);
                            }
                        });
                        mService.disconnect();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        if (mSettings.isTorEnabled()) {
            if (!OrbotHelper.isOrbotInstalled(this)) {
                mSettings.disableTor();
                new MaterialAlertDialogBuilder(MumlaActivity.this)
                        .setMessage(R.string.orbot_not_installed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            } else {
                if (!isPortOpen(HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT, 2000)) {
                    new MaterialAlertDialogBuilder(MumlaActivity.this)
                            .setMessage(getString(R.string.orbot_tor_failed, HumlaConnection.TOR_PORT))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
            }
        }

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (grantResults.length == 0) {
            return;
        }

        switch (requestCode) {
            case PERMISSIONS_REQUEST_RECORD_AUDIO:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    connectToServerWithPerm();
                } else {
                    Toast.makeText(MumlaActivity.this, getString(R.string.grant_perm_microphone),
                            Toast.LENGTH_LONG).show();
                }
                break;
            case PERMISSIONS_REQUEST_POST_NOTIFICATIONS:
                mPermPostNotificationsAsked = true;
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(MumlaActivity.this,
                            Manifest.permission.POST_NOTIFICATIONS)) {
                        Toast.makeText(MumlaActivity.this,
                                getString(R.string.grant_perm_notifications), Toast.LENGTH_LONG).show();
                    }
                }
                connectToServerWithPerm();
                break;
        }
    }

    private boolean isPortOpen(final String host, final int port, final int timeout) {
        final AtomicBoolean open = new AtomicBoolean(false);
        try {
            Thread thread = new Thread(() -> {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), timeout);
                    socket.close();
                    open.set(true);
                } catch (Exception e) {
                    Log.d(TAG, "isPortOpen() run()" + e);
                }
            });
            thread.start();
            thread.join();
            return open.get();
        } catch (Exception e) {
            Log.d(TAG, "isPortOpen() " + e);
        }
        return false;
    }

    public void connectToPublicServer(final PublicServer server) {
        final Settings settings = Settings.getInstance(this);
        final EditText usernameField = new EditText(this);
        usernameField.setHint(settings.getDefaultUsername());
        FrameLayout layout = new FrameLayout(this);
        layout.addView(usernameField);
        int horizontalPadding = (int) getResources().getDimension(R.dimen.padding_medium);
        layout.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        new MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setTitle(R.string.connectToServer)
                .setPositiveButton(R.string.connect, (dialog, which) -> {
                    if (usernameField.getText().toString().isEmpty()) {
                        server.setUsername(settings.getDefaultUsername());
                    } else {
                        server.setUsername(usernameField.getText().toString());
                    }
                    connectToServer(server);
                })
                .show();
    }

    private void setStayAwake(boolean stayAwake) {
        if (stayAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateConnectionState(IHumlaService service) {
        if (mConnectingDialog != null) {
            mConnectingDialog.dismiss();
        }
        if (mErrorDialog != null)
            mErrorDialog.dismiss();

        if (mService == null) {
            return;
        }

        switch (mService.getConnectionState()) {
            case CONNECTING:
                Server server = service.getTargetServer();
                mConnectingDialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.connecting_to_server, server.getHost()) + (mSettings.isTorEnabled() ? " (Tor)" : ""))
                        .setView(R.layout.dialog_progress)
                        .setCancelable(true)
                        .setOnCancelListener(dialog -> {
                            mService.disconnect();
                            Toast.makeText(MumlaActivity.this, R.string.cancelled,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .create();
                mConnectingDialog.show();
                break;
            case CONNECTION_LOST:
                // Cast ke IMumlaService agar method isErrorShown dan markErrorShown bisa diakses
                IMumlaService mumlaService = (IMumlaService) getService();
                if (mumlaService != null && !mumlaService.isErrorShown()) {
                    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MumlaActivity.this);
                    builder.setTitle(getString(R.string.connectionRefused) + (mSettings.isTorEnabled() ? " (Tor)" : ""));
                    HumlaException error = mumlaService.getConnectionError();
                    if (error != null && mService.isReconnecting()) {
                        builder.setMessage(error.getMessage() + "\n\n"
                                + getString(R.string.attempting_reconnect,
                                error.getCause() != null ? error.getCause().getMessage() : "unknown"));
                        builder.setPositiveButton(R.string.cancel_reconnect, (dialog, which) -> {
                            IMumlaService innerService = (IMumlaService) getService();
                            if (innerService != null) {
                                innerService.cancelReconnect();
                                innerService.markErrorShown();
                            }
                        });
                    } else if (error != null &&
                            error.getReason() == HumlaException.HumlaDisconnectReason.REJECT &&
                            (error.getReject().getType() == Mumble.Reject.RejectType.WrongUserPW ||
                                    error.getReject().getType() == Mumble.Reject.RejectType.WrongServerPW)) {
                        final EditText passwordField = new EditText(this);
                        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                        passwordField.setHint(R.string.password);
                        builder.setTitle(R.string.invalid_password);
                        builder.setMessage(error.getMessage());
                        builder.setView(passwordField);
                        builder.setPositiveButton(R.string.reconnect, (dialog, which) -> {
                            Server server1 = mumlaService.getTargetServer();
                            if (server1 == null) {
                                return;
                            }
                            String password = passwordField.getText().toString();
                            server1.setPassword(password);
                            if (server1.isSaved()) {
                                mDatabase.updateServer(server1);
                            }
                            connectToServer(server1);
                        });
                        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                            IMumlaService innerService = (IMumlaService) getService();
                            if (innerService != null) {
                                innerService.markErrorShown();
                            }
                        });
                    } else {
                        String msg = error != null ? error.getMessage() : getString(R.string.unknown);
                        builder.setMessage(msg);
                        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            IMumlaService innerService = (IMumlaService) getService();
                            if (innerService != null) {
                                innerService.markErrorShown();
                            }
                        });
                    }
                    builder.setCancelable(false);
                    mErrorDialog = builder.show();
                }
                break;
            default:
                break;
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    @Override
    public IMumlaService getService() {
        return mService;
    }
    @Override
    public MumlaDatabase getDatabase() {
        return mDatabase;
    }

    @Override
    public void addServiceFragment(HumlaServiceFragment fragment) {
        if (!mServiceFragments.contains(fragment)) {
            mServiceFragments.add(fragment);
        }
    }

    @Override
    public void removeServiceFragment(HumlaServiceFragment fragment) {
        mServiceFragments.remove(fragment);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case Settings.PREF_STAY_AWAKE:
                setStayAwake(mSettings.shouldStayAwake());
                break;
            case Settings.PREF_HANDSET_MODE:
                setVolumeControlStream(mSettings.isHandsetMode() ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                break;
        }
    }

    @Override
    public boolean isConnected() {
        return mService != null && mService.isConnected();
    }

    @Override
    public String getConnectedServerName() {
        if (mService != null && mService.isConnected()) {
            Server server = mService.getTargetServer();
            return server.getName().isEmpty() ? server.getHost() : server.getName();
        }
        if (BuildConfig.DEBUG)
            throw new RuntimeException("getConnectedServerName should only be called if connected!");
        return "";
    }

    @Override
    public void onServerEdited(ServerEditFragment.Action action, Server server) {
        switch (action) {
            case ADD_ACTION:
                mDatabase.addServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case EDIT_ACTION:
                mDatabase.updateServer(server);
                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                break;
            case CONNECT_ACTION:
                connectToServer(server);
                break;
        }
    }

    private void performLogin(String nrpInput) {
        // Ganti dengan alamat IP server CodeIgniter 4 Anda atau Domain / Localhost (10.0.2.2 untuk Emulator Android Studio)
        String url = "https://mumble.tekkombali.com/api/login";

        OkHttpClient client = new OkHttpClient();

        RequestBody formBody = new FormBody.Builder()
                .add("nrp", nrpInput)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("MUMBLE_LOGIN", "Koneksi ke server gagal: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Gagal terhubung ke server backend!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();

                    // Parsing JSON menggunakan Gson ke class LoginResponse.java yang sudah dibuat
                    Gson gson = new Gson();
                    LoginResponse loginData = gson.fromJson(jsonResponse, LoginResponse.class);

                    // Jalankan di UI Thread jika ingin memodifikasi tampilan / pindah halaman
                    runOnUiThread(() -> {
                        if (loginData != null && loginData.isStatus()) {
                            String nama = loginData.getProfile().getRealname();
                            String kesatuan = loginData.getProfile().getKesatuan();

                            // 1. SIMPAN SESI LOGIN & CHANNEL IZIN KE SHAREDPREFERENCES
                            SessionManager sessionManager = new SessionManager(getApplicationContext());
                            sessionManager.createLoginSession(loginData.getProfile(), loginData.getAllowed_channels());

                            Toast.makeText(getApplicationContext(), "Login Berhasil: " + nama + " (" + kesatuan + ")", Toast.LENGTH_LONG).show();

                            // 2. LANJUTKAN MASUK KE HALAMAN UTAMA / KONEKSI MUMBLE
                            // Contoh: Intent intent = new Intent(CurrentActivity.this, MumlaActivity.class);
                            // startActivity(intent);
                            // finish(); // Menutup activity login agar tidak bisa ditekan tombol back

                        } else {
                            String pesanError = loginData != null ? loginData.getMessage() : "NRP tidak dikenali";
                            Toast.makeText(getApplicationContext(), "Login Ditolak: " + pesanError, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }
}