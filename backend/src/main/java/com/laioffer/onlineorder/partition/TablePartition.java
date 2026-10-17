package com.laioffer.onlineorder.partition;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * One partition of a range-partitioned table, as PostgreSQL reports its bound ({@code pg_get_expr(relpartbound)}).
 *
 * @param from inclusive lower bound; null for MINVALUE and for the default partition
 * @param to   exclusive upper bound; null for MAXVALUE and for the default partition
 */
public record TablePartition(String name, LocalDateTime from, LocalDateTime to, boolean isDefault) {

    private static final Pattern RANGE = Pattern.compile(
            "FOR VALUES FROM \\((.+)\\) TO \\((.+)\\)", Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();


    /**
     * @param bound {@code FOR VALUES FROM ('2026-10-01 00:00:00') TO ('2026-11-01 00:00:00')} or {@code DEFAULT}
     * @throws IllegalArgumentException for a bound that is neither (a list or hash partition)
     */
    public static TablePartition parse(String name, String bound) {
        if (bound == null || "DEFAULT".equalsIgnoreCase(bound.trim())) {
            return new TablePartition(name, null, null, true);
        }
        Matcher matcher = RANGE.matcher(bound.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a range partition bound: " + bound);
        }
        return new TablePartition(name, parseValue(matcher.group(1)), parseValue(matcher.group(2)), false);
    }


    private static LocalDateTime parseValue(String value) {
        String trimmed = value.trim();
        if ("MINVALUE".equalsIgnoreCase(trimmed) || "MAXVALUE".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (trimmed.length() < 2 || trimmed.charAt(0) != '\'' || trimmed.charAt(trimmed.length() - 1) != '\'') {
            throw new IllegalArgumentException("Unexpected partition bound value: " + value);
        }
        return LocalDateTime.parse(trimmed.substring(1, trimmed.length() - 1), TIMESTAMP);
    }


    /**
     * Whether this range partition shares any instant with {@code [start, end)}. The default partition overlaps
     * nothing.
     */
    public boolean overlaps(LocalDateTime start, LocalDateTime end) {
        if (isDefault) {
            return false;
        }
        boolean startsBeforeEnd = from == null || from.isBefore(end);
        boolean endsAfterStart = to == null || to.isAfter(start);
        return startsBeforeEnd && endsAfterStart;
    }
}
