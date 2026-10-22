package com.blackfalcon.sensorinfo;

/** Pure helper: GNSS carrier frequency (MHz) to a band label. */
public class Bands {
    public static String name(double mhz) {
        if (near(mhz, 1575.42, 2)) return "L1/E1/B1C";
        if (near(mhz, 1561.098, 2)) return "B1I";
        if (near(mhz, 1176.45, 2)) return "L5/E5a/B2a";
        if (near(mhz, 1227.60, 2)) return "L2";
        if (near(mhz, 1207.14, 2)) return "E5b/B2b";
        if (near(mhz, 1268.52, 2)) return "E6/B3";
        if (near(mhz, 2492.028, 3)) return "S-band";
        if (mhz >= 1598 && mhz <= 1607) return "G1";
        if (mhz >= 1242 && mhz <= 1252) return "G2";
        return Math.round(mhz) + " MHz";
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }
}
