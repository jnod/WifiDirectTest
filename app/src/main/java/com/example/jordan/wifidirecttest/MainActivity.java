package com.example.jordan.wifidirecttest;

import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;

public class MainActivity extends AppCompatActivity implements WifiP2pManager.PeerListListener {

    private IntentFilter intentFilter;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private WiFiDirectBroadcastReceiver wiFiDirectBroadcastReceiver;

    private Spinner spinner;
    private TextView textLatency, textAverageLatency;
    ArrayAdapter<ConnectionSpinnerItem> adapter;

    private JSONSocket socket;
    private String macAddress;

    private ServerSocket serverSocket;

    double average = 0;
    int count = 0;

    private final String TAG = "MainActivity";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WifiManager manager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = manager.getConnectionInfo();
        macAddress = info.getMacAddress();

        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = wifiP2pManager.initialize(this, getMainLooper(), null);
        wiFiDirectBroadcastReceiver = new WiFiDirectBroadcastReceiver(wifiP2pManager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        spinner = (Spinner) findViewById(R.id.spinner_connections);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        spinner.setAdapter(adapter);

        textLatency = (TextView) findViewById(R.id.text_latency);
        textAverageLatency = (TextView) findViewById(R.id.text_average_latency);

        startServer();

        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // onPeersAvailable will be called when this task is complete
            }

            @Override
            public void onFailure(int reasonCode) {
                Toast.makeText(MainActivity.this, "Unable to Refresh", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(wiFiDirectBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(wiFiDirectBroadcastReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void refreshConnections(View view) {
        wifiP2pManager.requestPeers(channel, this);
    }

    public void connect(View view) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = ((ConnectionSpinnerItem)spinner.getSelectedItem()).device.deviceAddress;
        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(MainActivity.this, "Connection Failure", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        Collection<WifiP2pDevice> devices = peers.getDeviceList();
        adapter.clear();

        for (WifiP2pDevice device : devices) {
            adapter.add(new ConnectionSpinnerItem(device));
        }
    }

    public void onGroupFormed(boolean isOwner, InetAddress ownerAddress) {
        Toast.makeText(MainActivity.this, "Connected", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "isOwner: " + isOwner);
        Log.i(TAG, "Owner Address: " + ownerAddress.toString());

        if (!isOwner) {
            startClient(ownerAddress);
        }
    }

    private void startClient(final InetAddress ownerAddress) {
        Log.i(TAG,"Starting Client");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG,"Creating Socket");
                Socket socket = new Socket();
                try {
                    socket.bind(null);
                    Log.i(TAG,"Connecting to Owner");
                    socket.connect((new InetSocketAddress(ownerAddress, 8888)), 500);

                    setSocket(socket);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void onDisconnected() {
        Toast.makeText(MainActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    private void startServer() {
        Log.i(TAG, "Starting Server");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(8888);
                    Log.i(TAG,"Server Started");
                    while (true) {
                        Socket socket = serverSocket.accept();
                        Log.i(TAG,"Socket Accepted");

                        setSocket(socket);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void setSocket(Socket socket) throws IOException, JSONException {
        this.socket = new JSONSocket(socket, new JSONSocket.JSONMessageCallback() {
            @Override
            public void receiveMessage(final JSONObject message) {
                long timestamp = System.nanoTime();

                try {
                    if (message.getString("mac_addr").equals(macAddress)) {
                        // sent by this device, display latency
                        count++;
                        final double latency = ((double)(timestamp - message.getLong("time_sent"))) / 1000000;
                        average = ((count - 1) * average + latency) / count;


                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textLatency.setText(String.format("Latency: %.4f", latency));
                                textAverageLatency.setText(String.format("Average Latency: %.4f", average));
                            }
                        });
                    } else {
                        // send by other device, echo it back
                        try {
                            sendMessage(message);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        this.socket.start();
        Log.i(TAG,"Socket Set");
    }

    private void sendMessage(JSONObject message) throws IOException {
        if (socket != null) {
            socket.send(message);
        } else {
            throw new IOException();
        }
    }

    public void sendMessage(View view) {
        try {
            JSONObject message = new JSONObject();
            message.put("mac_addr", macAddress);
            message.put("time_sent", System.nanoTime());

            sendMessage(message);
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public class ConnectionSpinnerItem {
        public final WifiP2pDevice device;

        public ConnectionSpinnerItem(WifiP2pDevice device) {
            this.device = device;
        }

        @Override
        public String toString() {
            return device.deviceName + '\n' + device.deviceAddress;
        }
    }
}
