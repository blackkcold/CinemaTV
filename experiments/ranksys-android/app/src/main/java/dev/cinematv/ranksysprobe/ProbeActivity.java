package dev.cinematv.ranksysprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keeps the real guarded path reachable in the minified APK; synthetic fixture only. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        List<GuardedRankSys.Candidate> input = Arrays.asList(
            new GuardedRankSys.Candidate("a", 1, Collections.singletonMap("x", 1.0)),
            new GuardedRankSys.Candidate("b", 1, Collections.singletonMap("x", 1.0)),
            new GuardedRankSys.Candidate("c", 1, Collections.singletonMap("y", 1.0)));
        List<GuardedRankSys.Decision> output = GuardedRankSys.rank(input, .2, 3, () -> {});
        StringBuilder text = new StringBuilder(GuardedRankSys.VERSION);
        for (GuardedRankSys.Decision decision : output) text.append("\n").append(decision.key).append(": ").append(decision.selectionScore);
        boolean correct = output.size() == 3 && output.get(0).key.equals("a") && output.get(1).key.equals("c") && output.get(2).key.equals("b");
        if (!correct) throw new AssertionError("Minified core fixture failed");
        android.util.Log.i("RankSysProbe", "MINIFIED_GUARDED_PASS order=a,c,b version=" + GuardedRankSys.VERSION + " nonce=" + getIntent().getStringExtra("probe_nonce"));
        for (String name : Arrays.asList("THIRD-PARTY.txt", "MPL-2.0.txt", "Apache-2.0.txt")) {
            try (InputStream source = getAssets().open("third-party/" + name)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
                while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
                text.append("\n\n").append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } catch (IOException failure) { throw new IllegalStateException("Required license absent", failure); }
        }
        for (String module : Arrays.asList("core", "novdiv", "diversity")) {
            try (InputStream source = getAssets().open("third-party/sources/RankSys-" + module + "-0.4.3-sources.jar")) {
                if (source.read() == -1) throw new IllegalStateException("Empty MPL source archive");
            } catch (IOException failure) { throw new IllegalStateException("Required source absent", failure); }
        }
        android.util.Log.i("RankSysProbe", "BUNDLED_LICENSE_AND_SOURCE_PASS nonce=" + getIntent().getStringExtra("probe_nonce"));
        TextView view = new TextView(this); view.setText(text);
        ScrollView scroll = new ScrollView(this); scroll.addView(view); setContentView(scroll);
    }
}
