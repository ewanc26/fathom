package uk.ewancroft.fathom

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uk.ewancroft.fathom.shared.auth.OAuthCallback
import uk.ewancroft.fathom.shared.auth.OAuthRedirect
import uk.ewancroft.fathom.shared.auth.PendingAuthorization
import uk.ewancroft.fathom.ui.FathomRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveOAuthCallback(intent)
        setContent { FathomRoot() }
    }

    /**
     * A callback arriving while Fathom already runs lands here, because the
     * activity is singleTask. A cold start delivers the same intent to
     * onCreate above, so both paths funnel through [receiveOAuthCallback].
     *
     * Parsing lives in the shared core ([OAuthRedirect]); the only Android
     * responsibility is turning the framework intent into its URI string.
     * The parsed result is retained by [PendingAuthorization] for the login
     * flow (issue #9) to claim. Nothing else happens yet: no flow can have
     * started, so the app never claims a login it cannot verify.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveOAuthCallback(intent)
    }

    private fun receiveOAuthCallback(intent: Intent?) {
        val uri = intent?.dataString ?: return
        val parsed = OAuthRedirect.parse(uri)
        if (parsed is OAuthCallback.Authorised) {
            PendingAuthorization.retain(parsed)
        }
    }
}
