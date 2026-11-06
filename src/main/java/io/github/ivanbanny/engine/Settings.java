package io.github.ivanbanny.engine;

/** Null seed requests a fresh random seed from the engine. */
public record Settings(
        double cfgScale, int shapeSteps, int materialSteps, Long seed, int previewEverySteps) {
    public static final long MAX_SEED = (1L << 53) - 1;
    public static final Settings DEFAULT = new Settings(5, 30, 30, null, 3);

    public Settings {
        if (!Double.isFinite(cfgScale) || cfgScale < 0 || cfgScale > 20)
            throw new IllegalArgumentException("CFG must be 0..20");
        if (shapeSteps < 1 || shapeSteps > 200 || materialSteps < 1 || materialSteps > 200)
            throw new IllegalArgumentException("Steps must be 1..200");
        if (seed != null && (seed < 0 || seed > MAX_SEED))
            throw new IllegalArgumentException("Seed must be 0.." + MAX_SEED);
        if (previewEverySteps < 0 || previewEverySteps > 10000)
            throw new IllegalArgumentException("Preview cadence must be 0..10000");
    }

    public String describe() {
        return "CFG "
                + cfgScale
                + "; steps "
                + shapeSteps
                + "/"
                + materialSteps
                + "; seed "
                + (seed == null ? "random" : seed)
                + "; preview every "
                + previewEverySteps
                + " steps";
    }
}
