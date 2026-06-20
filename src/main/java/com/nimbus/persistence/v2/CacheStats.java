package com.nimbus.persistence.v2;

public class CacheStats {
    private long hits = 0;
    private long misses = 0;

    void recordHit()  { hits++;   }
    void recordMiss() { misses++; }

    public long getHits()   { return hits; }
    public long getMisses() { return misses; }
    public long getTotal()  { return hits + misses; }

    public double getHitRatio() {
        return getTotal() == 0 ? 0.0 : (double) hits / getTotal();
    }

    @Override
    public String toString() {
        return "CacheStats{hits=" + hits + ", misses=" + misses
            + ", ratio=" + String.format("%.0f%%", getHitRatio() * 100) + "}";
    }
}
