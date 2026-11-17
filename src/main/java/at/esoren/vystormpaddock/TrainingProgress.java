package at.esoren.vystormpaddock;

/** Animal-owned progress, encoded in its existing persistent entity data and stable snapshots. */
record TrainingProgress(long riding, int jumps, int speedLevel, int jumpLevel, long cooldownUntil) {
    static final TrainingProgress EMPTY = new TrainingProgress(0, 0, 0, 0, 0);
    TrainingProgress {
        if (riding < 0 || riding > 10_000_000 || jumps < 0 || jumps > 10000 || speedLevel < 0 || speedLevel > 10 || jumpLevel < 0 || jumpLevel > 10 || cooldownUntil < 0)
            throw new IllegalArgumentException("Invalid animal training progress");
    }
    static TrainingProgress decode(String raw) {
        if (raw == null) return EMPTY;
        String[] fields = raw.split(";", -1);
        if (fields.length != 6 || !fields[0].equals("1")) throw new IllegalArgumentException("Unknown animal training data");
        return new TrainingProgress(Long.parseLong(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Long.parseLong(fields[5]));
    }
    String encode() {
        return "1;" + riding + ";" + jumps + ";" + speedLevel + ";" + jumpLevel + ";" + cooldownUntil;
    }
    TrainingProgress add(boolean ride, long units, long target, int maxLevel, long now, long cooldownMillis) {
        if (units <= 0 || now < cooldownUntil || (ride ? speedLevel : jumpLevel) >= maxLevel) return this;
        long progress = Math.min(target, (ride ? riding : jumps) + units);
        boolean level = progress >= target;
        return new TrainingProgress(ride ? (level ? 0 : progress) : riding, ride ? jumps : (int) (level ? 0 : progress), speedLevel + (ride && level ? 1 : 0), jumpLevel + (!ride && level ? 1 : 0), level ? now + cooldownMillis : cooldownUntil);
    }
}
