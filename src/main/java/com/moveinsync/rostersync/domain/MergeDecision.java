package com.moveinsync.rostersync.domain;

import java.util.List;

/**
 * The result of comparing an incoming change with the stored state.
 * A sealed interface: the compiler forces every caller to handle every outcome.
 */
public sealed interface MergeDecision
        permits MergeDecision.Apply, MergeDecision.Stale, MergeDecision.Duplicate, MergeDecision.Conflict {

    Outcome outcome();

    enum Outcome {
        /** A new state was written and a change event will be published. */
        APPLIED,
        /** The version moved forward but no data changed (e.g. export re-sends same data). No event. */
        VERSION_ONLY,
        /** Older than what we already have: ignored. */
        STALE,
        /** Same version, same content: a replay. No-op. */
        DUPLICATE,
        /** Same version but different content: the source broke its own versioning. Rejected and reported. */
        CONFLICT
    }

    /**
     * @param eventType null when nothing downstream needs to know (VERSION_ONLY)
     * @param changedFields field NAMES only, never values
     */
    record Apply(EmployeeState newState, ChangeEventType eventType, List<String> changedFields,
                 boolean isInsert) implements MergeDecision {
        @Override
        public Outcome outcome() {
            return eventType == null ? Outcome.VERSION_ONLY : Outcome.APPLIED;
        }
    }

    record Stale(long storedVersion) implements MergeDecision {
        @Override
        public Outcome outcome() {
            return Outcome.STALE;
        }
    }

    record Duplicate() implements MergeDecision {
        @Override
        public Outcome outcome() {
            return Outcome.DUPLICATE;
        }
    }

    record Conflict(String reasonCode) implements MergeDecision {
        @Override
        public Outcome outcome() {
            return Outcome.CONFLICT;
        }
    }
}
