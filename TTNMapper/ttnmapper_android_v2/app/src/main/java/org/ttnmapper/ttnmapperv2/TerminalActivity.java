package org.ttnmapper.ttnmapperv2;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.UnsupportedEncodingException;
import java.util.Map;


public class TerminalActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.jpmeijers.ttnmapper.USB_PERMISSION";
    private static final String TAG = "TerminalActivity";
    private final String startString = "Please connect a device to continue...";
    private final String pingMsg = "AT+CMSG=ping-";
    private static boolean ping = false;
    private static boolean GPSMap = false;
    private static long mediumPingTime = 0;
    private static int pingCounter = 0;
    private static int successPing = 0;
    private static int failedPing = 0;
    private static int defaultPingCnt = 4;
    private final int pingDelay = 500;

    private static long startTime;

    PendingIntent mPermissionIntent;

    UsbDevice deviceFound = null;
    UsbDeviceConnection connection = null;
    UsbSerialDevice serial;

    private int cloopcnt = 0;
    private boolean loop = false;
    private boolean cloop = false;
    private int[] loopSettings = {0, 0};
    private int state = 0;
    private String loopCommand;

    TextView terminalOut;
    EditText lineCommand;
    Button btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);



        terminalOut = (TextView) findViewById(R.id.terminalOutput);
        lineCommand = (EditText) findViewById(R.id.commandLine);

        btnSend = (Button) findViewById(R.id.sendBtn);

        terminalOut.setMovementMethod(new ScrollingMovementMethod());

        terminalOut.setText(startString);



        // register the broadcast receiver
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);

        registerReceiver(mUsbReceiver, filter);

        registerReceiver(mUsbDeviceReceiver, new IntentFilter(
                UsbManager.ACTION_USB_DEVICE_ATTACHED));
        registerReceiver(mUsbDeviceReceiver, new IntentFilter(
                UsbManager.ACTION_USB_DEVICE_DETACHED));

        registerGPS();
        registerMqtt();
        connectUsb();

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commandHandler();
            }
        });
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    terminalOut.append("\n\n--------------------------------------------------------------------------------------\n" +
                                       "MQTT Received:\n" + msg + "" +
                                       "\n--------------------------------------------------------------------------------------\n");
                }
            });

            if (cloop) {
                cloopcnt++;
            }
        }
    };

    private BroadcastReceiver mGPSReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("message");
            String lat = intent.getStringExtra("lat");
            String lon = intent.getStringExtra("lon");
            String alt = intent.getStringExtra("alt");
            String acc = intent.getStringExtra("acc");

            String dlat = intent.getStringExtra("dlat");
            String dlon = intent.getStringExtra("dlon");
            String dalt = intent.getStringExtra("dalt");
            String dacc = intent.getStringExtra("dacc");

            String toSend = "AT+MSGHEX=" + lat + "FFFF" + lon + "FFFF" + alt + "FFFF" + acc;


            Log.d(TAG, "GPSMap: Pos Received");


            if (lat != "not accurate enought" && lon != "not accurate enought") {
                serial.write(toSend.getBytes());

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        terminalOut.append("\n--------------------------------------------\n" +
                                            "GPS Pos:\n\n" +
                                            "Lat: " + dlat + "Lat(HEX): " + lat +
                                            "\nLon: " + dlon + "\nLon(HEX): " + lon +
                                            "\nAlt: " + dalt + "\nAlt(HEX): " + alt +
                                            "\nAcc: " + dacc + "\nAcc(HEX): " + acc +
                                            "\n\n" + toSend +
                                            "\n--------------------------------------------\n\n");
                    }
                });
            }else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        terminalOut.append("\n----------------------------------------------------\n" +
                                            "GPS Pos Received but was not accurate enought (>20m).\nCurrent accuracy: " + dacc +
                                            "\n----------------------------------------------------\n\n");
                    }
                });
            }
            Log.d(TAG, "GPSMap: Pos Printed");
        }
    };

    private void commandHandler() {
        String command = lineCommand.getText().toString();

        if (!loop) {
            if (command.toUpperCase().equals("DISCONNECT") || command.toUpperCase().equals("CLOSE")) {
                releaseUsb();
            } else if (command.toUpperCase().equals("CLS") || command.toUpperCase().equals("CLEAR")) {
                terminalOut.setText("");
            } else if (command.toUpperCase().equals("LOOP")) {
                loop = true;
                terminalOut.append("\nEnter the command:");
                state = 1;
            } else if (command.toUpperCase().equals("CLOOP")) {
                loop = true;
                cloop = true;
                terminalOut.append("\nType the number of events desired: ");
                state = 10;
            }else if (command.toUpperCase().equals("PING")) {
                doPing();
            }else if (command.toUpperCase().equals("GPSMAP")) {
                startGPSMap();
            }else if (command.toUpperCase().equals("STOP")) {
                GPSMap = false;

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }else if (command.toUpperCase().equals("UFSM")) {
                MyApplication mApplication = (MyApplication)getApplicationContext();

                terminalOut.append("\n\n--------------------------------------------------------------\n" +
                                       "        Running UFSM Config:\n");
                mApplication.setTtnApplicationId(UFSM_AppID);
                terminalOut.append("\nApplicationID = " + UFSM_AppID);
                mApplication.setTtnDeviceId(UFSM_DevID);
                terminalOut.append("\nDevID = " + UFSM_DevID);
                mApplication.setTtnAccessKey(UFSM_AccessKey);
                terminalOut.append("\nAccessKey = " + UFSM_AccessKey);
                mApplication.setTtnBroker(UFSM_Broker);
                terminalOut.append("\nMQTT Broker: " + UFSM_Broker);

                terminalOut.append("\n--------------------------------------------------------------\n\n");
            }else if (command.toUpperCase().equals("CELTAB")) {
                MyApplication mApplication = (MyApplication)getApplicationContext();

                terminalOut.append("\n\n--------------------------------------------------------------\n" +
                                       "        Running CELTAB Config:\n");
                mApplication.setTtnApplicationId(CELTAB_AppID);
                terminalOut.append("\nApplicationID = " + CELTAB_AppID);
                mApplication.setTtnDeviceId(CELTAB_DevID);
                terminalOut.append("\nDevID = " + CELTAB_DevID);
                mApplication.setTtnAccessKey(CELTAB_AccessKey);
                terminalOut.append("\nAccessKey = " + CELTAB_AccessKey);
                mApplication.setTtnBroker(CELTAB_Broker);
                terminalOut.append("\nMQTT Broker: " + CELTAB_Broker);

                terminalOut.append("\n--------------------------------------------------------------\n\n");
            }else {
                serial.write(command.getBytes());
            }
        }else {
            switch (state) {
                case 1:
                    loopCommand = lineCommand.getText().toString();
                    terminalOut.append("\nCommand: " + loopCommand + "\n\nType cancel to abort...\n\nType the number of events desired:");
                    state = 10;
                    break;
                case 10:
                    if(lineCommand.toString().toUpperCase().equals("CANCEL")) {
                        terminalOut.append("\n\nOperation canceled.\n");
                        state = 1010;
                        break;
                    }else {
                        loopSettings[0] = Integer.parseInt(lineCommand.getText().toString());
                        terminalOut.append("\n\nNumber of events: " + loopSettings[0] + "\n\nType the interval in ms:");
                        state = 100;
                        break;
                    }
                case 100:
                    loopSettings[1] = Integer.parseInt(lineCommand.getText().toString());
                    terminalOut.append(" " + loopSettings[1] + " ms\n\n");
                    loopSend();
                    state = 1010;
                    break;
            }

        }
        if(state == 1010) {
            state = 0;
            loop = false;
        }

        lineCommand.setText("");
    }

    private void startGPSMap() {
        GPSMap = true;
        terminalOut.append("\n--------------------------------\n" +
                "GPS Mapping Started\n--------------------------------\n\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (GPSMap) {
                    GPSReq();
                }
            }
        }).start();
    }

    private void GPSReq() {
        Intent intent = new Intent("ttn-mapper-gpsreq-service");
        intent.putExtra("message", "notification");

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, "GPSMap: Pos Req");

        try {
          //  Log.d(TAG, "GPSMap: Sleep");
            Thread.sleep(5000);
          //  Log.d(TAG, "GPSMap: Wake");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void doPing() {
        if (pingCounter == 0) {
            ping = true;
            lineCommand.setEnabled(false);
            btnSend.setEnabled(false);
            startTime = System.currentTimeMillis();
            String test = pingMsg + pingCounter;
            terminalOut.append("\n\n---------------------------------------------------------------\n" +
                                   "                       Starting Ping:\n" +
                               "\nSending Message: " + test + "\n");
            serial.write(test.getBytes());
            pingCounter++;
        }else if (pingCounter < defaultPingCnt) {
            try {
                Thread.sleep(pingDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            startTime = System.currentTimeMillis();
            String test = pingMsg + pingCounter;
            terminalOut.append("\n-------------------------------------------\nSending Message: " + test + "\n");
            serial.write(test.getBytes());
            pingCounter++;
        }else {
            ping = false;

            float successRate = (successPing*100) / pingCounter;
            float failRate = 100 - successRate;
            float medium = mediumPingTime/pingCounter;

            String pingSuccess = String.format("%.02f", successRate);
            String pingFail = String.format("%.02f", failRate);
            String mediumTime = String.format("%.02f", medium);

            Log.d(TAG, "PING Stats: \n" + "\n-------------------------------------------------" +
                    "\nSuccess ping: " + successPing +
                    "\nFailed  ping: " + failedPing +
                    "\n\nSuccess rate: " + pingSuccess + "%\nFail rate: " + pingFail + "%" +
                    "\nMedium ping Time: " +  mediumTime +
                    "\n-------------------------------------------------\n\n");

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    terminalOut.append("\n-------------------------------------------------" +
                            "\nSuccess ping: " + successPing +
                            "\nFailed  ping: " + failedPing +
                            "\n\nSuccess rate: " + pingSuccess + "%\nFail rate: " + pingFail + "%" +
                            "\nMedium ping Time: " +  mediumTime +
                            "ms\n-------------------------------------------------\n"+"" +
                            "---------------------------------------------------------------\n\n");
                    lineCommand.setEnabled(true);
                    btnSend.setEnabled(true);

                    pingCounter = 0;
                    successPing = 0;
                    failedPing = 0;
                    mediumPingTime = 0;
                }
            });
        }
    }

    private void loopSend() {
        //lineCommand.setFocusable(false);
        lineCommand.setEnabled(false);
        btnSend.setEnabled(false);

        if(!cloop && loopCommand.toUpperCase().contains("MSG") && loopSettings[1] < 5000) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    terminalOut.append("\n-------------------------------------------------------\n" +
                                         "        Interval too short, changing to 5000ms." +
                                         "\n-------------------------------------------------------\n");
                }
            });
            loopSettings[1] = 5000;
        }else if (cloop) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    terminalOut.append("\n-------------------------------------------------------\n" +
                            "        Interval too short, changing to 5000ms." +
                            "\n-------------------------------------------------------\n");
                }
            });
            loopSettings[1] = 5000;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int done = 0;
                for (int step = loopSettings[0]; step > 0; step--){
                    String stepsDone = String.valueOf(done+1);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            terminalOut.append("--------------------------------------------------------\n\n" +
                                               "--------------------------------------------------------" +
                                               "\nRunning " + stepsDone + " of " + String.valueOf(loopSettings[0]) + ":\n");
                        }
                    });

                    if(!cloop) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                terminalOut.append("Command: " + loopCommand + "\n");
                            }
                        });

                        serial.write(loopCommand.getBytes());
                    }else {
                        String toSend = "AT+MSG=" + stepsDone;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                terminalOut.append("Command: " + toSend + "\n");
                            }
                        });

                        serial.write(toSend.getBytes());
                    }
                    try {
                        done++;
                        Thread.sleep(loopSettings[1]);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                //serial.write(loopCommand.getBytes());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                loopCompleted();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lineCommand.setEnabled(true);
                        btnSend.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void loopCompleted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                terminalOut.append("\n\nLoop completed.\n");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                float successrate = cloopcnt*100 / loopSettings[0];
                String mqttSuccess = String.format("%.02f", successrate);

                terminalOut.append("\n-------------------------------------------------------\n" +
                                     "   MQTT Packages recived during CLOOP: " + String.valueOf(cloopcnt) + "\n" +
                                     "\n  Success Rate: " + mqttSuccess +
                                     "%\n-------------------------------------------------------\n\n");
                cloopcnt = 0;
            }
        });
    }

    private void connectUsb() {
        UsbManager usbManager = getSystemService(UsbManager.class);
        Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();
        for(UsbDevice device : connectedDevices.values()) {
            if(device.getVendorId() == 1155) {
                if (!usbManager.hasPermission(device)) {
                    terminalOut.append("Please give the device permission to connect.\n");
                    mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(device, mPermissionIntent);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (!usbManager.hasPermission(device)){
                        terminalOut.append("Error: Device has no permissions.");
                        return;
                    }else {
                        SerialConnectionSetup(usbManager, device);
                        break;
                    }
                }else {
                    SerialConnectionSetup(usbManager, device);
                    break;
                }
            }
        }

        if (terminalOut.getText().toString().equals(startString)) {
            terminalOut.setText("Device not found, please connect a supported device to continue...");
            return;
        }
    }

    private void SerialConnectionSetup(UsbManager usbManager, UsbDevice device) {
        try {
            connection = usbManager.openDevice(device);
            serial = UsbSerialDevice.createUsbSerialDevice(device, connection);

            if (serial != null && serial.open()) {
                serial.setBaudRate(115200);
                serial.setDataBits(UsbSerialInterface.DATA_BITS_8);
                serial.setStopBits(UsbSerialInterface.STOP_BITS_1);
                serial.setParity(UsbSerialInterface.PARITY_NONE);
                serial.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                serial.read(mCallback);

                terminalOut.setText("Device connected: " + device.getProductName() + "\nProductID: " + device.getProductId() + "\nVendorID:" + device.getVendorId() + "\n\n");
                serial.write("AT\n".getBytes());
                terminalOut.append("\n\n");
            }

        } catch (Exception e) {
            e.printStackTrace();
            terminalOut.append("\n\n------------------------------------------------------------------------" +
                                "\nError:\n" + e.toString() +
                                "------------------------------------------------------------------------\n\n");
            Toast.makeText(getApplicationContext(), "Error: " + e.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseUsb(){
       // Toast.makeText(getApplicationContext(), "releaseUsb", Toast.LENGTH_SHORT).show();

        if (serial != null) {
            serial.close();
            if (loop) {
                loop = false;
                state = 0;
            }
            if (cloop) {
                cloop = false;
            }
            serial = null;
            deviceFound = null;

            terminalOut.append("\n\n.....Device disconnected......");
        }
    }

    @Override
    protected void onDestroy() {
        releaseUsb();
        unRegisterMqtt();
        unregisterReceiver(mUsbReceiver);
        unregisterReceiver(mUsbDeviceReceiver);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
     //   unRegisterMqtt();
        super.onPause();
    }

    @Override
    protected void onResume() {
    //    registerMqtt();
        super.onResume();
    }

    private void registerMqtt () {
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("ttn-terminal-service-event"));
    }

    private void unRegisterMqtt() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
    }

    private void registerGPS () {
        LocalBroadcastManager.getInstance(this).registerReceiver(mGPSReceiver, new IntentFilter("ttn-mapper-gpspos-event"));
    }

    private void unRegisterGPS() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mGPSReceiver);
    }


    UsbSerialInterface.UsbReadCallback mCallback = (data) -> {
        String dataStr = null;
        boolean exit = false;
        try {
            dataStr = new String(data, "UTF-8");
            String finalDataStr;
            if(ping) {
                if (dataStr.contains("ACK Received")) {
                    long elapsedtime = System.currentTimeMillis() - startTime;
                    finalDataStr = dataStr + "\nPing received in: " + elapsedtime +  "ms\n-------------------------------------------";
                    successPing++;
                    mediumPingTime += elapsedtime;
                    exit = true;
                }else if(dataStr.contains("ERROR")||dataStr.contains("Done")){
                    finalDataStr = dataStr + "\nPing Failed\n-------------------------------------------";
                    failedPing++;
                    exit = true;
                }else {
                    finalDataStr = dataStr;
                }
            }else {
                finalDataStr = dataStr;
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    terminalOut.append(finalDataStr);
                }
            });

            if(ping && exit) {
                doPing();
            }

        } catch (UnsupportedEncodingException e) {
            Log.d(TAG, "Error: " + e.toString());
            Toast.makeText(getApplicationContext(), "Error:\n" + e.toString(), Toast.LENGTH_SHORT).show();
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(ACTION_USB_PERMISSION.equals(action)) {

              //  Toast.makeText(getApplicationContext(), "ACTION_USB_PERMISSION", Toast.LENGTH_SHORT).show();

                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectUsb();
                        }
                    }else {
                      //  Toast.makeText(getApplicationContext(), "Permission denied for device " + device, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };


    private final BroadcastReceiver mUsbDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {

                deviceFound = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
               // Toast.makeText(getApplicationContext(), "ACTION_USB_ATTACHED: \n" + deviceFound.toString(), Toast.LENGTH_SHORT).show();
                connectUsb();
            }else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

             //   Toast.makeText(getApplicationContext(), "ACTION_USB_DEATACHED: \n" + device.toString(), Toast.LENGTH_SHORT).show();

                if (device != null) {
                    if (device == deviceFound) {
                        releaseUsb();
                    }
                }
            }
        }
    };
}
