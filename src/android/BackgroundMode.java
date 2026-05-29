/*
 Copyright 2013 Sebastián Katzer

 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package de.appplant.cordova.plugin.background;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONObject;

import de.appplant.cordova.plugin.background.ForegroundService.ForegroundBinder;

import static android.content.Context.BIND_AUTO_CREATE;
import static de.appplant.cordova.plugin.background.BackgroundModeExt.clearKeyguardFlags;

public class BackgroundMode extends CordovaPlugin {

    // Event types for callbacks
    private enum Event { ACTIVATE, DEACTIVATE, FAILURE }

    // Plugin namespace
    private static final String JS_NAMESPACE = "cordova.plugins.backgroundMode";

    // Flag indicates if the app is in background or foreground
    private boolean inBackground = false;

    // Flag indicates if the plugin is enabled or disabled
    private boolean isDisabled = true;

    // Flag indicates if the service is bind
    private boolean isBind = false;

    // Default settings for the notification
    private static JSONObject defaultSettings = new JSONObject();

    // Service that keeps the app awake
    private ForegroundService service;

    // Used to (un)bind the service to with the activity
    private final ServiceConnection connection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected (ComponentName name, IBinder service)
        {
            ForegroundBinder binder = (ForegroundBinder) service;
            BackgroundMode.this.service = binder.getService();
        }

        @Override
        public void onServiceDisconnected (ComponentName name)
        {
            fireEvent(Event.FAILURE, "'service disconnected'");
        }
    };

    // Define constants declared in SDK 33.
    private static final int Build_VERSION_CODES_TIRAMISU = 33;
    private static final int Context_RECEIVER_NOT_EXPORTED = 4;
    private static final String Manifest_permission_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.backgroundmode.close" + cordova.getActivity().getApplicationContext().getPackageName());
        if (Build.VERSION.SDK_INT >= Build_VERSION_CODES_TIRAMISU)
            cordova.getActivity().registerReceiver(receiver, filter, Context_RECEIVER_NOT_EXPORTED);
        else
            cordova.getActivity().registerReceiver(receiver, filter);
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            cordova.getActivity().finish();

        }
    };

    /**
     * Executes the request.
     *
     * @param action   The action to execute.
     * @param args     The exec() arguments.
     * @param callback The callback context used when
     *                 calling back into JavaScript.
     *
     * @return Returning false results in a "MethodNotFound" error.
     */
    @Override
    public boolean execute (String action, JSONArray args,
                            CallbackContext callback)
    {
        boolean validAction = true;

        if ("configure".equals(action))
            configure(args.optJSONObject(0), args.optBoolean(1));
        else if ("enable".equals(action))
            enableMode();
        else if ("disable".equals(action))
            disableMode();
        else
            validAction = false;

        if (validAction) {
            callback.success();
        } else {
            callback.error("Invalid action: " + action);
        }

        return validAction;
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onPause(boolean multitasking)
    {
        try {
            inBackground = true;
        } finally {
            clearKeyguardFlags(cordova.getActivity());
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     */
    @Override
    public void onStop () {
        clearKeyguardFlags(cordova.getActivity());
    }

    /**
     * Called when the activity will start interacting with the user.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onResume (boolean multitasking)
    {
        inBackground = false;
    }

    /**
     * Called when the activity will be destroyed.
     */
    @Override
    public void onDestroy()
    {
        stopService();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
    {
        Log.d(getLogContext(), "onRequestPermissionResult: permissions=" + java.util.Arrays.toString(permissions) + "; grantResults=" + java.util.Arrays.toString(grantResults));
        if (Manifest_permission_POST_NOTIFICATIONS.equals(permissions[0])) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED)
                Log.w(getLogContext(), "User refused to grant permission to Post Notifications. The Foreground Service should still enable Audio Recording to work in the background but there will be no notification visible to the user");
            startServiceImpl();
        }
    }

    private String getLogContext() {
        return getClass().getSimpleName();
    }

    /**
     * Enable the background mode.
     */
    private void enableMode()
    {
        isDisabled = false;

        if (!isBind) {
            startService();
        }
    }

    /**
     * Disable the background mode.
     */
    private void disableMode()
    {
        stopService();
        isDisabled = true;
    }

    /**
     * Update the default settings and configure the notification.
     *
     * @param settings The settings
     * @param update A truthy value means to update the running service.
     */
    private void configure(JSONObject settings, boolean update)
    {
        if (update) {
            updateNotification(settings);
        } else {
            setDefaultSettings(settings);
        }
    }

    /**
     * Update the default settings for the notification.
     *
     * @param settings The new default settings
     */
    private void setDefaultSettings(JSONObject settings)
    {
        defaultSettings = settings;
    }

    /**
     * Returns the settings for the new/updated notification.
     */
    static JSONObject getSettings () {
        return defaultSettings;
    }

    /**
     * Update the notification.
     *
     * @param settings The config settings
     */
    private void updateNotification(JSONObject settings)
    {
        if (isBind) {
            service.updateNotification(settings);
        }
    }

    /**
     * Bind the activity to a background service and put them into foreground
     * state.
     */
    private void startService()
    {
        if (isDisabled || isBind)
            return;

        if (Build.VERSION.SDK_INT < Build_VERSION_CODES_TIRAMISU || cordova.hasPermission(Manifest_permission_POST_NOTIFICATIONS))
            startServiceImpl();
        else
            cordova.requestPermission(this, 0, Manifest_permission_POST_NOTIFICATIONS);
    }

    private void startServiceImpl()
    {
        Activity context = cordova.getActivity();
        Intent intent = new Intent(context, ForegroundService.class);

        try {
            context.bindService(intent, connection, BIND_AUTO_CREATE);
            fireEvent(Event.ACTIVATE, null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent);
            else
                context.startService(intent);
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'%s'", e.getMessage()));
        }

        isBind = true;
    }

    private void stopService()
    {
        Activity context = cordova.getActivity();
        Intent intent    = new Intent(context, ForegroundService.class);

        if (!isBind) return;

        fireEvent(Event.DEACTIVATE, null);
        context.unbindService(connection);
        context.stopService(intent);

        isBind = false;
    }

    /**
     * Fire vent with some parameters inside the web view.
     *
     * @param event The name of the event
     * @param params Optional arguments for the event
     */
    private void fireEvent (Event event, String params)
    {
        String eventName = event.name().toLowerCase();
        Boolean active   = event == Event.ACTIVATE;

        String str = String.format("%s._setActive(%b)",
                JS_NAMESPACE, active);

        str = String.format("%s;%s.on('%s', %s)",
                str, JS_NAMESPACE, eventName, params);

        str = String.format("%s;%s.fireEvent('%s',%s);",
                str, JS_NAMESPACE, eventName, params);

        final String js = str;

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl("javascript:" + js);
            }
        });
    }
}
