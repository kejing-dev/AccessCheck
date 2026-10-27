package io.embodit.accesscheck.ui.access.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.accesscheck.domain.model.ChairModel
import io.embodit.accesscheck.domain.model.ChairPose
import io.embodit.accesscheck.domain.model.Quat4
import io.embodit.accesscheck.domain.model.Vec3
import kotlin.math.PI

/**
 * The 1:1 wheelchair, built from boxes and cylinders (design `primitiveRecipes`, at most 18 primitives,
 * no textures, no external model): the footprint rectangle on the floor is the truth, the body only has
 * to be recognisable. Local frame: origin at the footprint centre on the floor, -Z ahead, +X right.
 *
 * The footprint frame (8 mm lines, 3 mm above the floor) and the heading arrow turn red while the chair
 * presses something, together with the frame of the body; a 35 % red face fills the footprint then.
 * A box collider over the whole chair is the drag target (`chair`).
 *
 * v0.1.1: while placing the body is a see-through ghost ([Look.GHOST]); while the head is inside the
 * footprint it turns see-through (taste profile D14, [Look.SEE_THROUGH]) so it never fills the view.
 */
internal class ChairView(private val root: Entity, private val m: SceneMaterials, private val shared: SharedResources) {

    private val group = Entity().named("chair")
    private var body: Entity? = null
    private val frameParts = ArrayList<Entity>()

    /** Every body part with its own (solid) material, for the ghost / see-through looks. */
    private val bodyParts = ArrayList<Pair<Entity, Material>>()
    private val footParts = ArrayList<Entity>()
    private var footFace: Entity? = null
    private var tap: Entity? = null
    private var bandMesh: MeshResource? = null
    private var faceMesh: MeshResource? = null
    private var unitBox: MeshResource? = null
    private var unitCylinder: MeshResource? = null
    private var model: ChairModel? = null
    private var hit = false
    private var stale = false
    private var shown = false
    private var look = Look.SOLID
    private var tappable = true

    enum class Look { SOLID, GHOST, SEE_THROUGH }

    val tapEntity: Entity?
        get() = tap

    fun prepare() {
        root.addChild(group)
        group.enabled = false
        bandMesh = shared.keep(MeshFactory.unitBand("chair_band"))
        faceMesh = shared.keep(MeshFactory.cube("chair_face"))
        // every body part is the unit box or the unit cylinder scaled: building the chair creates no mesh
        // (a mesh built on the main thread waits for the render thread, about a second each on the idle emulator)
        unitBox = shared.keep(MeshFactory.cube("chair_cube"))
        unitCylinder = shared.keep(MeshFactory.cylinder(1f, 1f, "unit_cylinder", segments = 24))
    }

    fun isChair(entity: Entity): Boolean = tap?.id == entity.id

