package dev.cinematv.data.recommendation.ranksys;

import es.uam.eps.ir.ranksys.core.Recommendation;
import es.uam.eps.ir.ranksys.core.feature.SimpleFeatureData;
import es.uam.eps.ir.ranksys.core.util.Stats;
import es.uam.eps.ir.ranksys.diversity.distance.reranking.MMR;
import es.uam.eps.ir.ranksys.novdiv.distance.CosineFeatureItemDistanceModel;
import es.uam.eps.ir.ranksys.novdiv.distance.ItemDistanceModel;
import org.ranksys.core.util.tuples.Tuple2od;
import org.jooq.lambda.tuple.Tuple3;
import java.util.*;
import static org.jooq.lambda.tuple.Tuple.tuple;

/**
 * Experimental CinemaTV policy around the REAL published RankSys implementation.
 * Not stock behavior: zero/tiny-variance dimensions contribute zero instead of NaN.
 * Ranking, average-distance updates and selection remain RankSys MMR. Missing-feature
 * items must use the application's explicit fallback, never an invented distance.
 */
public final class GuardedRankSys {
    public static final String VERSION = "RankSys-0.4.3+CinemaTV-zero-variance-v1";
    private GuardedRankSys() {}
    public static final class Candidate {
        public final String key;
        public final double relevance;
        public final Map<String, Double> features;
        public Candidate(String key, double relevance, Map<String, Double> features) {
            this.key = key; this.relevance = relevance;
            this.features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
        }
    }
    public static final class Decision {
        public final String key;
        public final double rawRelevance, relevanceZ, meanDistance, diversityZ, relevanceContribution, diversityContribution, selectionScore;
        public final int rank;
        private Decision(String key, int rank, double relevance, double relevanceZ, double meanDistance, double diversityZ,
                         double relevanceContribution, double diversityContribution, double score) {
            this.key = key; this.rank = rank; this.rawRelevance = relevance; this.relevanceZ = relevanceZ;
            this.meanDistance = meanDistance; this.diversityZ = diversityZ;
            this.relevanceContribution = relevanceContribution; this.diversityContribution = diversityContribution;
            this.selectionScore = score;
        }
    }
    public static List<Decision> rank(List<Candidate> input, double diversityWeight, int limit, Runnable checkCancelled) {
        if (!Double.isFinite(diversityWeight) || diversityWeight <= 0 || diversityWeight > 1 || limit < 0 || limit > 50 || input.size() > 2000)
            throw new IllegalArgumentException("Invalid bounded MMR policy");
        if (input.isEmpty() || limit == 0) return Collections.emptyList();
        List<Tuple3<String, String, Double>> features = new ArrayList<>();
        List<Tuple2od<String>> scores = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Candidate candidate : input) {
            checkCancelled.run();
            if (candidate.key == null || candidate.key.isEmpty() || !keys.add(candidate.key) ||
                !Double.isFinite(candidate.relevance) || candidate.relevance < 0 || candidate.relevance > 1 ||
                candidate.features.isEmpty() || candidate.features.size() > 64)
                throw new IllegalArgumentException("Invalid candidate or missing features");
            double max = 0;
            for (Map.Entry<String, Double> entry : candidate.features.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null ||
                    !Double.isFinite(entry.getValue()) || entry.getValue() <= 0)
                    throw new IllegalArgumentException("Features must have unique names and finite positive weights");
                max = Math.max(max, entry.getValue());
            }
            // Rescale before RankSys squares magnitudes; cosine is scale-invariant.
            for (Map.Entry<String, Double> entry : candidate.features.entrySet()) {
                double scaled = entry.getValue() / max;
                if (scaled > 0) features.add(tuple(candidate.key, entry.getKey(), scaled));
            }
            scores.add(new Tuple2od<>(candidate.key, candidate.relevance));
        }
        CosineFeatureItemDistanceModel<String, String> coreDistance = new CosineFeatureItemDistanceModel<>(SimpleFeatureData.load(features.stream()));
        ItemDistanceModel<String> checkedDistance = a -> b -> {
            checkCancelled.run();
            double distance = coreDistance.dist(a).applyAsDouble(b);
            if (!Double.isFinite(distance) || distance < -1e-12 || distance > 1 + 1e-12)
                throw new IllegalStateException("RankSys returned invalid cosine distance");
            return Math.max(0, Math.min(1, distance));
        };
        ObservedMmr mmr = new ObservedMmr(diversityWeight, Math.min(limit, input.size()), checkedDistance, checkCancelled);
        int[] permutation = mmr.rerankPermutation(new Recommendation<>("local", scores), limit);
        if (mmr.decisions.size() != permutation.length) throw new IllegalStateException("Missing actual selection trace");
        for (int i = 0; i < permutation.length; i++) {
            if (!input.get(permutation[i]).key.equals(mmr.decisions.get(i).key)) throw new IllegalStateException("Trace disagrees with core permutation");
        }
        return Collections.unmodifiableList(new ArrayList<>(mmr.decisions));
    }
    private static final class ObservedMmr extends MMR<String, String> {
        final List<Decision> decisions = new ArrayList<>();
        final Runnable checkCancelled;
        ObservedMmr(double weight, int cutoff, ItemDistanceModel<String> distance, Runnable checkCancelled) {
            super(weight, cutoff, distance); this.checkCancelled = checkCancelled;
        }
        @Override protected LambdaUserReranker getUserReranker(Recommendation<String, String> input, int maxLength) {
            return new GuardedUser(input, maxLength);
        }
        final class GuardedUser extends UserMMR {
            final Map<String, Double> current = new HashMap<>();
            GuardedUser(Recommendation<String, String> input, int maxLength) { super(input, maxLength); }
            @Override protected double norm(double score, Stats stats) {
                double mean = stats.getMean();
                // RankSys uses sample variance, undefined for one remaining item.
                if (stats.getN() == 1 && Double.isFinite(score) && Double.isFinite(mean)) return 0;
                double deviation = stats.getStandardDeviation();
                if (!Double.isFinite(score) || !Double.isFinite(mean) || !Double.isFinite(deviation))
                    throw new IllegalStateException("Nonfinite RankSys statistics");
                return deviation <= 1e-12 ? 0 : super.norm(score, stats);
            }
            @Override protected double value(Tuple2od<String> item) {
                checkCancelled.run();
                double actual = super.value(item);
                if (!Double.isFinite(actual)) throw new IllegalStateException("Nonfinite selection score");
                // Save the actual selection value; allocate detailed traces only for chosen items.
                current.put(item.v1, actual);
                return actual;
            }
            @Override protected void update(Tuple2od<String> selected) {
                Double actual = current.get(selected.v1);
                if (actual == null) throw new IllegalStateException("Selected item was not scored");
                double relZ = norm(selected.v2, relStats), meanDistance = novMap.getDouble(selected.v1), divZ = norm(meanDistance, novStats);
                decisions.add(new Decision(selected.v1, decisions.size() + 1, selected.v2, relZ, meanDistance, divZ,
                    (1 - lambda) * relZ, lambda * divZ, actual));
                current.clear();
                super.update(selected);
            }
        }
    }
}
