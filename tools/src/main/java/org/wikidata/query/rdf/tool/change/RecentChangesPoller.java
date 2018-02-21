package org.wikidata.query.rdf.tool.change;

import static java.lang.Boolean.TRUE;
import static org.wikidata.query.rdf.tool.wikibase.WikibaseRepository.inputDateFormat;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.query.rdf.tool.exception.RetryableException;
import org.wikidata.query.rdf.tool.wikibase.Continue;
import org.wikidata.query.rdf.tool.wikibase.RecentChangeResponse;
import org.wikidata.query.rdf.tool.wikibase.RecentChangeResponse.RecentChange;
import org.wikidata.query.rdf.tool.wikibase.WikibaseRepository;

import com.google.common.collect.ImmutableList;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Polls recent changes. The first batch polls the recent changes api starting
 * at the firstStartTime. Subsequent batches either start on the last continue
 * of the previous poll, or, if there isn't a continue, then they start one
 * second after the last first start time.
 */
@SuppressFBWarnings("FCCD_FIND_CLASS_CIRCULAR_DEPENDENCY")
public class RecentChangesPoller implements Change.Source<RecentChangesPoller.Batch> {
    private static final Logger log = LoggerFactory.getLogger(RecentChangesPoller.class);

    /**
     * How many IDs we keep in seen IDs map.
     * Should be enough for 1 hour for speeds up to 100 updates/s.
     * If we ever get faster updates, we'd have to bump this.
     */
    private static final int MAX_SEEN_IDS = 100 * 60 * 60;
    /**
     * Wikibase repository to poll.
     */
    private final WikibaseRepository wikibase;
    /**
     * The first start time to poll.
     */
    private final Date firstStartTime;
    /**
     * Size of the batches to poll against wikibase.
     */
    private final int batchSize;
    /**
     * Set of the IDs we've seen before.
     * We have to use map here because LinkedHashMap has removeEldestEntry
     * and it does not implement Set. So we're using
     * Map with boolean values as Set.
     */
    private final Map<Long, Boolean> seenIDs;
    /**
     * How far back should we tail with secondary tailing poller.
     * The value is in milliseconds.
     * 0 or negative means no tailing poller;
     */
    private final int tailSeconds;
    /**
     * How much to back off for recent fetches, in seconds.
     */
    private static final int BACKOFF_TIME = 10;
    /**
     * How old should the change be to not apply backoff.
     * The number is in minutes.
     */
    private static final int BACKOFF_THRESHOLD = 2;

    /**
     * Queue for communicating with tailing updater.
     */
    private final BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(100);

    /**
     * Optional tailing poller.
     * This will be instantiated only if we were asked for it (tailSeconds > 0)
     * and when the poller catches up enough so that it is beyond the tailing gap
     * itself.
     * Note that tailing poller runs in different thread.
     */
    private TailingChangesPoller tailPoller;

