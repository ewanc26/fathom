package uk.ewancroft.fathom.shared

/**
 * The copy and addresses the About sections share.
 *
 * Version and build number are deliberately absent: those must come from the
 * actual bundle, not from a literal that can drift from the store build.
 */
object AboutInfo {
    const val APP_NAME: String = "Fathom"
    const val DESCRIPTION: String = "A native teal.fm dashboard for iOS and Android."
    const val COPYRIGHT: String = "© 2026 Ewan Croft"

    const val SOURCE_URL: String = "https://github.com/ewanc26/fathom"
    const val LICENCE_NAME: String = "GNU Affero General Public License v3.0"
    const val LICENCE_URL: String = "https://www.gnu.org/licenses/agpl-3.0.html"
    const val PRIVACY_URL: String = "https://fathom.ewancroft.uk/privacy"
    const val TERMS_URL: String = "https://fathom.ewancroft.uk/terms"

    /**
     * The notices a binary has to carry for the libraries it actually ships.
     *
     * The full AGPL text is bundled separately as `AGPL-3.0.txt`; this list
     * names the third-party projects and their licences so the About screen
     * is useful without a network request.
     */
    const val THIRD_PARTY_NOTICES: String =
        "Fathom is built with the following open-source projects:\n\n" +
            "• Kotlin, kotlinx.coroutines and kotlinx.serialization — Apache License 2.0, " +
            "JetBrains s.r.o.\n" +
            "• AndroidX, Jetpack Compose and Material Components — Apache License 2.0, " +
            "The Android Open Source Project.\n" +
            "• OkHttp — Apache License 2.0, Square, Inc.\n" +
            "• Kotlin/Native runtime — Apache License 2.0, JetBrains s.r.o.\n\n" +
            "The full licence texts are available from each project and at " +
            "https://www.apache.org/licenses/LICENSE-2.0. " +
            "Fathom itself is licensed under the GNU Affero General Public License v3.0."
}
