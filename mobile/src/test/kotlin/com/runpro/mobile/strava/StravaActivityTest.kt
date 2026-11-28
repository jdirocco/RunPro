package com.runpro.mobile.strava

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.children
import com.runpro.core.sync.strava.MultipartFilePart
import com.runpro.core.sync.strava.StravaApiClient
import com.runpro.core.sync.strava.StravaConfig
import com.runpro.core.sync.strava.StravaHttpResponse
import com.runpro.core.sync.strava.StravaHttpTransport
import com.runpro.core.sync.strava.StravaStores
import com.runpro.core.sync.strava.StravaTokens
import com.runpro.core.sync.strava.StravaUploader
import com.runpro.mobile.BuildConfig
import com.runpro.mobile.FitReceiverService
import com.runpro.mobile.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StravaActivityTest {
    private val originalFactory = StravaUploadRunner.uploaderFactory
    private val originalDispatcher = StravaUploadRunner.dispatcher
    private val context: Context = RuntimeEnvironment.getApplication()
    private val transport = FakeTransport()
    private val configured = StravaConfig(clientId = "205420", clientSecret = "test-secret")

    @Before
    fun setUp() {
        StravaUploadRunner.dispatcher = Dispatchers.Unconfined
        StravaUploadRunner.stateDir(context).deleteRecursively()
        FitReceiverService.runs(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        StravaUploadRunner.uploaderFactory = originalFactory
        StravaUploadRunner.dispatcher = originalDispatcher
    }

    @Test
    fun `manifest requests internet and routes launcher and OAuth callback to the Strava screen`() {
        val pm = context.packageManager
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
        assertTrue(android.Manifest.permission.INTERNET in requested)

        val launcher = pm.getLaunchIntentForPackage(context.packageName)
        assertEquals(StravaActivity::class.java.name, launcher?.component?.className)

        val callback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("runpro://${BuildConfig.STRAVA_CALLBACK_HOST}/oauth/callback?state=s&code=c")
        ).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        val resolved = requireNotNull(pm.resolveActivity(callback, 0))
        assertEquals(StravaActivity::class.java.name, resolved.activityInfo.name)
    }

    @Test
    fun `a build without Strava credentials shows how to configure it and disables actions`() {
        useConfig(StravaConfig(clientId = "", clientSecret = ""))
        val controller = Robolectric.buildActivity(StravaActivity::class.java).setup()
        val activity = controller.get()

        assertEquals(
            activity.getString(R.string.strava_status_not_configured),
            activity.findViewById<TextView>(R.id.strava_status).text.toString()
        )
        assertFalse(activity.findViewById<Button>(R.id.strava_connect_button).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.strava_upload_button).isEnabled)
        assertFalse(activity.findViewById<Switch>(R.id.strava_auto_upload_switch).isEnabled)
        controller.destroy()
    }

    @Test
    fun `connect opens Strava authorization and the callback stores the tokens`() {
        useConfig(configured)
        val controller = Robolectric.buildActivity(StravaActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<Button>(R.id.strava_connect_button).performClick()
        val opened = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        val authorize = opened.data.toString()
        assertTrue(authorize.startsWith("https://www.strava.com/oauth/mobile/authorize?"))
        val state = Uri.parse(authorize).getQueryParameter("state")

        controller.newIntent(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "runpro://localhost/oauth/callback?state=$state&code=c1&scope=read,activity:write"
                )
            )
        )

        assertEquals(1, transport.tokenCalls)
        assertEquals(
            activity.getString(R.string.strava_status_connected_as, "Test Runner"),
            activity.findViewById<TextView>(R.id.strava_status).text.toString()
        )
        assertTrue(activity.findViewById<Button>(R.id.strava_upload_button).isEnabled)
        assertTrue(File(StravaUploadRunner.stateDir(context), "tokens.json").isFile)
        controller.destroy()
    }

    @Test
    fun `upload pending posts the run, shows its Strava link and auto upload is opt-in`() {
        useConfig(configured)
        StravaStores.fileBacked(StravaUploadRunner.stateDir(context).toPath()).tokens.save(
            StravaTokens("access", "refresh", expiresAtEpochSeconds = Long.MAX_VALUE / 2)
        )
        val runs = FitReceiverService.runs(context).apply { mkdirs() }
        File(runs, "run-1.fit").writeBytes(byteArrayOf(1, 2, 3))
        val controller = Robolectric.buildActivity(StravaActivity::class.java).setup()
        val activity = controller.get()

        val auto = activity.findViewById<Switch>(R.id.strava_auto_upload_switch)
        assertFalse(auto.isChecked)
        auto.performClick()
        assertTrue(StravaUploadRunner.uploader(context).account.preferences().autoUpload)

        activity.findViewById<Button>(R.id.strava_upload_button).performClick()
        assertEquals(1, transport.uploadCalls)
        assertEquals("run", transport.lastFields["activity_type"])
        assertEquals("fit", transport.lastFields["data_type"])

        val rows = activity.findViewById<LinearLayout>(R.id.strava_runs).children.toList()
        assertEquals(
            activity.getString(R.string.strava_run_uploaded, "run-1.fit"),
            (rows[0] as TextView).text.toString()
        )
        (rows[1] as Button).performClick()
        val opened = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals("https://www.strava.com/activities/4321", opened.data.toString())
        assertTrue(File(runs, "run-1.fit").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        controller.destroy()
    }

    private fun useConfig(config: StravaConfig) {
        StravaUploadRunner.uploaderFactory = { ctx ->
            StravaUploader(
                runsDir = FitReceiverService.runs(ctx).toPath(),
                config = config,
                client = StravaApiClient(config, transport),
                stores = StravaStores.fileBacked(StravaUploadRunner.stateDir(ctx).toPath()),
                pollPolicy = StravaUploader.PollPolicy(initialDelayMillis = 0L, sleepMillis = {})
            )
        }
    }

    private class FakeTransport : StravaHttpTransport {
        var tokenCalls = 0
        var uploadCalls = 0
        var lastFields: Map<String, String> = emptyMap()

        override fun postForm(url: String, formFields: Map<String, String>): StravaHttpResponse {
            tokenCalls++
            return StravaHttpResponse(
                200,
                """{"expires_at":4102444800,"refresh_token":"r","access_token":"a",""" +
                    """"athlete":{"id":1,"firstname":"Test","lastname":"Runner"}}"""
            )
        }

        override fun postMultipart(
            url: String,
            headers: Map<String, String>,
            fields: Map<String, String>,
            filePart: MultipartFilePart
        ): StravaHttpResponse {
            uploadCalls++
            lastFields = fields
            return StravaHttpResponse(
                201,
                """{"id":99,"status":"Your activity is ready.","error":null,"activity_id":4321}"""
            )
        }

        override fun get(url: String, headers: Map<String, String>): StravaHttpResponse =
            StravaHttpResponse(500, """{"message":"unexpected poll"}""")
    }
}
