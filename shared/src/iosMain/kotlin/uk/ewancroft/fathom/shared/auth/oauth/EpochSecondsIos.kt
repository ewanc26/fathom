package uk.ewancroft.fathom.shared.auth.oauth

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

/** The one clock the OAuth engine needs: posix time's epoch seconds. */
@OptIn(ExperimentalForeignApi::class)
actual fun epochSeconds(): Long = time(null)
