package com.mypos.mdbdemo.service.callbacks;

import androidx.lifecycle.LiveData;

import com.mypos.mdbsdk.DeviceStatus;
import com.mypos.smartsdk.MyPOSVendingPayment;

import java.util.Locale;

public interface IActivityInterface {
    void startPurchase(MyPOSVendingPayment payment);
    void stopPurchase();
    void startCompleteTransaction(double partialAmount, String sidOriginal, String credential, Locale language, boolean skipConfirmationScreen);
    void startCancelTransaction(String sidOriginal, Locale language, boolean skipConfirmationScreen);
    void onCheckAgeVerification(int age);
    void setLiveData(LiveData<DeviceStatus> uiStateLiveData, LiveData<Integer> idleMode);
    boolean isTerminalBusy();
    void debugLog(String t, String m);
}