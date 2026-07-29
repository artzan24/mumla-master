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

package se.lublin.mumla.servers;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.PublicServer;

/**
 * Displays a list of servers, and allows the user to connect and edit them.
 * @author morlunk
 *
 */
public class FavouriteServerListFragment extends Fragment implements OnItemClickListener, FavouriteServerAdapter.FavouriteServerAdapterMenuListener {

    private ServerConnectHandler mConnectHandler;
    private DatabaseProvider mDatabaseProvider;
    private GridView mServerGrid;
    private ServerAdapter<Server> mServerAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mConnectHandler = (ServerConnectHandler)activity;
            mDatabaseProvider = (DatabaseProvider) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()+" must implement ServerConnectHandler!");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_server_list, container, false);
        mServerGrid = (GridView) view.findViewById(R.id.server_list_grid);
        mServerGrid.setOnItemClickListener(this);
        mServerGrid.setEmptyView(view.findViewById(R.id.server_list_grid_empty));

        registerForContextMenu(mServerGrid);
        // Menambahkan event klik untuk teks + Add Server
        TextView addServerText = (TextView) view.findViewById(R.id.menu_add_server_text);
        if (addServerText != null) {
            addServerText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAddServerDialog();
                }
            });
        }
        return view;
    }

    private void showAddServerDialog() {
        LayoutInflater inflater = LayoutInflater.from(requireActivity());
        View dialogView = inflater.inflate(R.layout.dialog_server_edit, null);

        final EditText nameField = dialogView.findViewById(R.id.server_edit_name);
        final EditText hostField = dialogView.findViewById(R.id.server_edit_host);
        final EditText portField = dialogView.findViewById(R.id.server_edit_port);
        final EditText userField = dialogView.findViewById(R.id.server_edit_username);
        final EditText passField = dialogView.findViewById(R.id.server_edit_password);

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.add)
                .setView(dialogView)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = nameField.getText().toString().trim();
                        String host = hostField.getText().toString().trim();
                        String portStr = portField.getText().toString().trim();
                        String username = userField.getText().toString().trim();
                        String password = passField.getText().toString().trim();

                        if (!host.isEmpty()) {
                            int port = 64738;
                            try {
                                if (!portStr.isEmpty()) {
                                    port = Integer.parseInt(portStr);
                                }
                            } catch (NumberFormatException e) {
                                port = 64738;
                            }

                            if (name.isEmpty()) {
                                name = host;
                            }

                            Server server = new Server(
                                    -1,
                                    name,
                                    host,
                                    port,
                                    username,
                                    password
                            );

                            if (mDatabaseProvider != null) {
                                MumlaDatabase database = mDatabaseProvider.getDatabase();
                                if (database != null) {
                                    database.addServer(server);
                                    Toast.makeText(requireActivity(), "Server ditambahkan", Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else {
                            Toast.makeText(requireActivity(), "Host tidak boleh kosong", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_server_list, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateServers();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_add_server_item) {
            addServer();
            return true;
        } else if (itemId == R.id.menu_quick_connect) {
            ServerEditFragment.createServerEditDialog(getActivity(), null, ServerEditFragment.Action.CONNECT_ACTION, true)
                    .show(getFragmentManager(), "serverInfo");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void addServer() {
        ServerEditFragment.createServerEditDialog(getActivity(), null, ServerEditFragment.Action.ADD_ACTION, false)
                .show(getFragmentManager(), "serverInfo");
    }

    public void editServer(Server server) {
        ServerEditFragment.createServerEditDialog(getActivity(), server, ServerEditFragment.Action.EDIT_ACTION, false)
                .show(getFragmentManager(), "serverInfo");
    }

    public void shareServer(Server server) {
        // Build Mumble server URL
        String serverUrl = "mumble://" + server.getHost()
            + (server.getPort() == 0 ? "" : ":" + server.getPort()) + "/";

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.shareMessage, serverUrl));
        intent.setType("text/plain");
        startActivity(intent);
    }

    public void deleteServer(final Server server) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.confirm_delete_server)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    mDatabaseProvider.getDatabase().removeServer(server);
                    mServerAdapter.remove(server);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void updateServers() {
        List<Server> servers = getServers();
        mServerAdapter = new FavouriteServerAdapter(getActivity(), servers, this);
        mServerGrid.setAdapter(mServerAdapter);
    }



    public List<Server> getServers() {
        List<Server> servers = mDatabaseProvider.getDatabase().getServers();
        return servers;
    }

    @Override
    public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
        mConnectHandler.connectToServer(mServerAdapter.getItem(arg2));
    }

    public static interface ServerConnectHandler {
        public void connectToServer(Server server);
        public void connectToPublicServer(PublicServer server);
    }
}
