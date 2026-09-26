package com.mybrowser.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of each movement, not its speed.
 *
 * Speed is easy to eyeball and easy to change by taste, which is how a motion system drifts. Shape
 * is what a user actually feels as "arriving" or "heaving itself in", and it is measurable, so these
 * tests hold it down: a surface the user opened has to be most of the way there almost immediately
 * and then brake, while motion a finger drives has to be able to continue from a speed rather than
 * replay a curve.
 *
 * The numbers are Material 3's own curve, evaluated at the same points, so a spec that stops being
 * the spec's curve fails here instead of on someone's phone.
 */
class BrowserMotionTest {

    private fun sample(spec: FiniteAnimationSpec<Float>, at: Float): Float {
        val tween = spec as? TweenSpec<Float> ?: error("not a curve: $spec")
        val easing: Easing = tween.easing ?: error("a curve is expected to name its easing")
        return easing.transform(at)
    }

    /** Material 3 `easing.emphasised decelerate`, written out here rather than reused. */
    private val md3Decelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)

    /** Material 3 `easing.emphasised accelerate`. */
    private val md3Accelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)

    @Test
    fun aPanelArrivesFrontLoadedAndBrakes() {
        // The property a spring cannot provide: a surface that is already there in the first tenth
        // of its duration and spends the rest of the time settling.
        for (spec in listOf(BrowserMotion.panelArrive<Float>(), BrowserMotion.windowArrive<Float>())) {
            val atOneTenth = sample(spec, .1f)
            assertTrue(
                "an arriving surface covers most of the distance at once, not a sixth of it: $atOneTenth",
                atOneTenth > .55f,
            )
            assertTrue("and is not already finished: $atOneTenth", atOneTenth < .75f)
            // Monotonic: it brakes rather than arriving and backing off.
            var previous = 0f
            for (step in 1..20) {
                val value = sample(spec, step / 20f)
                assertTrue("arrival must not go backwards at $step/20", value >= previous)
                previous = value
            }
            assertEquals("and settles exactly", 1f, sample(spec, 1f), 1e-4f)
        }
    }

    @Test
    fun aPanelLeavesOnTheAccelerateCurve() {
        // An exit is the mirror image of an arrival, and Material 3 shapes it the other way round:
        // accelerate is *back-loaded*, so the surface barely moves for its first half and then
        // clears out. That is the spec's shape, not a bug in it — which is why an exit gets a
        // shorter duration than the entrance it mirrors, and why the durations below are pinned in
        // the same test.
        for (spec in listOf(BrowserMotion.panelDepart<Float>(), BrowserMotion.windowDepart<Float>())) {
            val atOneTenth = sample(spec, .1f)
            assertTrue("an leaving surface lingers at first, per the curve: $atOneTenth", atOneTenth < .10f)
            val atHalf = sample(spec, .5f)
            assertTrue("and is still under way at the middle: $atHalf", atHalf < .25f)
            var previous = 0f
            for (step in 1..20) {
                val value = sample(spec, step / 20f)
                assertTrue("an exit must not go backwards at $step/20", value >= previous)
                previous = value
            }
            assertEquals("and ends at the edge", 1f, sample(spec, 1f), 1e-4f)
        }
    }

    @Test
    fun everyExitIsShorterThanTheEntranceItMirrors() {
        // Material 3's rule, and the only thing that keeps a back-loaded exit from feeling slow.
        val pairs = listOf(
            BrowserMotion.panelArrive<Float>() to BrowserMotion.panelDepart<Float>(),
            BrowserMotion.windowArrive<Float>() to BrowserMotion.windowDepart<Float>(),
            BrowserMotion.pageArrive<Float>() to BrowserMotion.pageDepart<Float>(),
            BrowserMotion.localArrive<Float>() to BrowserMotion.localDepart<Float>(),
        )
        for ((arrive, depart) in pairs) {
            val arriving = (arrive as TweenSpec<Float>).durationMillis
            val leaving = (depart as TweenSpec<Float>).durationMillis
            assertTrue("an exit must be shorter than its entrance: $leaving vs $arriving", leaving < arriving)
        }
    }

    @Test
    fun theCurvesAreMaterial3sOwn() {
        // Sampled from the spec's published curve; a hand-tuned replacement would drift off it.
        for (point in listOf(.1f, .25f, .5f, .75f)) {
            assertEquals(
                "panel arrival must be emphasised decelerate",
                md3Decelerate.transform(point), sample(BrowserMotion.panelArrive<Float>(), point), 1e-4f,
            )
            assertEquals(
                "panel departure must be emphasised accelerate",
                md3Accelerate.transform(point), sample(BrowserMotion.panelDepart<Float>(), point), 1e-4f,
            )
        }
    }

    @Test
    fun theDurationsAreMaterial3sOwn() {
        // A curve is only half of a token; the duration is the other half.
        val durations = mapOf(
            BrowserMotion.panelArrive<Float>() to 300,
            BrowserMotion.panelDepart<Float>() to 200,
            BrowserMotion.windowArrive<Float>() to 500,
            BrowserMotion.windowDepart<Float>() to 250,
            BrowserMotion.pageArrive<Float>() to 250,
            BrowserMotion.pageDepart<Float>() to 200,
            BrowserMotion.localArrive<Float>() to 150,
            BrowserMotion.localDepart<Float>() to 100,
            BrowserMotion.contentCross<Float>() to 200,
            BrowserMotion.chromeFade<Float>() to 150,
            BrowserMotion.itemFadeIn<Float>() to 150,
            BrowserMotion.itemFadeOut<Float>() to 100,
        )
        for ((spec, expected) in durations) {
            assertEquals("unexpected duration for $spec", expected, (spec as TweenSpec<Float>).durationMillis)
        }
    }

    @Test
    fun theDimRampsEvenlyOverTheSurfaceSDuration() {
        // Two properties, both of them about not flickering. It must not share the surface's curve,
        // because a front-loaded dim reaches most of its strength in the first frame or two and
        // reads as a flash; and it must take exactly as long as the surface, or it arrives before
        // the thing it is dimming for (or lingers after it has gone).
        val surfaces = listOf(
            BrowserMotion.panelArrive<Float>(), BrowserMotion.panelDepart<Float>(),
            BrowserMotion.windowArrive<Float>(), BrowserMotion.windowDepart<Float>(),
        )
        for (surface in surfaces) {
            val scrim = BrowserMotion.scrimFor(surface)
            val surfaceMs = (surface as TweenSpec<Float>).durationMillis
            assertEquals("the dim must run as long as the surface", surfaceMs, (scrim as TweenSpec<Float>).durationMillis)
            for (point in listOf(.1f, .25f, .5f, .75f)) {
                assertEquals(
                    "the dim must ramp evenly, not follow the surface's curve",
                    point, sample(scrim, point), 1e-3f,
                )
            }
        }
        // Only meaningful against the arrivals: an exit's accelerate curve is *more* back-loaded
        // than a straight line, so the dim is legitimately ahead of it early on.
        for (arrive in listOf(BrowserMotion.panelArrive<Float>(), BrowserMotion.windowArrive<Float>())) {
            assertTrue(
                "the dim must not jump the way an arriving surface does",
                sample(BrowserMotion.scrimFor(arrive), .1f) < sample(arrive, .1f),
            )
        }
    }

    @Test
    fun motionAFingerDrivesCanContinueFromASpeed() {
        // The other half of the system: these are springs, because a tween started mid-flight
        // restarts its easing from zero and stops the surface dead.
        for (spec in listOf(BrowserMotion.chromeSpatial<Float>(), BrowserMotion.resume<Float>())) {
            val spring = spec as? SpringSpec<Float> ?: error("expected a spring: $spec")
            assertTrue("a spring carries velocity by construction: ${spring.dampingRatio}", spring.dampingRatio < 1f)
            assertTrue("and must actually be stiff enough to settle: ${spring.stiffness}", spring.stiffness >= 300f)
        }
    }

    @Test
    fun aResumeIsSlowerThanFollowingTheFinger() {
        // A resume carries a whole panel where the chrome carries a bar, so it must not be the
        // faster of the two.
        val following = BrowserMotion.chromeSpatial<Float>() as SpringSpec<Float>
        val resuming = BrowserMotion.resume<Float>() as SpringSpec<Float>
        assertTrue(
            "a panel settling must not outrun the chrome following a scroll",
            resuming.stiffness < following.stiffness,
        )
    }
}
