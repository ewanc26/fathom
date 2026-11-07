package uk.ewancroft.fathom.shared.math

/**
 * Floor division and modulo for integers.
 *
 * Negative epoch offsets are legal in RFC 3339 and a listener west of
 * Greenwich produces negative shifted timestamps, so bucketing has to round
 * towards negative infinity rather than towards zero. Kotlin's `/` and `%`
 * truncate, and `java.lang.Math` is not available in common code, so both are
 * spelled out here once and shared by the date code.
 */
internal fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val exact = value % divisor == 0L
    val signsDiffer = (value < 0L) != (divisor < 0L)
    return if (!exact && signsDiffer) quotient - 1L else quotient
}

internal fun floorMod(value: Long, divisor: Long): Long =
    value - floorDiv(value, divisor) * divisor