    /** Builds (or rebuilds) the body for [model]. */
    fun build(model: ChairModel) {
        if (this.model == model) return
        this.model = model
        runCatching { body?.destroy(true) }
        frameParts.clear()
        footParts.clear()
        bodyParts.clear()
        val b = Entity().named("chairBody")
        group.addChild(b)
        body = b
        val w = model.widthM
        val l = model.lengthM
        val hw = w / 2f
        val hl = l / 2f
        val rr = model.rearWheelR
        val ub = unitBox ?: return
        val uc = unitCylinder ?: return
        fun box(size: Vec3, at: Vec3, mat: Material, frame: Boolean = false, rot: Quat4? = null): Entity {
            val e = ModelEntity(ub, mat)
            b.addChild(e)
            e.setLocal(position = at, rotation = rot, scale = size)
            if (frame) frameParts += e else bodyParts += e to mat
            return e
        }
        fun wheel(r: Float, thick: Float, at: Vec3) {
            // cylinder along +Y, turned so that its axis runs along X
            val e = ModelEntity(uc, m.chairDark)
            b.addChild(e)
            e.setLocal(position = at + Vec3(-thick / 2f, 0f, 0f), rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), -(PI / 2).toFloat()), scale = Vec3(r, thick, r))
            bodyParts += e to m.chairDark
        }
        val seatY = 0.48f
        val seatZ = 0.05f
        // seat and backrest
        box(Vec3(0.46f, 0.04f, 0.44f), Vec3(0f, seatY, seatZ), m.chairSeat)
        box(Vec3(0.44f, 0.42f, 0.03f), Vec3(0f, seatY + 0.21f, seatZ + 0.22f + 0.03f), m.chairSeat, rot = Quat4.axisAngle(Vec3.RIGHT, -0.14f))
        // rear wheels at the outer edge, front casters tucked in
        val axleZ = seatZ + 0.20f
        wheel(rr, 0.03f, Vec3(hw, rr, axleZ))
        wheel(rr, 0.03f, Vec3(-hw + 0.03f, rr, axleZ))
        val frontZ = -hl + 0.20f
        wheel(0.09f, 0.04f, Vec3(hw - 0.08f, 0.09f, frontZ))
        wheel(0.09f, 0.04f, Vec3(-hw + 0.08f + 0.04f, 0.09f, frontZ))
        // side frames, armrests, footrests, push handles
        for (s in intArrayOf(-1, 1)) {
            val x = s * (0.23f)
            box(Vec3(0.02f, 0.02f, 0.80f), Vec3(x, 0.30f, seatZ - 0.05f), m.chairFrame, frame = true)
            box(Vec3(0.02f, 0.30f, 0.02f), Vec3(x, 0.30f, frontZ), m.chairFrame, frame = true)
            box(Vec3(0.04f, 0.03f, 0.32f), Vec3(s * 0.25f, 0.70f, seatZ), m.chairFrame, frame = true)
            box(Vec3(0.02f, 0.22f, 0.02f), Vec3(s * 0.25f, 0.59f, seatZ + 0.12f), m.chairFrame, frame = true)
            box(Vec3(0.16f, 0.02f, 0.12f), Vec3(s * 0.12f, 0.08f, -hl + 0.06f), m.chairFrame, frame = true)
            box(Vec3(0.03f, 0.03f, 0.12f), Vec3(s * 0.22f, model.handleY, hl - 0.06f), m.chairFrame, frame = true)
            box(Vec3(0.02f, model.handleY - seatY - 0.02f, 0.02f), Vec3(s * 0.22f, (model.handleY + seatY) / 2f, seatZ + 0.26f), m.chairFrame, frame = true)
        }
        if (model == ChairModel.POWER) box(Vec3(0.50f, 0.20f, 0.50f), Vec3(0f, 0.22f, seatZ), m.chairDark)
        // footprint frame: four bands, the arrow, the red face
        val band = bandMesh ?: return
        val y = 0.003f
        fun edge(from: Vec3, to: Vec3) {
            val e = ModelEntity(band, m.footWhite)
            b.addChild(e)
            val dir = (to - from).normalized()
            val up = Vec3.UP
            e.setLocal(position = from, rotation = Quat4.fromBasis(dir, up.cross(dir).normalized(), up), scale = Vec3(from.distanceTo(to), LINE_W, 1f))
            footParts += e
        }
        edge(Vec3(-hw, y, -hl), Vec3(hw, y, -hl))
        edge(Vec3(hw, y, -hl), Vec3(hw, y, hl))
        edge(Vec3(hw, y, hl), Vec3(-hw, y, hl))
        edge(Vec3(-hw, y, hl), Vec3(-hw, y, -hl))
        edge(Vec3(-0.08f, y, -hl + 0.10f), Vec3(0f, y, -hl + 0.02f))
        edge(Vec3(0.08f, y, -hl + 0.10f), Vec3(0f, y, -hl + 0.02f))
        faceMesh?.let { fm ->
            val f = ModelEntity(fm, m.footFaceRed)
            b.addChild(f)
            f.setLocal(position = Vec3(0f, 0.002f, 0f), scale = Vec3(w, 0.002f, l))
            f.enabled = false
            footFace = f
        }
        // drag target: the whole chair
        val t = Entity().named("chairTap")
        t.tapBox(Vec3(w, model.heightM, l), Vec3(0f, model.heightM / 2f, 0f))
        b.addChild(t)
        t.enabled = tappable
        tap = t
        applyColours()
    }

    fun setShown(on: Boolean) {
        if (shown == on) return
        shown = on
        group.enabled = on
    }

    /** Places the chair; [spin] adds a visual turn (the 1 s turn-around). */
    fun setPose(pose: ChairPose, floorY: Float, spin: Float = 0f) {
        group.setLocal(position = Vec3(pose.center.x, floorY, pose.center.z), rotation = Quat4.axisAngle(Vec3.UP, pose.theta + spin))
    }

    fun setHit(on: Boolean) {
        if (hit == on) return
        hit = on
        applyColours()
    }

    /** The drag target takes system taps only when [on] (a pushed chair must not catch taps meant for the console). */
    fun setTappable(on: Boolean) {
        if (tappable == on) return
        tappable = on
        tap?.enabled = on
    }

    fun setLook(next: Look) {
        if (look == next) return
        look = next
        applyColours()
    }

    fun setStale(on: Boolean) {
        if (stale == on) return
        stale = on
        applyColours()
    }

    private fun applyColours() {
        val sheer =
            when (look) {
                Look.SOLID -> null
                Look.GHOST -> if (hit) m.ghostRed else m.ghostBody
                Look.SEE_THROUGH -> m.seeThroughBody
            }
        val frame = sheer ?: if (stale) m.chairStale else if (hit) m.chairRed else m.chairFrame
        val foot = if (stale) m.footStale else if (hit) m.footRed else m.footWhite
        frameParts.forEach { it.setMaterial(frame) }
        bodyParts.forEach { (e, solid) -> e.setMaterial(sheer ?: solid) }
        footParts.forEach { it.setMaterial(foot) }
        footFace?.enabled = hit && !stale
    }

    fun destroy() {
        runCatching { group.destroy(true) }
    }

    companion object {
        const val LINE_W = 0.008f
    }
}
