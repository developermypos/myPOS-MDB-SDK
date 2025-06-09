package com.mypos.mdbdemo.service.callbacks;

import android.content.Intent;
import android.os.Binder;
import com.mypos.mdbsdk.AgeVerificationResult;

public abstract class IServiceInterface extends Binder {
    public abstract void cardDetected();
    public abstract void transactionFinish(int reason, Intent transactionData, int tranType);
    public abstract void beginSession();
    public abstract void cancelSession();
    public abstract void enableAgeVerification(boolean enable);
    public abstract void setAgeVerificationStatus(AgeVerificationResult result);
    public abstract void setTerminalControls(IActivityInterface terminalControls);
}