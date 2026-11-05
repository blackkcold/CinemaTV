package dev.cinematv.updateprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

/** Test-only, separately packaged instrumentation. Never included in CinemaTV. */
public final class UpgradeProbe extends Instrumentation {
    private String mode;
    private static final String SENTINEL = "TEST ONLY update data preservation";
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "verify");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            if (!target.getPackageName().equals("dev.cinematv")) throw new AssertionError("Wrong target");
            if (mode.equals("seed")) {
                if (!target.getSharedPreferences("cinematv_updates", 0).edit()
                        .putString("channel", "stable").putString("acceptance_sentinel", SENTINEL).commit()) {
                    throw new AssertionError("Preference write failed");
                }
                try (SQLiteDatabase db = target.openOrCreateDatabase("update_acceptance.db", 0, null)) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS sentinel (value TEXT NOT NULL)");
                    db.execSQL("DELETE FROM sentinel");
                    db.execSQL("INSERT INTO sentinel VALUES (?)", new Object[]{SENTINEL});
                }
            } else if (!mode.equals("verify")) throw new AssertionError("Unknown mode");
            if (!SENTINEL.equals(target.getSharedPreferences("cinematv_updates", 0).getString("acceptance_sentinel", null))) {
                throw new AssertionError("Private preferences lost");
            }
            if (!"stable".equals(target.getSharedPreferences("cinematv_updates", 0).getString("channel", null))) {
                throw new AssertionError("Subscription preference lost");
            }
            try (SQLiteDatabase db = target.openOrCreateDatabase("update_acceptance.db", 0, null);
                 Cursor cursor = db.rawQuery("SELECT value FROM sentinel", null)) {
                if (!cursor.moveToFirst() || !SENTINEL.equals(cursor.getString(0)) || cursor.moveToNext()) {
                    throw new AssertionError("Private SQLite data lost");
                }
            }
            result.putString("stream", "CINEMATV_UPDATE_PROBE_OK " + mode + "\n");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("stream", "CINEMATV_UPDATE_PROBE_FAILED " + error.getClass().getSimpleName() + "\n");
            finish(1, result);
        }
    }
}
