package com.mypos.mdbdemo.broadcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.mypos.mdbdemo.ui.activity.StartHomeChooserActivity;

public class StartHomeChooserBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.e(StartHomeChooserBroadcastReceiver.class.getSimpleName(), "onReceive");

        Intent selector = new Intent(context, StartHomeChooserActivity.class);
        selector.putExtras(intent.getExtras());
        selector.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(selector);
    }

}