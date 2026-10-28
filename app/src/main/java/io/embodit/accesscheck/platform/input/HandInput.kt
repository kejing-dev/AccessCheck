package io.embodit.accesscheck.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandAim
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.accesscheck.domain.model.PointerRay
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.platform.AcLog

/**
 * One tracked hand in one frame, Stage space: the palm, the index / middle / ring tips, the pinch strength
 * and the hand's aim ray (the system's hand pointer; null until ready).
 */
data class HandSample(val right: Boolean, val palm: Vec3, val tips: List<Vec3>, val pinch: Float, val aim: PointerRay? = null)

/**
 * Hand poses for taking hold of the push handle (v0.1.2): `HandTrackingProvider.latestData`, read once per
 * frame on the main thread (the documented ECS way), Stage space like the controller. Nothing when hand
 * tracking is off or the controllers are in use (support state other than SUPPORTED).
 */
class HandInput {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    fun start() {
        if (provider != null) return
        val p = HandTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        AcLog.i("TRACK hands start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { runCatching { it.stop() } }
        provider = null
    }

    fun sample(): List<HandSample> {
        val p = provider ?: return emptyList()
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            AcLog.i("TRACK hands support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return emptyList()
        val data = runCatching { p.latestData }.getOrNull() ?: return emptyList()
        return listOfNotNull(hand(data.left, data.leftPinch, data.leftAim, false), hand(data.right, data.rightPinch, data.rightAim, true))
    }

    private fun hand(pose: HandPose?, pinch: HandPinch?, aim: HandAim?, right: Boolean): HandSample? {
        val joints = pose?.handJoints ?: return null
        fun at(i: HandJoint.Index): Vec3? = joints.firstOrNull { it.index == i }?.position?.let { Vec3(it.x, it.y, it.z) }?.takeIf { it.isFinite() }
        val palm = at(HandJoint.Index.PALM) ?: return null
        val tips = listOfNotNull(at(HandJoint.Index.INDEX_TIP), at(HandJoint.Index.MIDDLE_TIP), at(HandJoint.Index.RING_TIP))
        val strength = if (pinch != null && pinch.isReady) pinch.pinchValue else 0f
        return HandSample(right, palm, tips, strength, aim?.let(::ray))
    }

    /** The aim pose's -Z, as for the controller (Stage space). */
    private fun ray(aim: HandAim): PointerRay? {
        if (!aim.isReady) return null
        val p = aim.aimPose.position
        val q = aim.aimPose.rotation
        val origin = Vec3(p.x, p.y, p.z)
        val forward = Quat4(q.x, q.y, q.z, q.w).rotate(Vec3.AHEAD)
        if (!origin.isFinite() || !forward.isFinite() || forward.length < 0.5f) return null
        return PointerRay(origin, forward.normalized())
    }
}
