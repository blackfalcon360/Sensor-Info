package com.blackfalcon.sensorinfo;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final int CYAN = 0xFF00ACC1;
    static final int GREEN = 0xFF43A047;
    static final int AMBER = 0xFFFFA000;
    // GnssStatus constellation ids (GPS=1 SBAS=2 GLONASS=3 QZSS=4 BEIDOU=5 GALILEO=6 IRNSS=7)
    static final int[] CONS = {1, 3, 6, 5, 4, 7, 2};

    static class Agg {
        int vis, tracked, used;
        float best;
        HashSet<String> bands = new HashSet<String>();
    }

    static class Sat {
        int cons, svid;
        float cn0, el, az;
        boolean used;
    }

    Store st;
    SensorManager sm;
    LocationManager lm;
    List<Sensor> sensors = new ArrayList<Sensor>();
    final HashMap<Integer, Agg> aggs = new HashMap<Integer, Agg>();
    final ArrayList<Sat> sats = new ArrayList<Sat>();
    boolean gnssOn = false;
    boolean hasFix = false;
    float fixAcc = -1;
    int totalVis = 0, totalUsed = 0;

    int cBg, cCard, cText, cSub;
    TextView[] tabViews;
    ScrollView[] scrolls;
    LinearLayout senBox, gnssBox, devBox, setBox, satBox;
    TextView tvFix, tvHw;
    final HashMap<Integer, TextView[]> consViews = new HashMap<Integer, TextView[]>();
    RadioGroup rgLang, rgTheme;
    int[] langIds = new int[3];
    int[] themeIds = new int[2];

    final GnssStatus.Callback gnssCb = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus s) {
            onSats(s);
        }
    };

    final LocationListener locL = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            hasFix = true;
            fixAcc = l.hasAccuracy() ? l.getAccuracy() : -1;
            updateGnssUi();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Store(this);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (sm != null) {
            sensors = new ArrayList<Sensor>(sm.getSensorList(Sensor.TYPE_ALL));
            Collections.sort(sensors, new Comparator<Sensor>() {
                @Override public int compare(Sensor a, Sensor c) {
                    if (a.getType() != c.getType()) return a.getType() - c.getType();
                    return a.getName().compareToIgnoreCase(c.getName());
                }
            });
        }
        buildUI();
        showTab(0);
        requestPermissions(new String[] {"android.permission.ACCESS_FINE_LOCATION"}, 41);
    }

    boolean hasPerm() {
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startGnss();
        updateGnssUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGnss();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        startGnss();
        updateGnssUi();
    }

    // ---------- GNSS ----------
    @SuppressWarnings("deprecation")
    void startGnss() {
        if (gnssOn || lm == null || !hasPerm()) return;
        try {
            lm.registerGnssStatusCallback(gnssCb);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locL);
            gnssOn = true;
        } catch (Exception e) { }
    }

    @SuppressWarnings("deprecation")
    void stopGnss() {
        if (!gnssOn || lm == null) return;
        try {
            lm.unregisterGnssStatusCallback(gnssCb);
            lm.removeUpdates(locL);
        } catch (Exception e) { }
        gnssOn = false;
    }

    void onSats(GnssStatus s) {
        aggs.clear();
        sats.clear();
        int n = s.getSatelliteCount();
        totalVis = n;
        totalUsed = 0;
        for (int i = 0; i < n; i++) {
            int c = s.getConstellationType(i);
            Agg a = aggs.get(c);
            if (a == null) { a = new Agg(); aggs.put(c, a); }
            a.vis++;
            float cn0 = s.getCn0DbHz(i);
            boolean used = s.usedInFix(i);
            if (used) { a.used++; totalUsed++; }
            if (cn0 > 0) {
                a.tracked++;
                if (cn0 > a.best) a.best = cn0;
                st.markSeen(String.valueOf(c));
                String band = Build.VERSION.SDK_INT >= 26 ? bandOf(s, i) : null;
                if (band != null) {
                    a.bands.add(band);
                    st.markSeen(c + ":" + band);
                }
            }
            Sat t = new Sat();
            t.cons = c;
            t.svid = s.getSvid(i);
            t.cn0 = cn0;
            t.el = s.getElevationDegrees(i);
            t.az = s.getAzimuthDegrees(i);
            t.used = used;
            sats.add(t);
        }
        updateGnssUi();
    }

    @TargetApi(26)
    String bandOf(GnssStatus s, int i) {
        if (!s.hasCarrierFrequencyHz(i)) return null;
        return Bands.name(s.getCarrierFrequencyHz(i) / 1e6);
    }

    @TargetApi(28)
    String hardwareInfo() {
        String m = lm.getGnssHardwareModelName();
        int y = lm.getGnssYearOfHardware();
        String s = (m != null && m.length() > 0) ? m : tr("نامعلوم", "unknown", "غير معروف");
        if (y > 0) s += "  (" + y + ")";
        return s;
    }

    String consName(int c) {
        switch (c) {
            case 1: return "GPS " + tr("(امریکہ)", "(USA)", "(أمريكا)");
            case 3: return "GLONASS " + tr("(روس)", "(Russia)", "(روسيا)");
            case 6: return "Galileo " + tr("(یورپ)", "(Europe)", "(أوروبا)");
            case 5: return "BeiDou " + tr("(چین)", "(China)", "(الصين)");
            case 4: return "QZSS " + tr("(جاپان)", "(Japan)", "(اليابان)");
            case 7: return "NavIC / IRNSS " + tr("(بھارت)", "(India)", "(الهند)");
            case 2: return "SBAS " + tr("(اضافی تصحیح)", "(augmentation)", "(تعزيز)");
            default: return "?";
        }
    }

    void updateGnssUi() {
        if (tvFix == null) return;
        String s;
        if (!hasPerm()) {
            s = tr("لوکیشن کی اجازت دیں تاکہ سیٹلائٹس نظر آئیں۔", "Allow location permission to see satellites.",
                    "اسمح بإذن الموقع لرؤية الأقمار.");
        } else if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            s = tr("GPS بند ہے؛ لوکیشن آن کریں۔", "GPS is off; turn location on.", "GPS مغلق؛ شغّل الموقع.");
        } else if (totalVis == 0) {
            s = tr("سیٹلائٹس کا انتظار… کھلے آسمان تلے ایک دو منٹ رکیں۔",
                    "Waiting for satellites… stay under open sky for a minute or two.",
                    "بانتظار الأقمار… ابقَ تحت السماء المفتوحة دقيقة أو دقيقتين.");
        } else {
            s = tr("نظر آنے والے: ", "In view: ", "مرئية: ") + totalVis
                + tr("   فکس میں: ", "   Used in fix: ", "   مستخدمة: ") + totalUsed
                + (hasFix && fixAcc >= 0 ? "   \u00B1" + Math.round(fixAcc) + " m" : "");
        }
        tvFix.setText(s);
        if (Build.VERSION.SDK_INT >= 28 && lm != null) {
            tvHw.setText(tr("GNSS چپ ماڈل: ", "GNSS chip model: ", "طراز شريحة GNSS: ") + hardwareInfo());
        } else {
            tvHw.setText(tr("GNSS چپ ماڈل: اینڈرائیڈ 9 سے اوپر ہی دستیاب",
                    "GNSS chip model: needs Android 9+", "طراز الشريحة: يتطلب أندرويد 9+"));
        }
        for (int c : CONS) {
            TextView[] v = consViews.get(c);
            if (v == null) continue;
            Agg a = aggs.get(c);
            boolean now = a != null && a.tracked > 0;
            boolean before = st.wasSeen(String.valueOf(c));
            int color = now ? GREEN : (before ? AMBER : 0xFF777777);
            v[0].setTextColor(color);
            String status = now ? tr("اب ٹریک ہو رہا ہے", "tracking now", "يتتبع الآن")
                : (before ? tr("پہلے ٹریک ہو چکا (اس فون میں موجود)", "seen before (supported)",
                    "رُصد سابقًا (مدعوم)")
                : tr("ابھی تک نہیں دیکھا", "not seen yet", "لم يُرصد بعد"));
            StringBuilder sb = new StringBuilder(status);
            if (a != null) {
                sb.append("\n").append(tr("نظر آئے: ", "In view: ", "مرئية: ")).append(a.vis)
                  .append(tr("  ٹریک: ", "  tracked: ", "  متتبعة: ")).append(a.tracked)
                  .append(tr("  فکس میں: ", "  used: ", "  مستخدمة: ")).append(a.used);
                if (a.best > 0) sb.append("\n").append(tr("بہترین سگنل: ", "Best signal: ", "أفضل إشارة: "))
                    .append(Math.round(a.best)).append(" dB-Hz");
            }
            String bands = st.bandsSeen(c);
            if (bands.length() > 0) sb.append("\n").append(tr("بینڈ: ", "Bands: ", "النطاقات: ")).append(bands);
            v[1].setText(sb.toString());
        }
        // satellite list
        satBox.removeAllViews();
        ArrayList<Sat> sorted = new ArrayList<Sat>(sats);
        Collections.sort(sorted, new Comparator<Sat>() {
            @Override public int compare(Sat a, Sat b) { return Float.compare(b.cn0, a.cn0); }
        });
        int shown = 0;
        for (Sat t : sorted) {
            String line = shortName(t.cons) + " #" + t.svid + "   "
                + String.format(Locale.ENGLISH, "%.0f dB-Hz   El %.0f\u00B0   Az %.0f\u00B0", t.cn0, t.el, t.az)
                + (t.used ? "   \u2714" : "");
            satBox.addView(tv(line, 12, t.cn0 > 0 ? cText : cSub, false));
            if (++shown >= 70) break;
        }
    }

    String shortName(int c) {
        switch (c) {
            case 1: return "GPS";
            case 3: return "GLO";
            case 6: return "GAL";
            case 5: return "BDS";
            case 4: return "QZS";
            case 7: return "NavIC";
            case 2: return "SBAS";
            default: return "?";
        }
    }

    // ---------- helpers ----------
    String tr(String ur, String en, String ar) { return st.tr(ur, en, ar); }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    TextView tv(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(cCard, 14));
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(5), dp(10), dp(5));
        c.setLayoutParams(lp);
        return c;
    }

    Button btn(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(color, 10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- main UI ----------
    void buildUI() {
        boolean dark = st.dark();
        cBg = dark ? 0xFF000000 : 0xFFF2F2F2;
        cCard = dark ? 0xFF1A1A1A : 0xFFFFFFFF;
        cText = dark ? 0xFFFFFFFF : 0xFF111111;
        cSub = dark ? 0xFFAAAAAA : 0xFF555555;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setLayoutDirection(st.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        TextView title = tv("Sensor Info", 22, cText, true);
        title.setPadding(dp(14), dp(14), dp(14), dp(6));
        root.addView(title);

        String[] names = {tr("سینسرز", "Sensors", "الحساسات"), "GNSS",
            tr("فون", "Device", "الجهاز"), tr("سیٹنگز", "Settings", "الإعدادات")};
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(6), dp(4), dp(6), dp(8));
        tabViews = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = tv(names[i], 14, cText, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(9), dp(4), dp(9));
            t.setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(t, lp);
            tabViews[i] = t;
        }
        root.addView(tabs);

        FrameLayout content = new FrameLayout(this);
        scrolls = new ScrollView[4];
        senBox = new LinearLayout(this);
        gnssBox = new LinearLayout(this);
        devBox = new LinearLayout(this);
        setBox = new LinearLayout(this);
        LinearLayout[] boxes = {senBox, gnssBox, devBox, setBox};
        for (int i = 0; i < 4; i++) {
            boxes[i].setOrientation(LinearLayout.VERTICAL);
            boxes[i].setPadding(0, dp(4), 0, dp(12));
            scrolls[i] = new ScrollView(this);
            scrolls[i].addView(boxes[i]);
            content.addView(scrolls[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView credit = tv("By: Black Falcon \uD83E\uDD85", 13, cSub, false);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(8), 0, dp(10));
        root.addView(credit);

        setContentView(root);
        buildSensors();
        buildGnss();
        buildDevice();
        buildSettings();
        updateGnssUi();
    }

    void showTab(int i) {
        for (int k = 0; k < 4; k++) {
            boolean sel = k == i;
            tabViews[k].setTextColor(sel ? Color.WHITE : cText);
            tabViews[k].setBackground(round(sel ? CYAN : cCard, 20));
            scrolls[k].setVisibility(sel ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- sensors tab ----------
    static final int[] COMMON = {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_HEART_RATE,
        Sensor.TYPE_SIGNIFICANT_MOTION
    };

    String[] commonNames() {
        return new String[] {
            tr("ایکسلرومیٹر", "Accelerometer", "مقياس التسارع"),
            tr("جائروسکوپ", "Gyroscope", "الجيروسكوب"),
            tr("مقناطیسی (کمپاس)", "Magnetometer (compass)", "المغناطيسي (البوصلة)"),
            tr("بیرومیٹر (دباؤ)", "Barometer (pressure)", "البارومتر (الضغط)"),
            tr("روشنی", "Light", "الضوء"),
            tr("قربت (Proximity)", "Proximity", "القرب"),
            tr("گریویٹی", "Gravity", "الجاذبية"),
            tr("لینیئر ایکسلریشن", "Linear acceleration", "التسارع الخطي"),
            tr("روٹیشن ویکٹر", "Rotation vector", "متجه الدوران"),
            tr("گیم روٹیشن ویکٹر", "Game rotation vector", "متجه دوران الألعاب"),
            tr("جیو میگنیٹک روٹیشن", "Geomagnetic rotation", "الدوران الجيومغناطيسي"),
            tr("اسٹیپ کاؤنٹر", "Step counter", "عداد الخطوات"),
            tr("اسٹیپ ڈیٹیکٹر", "Step detector", "كاشف الخطوات"),
            tr("محیطی درجہ حرارت", "Ambient temperature", "درجة حرارة المحيط"),
            tr("نمی (Humidity)", "Humidity", "الرطوبة"),
            tr("دل کی دھڑکن", "Heart rate", "معدل ضربات القلب"),
            tr("اہم حرکت", "Significant motion", "الحركة المهمة")
        };
    }

    String typeLabel(Sensor s) {
        String t = s.getStringType();
        if (t == null) return String.valueOf(s.getType());
        if (t.startsWith("android.sensor.")) t = t.substring("android.sensor.".length());
        return t.replace('_', ' ');
    }

    String unit(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION: return "m/s\u00B2";
            case Sensor.TYPE_GYROSCOPE: return "rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD: return "\u00B5T";
            case Sensor.TYPE_LIGHT: return "lx";
            case Sensor.TYPE_PRESSURE: return "hPa";
            case Sensor.TYPE_PROXIMITY: return "cm";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "\u00B0C";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "%";
            case Sensor.TYPE_HEART_RATE: return "bpm";
            case Sensor.TYPE_STEP_COUNTER: return "steps";
            default: return "";
        }
    }

    String modeName(int m) {
        switch (m) {
            case Sensor.REPORTING_MODE_CONTINUOUS: return tr("مسلسل", "continuous", "مستمر");
            case Sensor.REPORTING_MODE_ON_CHANGE: return tr("تبدیلی پر", "on change", "عند التغيّر");
            case Sensor.REPORTING_MODE_ONE_SHOT: return tr("ایک بار (ٹرگر)", "one-shot (trigger)", "مرة واحدة");
            default: return tr("خصوصی", "special", "خاص");
        }
    }

    String specs(Sensor s) {
        StringBuilder sb = new StringBuilder();
        sb.append(tr("قسم: ", "Type: ", "النوع: ")).append(typeLabel(s)).append("\n");
        sb.append(tr("بنانے والا: ", "Vendor: ", "المصنّع: ")).append(s.getVendor()).append("\n");
        sb.append(tr("ورژن: ", "Version: ", "الإصدار: ")).append(s.getVersion()).append("\n");
        sb.append(tr("رینج: ", "Range: ", "المدى: ")).append(s.getMaximumRange()).append(" ").append(unit(s.getType())).append("\n");
        sb.append(tr("ریزولوشن: ", "Resolution: ", "الدقة: ")).append(s.getResolution()).append(" ").append(unit(s.getType())).append("\n");
        sb.append(tr("بجلی: ", "Power: ", "الطاقة: ")).append(s.getPower()).append(" mA\n");
        if (s.getMinDelay() > 0) {
            sb.append(tr("زیادہ سے زیادہ ریٹ: ", "Max rate: ", "أقصى معدل: "))
              .append(String.format(Locale.ENGLISH, "%.0f Hz", 1000000.0 / s.getMinDelay())).append("\n");
        }
        sb.append(tr("رپورٹنگ: ", "Reporting: ", "الإبلاغ: ")).append(modeName(s.getReportingMode())).append("\n");
        sb.append(tr("Wake-up سینسر: ", "Wake-up sensor: ", "حساس إيقاظ: "))
          .append(s.isWakeUpSensor() ? tr("ہاں", "yes", "نعم") : tr("نہیں", "no", "لا")).append("\n");
        sb.append("FIFO: ").append(s.getFifoMaxEventCount());
        return sb.toString();
    }

    void buildSensors() {
        senBox.removeAllViews();
        LinearLayout sum = cardBox();
        sum.addView(tv(tr("اس فون میں کل سینسرز: ", "Total sensors on this phone: ", "إجمالي الحساسات: ")
                + sensors.size(), 18, cText, true));
        String[] cn = commonNames();
        for (int i = 0; i < COMMON.length; i++) {
            boolean has = sm != null && sm.getDefaultSensor(COMMON[i]) != null;
            TextView t = tv((has ? "\u2714  " : "\u2716  ") + cn[i], 13, has ? GREEN : 0xFFE53935, false);
            t.setPadding(0, dp(2), 0, dp(2));
            sum.addView(t);
        }
        senBox.addView(sum);
        senBox.addView(tv(tr("تفصیل اور لائیو ریڈنگ کے لیے سینسر پر ٹیپ کریں۔",
                "Tap a sensor for details and live readings.", "اضغط على حساس للتفاصيل والقراءة الحية."),
                12, cSub, false));
        for (final Sensor s : sensors) {
            LinearLayout c = cardBox();
            c.addView(tv(s.getName(), 15, cText, true));
            c.addView(tv(typeLabel(s) + "  \u2022  " + s.getVendor(), 12, cSub, false));
            c.setOnClickListener(v -> showSensor(s));
            senBox.addView(c);
        }
    }

    void showSensor(final Sensor s) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF222222);
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setLayoutDirection(st.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        sv.addView(box);
        final TextView live = tv("", 16, 0xFF69F0AE, true);
        live.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        live.setPadding(0, 0, 0, dp(10));
        box.addView(live);
        box.addView(tv(specs(s), 13, Color.WHITE, false));

        final String u = unit(s.getType());
        final SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                StringBuilder sb = new StringBuilder();
                String[] ax = {"X", "Y", "Z", "4", "5", "6"};
                int n = Math.min(e.values.length, 6);
                for (int i = 0; i < n; i++) {
                    sb.append(n == 1 ? "" : ax[i] + ": ").append(String.format(Locale.ENGLISH, "%.3f", e.values[i]));
                    if (i == 0 && n == 1 && u.length() > 0) sb.append(" ").append(u);
                    else if (n > 1 && u.length() > 0 && i < 3) sb.append(" ").append(u);
                    if (i < n - 1) sb.append("\n");
                }
                live.setText(sb.toString());
            }
            @Override public void onAccuracyChanged(Sensor sen, int acc) { }
        };
        boolean ok = false;
        if (sm != null && s.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
            try { ok = sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI); } catch (Exception e) { }
        }
        live.setText(ok ? tr("ریڈنگ کا انتظار…", "Waiting for reading…", "بانتظار القراءة…")
            : tr("لائیو ریڈنگ دستیاب نہیں (ٹرگر سینسر یا اجازت درکار)",
                "No live reading (trigger sensor or permission needed)", "لا قراءة حية (حساس تشغيلي أو يلزم إذن)"));
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(s.getName())
            .setView(sv)
            .setPositiveButton(tr("بند", "Close", "إغلاق"), null)
            .create();
        dlg.setOnDismissListener(x -> { if (sm != null) sm.unregisterListener(l); });
        dlg.show();
    }

    // ---------- GNSS tab ----------
    void buildGnss() {
        gnssBox.removeAllViews();
        consViews.clear();
        LinearLayout top = cardBox();
        tvFix = tv("", 14, cText, true);
        tvHw = tv("", 12, cSub, false);
        top.addView(tvFix);
        top.addView(tvHw);
        gnssBox.addView(top);
        for (int c : CONS) {
            LinearLayout card = cardBox();
            TextView h = tv("\u25CF  " + consName(c), 16, 0xFF777777, true);
            TextView d = tv("", 12, cSub, false);
            d.setPadding(0, dp(3), 0, 0);
            card.addView(h);
            card.addView(d);
            gnssBox.addView(card);
            consViews.put(c, new TextView[] {h, d});
        }
        LinearLayout sc = cardBox();
        sc.addView(tv(tr("سیٹلائٹس (سگنل کے لحاظ سے)", "Satellites (by signal)", "الأقمار (حسب الإشارة)"),
                15, cText, true));
        satBox = new LinearLayout(this);
        satBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(satBox);
        gnssBox.addView(sc);
        TextView note = tv(tr(
            "اینڈرائیڈ یہ سیدھا نہیں بتاتا کہ چپ کون سے نظام سنبھالتی ہے؛ ایپ وہ نظام دکھاتی ہے جن کے سیٹلائٹس واقعی ٹریک ہوئے۔ سبز = ابھی ٹریک ہو رہا، نارنجی = پہلے ٹریک ہو چکا (فون میں موجود)، سرمئی = ابھی نہیں دیکھا۔ \"ابھی نہیں دیکھا\" کا مطلب یہ نہیں کہ سپورٹ نہیں؛ کھلے آسمان تلے چند منٹ انتظار کریں۔ NavIC کے سیٹلائٹس پاکستان سے عموماً کم نظر آتے ہیں۔",
            "Android does not directly list which systems the chip supports; this app shows systems whose satellites were actually tracked. Green = tracking now, amber = seen before (supported), grey = not seen yet. \"Not seen yet\" does not mean unsupported; wait a few minutes under open sky. NavIC satellites are often weak or low from Pakistan.",
            "لا يذكر أندرويد مباشرة الأنظمة التي تدعمها الشريحة؛ يعرض التطبيق الأنظمة التي تم تتبع أقمارها فعلًا. أخضر = يتتبع الآن، برتقالي = رُصد سابقًا (مدعوم)، رمادي = لم يُرصد بعد. «لم يُرصد بعد» لا يعني عدم الدعم؛ انتظر دقائق تحت السماء المفتوحة."),
            11, cSub, false);
        note.setPadding(dp(14), dp(4), dp(14), dp(8));
        gnssBox.addView(note);
    }

    // ---------- device tab ----------
    @TargetApi(31)
    String socInfo() {
        String m = Build.SOC_MANUFACTURER, n = Build.SOC_MODEL;
        return (m == null ? "" : m) + " " + (n == null ? "" : n);
    }

    String[][] deviceLines() {
        String soc = Build.VERSION.SDK_INT >= 31 ? socInfo().trim() : "";
        return new String[][] {
            {tr("ماڈل", "Model", "الطراز"), Build.MANUFACTURER + " " + Build.MODEL},
            {tr("ڈیوائس", "Device", "الجهاز"), Build.DEVICE},
            {tr("ہارڈویئر / بورڈ", "Hardware / board", "العتاد / اللوحة"), Build.HARDWARE + " / " + Build.BOARD},
            {tr("چپ سیٹ (SoC)", "Chipset (SoC)", "المعالج (SoC)"), soc.length() > 0 ? soc : "\u2014"},
            {"Android", Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")"},
            {"ABI", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "\u2014"}
        };
    }

    static final String[][] FEATS = {
        {"android.hardware.location.gps", "GPS"},
        {"android.hardware.location.network", "Network location"},
        {"android.hardware.wifi", "Wi-Fi"},
        {"android.hardware.wifi.rtt", "Wi-Fi RTT"},
        {"android.hardware.bluetooth", "Bluetooth"},
        {"android.hardware.bluetooth_le", "Bluetooth LE"},
        {"android.hardware.nfc", "NFC"},
        {"android.hardware.uwb", "UWB"},
        {"android.hardware.fingerprint", "Fingerprint"},
        {"android.hardware.telephony", "Telephony"},
        {"android.hardware.consumerir", "IR blaster"},
        {"android.hardware.sensor.hifi_sensors", "Hi-Fi sensors"},
        {"android.hardware.camera.flash", "Camera flash"}
    };

    void buildDevice() {
        devBox.removeAllViews();
        LinearLayout c = cardBox();
        for (String[] r : deviceLines()) {
            c.addView(tv(r[0], 12, cSub, false));
            TextView v = tv(r[1], 15, cText, true);
            v.setPadding(0, 0, 0, dp(6));
            c.addView(v);
        }
        devBox.addView(c);
        LinearLayout f = cardBox();
        f.addView(tv(tr("ہارڈویئر خصوصیات", "Hardware features", "ميزات العتاد"), 16, cText, true));
        for (String[] r : FEATS) {
            boolean has = getPackageManager().hasSystemFeature(r[0]);
            TextView t = tv((has ? "\u2714  " : "\u2716  ") + r[1], 13, has ? GREEN : 0xFFE53935, false);
            t.setPadding(0, dp(2), 0, dp(2));
            f.addView(t);
        }
        devBox.addView(f);
        LinearLayout sh = cardBox();
        sh.addView(btn(tr("پوری رپورٹ شیئر کریں", "Share full report", "مشاركة التقرير الكامل"), CYAN, v -> shareReport()));
        devBox.addView(sh);
    }

    void shareReport() {
        StringBuilder sb = new StringBuilder("Sensor Info report\n\n");
        for (String[] r : deviceLines()) sb.append(r[0]).append(": ").append(r[1]).append("\n");
        sb.append("\nGNSS (tracked on this phone):\n");
        for (int c : CONS) {
            String bands = st.bandsSeen(c);
            sb.append(consName(c)).append(" - ")
              .append(st.wasSeen(String.valueOf(c)) ? "seen" : "not seen yet")
              .append(bands.length() > 0 ? " [" + bands + "]" : "").append("\n");
        }
        sb.append("\nSensors (").append(sensors.size()).append("):\n");
        for (Sensor s : sensors) {
            sb.append("- ").append(s.getName()).append(" | ").append(typeLabel(s)).append(" | ")
              .append(s.getVendor()).append("\n");
        }
        sb.append("\nBy: Black Falcon");
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Share"));
    }

    // ---------- settings ----------
    TextView label(String s) {
        TextView t = tv(s, 16, cText, true);
        t.setPadding(dp(14), dp(14), dp(14), dp(6));
        return t;
    }

    RadioGroup radios(String[] labels, int checked, int[] ids) {
        RadioGroup g = new RadioGroup(this);
        g.setPadding(dp(14), 0, dp(14), 0);
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(labels[i]);
            r.setTextColor(cText);
            r.setTextSize(15);
            r.setId(View.generateViewId());
            ids[i] = r.getId();
            g.addView(r);
        }
        g.check(ids[checked]);
        return g;
    }

    void buildSettings() {
        setBox.removeAllViews();
        setBox.addView(label(tr("زبان", "Language", "اللغة")));
        String l = st.lang();
        rgLang = radios(new String[] {"اردو", "English", "العربية"},
                l.equals("en") ? 1 : (l.equals("ar") ? 2 : 0), langIds);
        setBox.addView(rgLang);

        setBox.addView(label(tr("تھیم", "Theme", "المظهر")));
        rgTheme = radios(new String[] {tr("ڈارک موڈ", "Dark mode", "الوضع الداكن"),
            tr("لائٹ موڈ", "Light mode", "الوضع الفاتح")}, st.dark() ? 0 : 1, themeIds);
        setBox.addView(rgTheme);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(14), 0, dp(14), 0);
        holder.addView(btn(tr("محفوظ کریں", "Save", "حفظ"), CYAN, v -> saveSettings()));
        setBox.addView(holder);

        TextView info = tv(tr(
            "ایپ انٹرنیٹ استعمال نہیں کرتی۔ صرف GNSS کے لیے لوکیشن کی اجازت لیتی ہے؛ سینسرز کی فہرست کو کسی اجازت کی ضرورت نہیں۔ کچھ سینسرز (دل کی دھڑکن، قدم) کی لائیو ریڈنگ کے لیے اینڈرائیڈ الگ اجازت مانگتا ہے جو ایپ نہیں مانگتی۔",
            "The app uses no internet. It asks only for location permission (for GNSS); the sensor list needs no permission. Live readings of some sensors (heart rate, steps) need separate Android permissions that this app does not request.",
            "لا يستخدم التطبيق الإنترنت. يطلب إذن الموقع فقط (لـ GNSS)؛ قائمة الحساسات لا تحتاج إذنًا. القراءة الحية لبعض الحساسات (نبض القلب، الخطوات) تحتاج أذونات منفصلة لا يطلبها التطبيق."),
            12, cSub, false);
        info.setPadding(dp(14), dp(16), dp(14), dp(8));
        setBox.addView(info);
    }

    void saveSettings() {
        String[] langs = {"ur", "en", "ar"};
        int sel = rgLang.getCheckedRadioButtonId();
        for (int i = 0; i < 3; i++) if (langIds[i] == sel) st.setLang(langs[i]);
        st.setDark(rgTheme.getCheckedRadioButtonId() == themeIds[0]);
        buildUI();
        showTab(3);
        Toast.makeText(this, tr("محفوظ ہو گیا", "Saved", "تم الحفظ"), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
            .setTitle(tr("ایپ بند کریں؟", "Exit app?", "إغلاق التطبيق؟"))
            .setMessage(tr("کیا آپ واقعی باہر نکلنا چاہتے ہیں؟", "Do you really want to exit?",
                    "هل تريد الخروج فعلًا؟"))
            .setPositiveButton(tr("ہاں", "Yes", "نعم"), (dlg, w) -> finish())
            .setNegativeButton(tr("نہیں", "No", "لا"), null)
            .show();
    }
}
