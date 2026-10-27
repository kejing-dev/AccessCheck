package io.embodit.accesscheck.ui.access.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector3
import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.DoorInfo
import io.embodit.accesscheck.domain.model.Obb2
import io.embodit.accesscheck.domain.model.Obstacle
import io.embodit.accesscheck.domain.model.Pin
import io.embodit.accesscheck.domain.model.PinTag
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Scuff
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.Vec3
import io.embodit.accesscheck.domain.model.Verdict
import io.embodit.accesscheck.domain.model.ViewHeight
import io.embodit.accesscheck.ui.access.TurnUi
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything drawn in the room besides the chair (design `entities3d`): scuffs (ellipse pool 40 + scrape
 * pool 20), pins (20), the turning ring (72 segments) with its red arrow, the clear-floor frame, the snap
 * frame (taste profile D1), two door gates with threshold lines, the reach band and the eye line. Pools
 * are built once in [prepare]; frames write transforms only.
 */
internal class MarksView(private val root: Entity, private val m: SceneMaterials, private val shared: SharedResources) {

    private class PinView(val group: Entity, val head: Entity)

    private class Gate(val left: Entity, val right: Entity, val sill: Strip) {
        var doorId: String? = null
        var verdict: Verdict? = null
        var flashing = false
    }

    private val scuffPool = ArrayList<Entity>()
    private val scrapePool = ArrayList<Strip>()
    private val pinPool = ArrayList<PinView>()
    private val ringSegments = ArrayList<Entity>()
    private var ringGroup: Entity? = null
    private var arrow: Entity? = null
    private var arrowShaft: Strip? = null
    private val clearFrame = ArrayList<Strip>()
    private val snapFrame = ArrayList<Strip>()
    private val gates = ArrayList<Gate>()
    private var reach: Strip? = null
    private var eye: Strip? = null
    private val obstacleViews = ArrayList<Entity>()
    private var obstaclesShown = false

    private var discMesh: MeshResource? = null
    private var bandMesh: MeshResource? = null
    private var segmentMesh: MeshResource? = null
    private var pinHeadMesh: MeshResource? = null
    private var needleMesh: MeshResource? = null
    private var coneMesh: MeshResource? = null
    private var barMesh: MeshResource? = null
    private var unitBox: MeshResource? = null

    private var scuffKey = ""
    private var pinKey = ""
    private var turnKey = ""

    fun prepare() {
        discMesh = shared.keep(MeshFactory.disc(1f, "scuff_disc", segments = 24))
        bandMesh = shared.keep(MeshFactory.unitBand("mark_band"))
        segmentMesh = shared.keep(arcSegment(AccessRules.TURN_RADIUS_M - 0.012f, AccessRules.TURN_RADIUS_M, 2 * PI / AccessRules.TURN_SEGMENTS * 0.82, "ring_segment"))
        pinHeadMesh = shared.keep(MeshFactory.cylinder(0.03f, 0.03f, "pin_head", segments = 16))
        needleMesh = shared.keep(MeshFactory.cylinder(0.004f, 0.08f, "pin_needle", segments = 8))
        coneMesh = shared.keep(MeshFactory.cone("arrow_cone"))
        unitBox = shared.keep(MeshFactory.cube("marks_cube"))
        barMesh = unitBox
        val disc = discMesh!!
        val band = bandMesh!!
        for (i in 0 until AccessRules.SCUFF_MAX) {
            val e = ModelEntity(disc, m.scuff1).named("scuff$i")
            root.addChild(e)
            e.enabled = false
            scuffPool += e
        }
        for (i in 0 until AccessRules.SCRAPE_MAX) scrapePool += Strip(root, band, m.scuff1, "scrape$i")
        for (i in 0 until AccessRules.PIN_MAX) {
            val g = Entity().named("pin$i")
            root.addChild(g)
            val needle = ModelEntity(needleMesh!!, m.pinWhite)
            g.addChild(needle)
            val head = ModelEntity(pinHeadMesh!!, m.pinOrange)
            g.addChild(head)
            head.setLocal(position = Vec3(0f, 0.08f, 0f))
            g.enabled = false
            pinPool += PinView(g, head)
        }
        val rg = Entity().named("turnRing")
        root.addChild(rg)
        rg.enabled = false
        ringGroup = rg
        for (i in 0 until AccessRules.TURN_SEGMENTS) {
            val e = ModelEntity(segmentMesh!!, m.ringGreen)
            rg.addChild(e)
            e.setLocal(rotation = Quat4.axisAngle(Vec3.UP, -(2.0 * PI * i / AccessRules.TURN_SEGMENTS).toFloat()))
            ringSegments += e
        }
        arrow = ModelEntity(coneMesh!!, m.arrowRed).also { rg.addChild(it); it.enabled = false }
        arrowShaft = Strip(rg, band, m.arrowRed, "arrowShaft")
        repeat(4) { clearFrame += Strip(root, band, m.clearWhite, "clear$it") }
        repeat(4) { snapFrame += Strip(root, band, m.snapDash, "snap$it") }
        repeat(2) { i ->
            val l = ModelEntity(barMesh!!, m.gateGrey).named("gateL$i")
            val r = ModelEntity(barMesh!!, m.gateGrey).named("gateR$i")
            root.addChild(l)
            root.addChild(r)
            l.enabled = false
            r.enabled = false
            gates += Gate(l, r, Strip(root, band, m.gateGrey, "sill$i"))
        }
        reach = Strip(root, band, m.reachBand, "reachBand")
        eye = Strip(root, band, m.eyeYellow, "eyeLine")
    }

