package com.laioffer.onlineorder.partition;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


import java.time.LocalDate;
import java.time.LocalDateTime;


class TablePartitionTests {

    @Test
    void parse_shouldReadRangeBoundsAsPostgresPrintsThem() {
        TablePartition partition = TablePartition.parse("audit_log_p2026_10",
                "FOR VALUES FROM ('2026-10-01 00:00:00') TO ('2026-11-01 00:00:00')");

        Assertions.assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), partition.from());
        Assertions.assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), partition.to());
        Assertions.assertFalse(partition.isDefault());
    }


    @Test
    void parse_shouldReadFractionsUnboundedEndsAndTheDefaultPartition() {
        TablePartition fraction = TablePartition.parse("p",
                "FOR VALUES FROM ('2026-10-01 00:00:00.5') TO (MAXVALUE)");
        Assertions.assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 500_000_000), fraction.from());
        Assertions.assertNull(fraction.to());

        TablePartition unboundedStart = TablePartition.parse("p", "FOR VALUES FROM (MINVALUE) TO ('2026-01-01 00:00:00')");
        Assertions.assertNull(unboundedStart.from());

        Assertions.assertTrue(TablePartition.parse("audit_log_default", "DEFAULT").isDefault());
        Assertions.assertTrue(TablePartition.parse("audit_log_default", null).isDefault());
    }


    @Test
    void parse_shouldRejectBoundsOfOtherPartitioningSchemes() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TablePartition.parse("p", "FOR VALUES IN ('a', 'b')"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TablePartition.parse("p", "FOR VALUES FROM (42) TO (43)"));
    }


    @Test
    void overlaps_shouldCompareHalfOpenRanges() {
        TablePartition october = TablePartition.parse("p",
                "FOR VALUES FROM ('2026-10-01 00:00:00') TO ('2026-11-01 00:00:00')");
        LocalDateTime september = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime octoberStart = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime november = LocalDateTime.of(2026, 11, 1, 0, 0);
        LocalDateTime december = LocalDateTime.of(2026, 12, 1, 0, 0);

        Assertions.assertTrue(october.overlaps(octoberStart, november));
        Assertions.assertTrue(october.overlaps(LocalDateTime.of(2026, 10, 15, 0, 0), december));
        Assertions.assertFalse(october.overlaps(november, december), "the upper bound is exclusive");
        Assertions.assertFalse(october.overlaps(september, octoberStart));
        Assertions.assertTrue(TablePartition.parse("p", "FOR VALUES FROM (MINVALUE) TO (MAXVALUE)")
                .overlaps(september, octoberStart));
        Assertions.assertFalse(TablePartition.parse("d", "DEFAULT").overlaps(september, december));
    }


    @Test
    void partitionedTable_shouldNamePartitionsByMonth() {
        Assertions.assertEquals("processed_events_p2026_03",
                PartitionedTable.PROCESSED_EVENTS.partitionName(LocalDate.of(2026, 3, 17)));
        Assertions.assertEquals("order_status_history_default",
                PartitionedTable.ORDER_STATUS_HISTORY.defaultPartitionName());
        Assertions.assertEquals("audit_log_p2026_12_dedup_key", PartitionedTable.dedupIndexName("audit_log_p2026_12"));
        Assertions.assertTrue(PartitionedTable.PROCESSED_EVENTS.dedupKeyPerPartition());
        Assertions.assertFalse(PartitionedTable.AUDIT_LOG.dedupKeyPerPartition());
        Assertions.assertEquals("processed_at", PartitionedTable.PROCESSED_EVENTS.partitionColumn());
    }
}
