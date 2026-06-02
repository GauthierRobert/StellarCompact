package com.stellarcompact.engine.state;

/**
 * Per-faction status of a tech node (game-design 03 Research, 06 tech tree).
 */
public enum TechStatus {
    /** Not yet started; prerequisites may or may not be met. */
    LOCKED,
    /** Currently being researched; {@code progress} advances toward research time. */
    RESEARCHING,
    /** Unlocked; its multipliers/unlocks apply. */
    UNLOCKED
}
