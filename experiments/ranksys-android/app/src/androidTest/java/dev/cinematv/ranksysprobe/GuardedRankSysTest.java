package dev.cinematv.ranksysprobe;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GuardedRankSysTest {
    private GuardedRankSys.Candidate item(String key, double score, String feature) {
        return new GuardedRankSys.Candidate(key, score, Collections.singletonMap(feature, 1.0));
    }
    @Test public void equalScoresDiversifyWithoutNaNFallbackAndTraceActualValues() {
        List<GuardedRankSys.Decision> rows = GuardedRankSys.rank(Arrays.asList(item("a", 1, "x"), item("b", 1, "x"), item("c", 1, "y")), .2, 3, () -> {});
        assertEquals(Arrays.asList("a", "c", "b"), rows.stream().map(x -> x.key).collect(Collectors.toList()));
        for (int i = 0; i < rows.size(); i++) {
            GuardedRankSys.Decision row = rows.get(i);
            assertEquals(i + 1, row.rank);
            assertTrue(Double.isFinite(row.selectionScore));
            assertEquals(row.relevanceContribution + row.diversityContribution, row.selectionScore, 1e-12);
        }
        assertTrue(rows.get(1).diversityContribution > 0);
        assertEquals(0, rows.get(2).selectionScore, 0); // Single remaining candidate: no variance.
    }
    @Test public void singletonAndIdenticalVectorsRemainFiniteAndStable() {
        assertEquals(0, GuardedRankSys.rank(Collections.singletonList(item("one", .5, "x")), .2, 1, () -> {}).get(0).selectionScore, 0);
        List<GuardedRankSys.Decision> rows = GuardedRankSys.rank(Arrays.asList(item("a", .4, "x"), item("b", .9, "x"), item("c", .7, "x")), .2, 3, () -> {});
        assertEquals(Arrays.asList("b", "c", "a"), rows.stream().map(x -> x.key).collect(Collectors.toList()));
        assertTrue(rows.stream().allMatch(x -> Double.isFinite(x.selectionScore)));
    }
    @Test public void malformedOrMissingFeaturesAreRejectedNotRewardedAsNovel() {
        List<GuardedRankSys.Candidate> invalid = Arrays.asList(
            new GuardedRankSys.Candidate("missing", .5, Collections.emptyMap()),
            new GuardedRankSys.Candidate("nan", Double.NaN, Collections.singletonMap("x", 1.0)),
            new GuardedRankSys.Candidate("inf", .5, Collections.singletonMap("x", Double.POSITIVE_INFINITY)));
        for (GuardedRankSys.Candidate candidate : invalid) {
            try { GuardedRankSys.rank(Collections.singletonList(candidate), .2, 1, () -> {}); fail("Expected input rejection"); }
            catch (IllegalArgumentException expected) { /* Explicit caller fallback required. */ }
        }
    }
    @Test public void finiteHugeFeaturesAreRescaledBeforeCoreSquares() {
        Map<String, Double> huge = new LinkedHashMap<>(); huge.put("x", Double.MAX_VALUE); huge.put("y", Double.MAX_VALUE / 2);
        List<GuardedRankSys.Decision> rows = GuardedRankSys.rank(Arrays.asList(new GuardedRankSys.Candidate("huge", .9, huge), item("small", .8, "x")), .2, 2, () -> {});
        assertTrue(rows.stream().allMatch(x -> Double.isFinite(x.selectionScore)));
    }
    @Test public void cancellationEscapesWithoutPublishingPartialRecommendations() {
        int[] checks = {0};
        CancellationException cancellation = new CancellationException("test cancellation");
        try {
            GuardedRankSys.rank(Arrays.asList(item("a", .9, "x"), item("b", .8, "x"), item("c", .7, "y")), .2, 3, () -> {
                if (++checks[0] >= 7) throw cancellation;
            });
            fail("Expected cooperative cancellation");
        } catch (CancellationException actual) { assertSame(cancellation, actual); }
    }
    @Test public void boundedGuardedCatalogRecordsMeasuredRuntime() {
        List<GuardedRankSys.Candidate> input = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Map<String, Double> tags = new LinkedHashMap<>(); tags.put("genre:" + (i % 20), 1.0); tags.put("actor:" + (i % 100), 1.25);
            input.add(new GuardedRankSys.Candidate("movie:" + i, 1 - i / 2500.0, tags));
        }
        long start = android.os.SystemClock.elapsedRealtimeNanos();
        List<GuardedRankSys.Decision> rows = GuardedRankSys.rank(input, .2, 50, () -> {});
        long elapsedUs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1000;
        assertEquals(50, rows.size());
        assertTrue(rows.stream().allMatch(x -> Double.isFinite(x.selectionScore)));
        android.util.Log.i("RankSysProbe", "GUARDED candidates=2000 output=50 elapsed_us=" + elapsedUs + " process_used_heap_bytes=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
    }
}
