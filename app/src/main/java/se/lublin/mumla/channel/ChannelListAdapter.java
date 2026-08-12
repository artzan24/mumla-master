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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Drawable.ConstantState;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.mumla.R;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.drawable.CircleDrawable;
import se.lublin.mumla.service.MumlaService;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;

/**
 * Created by andrew on 31/07/13.
 */
public class ChannelListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements UserMenu.IUserLocalStateListener {
    private static final String TAG = ChannelListAdapter.class.getName();

    // Set particular bits to make the integer-based model item ids unique.
    public static final long CHANNEL_ID_MASK = (0x1L << 32);
    public static final long USER_ID_MASK = (0x1L << 33);

    private Context mContext;
    private IHumlaService mService;
    private MumlaDatabase mDatabase;
    private List<Integer> mRootChannels;
    private List<Node> mNodes;
    private List<Node> mFilteredNodes; // <--- List untuk menampung hasil filter pencarian
    /**
     * A mapping of user-set channel expansions.
     * If a key is not mapped, default to hiding empty channels.
     */
    private HashMap<Integer, Boolean> mExpandedChannels;
    private OnUserClickListener mUserClickListener;
    private OnChannelClickListener mChannelClickListener;
    private boolean mShowChannelUserCount;
    private final FragmentManager mFragmentManager;


    public ChannelListAdapter(Context context, IHumlaService service, MumlaDatabase database,
                              FragmentManager fragmentManager, boolean showPinnedOnly,
                              boolean showChannelUserCount) throws RemoteException {
        setHasStableIds(true);
        mContext = context;
        mService = service;
        mDatabase = database;
        mFragmentManager = fragmentManager;
        mShowChannelUserCount = showChannelUserCount;

        mRootChannels = new ArrayList<Integer>();
        if(showPinnedOnly) {
            mRootChannels = mDatabase.getPinnedChannels(mService.getTargetServer().getId());
        } else {
            mRootChannels.add(0);
        }

        // Construct channel tree
        mNodes = new LinkedList<Node>();
        mFilteredNodes = new LinkedList<Node>(); // <--- Inisialisasi mFilteredNodes
        mExpandedChannels = new HashMap<Integer, Boolean>();
        updateChannels();
    }

