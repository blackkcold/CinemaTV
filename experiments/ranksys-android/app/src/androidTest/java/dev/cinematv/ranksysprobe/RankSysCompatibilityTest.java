package dev.cinematv.ranksysprobe;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import es.uam.eps.ir.ranksys.core.Recommendation;
import es.uam.eps.ir.ranksys.core.feature.SimpleFeatureData;
import es.uam.eps.ir.ranksys.diversity.distance.reranking.MMR;
import es.uam.eps.ir.ranksys.novdiv.distance.CosineFeatureItemDistanceModel;
import es.uam.eps.ir.ranksys.novdiv.distance.ItemDistanceModel;
import org.ranksys.core.util.tuples.Tuple2od;
import org.jooq.lambda.tuple.Tuple3;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.jooq.lambda.tuple.Tuple.tuple;
import static org.junit.Assert.*;

/** Actual published RankSys classes, synthetic data only; no upstream source copied. */
@RunWith(AndroidJUnit4.class)
public class RankSysCompatibilityTest {
    @Test public void publishedCosineAndMmrRunWithoutNativeOrNetworkServices() {
        SimpleFeatureData<String, String, Double> features = SimpleFeatureData.load(Stream.of(
            tuple("a", "genre:science", 1.0), tuple("b", "genre:science", 1.0), tuple("c", "genre:drama", 1.0)));
        CosineFeatureItemDistanceModel<String, String> distance = new CosineFeatureItemDistanceModel<>(features);
        assertEquals(0.0, distance.dist("a").applyAsDouble("b"), 1e-9);
        assertEquals(1.0, distance.dist("a").applyAsDouble("c"), 1e-9);
        Recommendation<String, String> input = new Recommendation<>("local", Arrays.asList(
            new Tuple2od<>("a", 1.0), new Tuple2od<>("b", .9), new Tuple2od<>("c", .8)));
        MMR<String, String> mmr = new MMR<>(.8, 3, distance);
        List<Tuple2od<String>> first = mmr.rerankRecommendation(input, 3).getItems();
        List<Tuple2od<String>> second = mmr.rerankRecommendation(input, 3).getItems();
        assertEquals(3, first.size());
        assertEquals(3, first.stream().map(x -> x.v1).collect(Collectors.toSet()).size());
        assertEquals(first.stream().map(x -> x.v1).collect(Collectors.toList()), second.stream().map(x -> x.v1).collect(Collectors.toList()));
        assertTrue(first.stream().allMatch(x -> Double.isFinite(x.v2)));
        Log.i("RankSysProbe", "small_order=" + first.stream().map(x -> x.v1).collect(Collectors.joining(",")));
    }

    @Test public void actualMmrPermutationChangesWithTheDiversityWeight() {
        SimpleFeatureData<String, String, Double> features = SimpleFeatureData.load(Stream.of(
            tuple("a", "x", 1.0), tuple("b", "x", 1.0), tuple("c", "y", 1.0),
            tuple("d", "x", 1.0), tuple("d", "y", 1.0)));
        CosineFeatureItemDistanceModel<String, String> distance = new CosineFeatureItemDistanceModel<>(features);
        Recommendation<String, String> input = new Recommendation<>("local", Arrays.asList(
            new Tuple2od<>("a", 1.0), new Tuple2od<>("b", .9), new Tuple2od<>("c", .8), new Tuple2od<>("d", .7)));
        TraceMmr diverse = new TraceMmr(.8, 4, distance);
        assertArrayEquals(new int[]{0, 2, 1, 3}, diverse.rerankPermutation(input, 4));
        assertArrayEquals(new int[]{0, 1, 2, 3}, new MMR<String, String>(.2, 4, distance).rerankPermutation(input, 4));
        assertArrayEquals(new int[]{0, 1, 2, 3}, new MMR<String, String>(0, 4, distance).rerankPermutation(input, 4));
        assertTrue(Double.isFinite(diverse.valuesByStep.get(1).get("c")));
        assertTrue(diverse.valuesByStep.get(1).get("c") > diverse.valuesByStep.get(1).get("b"));
        Log.i("RankSysProbe", "actual_mmr_order=a,c,b,d lambda_is_diversity_weight=true average_distance=true");
    }

