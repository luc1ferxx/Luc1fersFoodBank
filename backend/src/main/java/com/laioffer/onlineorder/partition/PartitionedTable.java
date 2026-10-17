package com.laioffer.onlineorder.partition;


import java.time.LocalDate;
import java.util.Locale;


/**
 * The tables V10 partitions by month on PostgreSQL. Each has partitions named {@code <table>_pYYYY_MM} for
 * {@code [first of the month, first of the next month)} of {@link #partitionColumn()} and a DEFAULT partition
 * {@code <table>_default} for rows outside every range.
 */
public enum PartitionedTable {

    AUDIT_LOG("audit_log", "occurred_at", false),
    ORDER_STATUS_HISTORY("order_status_history", "occurred_at", false),
    /**
     * Every partition also needs UNIQUE (consumer_name, dedup_key), see ProcessedEventDedupImpl.
     */
    PROCESSED_EVENTS("processed_events", "processed_at", true);

    private final String tableName;
    private final String partitionColumn;
    private final boolean dedupKeyPerPartition;


    PartitionedTable(String tableName, String partitionColumn, boolean dedupKeyPerPartition) {
        this.tableName = tableName;
        this.partitionColumn = partitionColumn;
        this.dedupKeyPerPartition = dedupKeyPerPartition;
    }


    public String tableName() {
        return tableName;
    }


    public String partitionColumn() {
        return partitionColumn;
    }


    public boolean dedupKeyPerPartition() {
        return dedupKeyPerPartition;
    }


    public String defaultPartitionName() {
        return tableName + "_default";
    }


    /**
     * @param month any day of the month
     */
    public String partitionName(LocalDate month) {
        return String.format(Locale.ROOT, "%s_p%04d_%02d", tableName, month.getYear(), month.getMonthValue());
    }


    /**
     * The unique (consumer_name, dedup_key) index of a processed_events partition.
     */
    static String dedupIndexName(String partition) {
        return partition + "_dedup_key";
    }
}
