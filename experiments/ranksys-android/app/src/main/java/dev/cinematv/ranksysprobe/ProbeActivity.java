package dev.cinematv.ranksysprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
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
        android.util.Log.i("RankSysProbe", "MINIFIED_GUARDED_PASS order=a,c,b version=" + GuardedRankSys.VERSION);
        TextView view = new TextView(this); view.setText(text); setContentView(view);
    }
}
