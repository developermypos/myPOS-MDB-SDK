# myPOS-MDB-SDK

The MDB Cashless Peripheral SDK is a lightweight, easy-to-integrate Android library designed for myPOS terminals that require implementation of the MDB (Multi-Drop Bus) protocol in cashless peripheral mode. This SDK enables Android applications to act as the main cashless application on POS devices, facilitating seamless communication with MDB-compliant vending controllers (VMCs).

### Prerequisites

1. Merchant account on [www.myPOS.com](https://www.mypos.com/) (or received a test account).
2. Received myPOS terminal
3. Requested Access   [Developers myPOS](http://developers.mypos.com) site.
4.	Deployment Target Android 11 or later.
5.	Android SDK Platform 30 or later.

### Table of Contents

* [Integration](#integration)
  
  * [Installation](#installation)
  
  * [Cashless device](#cashless-device)

  * [Age verification](#age-verification)
  
# Integration

## Installation
Add the repository to your gradle dependencies:

```java
allprojects {
   repositories {
   	mavenCentral()
   }
}
```

Add the dependency to a module:

```java
implementation 'com.mypos:mdbsdk:1.0.0'
```


## Initialization

Add this to your AndroidManifest.xml file
```xml
<queries>
	<package android:name="com.mypos" />
        <package android:name="com.mypos.ipp" />
</queries>
```

Initialize the MyPos components in your app:

```Java
public class SampleApplication extends Application implements MDBManager.OnBindListener {

@Override
public void onCreate() {
	super.onCreate();

	MDBManager.getInstance().bind(this, this);
}

 @Override
    public void onBindComplete() {
        MDBManager.getInstance().init(paymentAppInfo,
                iCompletionCallback,
                iPaymentCallback
        );
    }
```

## CASHLESS DEVICE

Create MDB setup settings:

```Java
PaymentAppInfo paymentAppInfo = new PaymentAppInfo();
paymentAppInfo.setClientId("12345678");                 // provide the terminal tid
paymentAppInfo.setManufactureCode("XGD");
paymentAppInfo.setModel(Build.MODEL);
paymentAppInfo.setCountryCode("826");                   // provide the terminal country code

paymentAppInfo.setDecimalPlaces((byte) 0x02);           // if currency do not support cents, provide 0x00
paymentAppInfo.setScaleFactor(1);

paymentAppInfo.setResponseTime((byte) 0x3C);
paymentAppInfo.setLogLevel(LogLevel.INFO.ordinal());    // for debug purposes can provide log level ALL
paymentAppInfo.setReaderFeatureLevel((byte) 0x00);      // feature level can be forced between 1 and 3, 0 means stand with VMC FL

paymentAppInfo.setRevalueApproved(false);
paymentAppInfo.setRequestRefunds(true);
paymentAppInfo.setDeviceMode(DeviceMode.CASHLESS);
paymentAppInfo.setNotifyStatus(false);
paymentAppInfo.setRespondAckFirst(false);
paymentAppInfo.setDeviceType(0);

paymentAppInfo.setSupportCashSale(false);
paymentAppInfo.setDelayTm(0);
paymentAppInfo.setWaitEndSessionTm(30);
paymentAppInfo.setSupportMultiVend(false);
paymentAppInfo.setSupportRemoteVend(false);

paymentAppInfo.setEnableAlwaysIdle(true);
paymentAppInfo.setMonetaryFormat(0);
```

Setup payment callback:

```Java
private final IPaymentCallback iPaymentCallback = new IPaymentCallback.Stub() {
    @Override
    public int onPay(final int tradeType, final String itemPrice, final String itemNumber) {
        if (tradeType == VendType.SALE.ordinal()) {
            // cashless sale request
        }
        return 0;
    }

    @Override
    public void notifyMdbStatus(int mdbDeviceStatus) {
        // handle mdb status
        switch (DeviceStatus.values()[mdbDeviceStatus]) {
            case INACTIVE:
                break;
            case DISABLE:
                break;
            case ENABLE:
                break;
            case SESSION_IDLE:
                break;
            case VEND:
                break;
        }
    }

    @Override
    public void notifyVendResult(int result) {
        // handle vend result
        switch (VendResult.values()[result]) {
            case VEND_SUCCESS:
                break;
            case VEND_FAILURE:
                break;
            case VEND_END_SESSION:
                break;
            case VEND_CANCEL:
                break;
        }
    }

    @Override
    public void notifyVendParam(int type, byte[] data) throws RemoteException {
        // handle vmc params
        switch (VendParam.values()[type]) {
            case MIN_PRICE:
                double minPrice = ((double) Long.parseLong(ByteArray.bytesToHex(data), 16)) / 100.0;
                break;
            case MAX_PRICE:
                double maxPrice = ((double) Long.parseLong(ByteArray.bytesToHex(data), 16)) / 100.0;
                break;
            case IDLE_MODE:
                long idleMode = Long.parseLong(ByteArray.bytesToHex(data), 16); // 0 - idle, 1 - always idle
                break;
        }
    }

    @Override
    public void onPayResult(String result) throws RemoteException {}

    @Override
    public void onCallDiagnostics(byte[] bytes) throws RemoteException {}

    @Override
    public void onReaderCancel() throws RemoteException {}
};
```

Setup completion callback:

```Java
private final ICompletionCallback iCompletionCallback = new ICompletionCallback.Stub() {
    @Override
    public void onSuccess() {}

    @Override
    public void onFailure(int retCode) {}

    @Override
    public void onAppUpdate() {}

    @Override
    public void onNotifyVMCInfo(final String brand, final String model) {}

    @Override
    public void onReportError(int type, boolean success, String errorMessage) throws RemoteException {}
};

```

Begin Session:
```Java
JSONObject report = new JSONObject();
report.put(ReportKey.TYPE, ReportType.BEGIN_SESSION.ordinal());
report.put(ReportKey.FUNDS, hexAmount);
MDBManager.getInstance().reportToVMC(report.toString());
```

Cancel Session:
```Java
JSONObject report = new JSONObject();
report.put(ReportKey.TYPE, ReportType.CANCEL_SESSION_REQUEST.ordinal());
MDBManager.getInstance().reportToVMC(report.toString());
```

Report vend result from the requested transaction:
```Java
JSONObject report = new JSONObject();
report.put(ReportKey.TYPE, ReportType.TRADE_RESULT.ordinal());
report.put(ReportKey.TRADE_RESULT,TransResult.TRADE_SUCCESS.ordinal());
report.put(ReportKey.TRANS_RESULT_AMOUNT, hexAmount);
MDBManager.getInstance().reportToVMC(report.toString());
```

## AGE VERIFICATION

Enable age verification:
```Java
MDBManager.getInstance().setAgeVerificationCallback(ageVerificationCallback);
```

Setup age verification callback:
```Java
private IAgeVerificationCallback ageVerificationCallback = new IAgeVerificationCallback.Stub() {
    @Override
    public void onCheckAgeVerification(int age) throws RemoteException {
      // do the age verification
    }
};
```

Set age verification result:
```Java
AgeVerificationManager.getInstance().setDRAVSStatus(AgeVerificationResult.VALID_CARD);
```