    // ---- scuffs
    fun syncScuffs(scuffs: List<Scuff>) {
        val key = scuffs.joinToString("|") { "${it.id}:${it.level}:${it.exit != null}" }
        if (key == scuffKey) return
        scuffKey = key
        var disc = 0
        var scrape = 0
        for (s in scuffs) {
            if (disc >= scuffPool.size) break
            placeEllipse(scuffPool[disc++], s.enter, s.normal, s.level)
            val exit = s.exit ?: continue
            if (disc < scuffPool.size) placeEllipse(scuffPool[disc++], exit, s.normal, s.level)
            if (scrape < scrapePool.size) {
                val st = scrapePool[scrape++]
                st.setMaterial(m.scuff(s.level))
                st.draw(s.enter + s.normal * 0.004f, exit + s.normal * 0.004f, 0.02f, s.normal)
            }
        }
        for (i in disc until scuffPool.size) scuffPool[i].enabled = false
        for (i in scrape until scrapePool.size) scrapePool[i].hide()
    }

    private fun placeEllipse(e: Entity, at: Vec3, normal: Vec3, level: Int) {
        e.setMaterial(m.scuff(level))
        val n = normal.normalized()
        // the disc faces +Y: turn +Y onto the surface normal, stretch to 12 x 6 cm
        val rot = Quat4.fromTo(Vec3.UP, n)
        e.setLocal(position = at + n * 0.004f, rotation = rot, scale = Vec3(0.06f, 1f, 0.03f))
        e.enabled = true
    }

    // ---- pins
    fun syncPins(pins: List<Pin>) {
        val key = pins.joinToString("|") { "${it.id}:${it.tag}" }
        if (key == pinKey) return
        pinKey = key
        var i = 0
        for (p in pins) {
            if (i >= pinPool.size) break
            val v = pinPool[i++]
            val n = p.normal.normalized()
            v.group.setLocal(position = p.position, rotation = Quat4.fromTo(Vec3.UP, n))
            v.head.setMaterial(
                when (p.tag) {
                    PinTag.CANT_SEE -> m.pinYellow
                    PinTag.HAZARD -> m.pinRed
                    else -> m.pinOrange
                }
            )
            v.group.enabled = true
        }
        for (j in i until pinPool.size) pinPool[j].group.enabled = false
    }

    /** Where a pin's tag card hangs: 15 cm above the pin head. */
    fun pinCardPosition(p: Pin): Vec3 = p.position + p.normal.normalized() * 0.10f + Vec3(0f, 0.15f, 0f)

