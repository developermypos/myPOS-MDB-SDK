package com.mypos.mdbdemo.ui.activity;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.mypos.mdbdemo.R;
import com.mypos.mdbdemo.service.MDBService;
import com.mypos.mdbdemo.service.callbacks.IActivityInterface;
import com.mypos.mdbdemo.service.callbacks.IServiceInterface;
import com.mypos.mdbsdk.AgeVerificationResult;
import com.mypos.mdbsdk.DeviceStatus;
import com.mypos.smartsdk.MyPOSAPI;
import com.mypos.smartsdk.MyPOSUtil;
import com.mypos.smartsdk.MyPOSVendingPayment;
import com.mypos.smartsdk.OnPOSInfoListener;
import com.mypos.smartsdk.TransactionProcessingResult;
import com.mypos.smartsdk.data.POSInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements IActivityInterface {

    public static final boolean DEBUG_LOG = false;

    private static final int ACTIVATION_REQUEST_CODE = 2;

    private IServiceInterface mPaymentResultCallback;

    private CardDetectedBroadcastReceiver mCardDetectedBroadcastReceiver;
    private BroadcastReceiver updateResponseBroadcast;

    private String tid;

    private int requestCode = 0;

    private TextView stateTv;
    private Button beginSession;
    private Button cancelSession;

    private Handler handler;
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTerminalBusy()) {
                handler.postDelayed(this, 300_000);
                return;
            }

            MyPOSAPI.sendExplicitBroadcast(MainActivity.this, new Intent(com.mypos.mdbdemo.util.Utils.DOWNLOAD_CONFIG_ACTION));

            updateResponseBroadcast = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    unregisterReceiver(this);
                    updateResponseBroadcast = null;
                    int status = intent.getIntExtra("status", TransactionProcessingResult.TRANSACTION_FAILED);

                    if (status == -1)
                        status = TransactionProcessingResult.COMMUNICATION_ERROR;
                    else if (status != TransactionProcessingResult.TRANSACTION_SUCCESS)
                        status = TransactionProcessingResult.TRANSACTION_FAILED;
                }
            };

            registerReceiver(updateResponseBroadcast, new IntentFilter(com.mypos.mdbdemo.util.Utils.DOWNLOAD_CONFIG_RESPONSE_ACTION));

        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mPaymentResultCallback = (IServiceInterface) iBinder;
            mPaymentResultCallback.setTerminalControls(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            rebindService();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

//        WindowManager.LayoutParams lp = getWindow().getAttributes();
//        lp.screenBrightness = 0;
//        getWindow().setAttributes(lp);

        setContentView(R.layout.activity_main);

        stateTv = findViewById(R.id.state_text);
        beginSession = findViewById(R.id.begin_session);
        cancelSession = findViewById(R.id.cancel_session);

        SwitchCompat ageVerSwitch = findViewById(R.id.age_verification_switch);
        ageVerSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mPaymentResultCallback != null)
                    mPaymentResultCallback.enableAgeVerification(isChecked);
            }
        });

        beginSession.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
             if (mPaymentResultCallback != null)
                 mPaymentResultCallback.beginSession();
            }
        });

        cancelSession.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            if (mPaymentResultCallback != null)
                mPaymentResultCallback.cancelSession();
            }
        });

        handler = new Handler();

        getTerminalOptions(() -> {
            if (tid == null || tid.isEmpty())
                startActivationScreen();
            else
                rebindService();
        });

        if (savedInstanceState != null) {
            requestCode = savedInstanceState.getInt("request_code");
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("request_code", requestCode);
    }

    private void getTerminalOptions(Runnable callback) {
        MyPOSAPI.registerPOSInfo(this, new OnPOSInfoListener() {
            @Override
            public void onReceive(POSInfo posInfo) {
                tid = posInfo.getTID();
                callback.run();
            }
        });
    }

    private void startActivationScreen() {
        startActivityForResult(new Intent("eu.leupay.poslauncher.action.SHOW_ACTIVATION"), ACTIVATION_REQUEST_CODE);
    }

    private void rebindService() {
        Intent intent = new Intent(getApplicationContext(), MDBService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void startPurchase(MyPOSVendingPayment payment) {
        runOnUiThread(() -> {

            Log.e("MDB", "startPayment now");
            MyPOSAPI.openVendingPaymentActivity(MainActivity.this, payment, MyPOSUtil.TRANSACTION_TYPE_PAYMENT, true);
            requestCode =  MyPOSUtil.TRANSACTION_TYPE_PAYMENT;

            mCardDetectedBroadcastReceiver = new CardDetectedBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(MyPOSUtil.CARD_DETECTED);
            registerReceiver(mCardDetectedBroadcastReceiver, intentFilter);
        });
    }

    @Override
    public void stopPurchase() {
        runOnUiThread(() -> {
            if (requestCode != 0)
                finishActivity(requestCode);
            else
            if (mPaymentResultCallback != null)
                mPaymentResultCallback.transactionFinish(TransactionProcessingResult.TRANSACTION_CANCELED,  null,requestCode);

        });
    }

    @Override
    public void startCompleteTransaction(double partialAmount, String sidOriginal, String credential, Locale language, boolean skipConfirmationScreen) {
        runOnUiThread(() -> MyPOSAPI.openCompleteTxActivity(MainActivity.this, partialAmount, credential, sidOriginal, language, skipConfirmationScreen, MyPOSUtil.TRANSACTION_TYPE_COMPLETE_TX));
    }

    @Override
    public void startCancelTransaction(String sidOriginal, Locale language, boolean skipConfirmationScreen) {
        runOnUiThread(() -> MyPOSAPI.openCancelTxActivity(MainActivity.this, sidOriginal, language, skipConfirmationScreen, MyPOSUtil.TRANSACTION_TYPE_CANCEL_TX));
    }

    private boolean isInAgeValidation = false;
    private int ageForValidation;
    @Override
    public void onCheckAgeVerification(int age) {
        if (isInAgeValidation)
            return;

        isInAgeValidation = true;

        if (age > 0)
            ageForValidation = age;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Dialog ageVerDialog = new Dialog(MainActivity.this);
                ageVerDialog.setCancelable(false);
                View view = getLayoutInflater().inflate(R.layout.age_verification_dialog_layout, null);
                ((TextView)view.findViewById(R.id.age_tv)).setText("Age: " + ageForValidation);

                view.findViewById(R.id.valid_card).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mPaymentResultCallback != null)
                            mPaymentResultCallback.setAgeVerificationStatus(AgeVerificationResult.VALID_CARD);

                        isInAgeValidation = false;

                        ageVerDialog.dismiss();
                    }
                });
                view.findViewById(R.id.invalid_card).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mPaymentResultCallback != null)
                            mPaymentResultCallback.setAgeVerificationStatus(AgeVerificationResult.INVALID_CARD);

                        isInAgeValidation = false;

                        ageVerDialog.dismiss();
                    }
                });
                view.findViewById(R.id.less_than_age).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mPaymentResultCallback != null)
                            mPaymentResultCallback.setAgeVerificationStatus(AgeVerificationResult.LESS_THAN_AGE);

                        isInAgeValidation = false;

                        ageVerDialog.dismiss();
                    }
                });
                ageVerDialog.setContentView(view);
                ageVerDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        isInAgeValidation = false;
                    }
                });
                ageVerDialog.show();
            }
        });

    }


    @Override
    public void setLiveData(LiveData<DeviceStatus> uiStateLiveData, LiveData<Integer> idleMode) {
        uiStateLiveData.observe(this, new Observer<DeviceStatus>() {
            @Override
            public void onChanged(DeviceStatus uiState) {
               stateTv.setText(uiState.name());
               switch (uiState) {
                   case INACTIVE:
                   case DISABLE:
                   case VEND:
                       beginSession.setEnabled(false);
                       cancelSession.setEnabled(false);
                       beginSession.setAlpha(.5f);
                       cancelSession.setAlpha(.5f);
                       break;
                   case SESSION_IDLE:
                       beginSession.setEnabled(false);
                       cancelSession.setEnabled(true);
                       beginSession.setAlpha(.5f);
                       cancelSession.setAlpha(1.0f);
                       break;
                   case ENABLE:
                       beginSession.setEnabled(true);
                       cancelSession.setEnabled(false);
                       beginSession.setAlpha(1.0f);
                       cancelSession.setAlpha(.5f);
                       break;
               }
            }
        });

        idleMode.observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                switch (integer) {
                    case MDBService.IDLE_MODE:
                        beginSession.setVisibility(View.VISIBLE);
                        cancelSession.setVisibility(View.VISIBLE);
                    break;
                    default:
                        beginSession.setVisibility(View.GONE);
                        cancelSession.setVisibility(View.GONE);
                        break;
                }
            }
        });
    }

    @Override
    public boolean isTerminalBusy() {
        boolean busy = !getWindow().getDecorView().getRootView().isShown() || requestCode != 0 || updateResponseBroadcast != null;
        return busy;
    }

    @Override
    public void debugLog(String t, String m) {
        if (DEBUG_LOG) {

        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        this.requestCode = 0;

        if (requestCode == MyPOSUtil.TRANSACTION_TYPE_PAYMENT) {

            if (mCardDetectedBroadcastReceiver != null) {
                unregisterReceiver(mCardDetectedBroadcastReceiver);
                mCardDetectedBroadcastReceiver = null;
            }

            if (data != null) {
                if (mPaymentResultCallback != null) {
                    int status = data.getIntExtra("status", TransactionProcessingResult.TRANSACTION_FAILED);

                    if (data.hasExtra("transaction_approved"))
                        mPaymentResultCallback.transactionFinish(status, data, requestCode);
                    else
                        mPaymentResultCallback.transactionFinish(status, null, requestCode);
                }

                handler.removeCallbacks(updateRunnable);

                if (data.getBooleanExtra("update_pending", false)) {
                    handler.postDelayed(updateRunnable, 300_000);
                }
            }
            else {
                if (mPaymentResultCallback != null) {
                    mPaymentResultCallback.transactionFinish(TransactionProcessingResult.TRANSACTION_CANCELED,  null,requestCode);
                }
            }
        } else if (requestCode == MyPOSUtil.TRANSACTION_TYPE_COMPLETE_TX || requestCode == MyPOSUtil.TRANSACTION_TYPE_CANCEL_TX) {
            if (data != null) {
                if (mPaymentResultCallback != null) {
                    int status = data.getIntExtra("status", TransactionProcessingResult.TRANSACTION_FAILED);

                    mPaymentResultCallback.transactionFinish(status, data, requestCode);
                }
            }
            else {
                if (mPaymentResultCallback != null) {
                    mPaymentResultCallback.transactionFinish(TransactionProcessingResult.TRANSACTION_CANCELED, null, requestCode);
                }
            }
        } else if (requestCode == ACTIVATION_REQUEST_CODE) {
            getTerminalOptions(() -> {
                if (tid == null || tid.isEmpty())
                    startActivationScreen();
                else
                    rebindService();
            });
        }
    }


    @Override
    protected void onDestroy() {
        if (mCardDetectedBroadcastReceiver != null) {
            unregisterReceiver(mCardDetectedBroadcastReceiver);
            mCardDetectedBroadcastReceiver = null;
        }
        if (updateResponseBroadcast != null) {
            unregisterReceiver(updateResponseBroadcast);
            updateResponseBroadcast = null;
        }

        try {
            unbindService(serviceConnection);
        } catch (Exception ignored) {}
        mPaymentResultCallback = null;
        handler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }


    //FOR HIDDEN MENU
    private final int[] SETTINGS_CLICK_COMBINATION = new int[] {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN};
    private final int TIME_INTERVAL_CLICKS = 1000;

    private final List<Integer> currentState = new ArrayList<Integer>();
    private long lastClickTime = 0;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (System.currentTimeMillis() - lastClickTime > TIME_INTERVAL_CLICKS) {
                currentState.clear();
            }

            lastClickTime = System.currentTimeMillis();
            currentState.add(keyCode);

            if (currentState.size() < SETTINGS_CLICK_COMBINATION.length)
                return super.onKeyDown(keyCode, event);

            for (int i = 0; i < currentState.size(); i++) {
                if (currentState.get(i) != SETTINGS_CLICK_COMBINATION[i]) {
                    currentState.clear();
                    return super.onKeyDown(keyCode, event);
                }
            }

            currentState.clear();

            Intent launchIntent = getPackageManager().getLaunchIntentForPackage("eu.leupay.poslauncher");
            startActivity(launchIntent);
        }

        return super.onKeyDown(keyCode, event);
    }

    private class CardDetectedBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equalsIgnoreCase(MyPOSUtil.CARD_DETECTED)) {
                if (mPaymentResultCallback != null)
                    mPaymentResultCallback.cardDetected();
            }
        }
    }

    @Override
    public void onBackPressed() {
//        super.onBackPressed();
    }
}