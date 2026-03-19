package com.routeme.app

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeInstrumentationTest {

    @Test
    fun appContext_usesRouteMePackage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(appContext.packageName.startsWith("com.routeme.app"))
    }

    @Test
    fun mainActivity_launchesSuccessfully() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
        scenario.close()
    }

    @Test
    fun mainActivity_displaysTopToolbar() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<View>(R.id.topToolbar)
            assertNotNull(toolbar)
            assertTrue(toolbar.visibility == View.VISIBLE)
        }
        scenario.close()
    }
}