    // ---- turning ring
    fun syncTurn(turn: TurnUi?, floorY: Float) {
        val rg = ringGroup ?: return
        if (turn == null) {
            if (rg.enabled) rg.enabled = false
            turnKey = ""
            return
        }
        val key = "${turn.startedAtMs}:${turn.center}"
        if (key == turnKey && rg.enabled) return
        turnKey = key
        rg.setLocal(position = Vec3(turn.center.x, floorY + 0.003f, turn.center.z))
        for (i in ringSegments.indices) ringSegments[i].setMaterial(if (turn.red.getOrElse(i) { false }) m.ringRed else m.ringGreen)
        val dir = turn.blockerDir
        val a = arrow
        val shaft = arrowShaft
        if (dir != null && !turn.pass && a != null && shaft != null) {
            val d3 = Vec3(dir.x, 0f, dir.z).normalized()
            val from = d3 * 0.25f + Vec3(0f, 0.004f, 0f)
            val tip = d3 * (AccessRules.TURN_RADIUS_M - 0.05f) + Vec3(0f, 0.004f, 0f)
            shaft.draw(from, tip - d3 * 0.12f, 0.03f, Vec3.UP)
            // cone: apex at origin along +Z, length 1, radius 1 -> apex at the tip, pointing back along -dir
            a.setLocal(position = tip, rotation = Quat4.fromTo(Vec3(0f, 0f, 1f), -d3), scale = Vec3(0.05f, 0.012f, 0.12f))
            a.enabled = true
        } else {
            a?.enabled = false
            shaft?.hide()
        }
        rg.enabled = true
    }

    // ---- clear floor and snap frames
    fun syncClearFloor(pose: ChairPose?, shown: Boolean, short: Boolean, floorY: Float) {
        if (pose == null || !shown) {
            clearFrame.forEach { it.hide() }
            return
        }
        drawFrame(clearFrame, pose.clearFloor(), floorY + 0.003f, if (short) m.clearOrange else m.clearWhite, dashed = true)
    }

    fun syncSnap(target: ChairPose?, chairBox: Obb2?, floorY: Float) {
        if (target == null || chairBox == null) {
            snapFrame.forEach { it.hide() }
            return
        }
        drawFrame(snapFrame, Obb2(target.center, target.heading, chairBox.hu, chairBox.hv), floorY + 0.004f, m.snapDash, dashed = true)
    }

    private fun drawFrame(strips: List<Strip>, box: Obb2, y: Float, mat: Material, dashed: Boolean) {
        val c = box.corners().map { Vec3(it.x, y, it.z) }
        for (i in 0 until 4) {
            val s = strips[i]
            s.setMaterial(mat)
            s.draw(c[i], c[(i + 1) % 4], 0.008f, Vec3.UP)
        }
        @Suppress("UNUSED_EXPRESSION") dashed
    }

    // ---- door gates
    fun syncGate(slot: Int, door: DoorInfo?, verdict: Verdict?, thresholdRed: Boolean, flash: Boolean, chairSide: V2?) {
        val g = gates.getOrNull(slot) ?: return
        if (door == null || verdict == null) {
            if (g.doorId != null) {
                g.left.enabled = false
                g.right.enabled = false
                g.sill.hide()
                g.doorId = null
            }
            return
        }
        val same = g.doorId == door.id && g.verdict == verdict && g.flashing == flash
        if (same) return
        g.doorId = door.id
        g.verdict = verdict
        g.flashing = flash
        val h = door.heightM
        val half = door.along * (door.rawWidthM / 2f)
        val lp = door.center - half
        val rp = door.center + half
        val mat = if (flash) m.gateRed else m.gate(verdict)
        g.left.setMaterial(mat)
        g.right.setMaterial(mat)
        g.left.setLocal(position = Vec3(lp.x, door.floorY + h / 2f, lp.z), scale = Vec3(0.02f, h, 0.02f))
        g.right.setLocal(position = Vec3(rp.x, door.floorY + h / 2f, rp.z), scale = Vec3(0.02f, h, 0.02f))
        g.left.enabled = true
        g.right.enabled = true
        g.sill.setMaterial(if (thresholdRed) m.thresholdRed else m.gate(verdict))
        g.sill.draw(Vec3(lp.x, door.floorY + 0.003f, lp.z), Vec3(rp.x, door.floorY + 0.003f, rp.z), if (thresholdRed) 0.04f else 0.012f, Vec3.UP)
        @Suppress("UNUSED_EXPRESSION") chairSide
    }

    /** Where a door's plate stands: on the door's centre line, 20 cm under the top edge, pushed a little to the chair's side. */
    fun doorPlatePosition(door: DoorInfo, towards: V2, plateHalfHeightM: Float): Vec3 {
        val c = door.center + towards * 0.03f
        return Vec3(c.x, door.topY - 0.20f - plateHalfHeightM, c.z)
    }