    /**
     * Whether to use backoff.
     */
    private boolean useBackoff = true;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "TODO: move to LocalDateTime")
    public RecentChangesPoller(WikibaseRepository wikibase, Date firstStartTime,
            int batchSize, Map<Long, Boolean> seenIDs, int tailSeconds) {
        this.wikibase = wikibase;
        this.firstStartTime = firstStartTime;
        this.batchSize = batchSize;
        this.seenIDs = seenIDs;
        this.tailSeconds = tailSeconds;
    }

    public RecentChangesPoller(WikibaseRepository wikibase, Date firstStartTime, int batchSize) {
        this(wikibase, firstStartTime, batchSize, createSeenMap(), -1);
    }

    public RecentChangesPoller(WikibaseRepository wikibase, Date firstStartTime, int batchSize, int tailSeconds) {
        this(wikibase, firstStartTime, batchSize, createSeenMap(), tailSeconds);
    }

    /**
     * Whether to use backoff.
     * @param useBackoff
     */
    public void setBackoff(boolean useBackoff) {
        this.useBackoff = useBackoff;
    }
    /**
     * Create map of seen IDs.
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<Long, Boolean> createSeenMap() {
        // Create hash map with max size, evicting oldest ID
        final Map<Long, Boolean> map = new LinkedHashMap(MAX_SEEN_IDS, .75F, false) {
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_SEEN_IDS;
            }
        };
        return Collections.synchronizedMap(map);
    }

    @Override
    public Batch firstBatch() throws RetryableException {
        return batch(firstStartTime, null);
    }

    @Override
    public Batch nextBatch(Batch lastBatch) throws RetryableException {
        Batch newBatch = batch(lastBatch.leftOffDate, lastBatch);
        if (tailSeconds > 0) {
            // Check if tail poller has something to say.
            newBatch = checkTailPoller(newBatch);
        }
        return newBatch;
    }

    /**
     * Check if we need to poll something from secondary poller queue.
     * @param lastBatch
     * @return
     */
    private Batch checkTailPoller(Batch lastBatch) {
        if (tailSeconds <= 0) {
            // not enabled, we're done here
            return lastBatch;
        }

        if (tailPoller == null) {
            if (lastBatch.leftOffDate().before(DateUtils.addSeconds(new Date(), -tailSeconds))) {
                // still not caught up, do nothing
                return lastBatch;
            }
            // We don't have poller yet - start it
            log.info("Started trailing poller with gap of {} seconds", tailSeconds);
            // Create new poller starting back tailSeconds and same IDs map.
            final RecentChangesPoller poller = new RecentChangesPoller(wikibase, DateUtils.addSeconds(new Date(), -tailSeconds), batchSize, seenIDs, -1);
            poller.setBackoff(false);
            tailPoller = new TailingChangesPoller(poller, queue, tailSeconds);
            tailPoller.start();
        } else {
            tailPoller.setPollerTs(lastBatch.leftOffDate().getTime());
            final Batch queuedBatch = queue.poll();
            if (queuedBatch != null) {
                log.info("Merging {} changes from trailing queue", queuedBatch.changes().size());
                return lastBatch.merge(queuedBatch);
            }
        }
        return lastBatch;
    }

    /**
     * Batch implementation for this poller.
     */
    public static final class Batch extends Change.Batch.AbstractDefaultImplementation {
        /**
         * The date where we last left off.
         */
        private final Date leftOffDate;

        /**
         * Continue from last request. Can be null.
         */
        private final Continue lastContinue;

        /**
         * Flag that states we have had changes, even though we didn't return them.
         */
        private boolean hasChanges;

        /**
         * A batch that will next continue using the continue parameter.
         */
        private Batch(ImmutableList<Change> changes, long advanced,
                String leftOff, Date nextStartTime, Continue lastContinue) {
            super(changes, advanced, leftOff);
            leftOffDate = nextStartTime;
            this.lastContinue = lastContinue;
        }

        /**
         * Set changes status.
         * @param changes Whether we really had changes or not.
         */
        public void hasChanges(boolean changes) {
            hasChanges = changes;
        }

        @Override
        public boolean hasAnyChanges() {
            if (hasChanges) {
                return true;
            }
            return super.hasAnyChanges();
        }


        @Override
        public String advancedUnits() {
            return "milliseconds";
        }

        @Override
        @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "TODO: move to LocalDateTime")
        public Date leftOffDate() {
            return leftOffDate;
        }

        @Override
        public String leftOffHuman() {
            if (lastContinue != null) {
                return WikibaseRepository.inputDateFormat().format(leftOffDate)
                    + " (next: " + lastContinue.getRcContinue() + ")";
            } else {
                return WikibaseRepository.inputDateFormat().format(leftOffDate);
            }
        }

        /**
         * Merge this batch with another batch.
         * @param another
         * @return
         */
        @SuppressFBWarnings(value = "OCP_OVERLY_CONCRETE_PARAMETER", justification = "Type seems semantically correct")
        public Batch merge(Batch another) {
            final ImmutableList<Change> newChanges = new ImmutableList.Builder<Change>()
                    .addAll(another.changes())
                    .addAll(changes())
                    .build();
            return new Batch(newChanges, advanced(), leftOffDate.toString(), leftOffDate, lastContinue);
        }

        /**
         * Get continue object.
         * @return
         */
        public Continue getLastContinue() {
            return lastContinue;
        }
    }

    /**
     * Check whether change is very recent.
     * If it is we need to backoff to allow capturing unsettled DB changes.
     * @param nextStartTime
     * @return
     */
    private boolean changeIsRecent(Date nextStartTime) {
        return nextStartTime.after(DateUtils.addMinutes(new Date(), -BACKOFF_THRESHOLD));
    }

    /**
     * Fetch recent changes from Wikibase.
     * If we're close to current time, we back off a bit from last timestamp,
     * and fetch by timestamp. If it's back in the past, we fetch by continuation.
     * @param lastNextStartTime
     * @param lastBatch
     * @return
     * @throws RetryableException on fetch failure
     */
    private RecentChangeResponse fetchRecentChanges(Date lastNextStartTime, Batch lastBatch) throws RetryableException {
        if (useBackoff && changeIsRecent(lastNextStartTime)) {
            return wikibase.fetchRecentChangesByTime(
                    DateUtils.addSeconds(lastNextStartTime, -BACKOFF_TIME),
                    batchSize);
        } else {
            return wikibase.fetchRecentChanges(lastNextStartTime,
                    lastBatch != null ? lastBatch.getLastContinue() : null,
                    batchSize);
        }
    }

    /**
     * Parse a batch from the api result.
     *
     * @throws RetryableException on parse failure
     */
    @SuppressWarnings({"checkstyle:npathcomplexity", "checkstyle:cyclomaticcomplexity"})
    private Batch batch(Date lastNextStartTime, Batch lastBatch) throws RetryableException {
        RecentChangeResponse recentChanges = fetchRecentChanges(lastNextStartTime, lastBatch);
        // Using LinkedHashMap here so that changes came out sorted by order of arrival
        Map<String, Change> changesByTitle = new LinkedHashMap<>();
        Continue nextContinue = recentChanges.getContinue();
        long nextStartTime = lastNextStartTime.getTime();
        List<RecentChange> result = recentChanges.getQuery().getRecentChanges();

        for (RecentChange rc : result) {
            // Does not matter if the change matters for us or not, it
            // still advances the time since we've seen it.
            nextStartTime = Math.max(nextStartTime, rc.getTimestamp().getTime());
            if (!wikibase.isEntityNamespace(rc.getNs())) {
                log.info("Skipping change in irrelevant namespace:  {}", rc);
                continue;
            }
            if (!wikibase.isValidEntity(rc.getTitle())) {
                log.info("Skipping change with bogus title:  {}", rc.getTitle());
                continue;
            }
            if (seenIDs.containsKey(rc.getRcId())) {
                // This change was in the last batch
                log.debug("Skipping repeated change with rcid {}", rc.getRcId());
                continue;
            }
            seenIDs.put(rc.getRcId(), TRUE);
// Looks like we can not rely on changes appearing in order, so we have to take them all and let SPARQL
// sort out the dupes.
//                if (continueChange != null && rcid < continueChange.rcid()) {
//                    // We've already seen this change, since it has older rcid - so skip it
//                    continue;
//                }
            Change change;
            if (rc.getType().equals("log") && rc.getRevId() == 0) {
                // Deletes should always be processed, so put negative revision
                change = new Change(rc.getTitle(), -1L, rc.getTimestamp(), rc.getRcId());
            } else {
                change = new Change(rc.getTitle(), rc.getRevId(), rc.getTimestamp(), rc.getRcId());
            }
            /*
             * Remove duplicate changes by title keeping the latest
             * revision. Note that negative revision means always update, so those
             * are kept.
             */
            Change dupe = changesByTitle.put(change.entityId(), change);
            if (dupe != null && (dupe.revision() > change.revision() || dupe.revision() < 0)) {
                // need to remove so that order will be correct
                changesByTitle.remove(change.entityId());
                changesByTitle.put(change.entityId(), dupe);
            }
        }
        final ImmutableList<Change> changes = ImmutableList.copyOf(changesByTitle.values());
        // Backoff overflow is when:
        // a. We use backoff
        // b. We got full batch of changes.
        // c. None of those were new changes.
        // In this case, sleeping and trying again is obviously useless.
        final boolean backoffOverflow = useBackoff && changes.size() == 0 && result.size() >= batchSize;
        if (backoffOverflow) {
            // We have a problem here - due to backoff, we did not fetch any new items
            // Try to advance one second, even though we risk to lose a change - in hope
            // that trailing poller will pick them up.
            nextStartTime += 1000;
            log.info("Backoff overflow, advancing next time to {}", inputDateFormat().format(new Date(nextStartTime)));
        }

        if (changes.size() != 0) {
            log.info("Got {} changes, from {} to {}", changes.size(),
                    changes.get(0).toString(),
                    changes.get(changes.size() - 1).toString());
        } else {
            log.info("Got no real changes");
        }

        // Show the user the polled time - one second because we can't
        // be sure we got the whole second
        String upTo = inputDateFormat().format(new Date(nextStartTime - 1000));
        long advanced = nextStartTime - lastNextStartTime.getTime();
        Batch batch = new Batch(changes, advanced, upTo, new Date(nextStartTime), nextContinue);
        if (backoffOverflow && nextContinue != null) {
            // We will not sleep if continue is provided.
            log.info("Got only old changes, next is: {}", nextContinue);
            batch.hasChanges(true);
        }
        return batch;
    }

    @Override
    public void close() throws Exception {
        // TODO: Do we need to do anything here?
    }
}
