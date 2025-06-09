package com.mypos.mdbdemo.util;

import com.mypos.mdbdemo.R;

public class Utils {

    public static final String DOWNLOAD_CONFIG_ACTION                   = "com.mypos.action.DOWNLOAD_CONFIG";
    public static final String DOWNLOAD_CONFIG_RESPONSE_ACTION          = "com.mypos.broadcast.DOWNLOAD_CONFIG_DONE";

    public final static float DEFAULT_MAX_AMOUNT = 10.0f;
    public final static boolean DEFAULT_STATIC_PINPAD = true;
    public final static boolean DEFAULT_MC_BRANDING_ENABLED = true;
    public final static boolean DEFAULT_VISA_BRANDING_ENABLED = true;
    public final static boolean DEFAULT_SUPPORTS_REFUND = false;
    public final static boolean DEFAULT_SUPPORTS_REVALUE = false;
    public final static boolean DEFAULT_FORCE_FEATURE_LEVEL_1 = false;
    public final static boolean DEFAULT_RESPOND_ACK_FIRST = false;

    public final static boolean DEFAULT_TAG_MDB_LOG_ENABLED = false;
    public final static int DEFAULT_CASHLESS_TYPE = 0;

    public final static String TAG_MAX_AMOUNT = "max_amount";
    public final static String TAG_STATIC_PINPAD = "static_pinpad";
    public final static String TAG_MC_BRANDING_ENABLED = "mastercard_sonic_branding_enabled";
    public final static String TAG_VISA_BRANDING_ENABLED = "visa_sensory_branding_enabled";
    public final static String TAG_SUPPORTS_REFUND = "supports_refund";
    public final static String TAG_SUPPORTS_REVALUE = "supports_revalue";
    public final static String TAG_FORCE_FEATURE_LEVEL_1 = "feature_level_1";
    public final static String TAG_RESPOND_ACK_FIRST = "respond_ack_first";
    public final static String TAG_MDB_LOG_ENABLED = "mdb_log_enabled";
    public final static String TAG_CASHLESS_TYPE = "cashless_type";

    private static Utils _instance = null;

    public static synchronized Utils getInstance() {
        if (_instance == null) {
            _instance = new Utils();
        }
        return _instance;
    }


    public static boolean isDecimalAlowedForCurrency(String code) {
        try {
            return Integer.parseInt(code) != 352 && Integer.parseInt(code) != 348;
        }
        catch (NumberFormatException e) {
            return true;
        }
    }

}