    /**
     * FUNGSI FILTER PENCARIAN REAL-TIME
     */
    public void filter(String text, TextView emptyView) {
        if (mFilteredNodes == null) {
            mFilteredNodes = new ArrayList<>();
        }
        mFilteredNodes.clear();

        text = text != null ? text.toLowerCase().trim() : "";

        if (text.isEmpty()) {
            if (mNodes != null) {
                mFilteredNodes.addAll(mNodes);
            }
        } else {
            if (mNodes != null) {
                for (Node node : mNodes) {
                    String nodeName = "";
                    if (node.isChannel() && node.getChannel().getName() != null) {
                        nodeName = node.getChannel().getName();
                    } else if (node.isUser() && node.getUser().getName() != null) {
                        nodeName = node.getUser().getName();
                    }

                    if (nodeName.toLowerCase().contains(text)) {
                        mFilteredNodes.add(node);
                    }
                }
            }
        }

        notifyDataSetChanged();

        // Atur visibilitas teks "Tidak ada"
        if (emptyView != null) {
            emptyView.setVisibility(mFilteredNodes.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater inflater = (LayoutInflater)
                mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(viewType, viewGroup, false);
        if (viewType == R.layout.channel_row) {
            return new ChannelViewHolder(view);
        } else if (viewType == R.layout.channel_user_row) {
            return new UserViewHolder(view);
        }
        return null;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
        final Node node = mFilteredNodes.get(position); // <--- Menggunakan mFilteredNodes
        if (node.isChannel()) {
            final IChannel channel = node.getChannel();
            final ChannelViewHolder cvh = (ChannelViewHolder) viewHolder;
            cvh.itemView.setOnClickListener(v -> {
                android.content.Intent intent = new android.content.Intent(mContext, ChannelDetailActivity.class);
                intent.putExtra("channel_id", channel.getId());
                intent.putExtra("channel_name", channel.getName());
                intent.putExtra("channel_description", channel.getDescription());
                mContext.startActivity(intent);
            });

            String channelName = channel.getName();
            String initials = "";
            if (channelName != null && !channelName.isEmpty()) {
                String[] words = channelName.trim().split("\\s+");
                if (words.length >= 2) {
                    initials = (words[0].substring(0, Math.min(words[0].length(), 1)) +
                            words[1].substring(0, Math.min(words[1].length(), 1))).toUpperCase();
                } else if (channelName.length() >= 2) {
                    initials = channelName.substring(0, 2).toUpperCase();
                } else {
                    initials = channelName.toUpperCase();
                }
            }
            cvh.mChannelExpandToggle.setText(initials);
            cvh.mChannelExpandToggle.setVisibility(View.VISIBLE);
            cvh.mChannelExpandToggle.setEnabled(false);

            cvh.mChannelName.setText(channelName);

            int nameTypeface = Typeface.NORMAL;
            if (mService != null && mService.isConnected()) {
                IHumlaSession session = mService.HumlaSession();
                IChannel ourChan = null;
                try {
                    ourChan = session.getSessionChannel();
                } catch(IllegalStateException e) {
                    Log.d(TAG, "exception in onBindViewHolder: " + e);
                }
                if (ourChan != null) {
                    if (channel.equals(ourChan)) {
                        nameTypeface |= Typeface.BOLD;
                        if (channel.getLinks().size() > 0) {
                            nameTypeface |= Typeface.ITALIC;
                        }
                    }
                    if (channel.getLinks().contains(ourChan)) {
                        nameTypeface |= Typeface.ITALIC;
                    }
                }
            }
            cvh.mChannelName.setTypeface(null, nameTypeface);

            int userCount = channel.getSubchannelUserCount();
            if (cvh.mChannelUserCount != null) {
                if (userCount > 0) {
                    cvh.mChannelUserCount.setVisibility(View.VISIBLE);
                    cvh.mChannelUserCount.setText(userCount + " users active");
                } else {
                    cvh.mChannelUserCount.setVisibility(View.GONE);
                }
            }

            boolean isJoined = false;
            if (mService != null && mService.isConnected()) {
                try {
                    IHumlaSession session = mService.HumlaSession();
                    if (session != null) {
                        IChannel activeChannel = session.getSessionChannel();
                        if (activeChannel != null) {
                            isJoined = (channel.getId() == activeChannel.getId());
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error checking active channel: " + e);
                }
            }
            cvh.mJoinButton.setActivated(isJoined);
            cvh.itemView.setActivated(isJoined);

            DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            int zeroPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, metrics);
            cvh.mChannelHolder.setPadding(zeroPadding,
                    cvh.mChannelHolder.getPaddingTop(),
                    zeroPadding,
                    cvh.mChannelHolder.getPaddingBottom());

            cvh.mJoinButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mService != null && mService.isConnected()) {
                        mService.HumlaSession().joinChannel(channel.getId());
                    }
                }
            });

            cvh.mMoreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ChannelMenu menu = new ChannelMenu(mContext, channel, mService, mDatabase, mFragmentManager);
                }
            });

            cvh.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return true;
                }
            });
        } else if (node.isUser()) {
            final IUser user = node.getUser();
            final UserViewHolder uvh = (UserViewHolder) viewHolder;
            uvh.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mUserClickListener != null) {
                        mUserClickListener.onUserClick(user);
                    }
                }
            });

            uvh.mUserName.setText(user.getName());

            final int typefaceStyle;
            int selfSession = -1;
            try {
                if (mService != null) {
                    selfSession = mService.HumlaSession().getSessionId();
                }
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in onBindViewHolder: " + e);
            }

            if (mService != null && mService.isConnected() && user.getSession() == selfSession) {
                typefaceStyle = Typeface.BOLD;
            } else {
                typefaceStyle = Typeface.NORMAL;
            }
            uvh.mUserName.setTypeface(null, typefaceStyle);

            uvh.mUserTalkHighlight.setImageDrawable(getTalkStateDrawable(user));

            DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
            float margin = (node.getDepth() + 1) * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25, metrics);
            uvh.mUserHolder.setPadding((int) margin,
                    uvh.mUserHolder.getPaddingTop(),
                    uvh.mUserHolder.getPaddingRight(),
                    uvh.mUserHolder.getPaddingBottom());

            uvh.mMoreButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UserMenu menu = new UserMenu(mContext, user, (MumlaService) mService,
                            mFragmentManager, ChannelListAdapter.this);
                    menu.showPopup(v);
                }
            });

            uvh.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return false;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return mFilteredNodes.size(); // <--- Menggunakan mFilteredNodes
    }

    @Override
    public int getItemViewType(int position) {
        Node node = mFilteredNodes.get(position); // <--- Menggunakan mFilteredNodes
        if (node.isChannel()) {
            return R.layout.channel_row;
        } else if (node.isUser()) {
            return R.layout.channel_user_row;
        } else {
            return 0;
        }
    }

    @Override
    public long getItemId(int position) {
        try {
            return mFilteredNodes.get(position).getId(); // <--- Menggunakan mFilteredNodes
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public void updateAllowedChannelsAndRefresh(String allowedChannelsCsv) {
        // 1. Simpan ke SharedPreferences agar sesuai dengan data terbaru CI4
        if (mContext != null) {
            SharedPreferences prefs = mContext.getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            prefs.edit().putString("allowed_channels", allowedChannelsCsv).apply();
        }

        // 2. Panggil ulang updateChannels() untuk membangun ulang pohon node berdasarkan izin baru
        updateChannels();
    }

    public void updateChannels() {
        if (mService == null || !mService.isConnected()) {
            return;
        }

        IHumlaSession session = mService.HumlaSession();
        mNodes.clear();
        try {
            List<Integer> allowedChannelIds = getAllowedChannelIdsForCurrentUser();

            for (int cid : mRootChannels) {
                IChannel channel = session.getChannel(cid);
                if (channel != null) {
                    constructNodes(null, channel, 0, mNodes, allowedChannelIds);
                }
            }
        } catch (IllegalStateException e) {
            Log.d(TAG, "exception in updateChannels: " + e);
        }

        // Sinkronkan ke mFilteredNodes setelah data asli diperbarui
        if (mFilteredNodes == null) {
            mFilteredNodes = new ArrayList<>();
        }
        mFilteredNodes.clear();
        mFilteredNodes.addAll(mNodes);
        notifyDataSetChanged();
    }

    private void constructNodes(Node parent, IChannel channel, int depth,
                                List<Node> nodes, List<Integer> allowedIds) {

        if (channel.getId() != 0) {
            if (allowedIds != null && !allowedIds.contains(channel.getId())) {
                return;
            }
        }

        Node channelNode;
        if (channel.getId() == 0) {
            channelNode = parent;
        } else {
            channelNode = new Node(parent, depth, channel);
            nodes.add(channelNode);
        }

        Boolean expandSetting = mExpandedChannels.get(channel.getId());
        if (channel.getId() != 0) {
            if ((expandSetting == null && channel.getSubchannelUserCount() == 0)
                    || (expandSetting != null && !expandSetting)) {
                channelNode.setExpanded(false);
                return;
            }
        }

        for (IChannel subc : channel.getSubchannels()) {
            int subDepth = (channel.getId() == 0) ? depth : depth + 1;
            constructNodes(channelNode, subc, subDepth, nodes, allowedIds);
        }
    }

    private List<Integer> getAllowedChannelIdsForCurrentUser() {
        List<Integer> allowed = new ArrayList<>();
        try {
            SharedPreferences prefs = mContext.getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            String channelsStr = prefs.getString("allowed_channels", "1");

            if (channelsStr != null && !channelsStr.isEmpty()) {
                String[] split = channelsStr.split(",");
                for (String s : split) {
                    allowed.add(Integer.parseInt(s.trim()));
                }
            }

            if (!allowed.contains(1)) {
                allowed.add(1);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing allowed channels: " + e);
            allowed.add(1);
        }
        return allowed;
    }

    public void updateUserStates(IUser user, RecyclerView view) {
        long itemId = user.getSession() | USER_ID_MASK;
        UserViewHolder uvh = (UserViewHolder) view.findViewHolderForItemId(itemId);
        if (uvh != null) {
            Drawable newState = getTalkStateDrawable(user);
            ConstantState state = uvh.mUserTalkHighlight.getDrawable().getCurrent().getConstantState();
            if (state != null && !state.equals(newState.getConstantState())) {
                uvh.mUserTalkHighlight.setImageDrawable(newState);
            }
        }
    }

    private Drawable getTalkStateDrawable(IUser user) {
        Resources resources = mContext.getResources();
        if (user.isSelfDeafened()) {
            return resources.getDrawable(R.drawable.outline_circle_deafened);
        } else if (user.isDeafened()) {
            return resources.getDrawable(R.drawable.outline_circle_server_deafened);
        } else if (user.isSelfMuted()) {
            return resources.getDrawable(R.drawable.outline_circle_muted);
        } else if (user.isMuted()) {
            return resources.getDrawable(R.drawable.outline_circle_server_muted);
        } else if (user.isSuppressed()) {
            return resources.getDrawable(R.drawable.outline_circle_suppressed);
        } else if (user.getTalkState() == TalkState.TALKING
                || user.getTalkState() == TalkState.SHOUTING
                || user.getTalkState() == TalkState.WHISPERING) {
            return resources.getDrawable(R.drawable.outline_circle_talking_on);
        } else {
            if (user.getTexture() != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(user.getTexture(), 0, user.getTexture().length);
                if (bitmap != null) {
                    return new CircleDrawable(mContext.getResources(), bitmap);
                }
            }
        }
        return resources.getDrawable(R.drawable.outline_circle_talking_off);
    }

    public int getUserPosition(int session) {
        long itemId = session | USER_ID_MASK;
        for (int i = 0; i < mFilteredNodes.size(); i++) {
            Node node = mFilteredNodes.get(i);
            try {
                if (node.getId() == itemId) {
                    return i;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    public int getChannelPosition(int channelId) {
        long itemId = channelId | CHANNEL_ID_MASK;
        for (int i = 0; i < mFilteredNodes.size(); i++) {
            Node node = mFilteredNodes.get(i);
            try {
                if (node.getId() == itemId) {
                    return i;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        mUserClickListener = listener;
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        mChannelClickListener = listener;
    }

    public void setShowChannelUserCount(boolean showUserCount) {
        mShowChannelUserCount = showUserCount;
        notifyDataSetChanged();
    }

    public void setService(IHumlaService service) {
        mService = service;
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            updateChannels();
            notifyDataSetChanged();
        }
    }

    @Override
    public void onLocalUserStateUpdated(final IUser user) {
        notifyDataSetChanged();

        final Server server = mService.getTargetServer();

        if (user.getUserId() >= 0 && server.isSaved()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (user.isLocalMuted()) {
                        mDatabase.addLocalMutedUser(server.getId(), user.getUserId());
                    } else {
                        mDatabase.removeLocalMutedUser(server.getId(), user.getUserId());
                    }
                    if (user.isLocalIgnored()) {
                        mDatabase.addLocalIgnoredUser(server.getId(), user.getUserId());
                    } else {
                        mDatabase.removeLocalIgnoredUser(server.getId(), user.getUserId());
                    }
                }
            }).start();
        }
    }

    private static class UserViewHolder extends RecyclerView.ViewHolder {
        public LinearLayout mUserHolder;
        public TextView mUserName;
        public ImageView mUserTalkHighlight;
        public ImageView mMoreButton;

        public UserViewHolder(View itemView) {
            super(itemView);
            mUserHolder = (LinearLayout) itemView.findViewById(R.id.user_row_title);
            mUserTalkHighlight = (ImageView) itemView.findViewById(R.id.user_row_talk_highlight);
            mUserName = (TextView) itemView.findViewById(R.id.user_row_name);
            mMoreButton = (ImageView) itemView.findViewById(R.id.user_row_more);
        }
    }

    private static class ChannelViewHolder extends RecyclerView.ViewHolder {
        public LinearLayout mChannelHolder;
        public TextView mChannelExpandToggle;
        public TextView mChannelName;
        public TextView mChannelUserCount;
        public ImageView mJoinButton;
        public ImageView mMoreButton;

        public ChannelViewHolder(View itemView) {
            super(itemView);
            mChannelHolder = (LinearLayout) itemView.findViewById(R.id.channel_row_title);
            mChannelExpandToggle = (TextView) itemView.findViewById(R.id.channel_row_expand);
            mChannelName = (TextView) itemView.findViewById(R.id.channel_row_name);
            mChannelUserCount = (TextView) itemView.findViewById(R.id.channel_row_count);
            mJoinButton = (ImageView) itemView.findViewById(R.id.channel_row_join);
            mMoreButton = (ImageView) itemView.findViewById(R.id.channel_row_more);
        }
    }

    private static class Node {
        private Node mParent;
        private IChannel mChannel;
        private IUser mUser;
        private int mDepth;
        private boolean mExpanded;

        public Node(Node parent, int depth, IChannel channel) {
            mParent = parent;
            mChannel = channel;
            mDepth = depth;
            mExpanded = true;
        }

        public Node(Node parent, int depth, IUser user) {
            mParent = parent;
            mUser = user;
            mDepth = depth;
        }

        public boolean isChannel() {
            return mChannel != null;
        }

        public boolean isUser() {
            return mUser != null;
        }

        public Node getParent() {
            return mParent;
        }

        public IChannel getChannel() {
            return mChannel;
        }

        public IUser getUser() {
            return mUser;
        }

        public Long getId() throws RemoteException {
            if (isChannel()) {
                return CHANNEL_ID_MASK | mChannel.getId();
            } else if (isUser()) {
                return USER_ID_MASK | mUser.getSession();
            }
            return null;
        }

        public int getDepth() {
            return mDepth;
        }

        public boolean isExpanded() {
            return mExpanded;
        }

        public void setExpanded(boolean expanded) {
            mExpanded = expanded;
        }
    }
}