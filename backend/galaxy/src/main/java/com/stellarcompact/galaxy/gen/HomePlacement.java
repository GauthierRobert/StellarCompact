package com.stellarcompact.galaxy.gen;

import java.util.List;

/**
 * The outcome of E2-04 home placement: the chosen home system for each faction,
 * in faction-index order (faction {@code i} starts on {@code homeStarIds.get(i)}),
 * together with each home's measured neighbourhood quality so the caller and the
 * tests can verify the balance guarantee.
 *
 * <p>Immutable value type. {@code homeStarIds} are {@link Star#id()} values that
 * are nodes of the {@link LaneGraph} the placement ran over. The quality scores are
 * the same {@link HomePlacementGenerator}-computed neighbourhood scores used to pick
 * the band; they exist on the result purely so the fairness invariant
 * ("spread within tolerance") is observable without re-deriving it.
 *
 * @param homeStarIds   the chosen home star id per faction, index = faction index;
 *                      immutable, size = {@code factionCount}
 * @param homeQualities the neighbourhood-quality score of each chosen home, aligned
 *                      by index with {@code homeStarIds}; immutable
 */
public record HomePlacement(List<Long> homeStarIds, List<Double> homeQualities) {

    public HomePlacement {
        homeStarIds = List.copyOf(homeStarIds);
        homeQualities = List.copyOf(homeQualities);
        if (homeStarIds.size() != homeQualities.size()) {
            throw new IllegalArgumentException(
                    "homeStarIds and homeQualities must align: "
                            + homeStarIds.size() + " vs " + homeQualities.size());
        }
    }

    /** @return the number of placed homes (one per faction). */
    public int count() {
        return homeStarIds.size();
    }

    /** @return the home star id assigned to faction {@code index}. */
    public long homeOf(int factionIndex) {
        return homeStarIds.get(factionIndex);
    }
}