    @Test public void characterizeUnmodifiedLibraryNaNFallbackRatherThanHidingIt() {
        SimpleFeatureData<String, String, Double> features = SimpleFeatureData.load(Stream.of(
            tuple("a", "x", 1.0), tuple("b", "x", 1.0), tuple("c", "y", 1.0)));
        CosineFeatureItemDistanceModel<String, String> distance = new CosineFeatureItemDistanceModel<>(features);
        Recommendation<String, String> equalScores = new Recommendation<>("local", Arrays.asList(
            new Tuple2od<>("a", 1.0), new Tuple2od<>("b", 1.0), new Tuple2od<>("c", 1.0)));
        TraceMmr trace = new TraceMmr(.8, 3, distance);
        assertArrayEquals(new int[]{0, 1, 2}, trace.rerankPermutation(equalScores, 3));
        assertTrue(trace.valuesByStep.get(0).values().stream().allMatch(x -> Double.isNaN(x)));
        assertTrue(trace.valuesByStep.get(1).values().stream().allMatch(x -> Double.isNaN(x)));
        assertTrue(Double.isNaN(distance.dist("a").applyAsDouble("missing")));
        // Public Recommendation scores are ordinal ranks, NOT the possibly-NaN decision values.
        assertTrue(trace.rerankRecommendation(equalScores, 3).getItems().stream().allMatch(x -> Double.isFinite(x.v2)));
        Log.w("RankSysProbe", "KNOWN_LIMIT equal_relevance_and_missing_features_need_guarded_adapter; public_scores_are_ordinal");
    }

    /** Observes actual published selection values without changing the algorithm. Small fixtures only. */
    static final class TraceMmr extends MMR<String, String> {
        final List<Map<String, Double>> valuesByStep = new ArrayList<>();
        final List<String> selected = new ArrayList<>();
        TraceMmr(double lambda, int cutoff, ItemDistanceModel<String> distance) { super(lambda, cutoff, distance); }
        @Override public int[] rerankPermutation(Recommendation<String, String> input, int maxLength) {
            valuesByStep.clear(); selected.clear();
            return super.rerankPermutation(input, maxLength);
        }
        @Override protected LambdaUserReranker getUserReranker(Recommendation<String, String> input, int maxLength) {
            return new TraceUser(input, maxLength);
        }
        final class TraceUser extends UserMMR {
            final Map<String, Double> current = new LinkedHashMap<>();
            TraceUser(Recommendation<String, String> input, int maxLength) { super(input, maxLength); }
            @Override protected double value(Tuple2od<String> item) {
                double value = super.value(item);
                current.put(item.v1, value);
                return value;
            }
            @Override protected void update(Tuple2od<String> item) {
                valuesByStep.add(new LinkedHashMap<>(current)); selected.add(item.v1); current.clear();
                super.update(item);
            }
        }
    }

    @Test public void boundedCatalogReportsEmulatorTimingAndHeapNotHardwareCertification() {
        List<Tuple3<String, String, Double>> triples = new ArrayList<>();
        List<Tuple2od<String>> scores = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            triples.add(tuple("movie:" + i, "genre:" + (i % 20), 1.0));
            triples.add(tuple("movie:" + i, "actor:" + (i % 100), 1.25));
            scores.add(new Tuple2od<>("movie:" + i, 1.0 - i / 2500.0));
        }
        long start = SystemClock.elapsedRealtimeNanos();
        SimpleFeatureData<String, String, Double> features = SimpleFeatureData.load(triples.stream());
        MMR<String, String> mmr = new MMR<>(.2, 50, new CosineFeatureItemDistanceModel<>(features));
        Recommendation<String, String> ranked = mmr.rerankRecommendation(new Recommendation<>("local", scores), 50);
        long elapsedUs = (SystemClock.elapsedRealtimeNanos() - start) / 1000;
        long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertEquals(50, ranked.getItems().size());
        assertEquals(50, ranked.getItems().stream().map(x -> x.v1).collect(Collectors.toSet()).size());
        Log.i("RankSysProbe", "candidates=2000 output=50 elapsed_us=" + elapsedUs + " process_used_heap_bytes=" + usedHeap);
    }
}
