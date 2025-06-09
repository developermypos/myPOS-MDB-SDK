package com.mypos.mdbdemo.util;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by kamen.troshev on 26.7.2016 г..
 */
public class ByteArray {
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static byte[] toByteArray(List<Byte> in) {
        final int n = in.size();
        byte[] ret = new byte[n];
        for (int i = 0; i < n; i++) {
            ret[i] = in.get(i);
        }
        return ret;
    }

    public static boolean isMatch(byte[] pattern, byte[] input, int pos) {
        for(int i = 0; i < pattern.length; i++) {
            if(pattern[i] != input[pos + i]) {
                return false;
            }
        }
        return true;
    }

    public static ArrayList<byte[]> split(byte[] pattern, byte[] input) {
        ArrayList<byte[]> splittedList = new ArrayList<>();
        int blockStart = 0;
        for( int i = 0; i < input.length; i++ ) {
            if( isMatch(pattern, input, i) ) {
                splittedList.add(Arrays.copyOfRange(input, blockStart, i));
                blockStart = i + pattern.length;
                i = blockStart;
            }
        }

        splittedList.add(Arrays.copyOfRange(input, blockStart, input.length ));

        if( splittedList.get(splittedList.size() - 1).length == 0 )
            splittedList.remove(splittedList.size() - 1);

        return splittedList;
    }

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
