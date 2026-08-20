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

package se.lublin.mumla.channel;

import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.PagerTabStrip;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.WhisperTarget;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.util.HumlaServiceFragment;

/**
 * Class to encapsulate both a ChannelListFragment and ChannelChatFragment.
 * Created by andrew on 02/08/13.
 */
public class ChannelFragment extends HumlaServiceFragment implements SharedPreferences.OnSharedPreferenceChangeListener, ChatTargetProvider {
    private static final String TAG = ChannelFragment.class.getName();

    private ViewPager mViewPager;
    private PagerTabStrip mTabStrip;
    private ImageButton mTalkButton;
    private View mTalkView;

    private View mTargetPanel;
    private ImageView mTargetPanelCancel;
    private TextView mTargetPanelText;

    private ChatTarget mChatTarget;
    /** Chat target listeners, notified when the chat target is changed. */
    private List<OnChatTargetSelectedListener> mChatTargetListeners = new ArrayList<OnChatTargetSelectedListener>();

    /** True iff the talk button has been hidden (e.g. when muted) */
    private boolean mTalkButtonHidden;

    private View mActiveSpeakerPanel;
    private TextView mActiveSpeakerText;
    private TextView mActiveSpeakerUser;
    private boolean mIsChannelBusy = false;
    private MenuItem mMenuRegisterItem;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }

            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
                return;
            }

            if (user != null) {
                // 1. Logika untuk tombol PTT jika diri sendiri yang bicara
                String username = user.getName();
                String channelId = (user.getChannel() != null) ? String.valueOf(user.getChannel().getId()) : "0";
                if (user.getSession() == selfSession) {
                    switch (user.getTalkState()) {
                        case TALKING:
                        case SHOUTING:
                        case WHISPERING:
                            mTalkButton.setPressed(true);
                            // Mengubah background & border tombol secara langsung menjadi HIJAU saat aktif/bicara
                            mTalkButton.setBackgroundResource(R.drawable.ptt_button_active_bg);
                            //Kirim Data ke CI4 tekan tombol PTT layout dan fisik
                            //sendPttDataToApi(username, channelId, "speak");
                            break;
                        case PASSIVE:
                            mTalkButton.setPressed(false);
                            // Mengembalikan background & border tombol ke warna normal (Oranye)
                            mTalkButton.setBackgroundResource(R.drawable.ptt_button_normal_bg);
                            //sendPttDataToApi(username, channelId, "release");
                            break;
                    }
                }

                // 2. Logika untuk Opsi 3: Menampilkan info user lain / siapa pun yang sedang bicara
                if (mActiveSpeakerPanel != null && mActiveSpeakerText != null) {
                    switch (user.getTalkState()) {
                        case TALKING:
                        case SHOUTING:
                        case WHISPERING:
                            mIsChannelBusy = true;
                            // Cek apakah user yang berbicara adalah Anda sendiri atau orang lain
                            String displayName;
                            if (user.getSession() == selfSession) {
                                displayName = "Anda";
                            } else {
                                displayName = user.getName();
                            }

                            // 1. Baris pertama (Nama User / Kata "Anda")
                            final String primaryText = displayName;

                            // 2. Baris kedua (Teks keterangan)
                            final String secondaryText = "sedang berbicara";

                            // 3. Nama channel untuk di bawah mic
                            final String channelText = (user.getChannel() != null) ? user.getChannel().getName() : "";

                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Set nama user/Anda di atas mic (baris utama)
                                    if (mActiveSpeakerUser != null) {
                                        mActiveSpeakerUser.setText(primaryText);
                                    }

                                    // Jika Anda memiliki TextView khusus untuk teks "sedang berbicara", atau
                                    // jika ingin digabung di satu TextView atas dengan newline (\n):
                                    if (mActiveSpeakerUser != null) {
                                        mActiveSpeakerUser.setText(primaryText + "\nsedang berbicara...");
                                    }

                                    // Set nama channel di bawah mic
                                    if (mActiveSpeakerText != null) {
                                        mActiveSpeakerText.setText(channelText);
                                    }

                                    // Tampilkan kotak modal di tengah
                                    mActiveSpeakerPanel.setVisibility(View.VISIBLE);
                                }
                            });
                            break;

                        case PASSIVE:
                            mIsChannelBusy = false;
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mActiveSpeakerPanel.setVisibility(View.GONE);
                                }
                            });
                            break;
                    }
                }
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }
            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }
            if (user != null && user.getSession() == selfSession) {
                configureInput();
                // Update visibilitas menu register secara real-time
                updateRegisterMenuVisibility();
            }
        }

        @Override
        public void onVoiceTargetChanged(VoiceTargetMode mode) {
            configureTargetPanel();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    private boolean isChannelBusy() {
        if (getService() == null || !getService().isConnected()) {
            return false;
        }

        try {
            int selfSession = getService().HumlaSession().getSessionId();
            java.util.List<? extends IUser> users = getService().HumlaSession().getSessionChannel().getUsers();
            if (users != null) {
                for (IUser user : users) {
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
            Log.d(TAG, "exception in isChannelBusy: " + e);
        }
        return false;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channel, container, false);
        mActiveSpeakerPanel = view.findViewById(R.id.active_speaker_panel);
        mActiveSpeakerText = (TextView) view.findViewById(R.id.active_speaker_text);
        mActiveSpeakerUser = (TextView) view.findViewById(R.id.active_speaker_user);

        mViewPager = (ViewPager) view.findViewById(R.id.channel_view_pager);

        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageSelected(int position) {
                if (position != 0) {
                    hideActiveSpeakerPanelAndReleasePtt();
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {}
        });

        mTalkView = view.findViewById(R.id.pushtotalk_view);
        mTalkButton = (ImageButton) view.findViewById(R.id.pushtotalk);

        mTalkButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Jika channel sibuk, blokir penekanan awal
                        if (mIsChannelBusy) {
                            showChannelBusyToast();
                            mTalkButton.setPressed(false);
                            v.setPressed(false);
                            return true;
                        }

                        if (getService() != null) {
                            getService().onTalkKeyDown();
                        }
                        mTalkButton.setPressed(true);
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // SELALU reset visual tombol agar kembali normal (release)
                        mTalkButton.setPressed(false);
                        v.setPressed(false);
                        mTalkButton.setBackgroundResource(R.drawable.ptt_button_normal_bg);

                        // SELALU kirim sinyal release ke service tanpa terhalang mIsChannelBusy
                        if (getService() != null) {
                            getService().onTalkKeyUp();
                        }
                        break;
                }
                return true;
            }
        });

        mTargetPanel = view.findViewById(R.id.target_panel);
        mTargetPanelCancel = (ImageView) view.findViewById(R.id.target_panel_cancel);
        mTargetPanelCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getService() == null || !getService().isConnected())
                    return;

                IHumlaSession session = getService().HumlaSession();
                if (session.getVoiceTargetMode() == VoiceTargetMode.WHISPER) {
                    byte target = session.getVoiceTargetId();
                    session.setVoiceTargetId((byte) 0);
                    session.unregisterWhisperTarget(target);
                }
            }
        });
        mTargetPanelText = (TextView) view.findViewById(R.id.target_panel_warning);
        configureInput();
        if (mTargetPanel != null) {
            mTargetPanel.setVisibility(View.GONE);
        }
        return view;
    }

    private void showChannelBusyToast() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(getActivity(), "Channel Sibuk", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.d(TAG, "Toast error: " + e);
                    }
                }
            });
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.registerOnSharedPreferenceChangeListener(this);

        if(mViewPager != null) { // Phone
            ChannelFragmentPagerAdapter pagerAdapter = new ChannelFragmentPagerAdapter(getChildFragmentManager());
            mViewPager.setAdapter(pagerAdapter);
        } else { // Tablet
            ChannelListFragment listFragment = new ChannelListFragment();
            Bundle listArgs = new Bundle();
            listArgs.putBoolean("pinned", isShowingPinnedChannels());
            listFragment.setArguments(listArgs);
            ChannelChatFragment chatFragment = new ChannelChatFragment();

            getChildFragmentManager().beginTransaction()
                    .replace(R.id.list_fragment, listFragment)
                    .replace(R.id.chat_fragment, chatFragment)
                    .commit();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        //inflater.inflate(R.menu.channel_menu, menu);
        // Ambil referensi item register dari menu
        mMenuRegisterItem = menu.findItem(R.id.action_register);

        // Cek status awal saat menu pertama kali dibuat
        updateRegisterMenuVisibility();
    }

    // Fungsi untuk mengecek dan menampilkan/menyembunyikan menu register
    private void updateRegisterMenuVisibility() {
        if (mMenuRegisterItem == null || getService() == null || !getService().isConnected()) {
            return;
        }

        try {
            IUser selfUser = getService().HumlaSession().getSessionUser();
            if (selfUser != null) {
                // Kondisi: Tampilkan menu hanya jika user belum terdaftar (userId < 0) dan hash valid
                boolean canRegister = selfUser.getUserId() < 0 &&
                        (selfUser.getHash() != null && !selfUser.getHash().isEmpty());

                // Gunakan getActivity().runOnUiThread jika dipanggil dari background thread
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mMenuRegisterItem.setVisible(canRegister);
                    }
                });
            }
        } catch (Exception e) {
            Log.d(TAG, "exception in updateRegisterMenuVisibility: " + e);
        }
    }

    public void filterChannels(String query) {
        Log.d("HYTERA_SEARCH", "4. ChannelFragment menerima query: [" + query + "]");

        // Cari fragment anak (child fragment) yang aktif di dalam ViewPager
        if (mViewPager != null && mViewPager.getAdapter() != null) {
            // Halaman 0 adalah ChannelListFragment
            Fragment activeFragment = getChildFragmentManager().findFragmentByTag("android:switcher:" + mViewPager.getId() + ":" + mViewPager.getCurrentItem());

            if (activeFragment instanceof ChannelListFragment) {
                Log.d("HYTERA_SEARCH", "5. Meniteruskan filter ke ChannelListFragment di dalam ViewPager");
                ((ChannelListFragment) activeFragment).filterChannels(query);
            } else {
                // Cara alternatif jika tag switcher berbeda
                List<Fragment> fragments = getChildFragmentManager().getFragments();
                for (Fragment f : fragments) {
                    if (f instanceof ChannelListFragment) {
                        ((ChannelListFragment) f).filterChannels(query);
                    }
                }
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Settings settings = Settings.getInstance(getActivity());
        int itemId = item.getItemId();
        if (itemId == R.id.action_register) {
            // Aksi ketika menu Register diklik oleh pengguna
            try {
                if (getService() != null && getService().isConnected()) {
                    IUser selfUser = getService().HumlaSession().getSessionUser();
                    if (selfUser != null && selfUser.getUserId() < 0) {
                        getService().HumlaSession().registerUser(selfUser.getSession());
                        android.widget.Toast.makeText(getActivity(), "Memproses pendaftaran...", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Gagal register manual: " + e);
            }
            return true;
        } else if (itemId == R.id.menu_input_voice) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_VOICE);
            return true;
        } else if (itemId == R.id.menu_input_ptt) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
            return true;
        } else if (itemId == R.id.menu_input_continuous) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_CONTINUOUS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        super.onPause();
        hideActiveSpeakerPanelAndReleasePtt();
        if (mTalkButton != null) {
            mTalkButton.setPressed(false);
            mTalkButton.setBackgroundResource(R.drawable.ptt_button_normal_bg);
        }
        if (getService() != null && getService().isConnected() &&
                !Settings.getInstance(getActivity()).isPushToTalkToggle()) {
            getService().HumlaSession().setTalkingState(false);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            hideActiveSpeakerPanelAndReleasePtt();
        }
    }

    // Fungsi khusus untuk menyembunyikan panel speaker & merilis PTT agar tidak nyangkut
    private void hideActiveSpeakerPanelAndReleasePtt() {
        // 1. Reset status sibuk lokal
        mIsChannelBusy = false;

        // 2. Sembunyikan panel & kembalikan tombol ke warna normal
        if (mActiveSpeakerPanel != null && getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mActiveSpeakerPanel.setVisibility(View.GONE);
                    if (mTalkButton != null) {
                        mTalkButton.setPressed(false);
                        mTalkButton.setBackgroundResource(R.drawable.ptt_button_normal_bg);
                    }
                }
            });
        }

        // 3. Paksa lepaskan PTT melalui service Mumble
        if (getService() != null && getService().isConnected()) {
            try {
                // Panggil key-up dan set talking state false
                getService().onTalkKeyUp();

                IHumlaSession session = getService().HumlaSession();
                if (session != null) {
                    session.setTalkingState(false);

                    // Ambil data user saat ini untuk dikirimkan status release ke API CI4
                    if (session.getSessionUser() != null) {
                        String username = session.getSessionUser().getName();
                        String channelId = (session.getSessionChannel() != null) ?
                                String.valueOf(session.getSessionChannel().getId()) : "0";

                        // KIRIM PAKSA STATUS RELEASE KE API CI4
                        //sendPttDataToApi(username, channelId, "release");
                    }
                }

                Log.d(TAG, "PTT secara paksa di-release dan dilaporkan ke API.");
            } catch (Exception e) {
                Log.d(TAG, "Error forcing talk key up / API release: " + e);
            }
        }
    }

    @Override
    public void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            configureTargetPanel();
            configureInput();
        }
    }

    private void configureTargetPanel() {
        // PAKSA SEMBUNYIKAN TOTAL: Abaikan semua logika target panel
        if (mTargetPanel != null) {
            mTargetPanel.setVisibility(View.GONE);
        }
        return;
    }

    // FUNGSI: KIRIM DATA PTT KE API CI4 DENGAN HEADER API KEY
    private void sendPttDataToApi(final String username, final String channelId, final String statusSpeak) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Ganti URL di bawah ini dengan endpoint API CodeIgniter 4 Anda
                    java.net.URL url = new java.net.URL("https://mumble.tekkombali.com/api/logspeak");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; utf-8");
                    conn.setRequestProperty("Accept", "application/json");

                    // TAMBAHKAN HEADER API KEY DI SINI
                    conn.setRequestProperty("X-API-KEY", "RAHASIA_RADIO_24101981");

                    conn.setDoOutput(true);

                    // Data JSON yang dikirim ke CI4
                    String jsonInputString = String.format(
                            "{\"username\": \"%s\", \"channel_id\": \"%s\", \"status_speak\": \"%s\"}",
                            username, channelId, statusSpeak
                    );

                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        byte[] input = jsonInputString.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    int responseCode = conn.getResponseCode();
                    Log.d(TAG, "API CI4 PTT Response Code: " + responseCode);

                } catch (Exception e) {
                    Log.d(TAG, "Gagal mengirim data PTT ke API: " + e.getMessage());
                }
            }
        }).start();
    }

    private boolean isShowingPinnedChannels() {
        return getArguments() != null &&
                getArguments().getBoolean("pinned");
    }

    private void configureInput() {
        Settings settings = Settings.getInstance(getActivity());

        boolean muted = false;
        if (getService() != null && getService().isConnected()) {
            IUser self = null;
            try {
                self = getService().HumlaSession().getSessionUser();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in configureInput: " + e);
            }
            muted = self == null || self.isMuted() || self.isSuppressed() || self.isSelfMuted();
        }
        boolean showPttButton =
                !muted &&
                        settings.isPushToTalkButtonShown() &&
                        settings.getInputMethod().equals(Settings.ARRAY_INPUT_METHOD_PTT);
        setTalkButtonHidden(!showPttButton);
    }

    private void setTalkButtonHidden(final boolean hidden) {
        mTalkView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        mTalkButtonHidden = hidden;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(Settings.PREF_INPUT_METHOD.equals(key)
                || Settings.PREF_PUSH_BUTTON_HIDE_KEY.equals(key)
                || Settings.PREF_PTT_BUTTON_HEIGHT.equals(key))
            configureInput();
    }

    @Override
    public ChatTarget getChatTarget() {
        return mChatTarget;
    }

    @Override
    public void setChatTarget(ChatTarget target) {
        mChatTarget = target;
        for(OnChatTargetSelectedListener listener : mChatTargetListeners)
            listener.onChatTargetSelected(target);
    }

    @Override
    public void registerChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.add(listener);
    }

    @Override
    public void unregisterChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.remove(listener);
    }

    private class ChannelFragmentPagerAdapter extends FragmentPagerAdapter {

        public ChannelFragmentPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            Fragment fragment = null;
            Bundle args = new Bundle();
            switch (i) {
                case 0:
                    fragment = new ChannelListFragment();
                    args.putBoolean("pinned", isShowingPinnedChannels());
                    break;
                case 1:
                    fragment = new ChannelChatFragment();
                    break;
            }
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getString(R.string.channel).toUpperCase();
                case 1:
                    return getString(R.string.chat).toUpperCase();
                default:
                    return null;
            }
        }



        @Override
        public int getCount() {
            return 2;
        }
    }
}