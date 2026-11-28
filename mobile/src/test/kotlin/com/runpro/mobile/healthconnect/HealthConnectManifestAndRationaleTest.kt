package com.runpro.mobile.healthconnect

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import com.runpro.core.sync.healthconnect.HealthConnectPort
import com.runpro.core.sync.healthconnect.HealthConnectRun
import com.runpro.mobile.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectManifestAndRationaleTest {
    private val originalFactory = PermissionsRationaleActivity.portFactory

    @After
    fun tearDown() {
        PermissionsRationaleActivity.portFactory = originalFactory
    }

    @Test
    fun `manifest declares write-only Health Connect permissions and both rationale intents`() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue(requested.containsAll(HealthConnectPermissions.ALL_WRITE_PERMISSIONS))
        assertTrue(
            "RunPro must not request Health Connect read permissions",
            requested.none { it.startsWith("android.permission.health.READ_") }
        )

        val legacyIntent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
            .setPackage(context.packageName)
        val legacyResolved = pm.resolveActivity(legacyIntent, 0)
        assertNotNull("Legacy Health Connect rationale intent must resolve", legacyResolved)
        assertEquals(
            PermissionsRationaleActivity::class.java.name,
            legacyResolved!!.activityInfo.name
        )

        val api34Intent = Intent("android.intent.action.VIEW_PERMISSION_USAGE")
            .addCategory("android.intent.category.HEALTH_PERMISSIONS")
            .setPackage(context.packageName)
        val api34Resolved = pm.resolveActivity(api34Intent, 0)
        assertNotNull("Android 14+ permission usage alias must resolve", api34Resolved)
        assertEquals(
            "android.permission.START_VIEW_PERMISSION_USAGE",
            api34Resolved!!.activityInfo.permission
        )
        assertEquals(
            PermissionsRationaleActivity::class.java.name,
            api34Resolved.activityInfo.targetActivity
        )
    }

    @Test
    @Config(sdk = [30])
    fun `rationale activity launches on API 30 and reflects unavailable Health Connect`() {
        PermissionsRationaleActivity.portFactory = {
            StaticPort(HealthConnectPort.Availability.UNAVAILABLE)
        }
        val controller = Robolectric.buildActivity(
            PermissionsRationaleActivity::class.java,
            Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
        ).setup()
        val activity = controller.get()

        val body = activity.findViewById<TextView>(R.id.health_connect_rationale_body)
        val status = activity.findViewById<TextView>(R.id.health_connect_status)
        val grant = activity.findViewById<Button>(R.id.health_connect_grant_button)
        val export = activity.findViewById<Button>(R.id.health_connect_export_button)

        assertTrue(body.text.toString().contains("Health Connect"))
        assertEquals(
            activity.getString(R.string.health_connect_status_unavailable),
            status.text.toString()
        )
        assertFalse(grant.isEnabled)
        assertFalse(export.isEnabled)
        controller.destroy()
    }

    @Test
    fun `rationale activity enables actions when Health Connect is available`() {
        PermissionsRationaleActivity.portFactory = {
            StaticPort(HealthConnectPort.Availability.AVAILABLE)
        }
        val controller = Robolectric.buildActivity(
            PermissionsRationaleActivity::class.java,
            Intent("android.intent.action.VIEW_PERMISSION_USAGE")
                .addCategory("android.intent.category.HEALTH_PERMISSIONS")
        ).setup()
        val activity = controller.get()

        val grant = activity.findViewById<Button>(R.id.health_connect_grant_button)
        val export = activity.findViewById<Button>(R.id.health_connect_export_button)
        val status = activity.findViewById<TextView>(R.id.health_connect_status)

        assertTrue(grant.isEnabled)
        assertTrue(export.isEnabled)
        assertEquals(
            activity.getString(R.string.health_connect_status_ready),
            status.text.toString()
        )
        assertTrue(HealthConnectClient.SDK_AVAILABLE > 0)
        controller.destroy()
    }

    private class StaticPort(private val state: HealthConnectPort.Availability) :
        HealthConnectPort {
        override fun availability(): HealthConnectPort.Availability = state
        override suspend fun write(run: HealthConnectRun): HealthConnectPort.WriteOutcome =
            HealthConnectPort.WriteOutcome.Written
    }
}