    // ---- reach band and eye line
    fun syncReach(wallBox: Obb2?, chairCenter: V2?, floorY: Float) {
        val r = reach ?: return
        if (wallBox == null || chairCenter == null) {
            r.hide()
            return
        }
        val closest = wallBox.closestPoint(chairCenter)
        var n = chairCenter - closest
        if (n.length < 1e-3f) n = wallBox.v
        n = n.normalized()
        val along = wallBox.u
        val halfLen = minOf(0.6f, wallBox.hu)
        val mid = (AccessRules.REACH_LOW_M + AccessRules.REACH_HIGH_M) / 2f + floorY
        val a = closest - along * halfLen + n * 0.006f
        val b = closest + along * halfLen + n * 0.006f
        r.draw(Vec3(a.x, mid, a.z), Vec3(b.x, mid, b.z), AccessRules.REACH_HIGH_M - AccessRules.REACH_LOW_M, Vec3(n.x, 0f, n.z))
    }

    fun syncEye(view: ViewHeight, hitPoint: Vec3?, wallNormal: Vec3?, floorY: Float) {
        val e = eye ?: return
        if (view == ViewHeight.STAND || hitPoint == null || wallNormal == null) {
            e.hide()
            return
        }
        val n = Vec3(wallNormal.x, 0f, wallNormal.z).normalized()
        val along = Vec3.UP.cross(n).normalized()
        val y = floorY + view.targetM
        val c = Vec3(hitPoint.x, y, hitPoint.z) + n * 0.005f
        e.setMaterial(if (view == ViewHeight.CHILD) m.eyeCyan else m.eyeYellow)
        e.draw(c - along * 1.0f, c + along * 1.0f, 0.006f, n)
    }

    /** Where the eye-line label hangs: just above the middle of the line. */
    fun eyeLabelPosition(view: ViewHeight, hitPoint: Vec3, wallNormal: Vec3, floorY: Float): Vec3 {
        val n = Vec3(wallNormal.x, 0f, wallNormal.z).normalized()
        return Vec3(hitPoint.x, floorY + view.targetM + 0.06f, hitPoint.z) + n * 0.02f
    }

    // ---- debug: every obstacle as a translucent box
    fun showObstacles(obstacles: List<Obstacle>, floorY: Float, on: Boolean) {
        obstacleViews.forEach { runCatching { it.destroy(true) } }
        obstacleViews.clear()
        obstaclesShown = on
        if (!on) return
        for (o in obstacles) {
            val h = (o.yHigh - o.yLow).coerceAtLeast(0.02f)
            val e = ModelEntity(unitBox ?: return, m.reachBand)
            root.addChild(e)
            e.setLocal(position = Vec3(o.box.center.x, floorY + o.yLow + h / 2f, o.box.center.z), rotation = Quat4.axisAngle(Vec3.UP, -atan2(o.box.u.z, o.box.u.x)), scale = Vec3(o.box.hu * 2f, h, o.box.hv * 2f))
            obstacleViews += e
        }
    }

    fun destroy() {
        scuffPool.forEach { runCatching { it.destroy(true) } }
        scrapePool.forEach { it.destroy() }
        pinPool.forEach { runCatching { it.group.destroy(true) } }
        runCatching { ringGroup?.destroy(true) }
        clearFrame.forEach { it.destroy() }
        snapFrame.forEach { it.destroy() }
        gates.forEach { runCatching { it.left.destroy(true) }; runCatching { it.right.destroy(true) }; it.sill.destroy() }
        reach?.destroy()
        eye?.destroy()
        obstacleViews.forEach { runCatching { it.destroy(true) } }
    }

    companion object {
        /** One flat arc from angle 0 to [span] (radians) in the XZ plane, facing +Y. */
        fun arcSegment(inner: Float, outer: Float, span: Double, name: String, steps: Int = 3): MeshResource {
            val positions = ArrayList<Vector3>()
            val normals = ArrayList<Vector3>()
            val indices = ArrayList<Int>()
            val up = Vector3(0f, 1f, 0f)
            for (i in 0 until steps) {
                val s = span * i / steps
                val e = span * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += up }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
            return MeshResource.createWithMeshModel(
                model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
                bounds = BoundingBox(center = Vector3(outer / 2f, 0f, outer / 2f), halfExtent = Vector3(outer, 0.001f, outer)),
                name = name,
            )
        }
    }
}
