package com.mypos.mdbdemo.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.mypos.mdbdemo.BuildConfig;
import com.mypos.mdbdemo.service.callbacks.IActivityInterface;
import com.mypos.mdbdemo.service.callbacks.IServiceInterface;
import com.mypos.mdbdemo.util.ByteArray;
import com.mypos.mdbdemo.util.Utils;
import com.mypos.mdbsdk.AgeVerificationManager;
import com.mypos.mdbsdk.AgeVerificationResult;
import com.mypos.mdbsdk.DeviceMode;
import com.mypos.mdbsdk.DeviceStatus;
import com.mypos.mdbsdk.IAgeVerificationCallback;
import com.mypos.mdbsdk.ICompletionCallback;
import com.mypos.mdbsdk.IPaymentCallback;
import com.mypos.mdbsdk.LogLevel;
import com.mypos.mdbsdk.MDBManager;
import com.mypos.mdbsdk.PaymentAppInfo;
import com.mypos.mdbsdk.ReportKey;
import com.mypos.mdbsdk.ReportType;
import com.mypos.mdbsdk.TransResult;
import com.mypos.mdbsdk.VendParam;
import com.mypos.mdbsdk.VendResult;
import com.mypos.mdbsdk.VendType;
import com.mypos.smartsdk.Currency;
import com.mypos.smartsdk.MyPOSUtil;
import com.mypos.smartsdk.MyPOSVendingPayment;
import com.mypos.smartsdk.ReferenceType;
import com.mypos.smartsdk.TransactionProcessingResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public class MDBService extends Service implements MDBManager.OnBindListener {

    private static final byte LEVEL_3 = 0x03;
    private static final byte LEVEL_2 = 0x02;
    private static final byte LEVEL_1 = 0x01;
    private static final byte LEVEL_0 = 0x00;

    private static final int TIMEOUT_CARD_DETECTION = 30;
    private static final int TIMEOUT_VEND_COMPLETE = 120;
    private static final int TIMEOUT_FINALIZING_PERIOD = 10;

    private byte FEATURE_LEVEL = LEVEL_0;

    public static final int IDLE_MODE = 0;
    public static final int ALWAYS_IDLE_MODE = 1;

    private SharedPreferences sharedPreferences;

    private IActivityInterface mActivityInterface;
    private String currencyName;
    private boolean isDecimalAllowed;

    private PaymentAppInfo paymentAppInfo;

    private double storedMaxPrice = 0;
    private double machineMaxPrice = -1;
    private String lastItemPrice;
    private String lastItemNumber;

    private int finalizingTries;

    private boolean isTerminalInVendingPurchase = false;
    private boolean isInTransactionProcessing = false;
    private boolean reverseLastTransaction = false;
    private boolean shouldRepeatPayment = false;
    private boolean vendFailure = false;

    private MutableLiveData<DeviceStatus> deviceState = new MutableLiveData<>(DeviceStatus.INACTIVE);
    private MutableLiveData<Integer> idleMode = new MutableLiveData<>(-1);

    private Handler handler;
    private Handler mInitParamHandler;
    private Runnable transactionCompleteRunnable;
    private Runnable finalizingRunnable;

    private BroadcastReceiver shutDownBroadcastReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        handler = new Handler(Looper.myLooper());

        HandlerThread initParamThread = new HandlerThread("initParamThread", -10);
        initParamThread.start();
        mInitParamHandler = new Handler(initParamThread.getLooper());


        try {
            MDBManager.getInstance().bind(this, this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        shutDownBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                closeMdbService();
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SHUTDOWN);
        intentFilter.addAction(Intent.ACTION_REBOOT);
        registerReceiver(shutDownBroadcastReceiver, intentFilter);
    }

    private void initData(){

        FEATURE_LEVEL = sharedPreferences.getBoolean(Utils.TAG_FORCE_FEATURE_LEVEL_1, Utils.DEFAULT_FORCE_FEATURE_LEVEL_1) ? LEVEL_1 : LEVEL_0;

        final Uri CONTENT_URI = Uri.parse("content://com.mypos.providers.POSInfoProvider/pos_info");

        Cursor cursor = getContentResolver().query(
                CONTENT_URI,
                new String[] {"CountryCode", "CurrencyCode", "CurrencyName", "tid"},
                null,
                null,
                null
        );

        if(cursor == null || cursor.getCount() < 1)
            return;

        cursor.moveToFirst();

        String tid = cursor.getString(cursor.getColumnIndex("tid"));
        String countryCode = cursor.getString(cursor.getColumnIndex("CountryCode"));
        String currencyCode = cursor.getString(cursor.getColumnIndex("CurrencyCode"));
        currencyName = cursor.getString(cursor.getColumnIndex("CurrencyName"));

        isDecimalAllowed = Utils.isDecimalAlowedForCurrency(currencyCode);

        cursor.close();

        paymentAppInfo = new PaymentAppInfo();
        paymentAppInfo.setClientId(tid);
        paymentAppInfo.setManufactureCode("XGD");
        paymentAppInfo.setModel(Build.MODEL);
        paymentAppInfo.setCountryCode(countryCode);
/*
      ActualPrice = P * X * pow(10,-Y)  P is the price,X is the scaled factor,and y is number of decimal place
      If  P is 100, X(scale factor) is 1, and Y(decimal place) is 2,the actual price is 1.
*/
        paymentAppInfo.setDecimalPlaces(isDecimalAllowed ? (byte) 0x02 : (byte) 0x00);
        paymentAppInfo.setScaleFactor(1);

        paymentAppInfo.setResponseTime((byte) 0x3C);
        //set LogLevel.INFO as default
        paymentAppInfo.setLogLevel(sharedPreferences.getBoolean(Utils.TAG_MDB_LOG_ENABLED, Utils.DEFAULT_TAG_MDB_LOG_ENABLED) ? LogLevel.ALL.ordinal() : LogLevel.INFO.ordinal());
        paymentAppInfo.setEnableAlwaysIdle(false);
        paymentAppInfo.setReaderFeatureLevel(FEATURE_LEVEL);

        paymentAppInfo.setRevalueApproved(sharedPreferences.getBoolean(Utils.TAG_SUPPORTS_REVALUE, Utils.DEFAULT_SUPPORTS_REVALUE));
        paymentAppInfo.setRequestRefunds(sharedPreferences.getBoolean(Utils.TAG_SUPPORTS_REFUND, Utils.DEFAULT_SUPPORTS_REFUND));
        paymentAppInfo.setDeviceMode(DeviceMode.CASHLESS);
        paymentAppInfo.setNotifyStatus(BuildConfig.DEBUG);
        paymentAppInfo.setRespondAckFirst(sharedPreferences.getBoolean(Utils.TAG_RESPOND_ACK_FIRST, Utils.DEFAULT_RESPOND_ACK_FIRST));
        paymentAppInfo.setDeviceType(sharedPreferences.getInt(Utils.TAG_CASHLESS_TYPE, Utils.DEFAULT_CASHLESS_TYPE));

        paymentAppInfo.setSupportCashSale(false);
        paymentAppInfo.setDelayTm(0);
        paymentAppInfo.setWaitEndSessionTm(30);
        paymentAppInfo.setSupportMultiVend(false);
        paymentAppInfo.setSupportRemoteVend(false);

//        paymentAppInfo.setUserDefinedData("USER DAtA");

        //only level 3 support always idle mode and monetary format
        if(FEATURE_LEVEL == LEVEL_3 || FEATURE_LEVEL == LEVEL_0){
            paymentAppInfo.setEnableAlwaysIdle(true);
            //- 0 = 16 bit monetary format, 1 = 32 bit monetary format;  set 0 as default,didn't support 1
            paymentAppInfo.setMonetaryFormat(0);
        }
    }

    private final ICompletionCallback iCompletionCallback = new ICompletionCallback.Stub() {
        @Override
        public void onSuccess() {
          debugLog( "call mdbClient success!!");
        }

        @Override
        public void onFailure(int retCode) {
          debugLog( "call mdbClient failure retCode: " + retCode);
        }

        @Override
        public void onAppUpdate() {
            restartMdbService();
        }

        @Override
        public void onNotifyVMCInfo(final String brand, final String model) {
           debugLog("brand info: " + brand + " " + model);
        }

        @Override
        public void onReportError(int type, boolean success, String errorMessage) throws RemoteException {
            debugLog("onReportError: " + ReportType.values()[type] + " success: " + success + " err:" + errorMessage);
        }
    };

    private final IPaymentCallback iPaymentCallback = new IPaymentCallback.Stub() {
        @Override
        public int onPay(final int tradeType, final String itemPrice, final String itemNumber) {

          debugLog( "onPay itemPrice: " + itemPrice + " tradeType: " + VendType.values()[tradeType]);

            if (tradeType == VendType.CASH_SALE.ordinal()){

            } else if(tradeType == VendType.SALE.ordinal()) {

                String prevItemPrice = lastItemPrice;
                String prevItemNumber = lastItemNumber;

                lastItemPrice = itemPrice;
                lastItemNumber = itemNumber;

                if (mActivityInterface != null) {

                    if (Double.parseDouble(itemPrice) <= 0.0) {
                        handler.postDelayed(() -> {
                            setTransResult(TransResult.TRADE_SUCCESS, itemPrice);
                        }, 1000);
                        lastItemPrice = lastItemNumber = null;

                        return 0;
                    }

                    if (isTerminalBusy()) {
                        if (itemPrice.equals(prevItemPrice) && itemNumber.equals(prevItemNumber))
                            debugLog("duplicate onPay called");
                        else
                            handler.postDelayed(() -> {
                                setTransResultCancel(itemPrice);

                                lastItemPrice = lastItemNumber = null;

                            }, 1000);
                        return 0;
                    }

                    sharedPreferences.edit().putBoolean("onpay_in_progress", true).apply();

                    mActivityInterface.startPurchase(MyPOSVendingPayment.builder()
                            .productAmount(Double.parseDouble(itemPrice) * (isDecimalAllowed ? 0.01 : 1))
                            .currency(com.mypos.smartsdk.Currency.valueOf(currencyName))
                            .reference(itemNumber, ReferenceType.PRODUCT_ID)
                            .printCustomerReceipt(MyPOSUtil.RECEIPT_OFF)
                            .printMerchantReceipt(MyPOSUtil.RECEIPT_OFF)
                            .cardDetectionTimeout(TIMEOUT_CARD_DETECTION)
                            .fixedPinpad(sharedPreferences.getBoolean(Utils.TAG_STATIC_PINPAD, Utils.DEFAULT_STATIC_PINPAD))
                            .mastercardSonicBranding(sharedPreferences.getBoolean(Utils.TAG_MC_BRANDING_ENABLED, Utils.DEFAULT_MC_BRANDING_ENABLED))
                            .visaSensoryBranding(sharedPreferences.getBoolean(Utils.TAG_VISA_BRANDING_ENABLED, Utils.DEFAULT_VISA_BRANDING_ENABLED))
                            .dccEnabled(false)
                            .build());

                    isTerminalInVendingPurchase = true;
                }
            }

            return 0;
        }

        @Override
        public void notifyMdbStatus(int mdbDeviceStatus) {
          debugLog( "notifyMdbStatus retCode: " + DeviceStatus.values()[mdbDeviceStatus]);


            if(mdbDeviceStatus == DeviceStatus.SERVICE_ERR.ordinal()){
                restartMdbService();
            }

            deviceState.postValue(DeviceStatus.values()[mdbDeviceStatus]);
        }

        @Override
        public void notifyVendResult(int result) {
          debugLog( "notifyVendResult retCode: " + VendResult.values()[result]);

            switch (VendResult.values()[result]){

                case VEND_SUCCESS:
                    sharedPreferences.edit().putBoolean("last_operation_success", true).apply();
                    vendFailure = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (transactionCompleteRunnable != null && handler.hasCallbacks(transactionCompleteRunnable)) {
                            handler.removeCallbacks(transactionCompleteRunnable);
                            transactionCompleteRunnable.run();
                        }
                    }
                    break;
                case VEND_FAILURE:
                    sharedPreferences.edit().putBoolean("last_operation_success", false).apply();
                    vendFailure = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (transactionCompleteRunnable != null && handler.hasCallbacks(transactionCompleteRunnable)) {
                            handler.removeCallbacks(transactionCompleteRunnable);
                            transactionCompleteRunnable.run();
                        }
                    }
                    break;
                case VEND_END_SESSION:
                case VEND_CANCEL:
                    if (isTerminalInVendingPurchase) {
                        if (!isInTransactionProcessing) {
                            if (mActivityInterface != null)
                                mActivityInterface.stopPurchase();
                        } else {
                            sharedPreferences.edit().putBoolean("last_operation_success", false).apply();
                            reverseLastTransaction = true;
                        }
                    }
                    break;
            }
        }

        @Override
        public void notifyVendParam(int type, byte[] data) throws RemoteException {
            debugLog("type: " + VendParam.values()[type] + " data: " + ByteArray.bytesToHex(data));

            switch (VendParam.values()[type]) {
                case MAX_PRICE:
                    if (!ByteArray.bytesToHex(data).equalsIgnoreCase("FFFF" )) {
                        machineMaxPrice = ((double) Long.parseLong(ByteArray.bytesToHex(data), 16)) / 100.0;
                        machineMaxPrice = Double.parseDouble(String.format(Locale.US, "%.2f", machineMaxPrice));
                    }
                    break;
                case IDLE_MODE:
                        idleMode.postValue((int) Long.parseLong(ByteArray.bytesToHex(data), 16));
                    break;
            }
        }

        @Override
        public void onPayResult(String result) throws RemoteException {
            debugLog("onPayResult " + result);
        }

        @Override
        public void onCallDiagnostics(byte[] bytes) throws RemoteException {
            debugLog("onPayResult " + ByteArray.bytesToHex(bytes));
            JSONObject report = new JSONObject();
            try {
                report.put(ReportKey.TYPE, ReportType.USER_DEFINED_DATA.ordinal());
                report.put(ReportKey.USER_DEFINED_DATA, "USER TEST DATA");
                MDBManager.getInstance().reportToVMC(report.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onReaderCancel() throws RemoteException {
            debugLog("onReaderCancel");
        }
    };

    private IAgeVerificationCallback ageVerificationCallback = new IAgeVerificationCallback.Stub() {
        @Override
        public void onCheckAgeVerification(int age) throws RemoteException {
            debugLog("onCheckAgeVerification: " + age);
            if (mActivityInterface != null)
                mActivityInterface.onCheckAgeVerification(age);
        }
    };

    private void closeMdbService() {
        try {
            MDBManager.getInstance().close();
            sharedPreferences.edit().putLong("last_init_time", -1).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void restartMdbService() {
        closeMdbService();
        try {
            MDBManager.getInstance().init(
                    paymentAppInfo,
                    iCompletionCallback,
                    iPaymentCallback);

            sharedPreferences.edit().putLong("last_init_time", SystemClock.elapsedRealtime()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private boolean startVendingPurchase() {
        if (deviceState.getValue() == DeviceStatus.DISABLE) {
            debugLog("startVending mdb disabled");
            return false;
        }

        if (isTerminalInVendingPurchase) {
            debugLog("startVending already called");
            return false;
        }

        storedMaxPrice = sharedPreferences.getFloat(Utils.TAG_MAX_AMOUNT, Utils.DEFAULT_MAX_AMOUNT);

        JSONObject report = new JSONObject();
        try {
            report.put(ReportKey.TYPE, ReportType.BEGIN_SESSION.ordinal());

            String hexAmount;
            if (machineMaxPrice > 0)
                hexAmount = Integer.toHexString((int) Math.round(machineMaxPrice * (isDecimalAllowed ? 100 : 1)));
            else
                hexAmount = Integer.toHexString((int) Math.round(storedMaxPrice * (isDecimalAllowed ? 100 : 1)));

            hexAmount = hexAmount.toUpperCase();
            while (hexAmount.length() < 4) {
                hexAmount = "0" + hexAmount;
            }

            report.put(ReportKey.FUNDS, hexAmount);
            debugLog("trans funding: " + report);
            MDBManager.getInstance().reportToVMC(report.toString());

            if (finalizingRunnable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && handler.hasCallbacks(finalizingRunnable)) {
                handler.removeCallbacks(finalizingRunnable);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }


        return true;
    }

    public boolean isTerminalBusy() {
       if (mActivityInterface != null)
           return mActivityInterface.isTerminalBusy();
       else
           return true;
    }

    private void setTransResultCancel(String transAmount) {
        setTransResult(TransResult.TRADE_FAILURE, transAmount);
        setTransResult(TransResult.TRADE_CANCEL, transAmount);
    }

    private void setTransResult(TransResult code, final String transAmount) {
        mInitParamHandler.post(new Runnable() {
            @Override
            public void run() {
                JSONObject report = new JSONObject();
                try {
                    report.put(ReportKey.TYPE, ReportType.TRADE_RESULT.ordinal());
                    report.put(ReportKey.TRADE_RESULT, code.ordinal());
                    report.put(ReportKey.TRANS_RESULT_AMOUNT, transAmount == null ? "00" : transAmount);

                    debugLog("trans result: " + report);
                    MDBManager.getInstance().reportToVMC(report.toString());

                    sharedPreferences.edit().putBoolean("onpay_in_progress", false).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void completeLastTransaction() {
        transactionCompleteRunnable = new Runnable() {
            @Override
            public void run() {
                if (!sharedPreferences.getBoolean("last_operation_success", false))
                    mActivityInterface.startCancelTransaction(null,null, true);
                else
                    mActivityInterface.startCompleteTransaction(0.0, null, null, null, true);

                transactionCompleteRunnable = null;
            }
        };
        handler.postDelayed(transactionCompleteRunnable, 1000);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceInterface;
    }

    private final IServiceInterface serviceInterface = new IServiceInterface() {

        @Override
        public void cardDetected() {
            isInTransactionProcessing = true;
        }

        @Override
        public void transactionFinish(int reason, Intent transactionData, int tranType) {
                if (tranType == MyPOSUtil.TRANSACTION_TYPE_PAYMENT) {
                    isTerminalInVendingPurchase = isInTransactionProcessing = shouldRepeatPayment = false;

                    debugLog("transactionFinish " + reason);

                    String lastItemPriceCopy = lastItemPrice;

                    switch (reason) {
                        case TransactionProcessingResult.TRANSACTION_SUCCESS:

                            lastItemPrice = lastItemNumber = null;

                            if (reverseLastTransaction) {
                                setTransResultCancel(lastItemPriceCopy);
                                sharedPreferences.edit().putBoolean("last_operation_success", false).apply();

                                handler.postDelayed(() -> {
                                    mActivityInterface.startCancelTransaction(null, null, false);
                                    reverseLastTransaction = false;
                                }, 1000);
                            }
                            else {
                                sharedPreferences.edit().putBoolean("last_operation_success", false).apply();
                                vendFailure = true;
                                transactionCompleteRunnable = new Runnable() {
                                    @Override
                                    public void run() {
                                        if (vendFailure)
                                            mActivityInterface.startCancelTransaction(null, null, false);
                                        else
                                            mActivityInterface.startCompleteTransaction(0.0, null, null, null, false);

                                        transactionCompleteRunnable = null;
                                    }
                                };
                                handler.postDelayed(transactionCompleteRunnable, TIMEOUT_VEND_COMPLETE * 1000);
                                setTransResult(TransResult.TRADE_SUCCESS, lastItemPriceCopy);
                            }
                            break;
                        case TransactionProcessingResult.TRANSACTION_CANCELED:
                            lastItemPrice = lastItemNumber = null;

                            if (deviceState.getValue() != DeviceStatus.INACTIVE) {
                                setTransResultCancel(lastItemPriceCopy);
                            }
                            break;
                        case TransactionProcessingResult.TRANSACTION_FAILED:
                        case TransactionProcessingResult.COMMUNICATION_ERROR:
                            lastItemPrice = lastItemNumber = null;

                            Uri CONTENT_URI = Uri.parse("content://com.mypos.providers.LastTransactionProvider/last_transaction");

                            Cursor cursor = getContentResolver().query(
                                    CONTENT_URI,
                                    null,
                                    null,
                                    null,
                                    null
                            );

                            if(cursor != null && cursor.getCount()  > 0) {

                                cursor.moveToFirst();

                                boolean cleared = cursor.getInt(cursor.getColumnIndex("cleared")) == 1;
                                cursor.close();

                                if (!cleared) {
                                    debugLog("last transaction not cleared");
                                    sharedPreferences.edit().putBoolean("last_operation_success", false).apply();
                                    completeLastTransaction();
                                    return;
                                }
                            }

                            if (deviceState.getValue() != DeviceStatus.INACTIVE) {
                                setTransResultCancel(lastItemPriceCopy);
                            }
                            break;
                        case TransactionProcessingResult.COMPLETE_TRANSACTION_PENDING:
                            shouldRepeatPayment = true;
                            completeLastTransaction();
                            return;
                        default:
                            lastItemPrice = lastItemNumber = null;
                            if (deviceState.getValue() != DeviceStatus.INACTIVE) {
                                setTransResultCancel(lastItemPriceCopy);
                            }
                            break;
                    }
                }
                else {
                    if (tranType == MyPOSUtil.TRANSACTION_TYPE_COMPLETE_TX) {
                        if (reason == TransactionProcessingResult.TRANSACTION_SUCCESS) {
                            sharedPreferences.edit().putBoolean("last_operation_success", false).apply();
                        }
                    }

                    boolean shouldRepeatPaymentOrigin = shouldRepeatPayment;

                    if (lastItemPrice == null)
                        shouldRepeatPayment = false;

                    if (shouldRepeatPayment && (reason == TransactionProcessingResult.TRANSACTION_SUCCESS || finalizingTries < 2)) {
                        if (finalizingRunnable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && handler.hasCallbacks(finalizingRunnable))
                            handler.removeCallbacks(finalizingRunnable);

                        finalizingRunnable = null;

                        if (reason == TransactionProcessingResult.TRANSACTION_SUCCESS)
                            finalizingTries = 0;
                        else
                            finalizingTries++;

                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mActivityInterface.startPurchase(MyPOSVendingPayment.builder()
                                        .productAmount(Double.parseDouble(lastItemPrice) * (isDecimalAllowed ? 0.01 : 1))
                                        .currency(Currency.valueOf(currencyName))
                                        .reference(lastItemNumber, ReferenceType.PRODUCT_ID)
                                        .printCustomerReceipt(MyPOSUtil.RECEIPT_OFF)
                                        .printMerchantReceipt(MyPOSUtil.RECEIPT_OFF)
                                        .cardDetectionTimeout(TIMEOUT_CARD_DETECTION)
                                        .fixedPinpad(sharedPreferences.getBoolean(Utils.TAG_STATIC_PINPAD, Utils.DEFAULT_STATIC_PINPAD))
                                        .mastercardSonicBranding(sharedPreferences.getBoolean(Utils.TAG_MC_BRANDING_ENABLED, Utils.DEFAULT_MC_BRANDING_ENABLED))
                                        .visaSensoryBranding(sharedPreferences.getBoolean(Utils.TAG_VISA_BRANDING_ENABLED, Utils.DEFAULT_VISA_BRANDING_ENABLED))
                                        .dccEnabled(false)
                                        .build());

                                isTerminalInVendingPurchase = true;
                            }
                        }, 1000);

                        return;
                    }
                    else {
                        if (shouldRepeatPaymentOrigin) {
                            setTransResultCancel(lastItemPrice);
                        }

                        finalizingTries = 0;
                        shouldRepeatPayment = false;
                        lastItemPrice = null;
                        lastItemNumber = null;
                    }

                    if (reason != TransactionProcessingResult.TRANSACTION_SUCCESS) {
                        finalizingRunnable = () -> {
                            finalizingRunnable = null;
                            completeLastTransaction();
                        };
                        handler.postDelayed(finalizingRunnable, 60_000);
                    }
                    else {
                        if (finalizingRunnable != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && handler.hasCallbacks(finalizingRunnable))
                            handler.removeCallbacks(finalizingRunnable);

                        finalizingRunnable = null;
                    }
                }

        }

        @Override
        public void beginSession() {
            if (idleMode.getValue() == IDLE_MODE) {
                debugLog("beginSession");
                startVendingPurchase();
            }
        }

        @Override
        public void cancelSession() {
            if (idleMode.getValue() == IDLE_MODE) {
                debugLog("select item timeout exceed,start cancel");
                try {
                    JSONObject report = new JSONObject();
                    report.put(ReportKey.TYPE, ReportType.CANCEL_SESSION_REQUEST.ordinal());
                    MDBManager.getInstance().reportToVMC(report.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void enableAgeVerification(boolean enable) {
            try {
                MDBManager.getInstance().setAgeVerificationCallback(ageVerificationCallback);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            AgeVerificationManager.getInstance().enable(enable);
        }
        @Override
        public void setAgeVerificationStatus(AgeVerificationResult result) {
            AgeVerificationManager.getInstance().setDRAVSStatus(result);
        }

        @Override
        public void setTerminalControls(IActivityInterface terminalControls) {
            mActivityInterface = terminalControls;
            mActivityInterface.setLiveData(deviceState, idleMode);
        }
    };

    private void debugLog(String log) {
        Log.e("MDB", log);
        if (mActivityInterface != null) {
            mActivityInterface.debugLog("MDB", log);
        }
    }

    @Override
    public void onDestroy() {
        Log.e("MDB", "MDBService onDestroy");
        mActivityInterface = null;
        unregisterReceiver(shutDownBroadcastReceiver);
        closeMdbService();

        handler.removeCallbacksAndMessages(null);

        MDBManager.getInstance().unbind(this);

        super.onDestroy();
    }

    @Override
    public void onBindComplete() {
        mInitParamHandler.post(new Runnable() {
            @Override
            public void run() {
                initData();

                long lastInitTime = sharedPreferences.getLong("last_init_time", -1);
                if (lastInitTime > 0 && lastInitTime < SystemClock.elapsedRealtime())
                    closeMdbService();

                try {
                    MDBManager.getInstance().init(paymentAppInfo,
                            iCompletionCallback,
                            iPaymentCallback
                    );

                    sharedPreferences.edit().putLong("last_init_time", SystemClock.elapsedRealtime()).apply();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void onUnbind() {
        try {
            MDBManager.getInstance().bind(this, this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}