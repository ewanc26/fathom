package uk.ewancroft.fathom.shared.auth.oauth

/** The one clock the OAuth engine needs, from the JVM/Android system clock. */
actual fun epochSeconds(): Long = System.currentTimeMillis() / 1_000L
