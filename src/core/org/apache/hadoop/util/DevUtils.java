package org.apache.hadoop.util;

public class DevUtils {

    public static void printStackTrace() {
        try { throw new Exception(); } catch (Exception e) { e.printStackTrace(); }
    }

    public static void printStackTrace(String msg) {
        try { throw new Exception(msg); } catch (Exception e) { e.printStackTrace(); }
    }

}
