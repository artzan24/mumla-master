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
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
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
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

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
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
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
import se.lublin.mumla.channel.ChannelListAdapter;
import se.lublin.mumla.channel.ChannelListFragment;
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
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import se.lublin.mumla.model.LoginResponse;
import se.lublin.mumla.helper.SessionManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

public class MumlaActivity extends AppCompatActivity implements ListView.OnItemClickListener,
        FavouriteServerListFragment.ServerConnectHandler, HumlaServiceProvider, DatabaseProvider,
        SharedPreferences.OnSharedPreferenceChangeListener, DrawerAdapter.DrawerDataProvider,
        ServerEditFragment.ServerEditListener {
    private static final String TAG = MumlaActivity.class.getName();
    public static boolean sUserCancelledReconnect = false;
    private final Handler mChannelSyncHandler = new Handler(Looper.getMainLooper());
    private final boolean[] isBackAlreadyPressedWhenEmpty = {false};
    private final boolean[] isReadyToClose = {false};
    private final long[] lastDeleteTime = {0};
    private final Runnable mChannelSyncRunnable = new Runnable() {
        @Override
        public void run() {
            // Lakukan pengecekan ke API CI4 hanya jika user sedang terhubung ke server Mumble
            if (mService != null && mService.isConnected()) {
                fetchAllowedChannelsFromApi();
            }
            // Ulangi setiap 30 detik (30000 milidetik)
            mChannelSyncHandler.postDelayed(this, 30000);
        }
    };

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
    private androidx.appcompat.widget.SearchView searchViewToolbar;
    private View btnSearch;

    private AlertDialog mConnectingDialog;
    private AlertDialog mErrorDialog;
    private boolean mIsPttBlocked = false;
    private boolean mHasShownWelcomeToast = false;
    private Toast loadingToast;

    /**
     * List of fragments to be notified about service state changes.
     */
    private final List<HumlaServiceFragment> mServiceFragments = new ArrayList<HumlaServiceFragment>();
    private static final int PERMISSION_REQUEST_BACKGROUND_LOCATION = 101;

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
        // 1. Jika savedInstanceState != null, artinya Activity ini DI-RESTART oleh sistem
        // (misalnya setelah pengguna mengubah izin/permission di Settings).
        // Maka paksa kembalikan ke SplashActivity!
        boolean isRestartedBySystem = (savedInstanceState != null);
        boolean isFromSplash = getIntent().getBooleanExtra("from_splash", false);
        boolean isFromLogout = getIntent().getBooleanExtra("EXTRA_SHOW_SERVER_LIST", false);
        boolean isActionView = Intent.ACTION_VIEW.equals(getIntent().getAction());

        // Tambahkan !isFromLogout agar TIDAK dilempar ke SplashActivity jika dipanggil dari Logout
        if ((isRestartedBySystem || (!isFromSplash && !isFromLogout)) && !isActionView) {
            // Hapus flag intent agar tidak berulang
            getIntent().removeExtra("from_splash");

            Intent intent = new Intent(this, SplashActivity.class);
            // Flag ini akan membersihkan stack Activity agar benar-benar mulai dari awal
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return; // Hentikan eksekusi onCreate
        }

        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        );

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        showZelloStylePermissionDialog();

        // Jika dipanggil dari logout paksa, langsung muat FavouriteServerListFragment
        if (isFromLogout) {
            getIntent().removeExtra("EXTRA_SHOW_SERVER_LIST");
            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
        } else if (getSupportFragmentManager().findFragmentById(R.id.content_frame) == null) {
            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
        }

        mSettings = Settings.getInstance(this);
        //checkInitialPermissions();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ImageButton btnSettings = findViewById(R.id.btn_toolbar_settings);
        ImageButton btnSearch = findViewById(R.id.btn_toolbar_search);
        ImageButton btnOverflow = findViewById(R.id.btn_toolbar_overflow);
        ImageButton btnToolbarBack = findViewById(R.id.btn_toolbar_back);

        // Inisialisasi global searchViewToolbar (hapus deklarasi 'androidx.appcompat.widget.SearchView' lokal di bawahnya)
        searchViewToolbar = findViewById(R.id.search_view_toolbar);

        if (btnSearch != null && searchViewToolbar != null) {
            View.OnClickListener searchClickListener = v -> {
                Log.d("HYTERA_SEARCH", "Tombol search diklik, membuka SearchView");
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("");
                }

                if (btnToolbarBack != null) {
                    btnToolbarBack.setVisibility(View.VISIBLE); // Tombol back muncul normal
                }

                searchViewToolbar.setVisibility(View.VISIBLE);
                searchViewToolbar.setIconified(false);

                View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
                if (searchEditText instanceof android.widget.EditText) {
                    android.widget.EditText editText = (android.widget.EditText) searchEditText;

                    editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    editText.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT);
                    editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

                    editText.requestFocus();
                }
            };

            btnSearch.setOnClickListener(searchClickListener);

            // Sembunyikan ikon kaca pembesar bawaan SearchView
            ImageView searchIcon = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_mag_icon);
            if (searchIcon != null) {
                searchIcon.setImageResource(android.R.color.transparent);
                searchIcon.setMinimumWidth(0);
                searchIcon.setMinimumHeight(0);
                searchIcon.getLayoutParams().width = 0;
                searchIcon.getLayoutParams().height = 0;
                searchIcon.setVisibility(View.GONE);
            }

            if (btnToolbarBack != null) {
                btnToolbarBack.setOnClickListener(v -> closeSearchView());

                btnToolbarBack.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && searchViewToolbar != null && searchViewToolbar.getVisibility() == View.VISIBLE) {
                        View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
                        if (searchEditText instanceof android.widget.EditText) {
                            android.widget.EditText editText = (android.widget.EditText) searchEditText;
                            String currentText = editText.getText().toString();

                            // Jika teks BELUM KOSONG -> Hapus 1 huruf
                            if (!currentText.isEmpty()) {
                                // Setiap kali masih ada teks, pastikan flag close di-reset
                                isReadyToClose[0] = false;

                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastDeleteTime[0] > 200) {
                                    lastDeleteTime[0] = currentTime;

                                    Log.d("HYTERA_SEARCH", "Menghapus 1 karakter");

                                    int selectionStart = editText.getSelectionStart();
                                    if (selectionStart > 0) {
                                        editText.getText().delete(selectionStart - 1, selectionStart);
                                    } else {
                                        editText.setText(currentText.substring(0, currentText.length() - 1));
                                        editText.setSelection(editText.getText().length());
                                    }
                                }
                                editText.requestFocus();
                            }
                            // Jika teks SUDAH KOSONG
                            else {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastDeleteTime[0] > 300) {
                                    lastDeleteTime[0] = currentTime;

                                    // Jika belum siap tutup, set jadi true dulu (artinya huruf baru saja habis dibersihkan)
                                    if (!isReadyToClose[0]) {
                                        isReadyToClose[0] = true;
                                        Log.d("HYTERA_SEARCH", "Teks sudah bersih. Tekan sekali lagi untuk keluar search.");
                                        editText.requestFocus();
                                    } else {
                                        // Jika sudah ditekan sekali lagi dalam keadaan kosong, baru tutup search!
                                        isReadyToClose[0] = false;
                                        Log.d("HYTERA_SEARCH", "Menutup search.");
                                        closeSearchView();
                                    }
                                } else {
                                    editText.requestFocus();
                                }
                            }
                        }
                    }
                });
            }

            // Tangani TextWatcher dan KeyListener langsung di dalam EditText
            View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
            if (searchEditText instanceof android.widget.EditText) {
                android.widget.EditText editText = (android.widget.EditText) searchEditText;

                editText.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        Log.d("HYTERA_SEARCH", "Teks berubah: " + s.toString());

                        // Jika ada ketikan baru, batalkan status siap tutup
                        if (s.length() > 0) {
                            isReadyToClose[0] = false;
                        }

                        filterChannels(s.toString());
                    }
                    @Override
                    public void afterTextChanged(android.text.Editable s) {}
                });

                // --- INI KUNCINYA: Tangkap tombol Back/Del langsung di komponen teks aktif ---
                editText.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) {
                            String currentText = editText.getText().toString();

                            if (!currentText.isEmpty()) {
                                Log.d("HYTERA_SEARCH", "Menghapus 1 karakter");
                                int selectionStart = editText.getSelectionStart();
                                if (selectionStart > 0) {
                                    editText.getText().delete(selectionStart - 1, selectionStart);
                                } else {
                                    editText.setText(currentText.substring(0, currentText.length() - 1));
                                    editText.setSelection(editText.getText().length());
                                }
                                return true;
                            } else {
                                closeSearchView();
                                return true;
                            }
                        }
                    }
                    return false;
                });
            }

            // Tombol Close (ikon X) di Kanan
            View closeButton = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_close_btn);
            if (closeButton != null) {
                closeButton.setClickable(true);
                closeButton.setEnabled(true);
                closeButton.setFocusable(true);
                closeButton.setFocusableInTouchMode(true);

                Runnable clearSearchAction = () -> {
                    searchViewToolbar.setQuery("", false);
                    if (searchEditText instanceof android.widget.EditText) {
                        ((android.widget.EditText) searchEditText).requestFocus();
                        ((android.widget.EditText) searchEditText).setSelection(0);
                    }
                };

                closeButton.setOnClickListener(v -> clearSearchAction.run());
            }
        }

        if (btnSearch != null && searchViewToolbar != null) {
            View.OnClickListener searchClickListener = v -> {
                Log.d("HYTERA_SEARCH", "Tombol search diklik, membuka SearchView");
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("");
                }

                if (btnToolbarBack != null) {
                    btnToolbarBack.setVisibility(View.VISIBLE); // Tombol back muncul normal
                }

                searchViewToolbar.setVisibility(View.VISIBLE);
                searchViewToolbar.setIconified(false);

                View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
                if (searchEditText instanceof android.widget.EditText) {
                    android.widget.EditText editText = (android.widget.EditText) searchEditText;

                    editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    editText.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT);
                    editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

                    editText.requestFocus();
                }
            };

            btnSearch.setOnClickListener(searchClickListener);

            // Sembunyikan ikon kaca pembesar bawaan SearchView
            ImageView searchIcon = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_mag_icon);
            if (searchIcon != null) {
                searchIcon.setImageResource(android.R.color.transparent);
                searchIcon.setMinimumWidth(0);
                searchIcon.setMinimumHeight(0);
                searchIcon.getLayoutParams().width = 0;
                searchIcon.getLayoutParams().height = 0;
                searchIcon.setVisibility(View.GONE);
            }

            // Tombol Back Kustom di Toolbar
            if (btnToolbarBack != null) {
                btnToolbarBack.setOnClickListener(v -> closeSearchView());
            }

            // Tangani TextWatcher untuk filtering
            View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
            if (searchEditText instanceof android.widget.EditText) {
                android.widget.EditText editText = (android.widget.EditText) searchEditText;

                editText.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        Log.d("HYTERA_SEARCH", "Teks berubah: " + s.toString());

                        // Reset status back jika pengguna mengetik huruf baru
                        if (s.length() > 0) {
                            isBackAlreadyPressedWhenEmpty[0] = false;
                        }

                        filterChannels(s.toString());
                    }
                    @Override
                    public void afterTextChanged(android.text.Editable s) {}
                });
            }

            // Tombol Close (ikon X) di Kanan
            View closeButton = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_close_btn);
            if (closeButton != null) {
                closeButton.setClickable(true);
                closeButton.setEnabled(true);
                closeButton.setFocusable(true);
                closeButton.setFocusableInTouchMode(true);

                Runnable clearSearchAction = () -> {
                    searchViewToolbar.setQuery("", false);
                    if (searchEditText instanceof android.widget.EditText) {
                        ((android.widget.EditText) searchEditText).requestFocus();
                        ((android.widget.EditText) searchEditText).setSelection(0);
                    }
                };

                closeButton.setOnClickListener(v -> clearSearchAction.run());
            }
        }

        if (btnOverflow != null) {
            btnOverflow.setOnClickListener(v -> {
                androidx.appcompat.widget.PopupMenu popupMenu = new androidx.appcompat.widget.PopupMenu(MumlaActivity.this, v);
                popupMenu.getMenuInflater().inflate(R.menu.channel_menu, popupMenu.getMenu());

                // --- PENGECEKAN STATUS REGISTER ---
                try {
                    IHumlaSession session = getService().HumlaSession();
                    if (session != null) {
                        IUser selfUser = session.getSessionUser();
                        if (selfUser != null) {
                            boolean isRegistered = (selfUser.getUserId() >= 0);
                            if (isRegistered) {
                                popupMenu.getMenu().findItem(R.id.action_register).setVisible(false);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d("PopupMenu", "Error checking register state: " + e);
                }
                // ----------------------------------

                popupMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();

                    if (itemId == R.id.action_disconnect) {
                        // --- TANGANI DISCONNECT LANGSUNG DI ACTIVITY ---
                        if (getService() != null && getService().isConnected()) {
                            new MaterialAlertDialogBuilder(MumlaActivity.this)
                                    .setMessage(getString(R.string.disconnectSure, getService().getTargetServer().getName()))
                                    .setPositiveButton(R.string.confirm, (dialog, which) -> {
                                        getService().disconnect();
                                        loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();
                        }
                        return true;
                    } else if (itemId == R.id.action_register) {
                        // --- TANGANI REGISTER LANGSUNG DI ACTIVITY ---
                        try {
                            IHumlaSession session = getService().HumlaSession();
                            if (session != null) {
                                session.registerUser(session.getSessionId());
                            }
                        } catch (Exception e) {
                            Log.d("PopupMenu", "Error registering user: " + e);
                        }
                        return true;
                    }

                    // Untuk menu lainnya (seperti Bluetooth atau Input Method), teruskan ke fragment
                    Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
                    if (currentFragment instanceof ChannelListFragment) {
                        return ((ChannelListFragment) currentFragment).onOptionsItemSelected(item);
                    }
                    return false;
                });

                popupMenu.show();
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);

                boolean isChannelScreen = false;
                if (currentFragment != null) {
                    String fragmentClassName = currentFragment.getClass().getName();
                    if (fragmentClassName.contains("ChannelFragment")) {
                        isChannelScreen = true;
                    }
                }

                if (isChannelScreen) {
                    moveTaskToBack(true);
                } else if (mService != null && mService.isConnected()) {
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

        setVolumeControlStream(mSettings.isHandsetMode() ?
                AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);

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

        if (savedInstanceState == null) {
            if (mSettings.isFirstRun()) {
                showFirstRunGuide();
            } else {
                new StartupAction().execute(this);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                if (searchViewToolbar != null && searchViewToolbar.getVisibility() == View.VISIBLE) {
                    View searchEditText = searchViewToolbar.findViewById(androidx.appcompat.R.id.search_src_text);
                    if (searchEditText instanceof android.widget.EditText) {
                        android.widget.EditText editText = (android.widget.EditText) searchEditText;
                        String currentText = editText.getText().toString();

                        Log.d("HYTERA_SEARCH", "dispatchKeyEvent global mencegat KeyCode: " + keyCode + " | Teks: [" + currentText + "]");

                        if (!currentText.isEmpty()) {
                            int selectionStart = editText.getSelectionStart();
                            if (selectionStart > 0) {
                                editText.getText().delete(selectionStart - 1, selectionStart);
                            } else {
                                editText.setText(currentText.substring(0, currentText.length() - 1));
                                editText.setSelection(editText.getText().length());
                            }
                            return true;
                        } else {
                            closeSearchView();
                            return true;
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void closeSearchView() {
        Log.d("HYTERA_SEARCH", "Menjalankan closeSearchView()");
        ImageButton btnToolbarBack = findViewById(R.id.btn_toolbar_back);

        // Ambil referensi SearchView secara langsung agar tidak null
        androidx.appcompat.widget.SearchView searchViewToolbar = findViewById(R.id.search_view_toolbar);

        if (searchViewToolbar != null) {
            searchViewToolbar.setQuery("", false);
            searchViewToolbar.clearFocus();
            searchViewToolbar.setIconified(true);
            searchViewToolbar.setVisibility(View.GONE); // Sekarang teks input pasti ikut hilang
        }

        if (btnToolbarBack != null) {
            btnToolbarBack.setVisibility(View.GONE); // Sembunyikan tombol back kustom
            btnToolbarBack.setFocusable(true);
        }

        updateActionBarTitleToCurrentChannel();

        View btnSearch = findViewById(R.id.btn_toolbar_search);
        if (btnSearch != null) {
            btnSearch.requestFocus();
        }

        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (currentFragment instanceof ChannelListFragment) {
            ((ChannelListFragment) currentFragment).filterChannels("");
        }
    }

    public void setToolbarTitle(String title) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        }
    }
    private void filterChannels(String query) {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);

        if (currentFragment instanceof ChannelFragment) {
            ((ChannelFragment) currentFragment).filterChannels(query);
        } else if (currentFragment instanceof ChannelListFragment) {
            ((ChannelListFragment) currentFragment).filterChannels(query);
        }
    }

    public void updateActionBarTitleToCurrentChannel() {
        try {
            if (getService() != null && getService().isConnected()) {
                IHumlaSession session = getService().HumlaSession();
                if (session != null) {
                    IUser selfUser = session.getSessionUser();
                    if (selfUser != null) {
                        IChannel currentChannel = selfUser.getChannel();
                        if (currentChannel != null && currentChannel.getName() != null) {
                            String channelName = currentChannel.getName();
                            if (getSupportActionBar() != null) {
                                getSupportActionBar().setTitle(channelName);
                            }
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d("MumlaActivity", "Error updating channel title: " + e.getMessage());
        }

        // Fallback: Jika belum connect atau tidak ada channel, gunakan nama aplikasi
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    private void checkInitialPermissions() {
        // 1. Cek Rekam Audio terlebih dahulu
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    201 // Request code khusus untuk Audio
            );
            return;
        }

        // 2. Jika Audio sudah, cek Notifikasi (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        202 // Request code khusus untuk Notifikasi
                );
                return;
            }
        }

        // 3. Jika Notifikasi sudah, cek Lokasi Utama (Fine Location)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    203 // Request code khusus untuk Lokasi Depan
            );
            return;
        }

        // 4. Jika semua izin dasar di atas sudah lengkap, lanjut ke izin latar belakang
        checkBackgroundLocationPermission();
    }

    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Akses Lokasi Latar Belakang")
                        .setMessage("Agar posisi perangkat selalu akurat terpantau seperti Zello, pilih opsi 'Selalu izinkan' (Always allow) pada menu berikutnya.")
                        .setPositiveButton("Lanjutkan", (dialog, which) -> {
                            ActivityCompat.requestPermissions(
                                    MumlaActivity.this,
                                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                    101 // Request code 101
                            );
                        })
                        .setCancelable(false)
                        .show();
            } else {
                // Jika izin lokasi latar belakang sudah aktif sebelumnya, langsung ke dialog baterai OEM
                showOemBatteryOptimizationDialog();
            }
        } else {
            // Untuk Android di bawah versi 10, langsung ke dialog baterai OEM
            showOemBatteryOptimizationDialog();
        }
    }

    private void showZelloStylePermissionDialog() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);

        // 1. Pengecekan pembatasan baterai DIHAPUS agar tidak memicu dialog terus-menerus di Hytera

        // 2. Cek apakah izin dasar (Audio & Lokasi) sudah diberikan oleh pengguna
        boolean isAudioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean isLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        // 3. JIKA IZIN DASAR SUDAH BERES, lewati dialog
        if (isAudioGranted && isLocationGranted) {
            prefs.edit().putBoolean("zello_perm_dialog_v7", true).apply();
            return;
        }

        // 4. Jika izin dicabut lagi, reset flag-nya agar dialog muncul kembali
        if (!isAudioGranted || !isLocationGranted) {
            prefs.edit().putBoolean("zello_perm_dialog_v7", false).apply();
        }

        // 5. Jika flag bernilai true dan semuanya aman, langsung jalankan inisialisasi
        if (prefs.getBoolean("zello_perm_dialog_v7", false)) {
            checkInitialPermissions();
            return;
        }

        // 6. Jika belum lengkap, TAMPILKAN LAYOUT CUSTOM DIALOG ZELLO
        getWindow().getDecorView().post(() -> {
            try {
                LayoutInflater inflater = LayoutInflater.from(this);
                View dialogView = inflater.inflate(R.layout.dialog_zello_permissions, null);

                androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                        .setView(dialogView)
                        .setCancelable(false)
                        .create();

                Button btnContinue = dialogView.findViewById(R.id.btnContinuePermissions);
                if (btnContinue != null) {
                    btnContinue.setOnClickListener(v -> {
                        prefs.edit().putBoolean("zello_perm_dialog_v7", true).apply();
                        dialog.dismiss();

                        // Memanggil pengecekan izin sistem Android
                        checkInitialPermissions();
                    });
                }

                dialog.show();
            } catch (Exception e) {
                e.printStackTrace();
                checkInitialPermissions();
            }
        });
    }

    private void showOemBatteryOptimizationDialog() {
        boolean isIgnoringBatteryOptimizations = false;

        // 1. Cek status baterai saat ini secara langsung dari sistem
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        }

        // 2. Jika baterai SUDAH diatur "Tidak ada pembatasan", jangan tampilkan dialog apa pun
        if (isIgnoringBatteryOptimizations) {
            // Opsional: simpan status selesai
            getPreferences(MODE_PRIVATE).edit().putBoolean("oem_battery_dialog_shown", true).apply();
            return;
        }

        // 3. Jika baterai BELUM disetting, PASTIKAN DIALOG TETAP MUNCUL (abaikan SharedPreferences yang memblokir)
        new MaterialAlertDialogBuilder(this)
                .setTitle("Setelan Latar Belakang")
                .setMessage("Agar radio Roip TIK Bali tetap aktif dan tidak mati saat layar terkunci, silakan ubah setelan baterai menjadi 'Tidak ada pembatasan' (Unrestricted).")
                .setPositiveButton("Buka Pengaturan", (dialog, which) -> {
                    // Panggil fungsi pembuka pengaturan baterai Xiaomi Anda
                    openXiaomiBatterySettings();
                })
                .setNegativeButton("Nanti", (dialog, which) -> {
                    // Pengguna memilih nanti, dialog akan ditutup tapi bisa muncul lagi nanti jika baterai belum disetting
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void openXiaomiBatterySettings() {
        boolean isIgnoringBatteryOptimizations = false;

        // Cek apakah status baterai sudah "Tidak ada pembatasan"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        }

        // Ambil status flag apakah perizinan lainnya sudah ditandai selesai oleh pengguna
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        boolean isOtherPermissionsDone = prefs.getBoolean("xiaomi_other_permissions_completed", false);

        Intent intentToOpen;

        // KONDISI 1: Jika baterai BELUM disetting, arahkan ke Detail Baterai terlebih dahulu
        if (!isIgnoringBatteryOptimizations) {
            intentToOpen = new Intent().setComponent(new android.content.ComponentName(
                            "com.miui.powerkeeper",
                            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )).putExtra("package_name", getPackageName())
                    .putExtra("package_uid", getApplicationInfo().uid);
        }
        // KONDISI 2: Jika baterai SUDAH aman, tapi Perizinan Lainnya BELUM ditandai selesai
        else if (!isOtherPermissionsDone) {
            intentToOpen = new Intent().setComponent(new android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )).putExtra("extra_pkgname", getPackageName());
        }
        // KONDISI 3: Jika SEMUA sudah beres, buka App Info standar sebagai fallback terakhir
        else {
            intentToOpen = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intentToOpen.setData(Uri.parse("package:" + getPackageName()));
        }

        try {
            intentToOpen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intentToOpen);
        } catch (Exception e) {
            // Fallback pengaman jika komponen khusus Xiaomi gagal terbuka
            try {
                Intent fallbackIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallbackIntent.setData(Uri.parse("package:" + getPackageName()));
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallbackIntent);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Menangkap respons dari izin satu per satu (Audio: 201, Notifikasi: 202, Lokasi: 203)
        if (requestCode == 201 || requestCode == 202 || requestCode == 203) {
            // Setelah satu izin di-klik, panggil kembali checkInitialPermissions()
            // untuk lanjut memunculkan izin berikutnya secara otomatis
            checkInitialPermissions();
        }
        else if (requestCode == 101) {
            // Setelah dialog Lokasi Latar Belakang selesai, lanjut ke Baterai OEM
            showOemBatteryOptimizationDialog();
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        //mDrawerToggle.syncState();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // =========================================================================
        // 0. PAKSA FOKUS KONTROL FISIK / TOTAL CONTROL KE ACTIVITY INI
        // =========================================================================
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        if (drawerLayout != null) {
            drawerLayout.setFocusable(true);
            drawerLayout.setFocusableInTouchMode(true);
            drawerLayout.requestFocus();
        }

        View contentView = findViewById(R.id.content_frame);
        if (contentView != null) {
            contentView.setFocusable(true);
            contentView.setFocusableInTouchMode(true);
            contentView.requestFocus();
        }

        // =========================================================================
        // 1. CEK JIKA DIPANGGIL DARI LOGOUT PAKSA (ChannelDetailActivity)
        // =========================================================================
        if (getIntent() != null && getIntent().getBooleanExtra("EXTRA_SHOW_SERVER_LIST", false)) {
            // Hapus extra agar tidak terus terpicu pada resume normal berikutnya
            getIntent().removeExtra("EXTRA_SHOW_SERVER_LIST");

            try {
                // Langsung timpa fragment ke FavouriteServerListFragment (List Server)
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_frame, new se.lublin.mumla.servers.FavouriteServerListFragment())
                        .commitAllowingStateLoss();
            } catch (Exception e) {
                Log.e("MumlaActivity", "Gagal mengganti ke ServerList: " + e.getMessage());
            }
        }
        // 2. Handling Batal/Keluar Reconnect dari ChannelDetailActivity
        if (sUserCancelledReconnect) {
            if (mErrorDialog != null && mErrorDialog.isShowing()) {
                try {
                    mErrorDialog.dismiss();
                } catch (Exception ignored) {}
            }
        }
        // =========================================================================

        Intent connectIntent = new Intent(this, MumlaService.class);
        bindService(connectIntent, mConnection, 0);

        // Refresh langsung saat resume / dari splash
        if (mService != null && mService.isConnected()) {
            fetchAllowedChannelsFromApi();
        }

        // Nyalakan polling berkala selanjutnya (Setiap 30 detik)
        if (mChannelSyncHandler != null && mChannelSyncRunnable != null) {
            mChannelSyncHandler.postDelayed(mChannelSyncRunnable, 30000);
        }

        if (mDrawerLayout != null) {
            mDrawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Memperbarui getIntent()

        if (intent != null && intent.getBooleanExtra("EXTRA_USER_CANCELLED_RECONNECT", false)) {
            if (mErrorDialog != null && mErrorDialog.isShowing()) {
                try {
                    mErrorDialog.dismiss();
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mChannelSyncHandler != null && mChannelSyncRunnable != null) {
            mChannelSyncHandler.removeCallbacks(mChannelSyncRunnable);
        }

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
                            if (mService != null) {
                                // 1. Pastikan flag manual disconnect aktif duluan di service
                                // (bisa dibantu panggil method disconnect yang sudah kita buat)
                                mService.disconnect();
                            }

                            // 2. Beri jeda sangat singkat (misal 150-200ms) menggunakan Handler
                            // agar service sempat memproses state "disconnected secara manual"
                            // sebelum Activity memuat ulang fragment favorites.
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                            }, 200);
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
        // Cek apakah tombol yang ditekan adalah tombol PTT yang dikonfigurasi
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

        // Tangani tombol OK / Center untuk memicu klik pada item list yang sedang difokuskan
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            View focusedView = getCurrentFocus();
            if (focusedView != null) {
                focusedView.performClick(); // Memicu aksi klik item yang sedang disorot
                return true;
            }
        }

        // Tangani tombol Panah Atas agar bisa melompat ke Toolbar jika berada di channel teratas
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            View focusedView = getCurrentFocus();
            if (focusedView != null && focusedView.getParent() instanceof RecyclerView) {
                RecyclerView recyclerView = (RecyclerView) focusedView.getParent();
                RecyclerView.ViewHolder viewHolder = recyclerView.getChildViewHolder(focusedView);

                // Jika ini adalah item paling atas di daftar channel (posisi 0)
                if (viewHolder != null && viewHolder.getAdapterPosition() == 0) {
                    // Cari tombol/ikon di Toolbar (misalnya ikon Settings di pojok kiri atas)
                    View toolbarAction = findViewById(R.id.toolbar); // Sesuaikan ID elemen di toolbar
                    if (toolbarAction != null) {
                        toolbarAction.requestFocus();
                        return true;
                    }
                }
            }
            return super.onKeyDown(keyCode, event);
        }

        // Tombol panah lainnya (Bawah, Kiri, Kanan) biarkan sistem yang menangani perpindahan fokusnya
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.onKeyDown(keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Tangani pelepasan tombol PTT
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

    public void loadDrawerFragment(int fragmentId) {
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

        //Toast.makeText(this, "Cek Username atau NRP ke server", Toast.LENGTH_SHORT).show();
        loadingToast = Toast.makeText(this, "Cek Username atau NRP ke server", Toast.LENGTH_SHORT);
        loadingToast.show();

        String url = "https://mumble.tekkombali.com/api/login";
        String apiKey = "RAHASIA_RADIO_24101981"; // Ganti dengan X-API-KEY yang valid di CI4 Anda

        OkHttpClient client = new OkHttpClient();
        // AMBIL PASSWORD LANGSUNG DARI SERVER OBJECT ATAU CEK JIKA KOSONG
        String passwordCi4 = server.getPassword();

        // (Opsional Cadangan): Jika server.getPassword() isinya password mumble "PoldaBali241081",
        // maka kita ambil dari SharedPreferences dengan fallback teks kosong
        if (passwordCi4 == null || passwordCi4.equals("PoldaBali241081")) {
            SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            passwordCi4 = prefs.getString("saved_ci4_password", "");
        }

        RequestBody formBody = new FormBody.Builder()
                .add("nrp", inputNrp.trim())
                .add("password", passwordCi4)
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
                    // Jika sukses (200 OK) dari API Login
                    Gson gson = new Gson();
                    LoginResponse loginData = gson.fromJson(responseBodyString, LoginResponse.class);

                    runOnUiThread(() -> {
                        if (loadingToast != null) {
                            loadingToast.cancel();
                        }
                        if (loginData != null && loginData.isStatus()) {
                            String realname = loginData.getProfile().getRealname();
                            String nrpAsli = inputNrp.trim();

                            SessionManager sessionManager = new SessionManager(getApplicationContext());
                            sessionManager.createLoginSession(loginData.getProfile(), loginData.getAllowed_channels());

                            try {
                                List<Channel> channelObjects = loginData.getAllowed_channels();
                                StringBuilder channelIdsBuilder = new StringBuilder("1");

                                if (channelObjects != null) {
                                    for (Channel ch : channelObjects) {
                                        int channelId = Integer.parseInt(ch.getId());
                                        if (channelId != 1) {
                                            channelIdsBuilder.append(",").append(channelId);
                                        }
                                    }
                                }

                                android.content.SharedPreferences prefs = getApplicationContext().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                                prefs.edit().putString("allowed_channels", channelIdsBuilder.toString()).apply();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            server.setUsername(nrpAsli);
                            if (server.isSaved()) {
                                mDatabase.updateServer(server);
                            }

                            // Tampilkan pesan selamat datang
                            /*String fullText = "Selamat datang,\n" + realname;
                            //android.text.SpannableString spannable = new android.text.SpannableString(fullText);
                            //spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    15, fullText.length(), 0);*/

                            android.content.SharedPreferences prefs = getApplicationContext().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                            prefs.edit().putString("realname", realname).apply();

                            //Toast toast = Toast.makeText(getApplicationContext(), spannable, Toast.LENGTH_LONG);
                            //toast.show();

                            // --- TAMBAHKAN PENGECEKAN KONEKSI SEBELUM LANJUT ---
                            // Pastikan server tujuan tidak kosong sebelum memicu koneksi socket Mumble
                            if (server != null) {
                                mServerPendingPerm = server;
                                connectToServerWithPerm();
                            } else {
                                showAccessDeniedDialog("Konfigurasi server tidak valid.");
                            }

                        } else {
                            String pesanError = loginData != null ? loginData.getMessage() : "NRP tidak terdaftar!";
                            showAccessDeniedDialog(pesanError);
                        }
                    });
                } else {
                    // Jika error dari server (Misal 401 Unauthorized, 404, dll)
                    if (loadingToast != null) {
                        loadingToast.cancel();
                    }
                    String errorMessage = "Terjadi kesalahan pada server (" + response.code() + ")";
                    try {
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

        // 2. Cek izin LOKASI (BARU)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
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

        server.setHost("roip.tekkombali.my.id");
        server.setPort(50000);
        server.setPassword("PoldaBali241081");

        ServerConnectTask connectTask = new ServerConnectTask(this, mDatabase);
        connectTask.execute(server);
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
                        .setTitle(getString(R.string.connecting_to_server) + (mSettings.isTorEnabled() ? " (Tor)" : ""))
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

            case CONNECTED:
                try {
                    if (!mHasShownWelcomeToast) {
                        android.content.SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                        String realname = prefs.getString("realname", "");

                        if (realname != null && !realname.isEmpty()) {
                            String fullText = "Login Sukses\nSelamat datang, " + realname;
                            android.text.SpannableString spannable = new android.text.SpannableString(fullText);
                            spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    15, fullText.length(), 0);

                            // MEMBUAT CUSTOM LAYOUT POLOS (DIJAMIN TANPA IKON APLIKASI)
                            android.widget.LinearLayout container = new android.widget.LinearLayout(MumlaActivity.this);
                            container.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                            container.setPadding(35, 25, 35, 25);

                            // Background semi-transparan ala Toast standar
                            android.graphics.drawable.GradientDrawable backgroundDrawable = new android.graphics.drawable.GradientDrawable();
                            backgroundDrawable.setColor(android.graphics.Color.parseColor("#CC323232")); // Hitam transparan
                            backgroundDrawable.setCornerRadius(20);
                            container.setBackground(backgroundDrawable);

                            android.widget.TextView textView = new android.widget.TextView(MumlaActivity.this);
                            textView.setText(spannable);
                            textView.setTextColor(android.graphics.Color.WHITE);
                            textView.setTextSize(14);
                            textView.setGravity(android.view.Gravity.CENTER);
                            textView.setTextAlignment(android.view.View.TEXT_ALIGNMENT_CENTER);

                            container.addView(textView);

                            android.widget.Toast customToast = new android.widget.Toast(getApplicationContext());
                            customToast.setView(container);
                            customToast.setDuration(android.widget.Toast.LENGTH_LONG);
                            customToast.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL, 0, 150);
                            customToast.show();

                            mHasShownWelcomeToast = true;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;

            case CONNECTION_LOST:
                try {
                    IMumlaService mumlaService = (IMumlaService) getService();
                    if (mumlaService != null) {
                        HumlaException error = mumlaService.getConnectionError();

                        String rawMsg = (error != null && error.getMessage() != null) ? error.getMessage() : "";
                        String lowerMsg = rawMsg.toLowerCase();

                        // CEK APAKAH INI ERROR SSL / SERTIFIKAT
                        if (lowerMsg.contains("certificate") || lowerMsg.contains("handshake") || lowerMsg.contains("ssl") || lowerMsg.contains("tls")) {

                            // 1. PERINTAH UTAMA: Paksa service menghentikan seluruh upaya auto-reconnect!
                            mumlaService.cancelReconnect();

                            // 2. Putus koneksi sepenuhnya
                            if (mService != null) {
                                mService.disconnect();
                            }

                            // 3. Tutup dialog error sebelumnya jika sempat terbuka
                            if (mErrorDialog != null && mErrorDialog.isShowing()) {
                                try {
                                    mErrorDialog.dismiss();
                                } catch (Exception ignored) {}
                            }

                            // 4. Tampilkan dialog peringatan biasa (TANPA LOADING / TANPA RECONNECT)
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;

                                new MaterialAlertDialogBuilder(MumlaActivity.this)
                                        .setTitle("Gagal Verifikasi Sertifikat")
                                        .setMessage("Sertifikat SSL/TLS server tidak valid atau username sudah digunakan Perangkat lain. Silahkan hubungi Administrator Bid TIK Polda Bali")
                                        .setPositiveButton("Tutup", (dialog, which) -> {
                                            dialog.dismiss();
                                            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                                        })
                                        .setCancelable(false)
                                        .show();
                            });

                            // 5. Hentikan eksekusi agar kode auto-reconnect di bawahnya TIDAK JALAN SAMA SEKALI
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling SSL check in CONNECTION_LOST", e);
                }

                // --- LANJUTAN KODE UNTUK ERROR BIASA (SERVER OFFLINE / INTERNET MATI) ---
                // (Kode auto-reconnect dengan progress bar spinner Anda ditaruh di bawah sini...)
                if (sUserCancelledReconnect) {
                    sUserCancelledReconnect = false;
                    try {
                        IMumlaService mumlaService = (IMumlaService) getService();
                        if (mumlaService != null) {
                            mumlaService.cancelReconnect();
                        }
                    } catch (Exception ignored) {}

                    if (mErrorDialog != null && mErrorDialog.isShowing()) {
                        try {
                            mErrorDialog.dismiss();
                        } catch (Exception ignored) {}
                    }
                    break;
                }

                try {
                    IMumlaService mumlaService = (IMumlaService) getService();
                    if (mumlaService != null && !mumlaService.isErrorShown()) {
                        HumlaException error = mumlaService.getConnectionError();
                        String rawMsg = (error != null && error.getMessage() != null) ? error.getMessage() : "";
                        String lowerMsg = rawMsg.toLowerCase();
                        String errorMsg;

                        if (lowerMsg.contains("refused") || lowerMsg.contains("unreachable")) {
                            errorMsg = "Server Offline / Port Tertutup";
                        } else if (lowerMsg.contains("timed out") || lowerMsg.contains("timeout")) {
                            errorMsg = "Koneksi Timeout (Cek IP Server)";
                        } else if (lowerMsg.contains("network") || lowerMsg.contains("resolve") || lowerMsg.contains("unknown host")) {
                            errorMsg = "Internet Offline / Host Tidak Ditemukan";
                        } else {
                            errorMsg = "Gagal Terhubung ke Server";
                        }

                        final String finalErrorMsg = errorMsg;

                        if (mService != null && mService.getTargetServer() != null) {
                            Server target = mService.getTargetServer();
                            mService.disconnect();
                            connectToServer(target);
                        }

                        runOnUiThread(() -> {
                            try {
                                if (isFinishing() || isDestroyed()) return;

                                if (mErrorDialog != null && mErrorDialog.isShowing()) {
                                    mErrorDialog.dismiss();
                                }

                                android.widget.LinearLayout layout = new android.widget.LinearLayout(MumlaActivity.this);
                                layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                                layout.setPadding(60, 50, 60, 30);
                                layout.setGravity(android.view.Gravity.CENTER);

                                com.google.android.material.progressindicator.CircularProgressIndicator progressBar =
                                        new com.google.android.material.progressindicator.CircularProgressIndicator(MumlaActivity.this);
                                progressBar.setIndeterminate(true);

                                android.widget.TextView tvError = new android.widget.TextView(MumlaActivity.this);
                                tvError.setText(finalErrorMsg);
                                tvError.setTextAlignment(android.view.View.TEXT_ALIGNMENT_CENTER);
                                tvError.setTextSize(14);
                                tvError.setPadding(0, 30, 0, 10);

                                android.widget.TextView tvRetryInfo = new android.widget.TextView(MumlaActivity.this);
                                tvRetryInfo.setText("Tunggu, mencoba konek ulang ke server...");
                                tvRetryInfo.setTextAlignment(android.view.View.TEXT_ALIGNMENT_CENTER);
                                tvRetryInfo.setTextSize(13);

                                layout.addView(progressBar);
                                layout.addView(tvError);
                                layout.addView(tvRetryInfo);

                                mErrorDialog = new MaterialAlertDialogBuilder(MumlaActivity.this)
                                        .setTitle("Koneksi Terputus")
                                        .setView(layout)
                                        .setCancelable(false)
                                        .setNegativeButton("Batal", (dialog, which) -> {
                                            mumlaService.cancelReconnect();
                                            if (mService != null) {
                                                mService.disconnect();
                                            }
                                            dialog.dismiss();
                                            loadDrawerFragment(DrawerAdapter.ITEM_FAVOURITES);
                                        })
                                        .create();

                                mErrorDialog.show();

                            } catch (Exception e) {
                                Log.e(TAG, "Error showing dialog", e);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling CONNECTION_LOST", e);
                }
                break;

            case DISCONNECTED:
                mHasShownWelcomeToast = false;
                break;

            default:
                break;
        }
    }

    /*
     * HERE BE IMPLEMENTATIONS
     */

    private void fetchAllowedChannelsFromApi() {
        SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
        String inputNrp = prefs.getString("saved_username", "");
        String passwordCi4 = prefs.getString("saved_ci4_password", "");

        if (inputNrp.isEmpty() && mService != null && mService.getTargetServer() != null) {
            inputNrp = mService.getTargetServer().getUsername();
        }

        if (inputNrp == null || inputNrp.trim().isEmpty()) {
            return;
        }

        String url = "https://mumble.tekkombali.com/api/login";
        String apiKey = "RAHASIA_RADIO_24101981";

        OkHttpClient client = new OkHttpClient();

        RequestBody formBody = new FormBody.Builder()
                .add("nrp", inputNrp.trim())
                .add("password", passwordCi4)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-API-KEY", apiKey)
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("MUMBLE_SYNC", "Gagal melakukan sync channel: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBodyString = response.body() != null ? response.body().string() : "";

                // A. JIKA HTTP RESPONSE MENUNJUKKAN 401 (UNAUTHORIZED / TIDAK AKTIF)
                if (response.code() == 401 || !response.isSuccessful()) {
                    String errorMsg = "Sesi berakhir atau akun Anda telah dinonaktifkan.";
                    try {
                        JSONObject jsonErr = new JSONObject(responseBodyString);
                        if (jsonErr.has("messages")) {
                            JSONObject messages = jsonErr.getJSONObject("messages");
                            if (messages.has("error")) {
                                errorMsg = messages.getString("error");
                            }
                        }
                    } catch (Exception ignored) {}

                    forceLogoutUser(errorMsg);
                    return;
                }

                // B. JIKA HTTP RESPONSE SUCCESS (200 OK)
                try {
                    Gson gson = new Gson();
                    LoginResponse loginData = gson.fromJson(responseBodyString, LoginResponse.class);

                    if (loginData != null && loginData.isStatus()) {
                        List<Channel> channelObjects = loginData.getAllowed_channels();

                        // Simpan sebagai List Integer untuk pengecekan presisi (menghindari bug "2" in "12")
                        List<String> allowedIdList = new ArrayList<>();
                        allowedIdList.add("1"); // Root / Guest selalu diizinkan

                        if (channelObjects != null) {
                            for (Channel ch : channelObjects) {
                                if (!ch.getId().equals("1")) {
                                    allowedIdList.add(ch.getId());
                                }
                            }
                        }

                        // Gabungkan menjadi string separated by comma
                        String newChannelsString = TextUtils.join(",", allowedIdList);
                        String oldChannelsString = prefs.getString("allowed_channels", "");

                        // JIKA ADA PERUBAHAN DIBANDINGKAN DENGAN SESSION SEBELUMNYA
                        if (!newChannelsString.equals(oldChannelsString)) {
                            // Simpan string channel baru ke SharedPreferences
                            prefs.edit().putString("allowed_channels", newChannelsString).apply();

                            // 1. Kirim Broadcast (Adapter & Receiver lain yang mendengar akan ter-trigger)
                            Intent updateIntent = new Intent("ACTION_UPDATE_CHANNELS");
                            updateIntent.putExtra("allowed_channels", newChannelsString);
                            sendBroadcast(updateIntent);

                            // 2. Cek apakah user sedang berada di channel yang izinnya ditarik
                            if (mService != null && mService.isConnected()) {
                                try {
                                    IHumlaSession session = mService.HumlaSession();
                                    if (session != null && session.getSessionChannel() != null) {
                                        int currentChannelId = session.getSessionChannel().getId();

                                        // Pengecekan Presisi List (Mencegah false positive seperti id 2 match dengan 12)
                                        if (currentChannelId != 1 && !allowedIdList.contains(String.valueOf(currentChannelId))) {
                                            session.joinChannel(1);
                                            runOnUiThread(() -> Toast.makeText(MumlaActivity.this, "Akses channel dicabut. Anda dipindahkan ke Guest.", Toast.LENGTH_LONG).show());
                                        }
                                    }
                                } catch (Exception serviceErr) {
                                    Log.e("MUMBLE_SYNC", "Error checking active channel: " + serviceErr.getMessage());
                                }
                            }

                            // 3. Update Fragment UI secara Menyeluruh (Mencari di Active Fragments)
                            runOnUiThread(() -> {
                                try {
                                    List<Fragment> fragments = getSupportFragmentManager().getFragments();
                                    refreshChannelFragmentsRecursively(fragments, newChannelsString);
                                } catch (Exception err) {
                                    Log.e("MUMBLE_SYNC", "Error updating UI fragment: " + err.getMessage());
                                }
                            });
                        }
                    } else {
                        forceLogoutUser("Akun tidak aktif.");
                    }
                } catch (Exception e) {
                    Log.e("MUMBLE_SYNC", "Parsing error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Method Helper untuk mencari ChannelListFragment secara Rekursif
     * di seluruh Fragment dan ChildFragment yang sedang aktif.
     */
    private void refreshChannelFragmentsRecursively(List<Fragment> fragments, String newChannelsString) {
        if (fragments == null || fragments.isEmpty()) return;

        for (Fragment fragment : fragments) {
            if (fragment != null && fragment.isVisible()) {
                if (fragment instanceof ChannelListFragment) {
                    ((ChannelListFragment) fragment).updateAllowedChannels(newChannelsString);
                }
                // Jika fragment ini memiliki child fragments (misal ViewPager/TabLayout)
                if (fragment.getChildFragmentManager() != null) {
                    refreshChannelFragmentsRecursively(fragment.getChildFragmentManager().getFragments(), newChannelsString);
                }
            }
        }
    }

    /**
     * Method penendang otomatis ke halaman utama (FavouriteServerListFragment)
     */
    private void forceLogoutUser(String reasonMessage) {
        runOnUiThread(() -> {
            // 1. Beritahu user via Toast
            Toast.makeText(MumlaActivity.this, reasonMessage, Toast.LENGTH_LONG).show();

            // 2. Putus koneksi radio Mumble
            if (mService != null && mService.isConnected()) {
                try {
                    mService.disconnect();
                } catch (Exception e) {
                    Log.e("MUMBLE_SYNC", "Gagal disconnect: " + e.getMessage());
                }
            }

            // 3. Hapus semua data session login
            SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            prefs.edit().clear().apply();

            SessionManager sessionManager = new SessionManager(getApplicationContext());
            sessionManager.logoutUser();

            // 4. Ganti Fragment utama langsung ke FavouriteServerListFragment (List Server)
            try {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.content_frame, new se.lublin.mumla.servers.FavouriteServerListFragment())
                        .commitAllowingStateLoss();
            } catch (Exception e) {
                Log.e("MUMBLE_SYNC", "Gagal memuat List Server: " + e.getMessage());
            }
        });
    }

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

                            SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                            String passwordCi4 = prefs.getString("saved_ci4_password", "");

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