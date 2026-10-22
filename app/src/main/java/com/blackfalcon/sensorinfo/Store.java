package com.blackfalcon.sensorinfo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;

public class Store {
    private final SharedPreferences p;
    private final HashSet<String> seen = new HashSet<String>();

    public Store(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String s = p.getString("seen", "");
        if (s.length() > 0) for (String t : s.split(",")) seen.add(t);
    }

    public String lang() { return p.getString("lang", "ur"); }
    public void setLang(String l) { p.edit().putString("lang", l).apply(); }
    public boolean dark() { return p.getBoolean("dark", true); }
    public void setDark(boolean v) { p.edit().putBoolean("dark", v).apply(); }
    public boolean rtl() { return !lang().equals("en"); }

    public String tr(String ur, String en, String ar) {
        String l = lang();
        if (l.equals("en")) return en;
        if (l.equals("ar")) return ar;
        return ur;
    }

    /** Remembers that a constellation ("5") or a band ("5:L1/E1/B1C") was really tracked on this phone. */
    public void markSeen(String token) {
        if (seen.add(token)) {
            StringBuilder sb = new StringBuilder();
            for (String t : seen) {
                if (sb.length() > 0) sb.append(',');
                sb.append(t);
            }
            p.edit().putString("seen", sb.toString()).apply();
        }
    }

    public boolean wasSeen(String token) { return seen.contains(token); }

    public String bandsSeen(int constellation) {
        StringBuilder sb = new StringBuilder();
        String pre = constellation + ":";
        for (String t : seen) {
            if (t.startsWith(pre)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(t.substring(pre.length()));
            }
        }
        return sb.toString();
    }
}
