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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.util.HumlaServiceFragment;

public class ChannelListFragment extends HumlaServiceFragment implements OnChannelClickListener, OnUserClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = ChannelListFragment.class.getName();
    private TextView mEmptyView;
    private MenuItem mMenuRegisterItem;

    private IHumlaObserver mServiceObserver = new HumlaObserver() {
        @Override
        public void onDisconnected(HumlaException e) {
            mChannelView.setAdapter(null);
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();

            if (getService() == null || !getService().isConnected()) {
                return;
            }

            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in onUserJoinedChannel: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                scrollToChannel(newChannel.getId());
            }
        }

        @Override
        public void onChannelAdded(IChannel channel) {
            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onChannelRemoved(IChannel channel) {
            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onChannelStateUpdated(IChannel channel) {
            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onUserConnected(IUser user) {
            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onUserRemoved(IUser user, String reason) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }

            mChannelListAdapter.updateChannels();
            mChannelListAdapter.notifyDataSetChanged();
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            mChannelListAdapter.updateUserStates(user, mChannelView);

            // Perbarui visibilitas menu register ketika status user berubah dari server
            updateRegisterMenuVisibility();

            if (getActivity() != null) {
                getActivity().supportInvalidateOptionsMenu();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            mChannelListAdapter.updateUserStates(user, mChannelView);
        }
    };

    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(getActivity() != null)
                getActivity().supportInvalidateOptionsMenu();
        }
    };

    private RecyclerView mChannelView;
    private ChannelListAdapter mChannelListAdapter;
    private ChatTargetProvider mTargetProvider;
    private DatabaseProvider mDatabaseProvider;
    private ActionMode mActionMode;
    private Settings mSettings;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mTargetProvider = (ChatTargetProvider) getParentFragment();
        } catch (ClassCastException e) {
            throw new ClassCastException(getParentFragment().toString()+" must implement ChatTargetProvider");
        }
        try {
            mDatabaseProvider = (DatabaseProvider) getActivity();
        } catch (ClassCastException e) {
            throw new ClassCastException(getActivity().toString()+" must implement DatabaseProvider");
        }
        mSettings = Settings.getInstance(activity);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channel_list, container, false);

        mChannelView = (RecyclerView) view.findViewById(R.id.channelUsers);
        mChannelView.setLayoutManager(new LinearLayoutManager(getActivity()));

        mChannelView.setFocusable(true);
        mChannelView.setFocusableInTouchMode(true);

        mChannelView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        View focusedChild = mChannelView.getFocusedChild();
                        if (focusedChild != null) {
                            focusedChild.performClick();
                            return true;
                        }
                    }
                }
                return false;
            }
        });

        mEmptyView = (TextView) view.findViewById(R.id.empty_search_view);

        return view;
    }

    private final BroadcastReceiver mChannelUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ACTION_UPDATE_CHANNELS".equals(intent.getAction())) {
                if (mChannelListAdapter != null) {
                    mChannelListAdapter.updateChannels();
                    mChannelListAdapter.notifyDataSetChanged();
                }
            }
        }
    };

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        IntentFilter channelFilter = new IntentFilter("ACTION_UPDATE_CHANNELS");

        if (getActivity() != null) {
            ContextCompat.registerReceiver(
                    getActivity(),
                    mChannelUpdateReceiver,
                    channelFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );

            ContextCompat.registerReceiver(
                    getActivity(),
                    mBluetoothReceiver,
                    new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        }
    }

    @Override
    public void onDetach() {
        try {
            if (getActivity() != null) {
                getActivity().unregisterReceiver(mBluetoothReceiver);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (getActivity() != null) {
                getActivity().unregisterReceiver(mChannelUpdateReceiver);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mServiceObserver;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        try {
            if (mChannelListAdapter == null) {
                setupChannelList();
            } else {
                mChannelListAdapter.setService(service);
            }

            if (service != null && service.isConnected()) {
                IHumlaSession session = service.HumlaSession();
                if (session != null) {
                    // Jika channel session masih 0, join ke channel 1 (sesuai kode asli Anda)
                    if (session.getSessionChannel().getId() == 0) {
                        session.joinChannel(1);
                    } else {
                        // --- TAMBAHKAN INI: Otomatis scroll ke channel yang sedang di-join ---
                        int activeChannelId = session.getSessionChannel().getId();
                        // Beri sedikit jeda post agar RecyclerView selesai merender layout list-nya
                        mChannelView.post(() -> scrollToChannel(activeChannelId));
                    }
                }
            }

        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        if (getService() != null && getService().isConnected()) {
            IHumlaSession humlaSession = getService().HumlaSession();

            MenuItem bluetoothItem = menu.findItem(R.id.menu_bluetooth);
            if (bluetoothItem != null && humlaSession != null) {
                bluetoothItem.setChecked(humlaSession.usingBluetoothSco());
            }
        }

        // Perbarui visibilitas menu register setiap kali menu disiapkan
        updateRegisterMenuVisibility();
    }

    public void updateAllowedChannels(String allowedChannelsCsv) {
        if (getActivity() != null) {
            SharedPreferences prefs = getActivity().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            prefs.edit().putString("allowed_channels", allowedChannelsCsv).apply();
        }

        if (mChannelListAdapter != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mChannelListAdapter.updateChannels();
                    mChannelListAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Perbarui judul toolbar ke channel aktif saat fragment ini tampil/aktif
        if (getActivity() instanceof MumlaActivity) {
            ((MumlaActivity) getActivity()).updateActionBarTitleToCurrentChannel();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Cek dulu apakah service valid
        if (getService() == null)
            return super.onOptionsItemSelected(item);

        int itemId = item.getItemId();

        if (itemId == R.id.menu_bluetooth) {
            if (getService().isConnected()) {
                IHumlaSession session = getService().HumlaSession();
                item.setChecked(!item.isChecked());
                if (item.isChecked()) {
                    session.enableBluetoothSco();
                } else {
                    session.disableBluetoothSco();
                }
            }
            return true;
        } else if (itemId == R.id.action_register) {
            if (getService().isConnected()) {
                try {
                    IHumlaSession session = getService().HumlaSession();
                    session.registerUser(session.getSessionId());
                } catch (Exception e) {
                    Log.d(TAG, "Error registering user: " + e);
                }
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.channel_menu, menu);

        // Simpan referensi item menu register
        mMenuRegisterItem = menu.findItem(R.id.action_register);
        updateRegisterMenuVisibility(); // Periksa visibilitas saat menu pertama kali dibuat
    }

    private void setupChannelList() throws RemoteException {
        mChannelListAdapter = new ChannelListAdapter(getActivity(), getService(),
                mDatabaseProvider.getDatabase(), getChildFragmentManager(),
                isShowingPinnedChannels(), mSettings.shouldShowUserCount());
        mChannelListAdapter.setOnChannelClickListener(this);
        mChannelListAdapter.setOnUserClickListener(this);
        mChannelView.setAdapter(mChannelListAdapter);
        mChannelListAdapter.notifyDataSetChanged();
    }

    // === METHOD UNTUK MENANGANI PENCARIAN/FILTER CHANNEL ===
    // === SESUAIKAN METHOD INI DI ChannelListFragment.java ===
    public void filterChannels(String query) {
        Log.d("HYTERA_SEARCH", "4. ChannelListFragment menerima query: [" + query + "]");

        // Ganti "mAdapter" dengan nama variabel adapter yang sesuai di class Anda (misal: mChannelListAdapter)
        if (mChannelListAdapter != null) {
            Log.d("HYTERA_SEARCH", "5. Adapter ditemukan, memanggil filter()");
            mChannelListAdapter.filter(query, mEmptyView);
        } else {
            Log.d("HYTERA_SEARCH", "5. ERROR: Variabel adapter di ChannelListFragment masih NULL!");
        }
    }

    public void scrollToChannel(int channelId) {
        int channelPosition = mChannelListAdapter.getChannelPosition(channelId);
        mChannelView.scrollToPosition(channelPosition);
    }

    public void scrollToUser(int userId) {
        int userPosition = mChannelListAdapter.getUserPosition(userId);
        mChannelView.scrollToPosition(userPosition);
    }

    private boolean isShowingPinnedChannels() {
        return getArguments().getBoolean("pinned");
    }

    @Override
    public void onChannelClick(IChannel channel) {
        if (mTargetProvider.getChatTarget() != null &&
                channel.equals(mTargetProvider.getChatTarget().getChannel()) &&
                mActionMode != null) {
            mActionMode.finish();
        } else {
            ActionMode.Callback cb = new ChatTargetActionModeCallback(mTargetProvider, new ChatTargetProvider.ChatTarget(channel)) {
                @Override
                public void onDestroyActionMode(ActionMode actionMode) {
                    super.onDestroyActionMode(actionMode);
                    mActionMode = null;
                }
            };
            mActionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(cb);
        }
    }

    private void updateRegisterMenuVisibility() {
        if (mMenuRegisterItem == null || getService() == null || !getService().isConnected()) {
            return;
        }

        try {
            IUser selfUser = getService().HumlaSession().getSessionUser();
            if (selfUser != null) {
                boolean canRegister = (selfUser.getUserId() < 0);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mMenuRegisterItem.setVisible(canRegister);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "exception in updateRegisterMenuVisibility: " + e);
        }
    }

    @Override
    public void onUserClick(IUser user) {
        if (mTargetProvider.getChatTarget() != null &&
                user.equals(mTargetProvider.getChatTarget().getUser()) &&
                mActionMode != null) {
            mActionMode.finish();
        } else {
            ActionMode.Callback cb = new ChatTargetActionModeCallback(mTargetProvider, new ChatTargetProvider.ChatTarget(user)) {
                @Override
                public void onDestroyActionMode(ActionMode actionMode) {
                    super.onDestroyActionMode(actionMode);
                    mActionMode = null;
                }
            };
            mActionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(cb);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Settings.PREF_SHOW_USER_COUNT.equals(key) && mChannelListAdapter != null) {
            mChannelListAdapter.setShowChannelUserCount(mSettings.shouldShowUserCount());
        }
    }
}