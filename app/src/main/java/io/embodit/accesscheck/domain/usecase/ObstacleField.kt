package io.embodit.accesscheck.domain.usecase

import io.embodit.accesscheck.domain.model.AccessRules
import io.embodit.accesscheck.domain.model.Obb2
import io.embodit.accesscheck.domain.model.Obstacle
import io.embodit.accesscheck.domain.model.ObstacleKind
import io.embodit.accesscheck.domain.model.ObstacleName
import io.embodit.accesscheck.domain.model.PlaneSurface
import io.embodit.accesscheck.domain.model.SurfaceKind
import io.embodit.accesscheck.domain.model.V2
import io.embodit.accesscheck.domain.model.fitObb
import io.embodit.accesscheck.domain.model.obbPenetration
import io.embodit.accesscheck.domain.model.pointInPolygon
import io.embodit.accesscheck.domain.model.rayToPolygonEdge
import io.embodit.accesscheck.domain.model.segmentEntersObb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Everything the wheelchair can bump into, on the floor plan (design `rules.collide`, review R1-7 / R1-8):
 * - WALL / VIRTUAL_WALL planes as thin bars, with the doorways (DOOR / OPENING planes whose bottom edge
 *   reaches the floor) cut out of them;
 * - furniture top planes (CABINET, WASHING_MACHINE, REFRIGERATOR, BED, SOFA, CHAIR, and TABLE under 0.71 m)
 *   extended down to the floor as solid blocks; vertical furniture planes as thin bars;
 * - a 5 cm occupancy grid filled from spatial-mesh vertices 0.10 - 0.68 m above the floor and more than
 *   5 cm from any wall (toilets, basin cabinets, bath rims: things the semantic planes do not name);
 * - virtual obstacles injected by the debug hooks.
 * Every obstacle records its lower and upper edge; only those with the lower edge under 0.68 m take part.
 * Plain JVM; the scene feeds it, the ViewModel and the scene both query it on the main thread.
 */
class ObstacleField {

    var floorY: Float = 0f
        private set

    /** No floor plane was found: the stand-in floor height (taste profile D7); a real floor plane replaces it. */
    fun useVirtualFloor(y: Float) {
        floorY = y
    }

    /** Outline of the largest FLOOR plane, floor plan; empty before the floor is known. */
    var floorPolygon: List<V2> = emptyList()
        private set

    private val planeObstacles = ArrayList<Obstacle>()
    private val wallBoxes = ArrayList<Obb2>()
    private var meshObstacles: List<Obstacle> = emptyList()
    private val cells = HashSet<Long>()
    val extras = ArrayList<Obstacle>()

    /** Bumped on every change, so that per-frame users can skip work. */
    var version = 0
        private set

    private var cached: List<Obstacle> = emptyList()
    private var cachedVersion = -1

    val meshCellCount: Int
        get() = cells.size

    /** All participating obstacles. */
    fun all(): List<Obstacle> {
        if (cachedVersion != version) {
            cached = (planeObstacles + meshObstacles + extras).filter { it.participates }
            cachedVersion = version
        }
        return cached
    }

    fun addExtra(o: Obstacle) {
        extras += o
        version++
    }

    fun clearExtras() {
        if (extras.isEmpty()) return
        extras.clear()
        version++
    }

    // =========================================================================================
    // building
    // =========================================================================================

    fun rebuildFromPlanes(planes: Collection<PlaneSurface>) {
        val floors = planes.filter { it.kind == SurfaceKind.FLOOR && it.horizontal }
        val floor = floors.maxByOrNull { it.vertices.size * 1000f + it.maxY * 0f }
        floorY = floors.minOfOrNull { it.minY } ?: floorY
        floorPolygon = floor?.vertices?.map { V2.of(it) } ?: emptyList()
        planeObstacles.clear()
        wallBoxes.clear()
        val doorways = planes.filter { it.kind.isDoorway && it.vertical && it.minY - floorY <= AccessRules.DOOR_BOTTOM_MAX_M + 0.05f }
        for (plane in planes) {
            when {
                plane.kind == SurfaceKind.WALL && plane.vertical -> addWall(plane, doorways)
                plane.kind.solidBlock && plane.horizontal -> addBlock(plane)
                plane.kind.solidBlock && plane.vertical -> addBar(plane, ObstacleName.of(plane.kind))
                else -> Unit
            }
        }
        version++
    }

    private fun addWall(plane: PlaneSurface, doorways: List<PlaneSurface>) {
        val n2 = V2(plane.normal.x, plane.normal.z)
        if (n2.length < 0.5f) return
        val normal = n2.normalized()
        val along = normal.leftPerp()
        val points = plane.vertices.map { V2.of(it) }
        val box = fitObb(points, along) ?: return
        if (box.hu < 0.02f) return
        val yLow = plane.minY - floorY
        val yHigh = plane.maxY - floorY
        // spans along the wall, with the doorways cut out
        var spans = listOf(-box.hu to box.hu)
        for (door in doorways) {
            val dn = V2(door.normal.x, door.normal.z)
            if (dn.length < 0.5f || abs(dn.normalized().dot(normal)) < 0.9f) continue
            val dc = V2.of(door.centroid)
            if (abs((dc - box.center).dot(normal)) > 0.12f) continue
            val dBox = fitObb(door.vertices.map { V2.of(it) }, along) ?: continue
            val c = (dBox.center - box.center).dot(along)
            val d0 = c - dBox.hu
            val d1 = c + dBox.hu
            spans = spans.flatMap { (a, b) ->
                when {
                    d1 <= a || d0 >= b -> listOf(a to b)
                    else -> listOfNotNull(if (d0 > a) a to d0 else null, if (d1 < b) d1 to b else null)
                }
            }
        }
        for ((i, span) in spans.withIndex()) {
            val (a, b) = span
            if (b - a < 0.05f) continue
            val mid = (a + b) / 2f
            val piece = Obb2(box.center + along * mid, along, (b - a) / 2f, WALL_HALF_M)
            wallBoxes += piece
            planeObstacles += Obstacle("wall:${plane.id}:$i", ObstacleKind.WALL, ObstacleName.WALL, piece, yLow, yHigh, plane.id)
        }
    }

    private fun addBlock(plane: PlaneSurface) {
        val top = plane.maxY - floorY
        if (top < 0.08f) return
        if (plane.kind == SurfaceKind.TABLE && top >= AccessRules.TABLE_KNEE_M) return
        val points = plane.vertices.map { V2.of(it) }
        val box = tightBox(plane, points) ?: return
        if (box.hu < 0.03f || box.hv < 0.03f) return
        planeObstacles += Obstacle("block:${plane.id}", ObstacleKind.FURNITURE, ObstacleName.of(plane.kind), box, 0f, top, plane.id)
    }

    private fun addBar(plane: PlaneSurface, name: ObstacleName) {
        val n2 = V2(plane.normal.x, plane.normal.z)
        if (n2.length < 0.5f) return
        val along = n2.normalized().leftPerp()
        val box = fitObb(plane.vertices.map { V2.of(it) }, along) ?: return
        if (box.hu < 0.03f) return
        planeObstacles += Obstacle("bar:${plane.id}", ObstacleKind.FURNITURE, name, Obb2(box.center, along, box.hu, WALL_HALF_M), plane.minY - floorY, plane.maxY - floorY, plane.id)
    }

    /** Smallest-area rectangle aligned with one of the polygon's triangle edges (the vertex order is not an edge order). */
    private fun tightBox(plane: PlaneSurface, points: List<V2>): Obb2? {
        val dirs = ArrayList<V2>()
        val idx = plane.indices
        var i = 0
        while (i + 2 < idx.size) {
            for ((a, b) in listOf(idx[i] to idx[i + 1], idx[i + 1] to idx[i + 2], idx[i + 2] to idx[i])) {
                if (a >= points.size || b >= points.size) continue
                val e = points[b] - points[a]
                if (e.length > 0.02f) dirs += e.normalized()
            }
            i += 3
        }
        if (dirs.isEmpty()) for (k in points.indices) {
            val e = points[(k + 1) % points.size] - points[k]
            if (e.length > 0.02f) dirs += e.normalized()
        }
        var best: Obb2? = null
        for (d in dirs) {
            val b = fitObb(points, d) ?: continue
            if (best == null || b.hu * b.hv < best.hu * best.hv) best = b
        }
        return best
    }

    /** Rebuilds the occupancy grid from mesh vertices (Stage metres); [forEachVertex] is called once. */
    fun rebuildMesh(forEachVertex: ((Float, Float, Float) -> Unit) -> Unit) {
        val next = HashSet<Long>()
        val low = floorY + AccessRules.MESH_LOW_M
        val high = floorY + AccessRules.MESH_HIGH_M
        val walls = wallBoxes
        val blocks = planeObstacles.filter { it.kind == ObstacleKind.FURNITURE }
        forEachVertex { x, y, z ->
            if (y < low || y > high) return@forEachVertex
            val p = V2(x, z)
            var skip = false
            for (w in walls) {
                if (w.distanceTo(p) <= AccessRules.MESH_WALL_CLEAR_M) {
                    skip = true
                    break
                }
            }
            if (!skip) {
                for (b in blocks) {
                    if (b.box.contains(p, 0.03f)) {
                        skip = true
                        break
                    }
                }
            }
            if (!skip) next += cellKey(p)
        }
        if (next == cells) return
        cells.clear()
        cells += next
        meshObstacles =
            cells.map { key ->
                val c = cellCenter(key)
                Obstacle("cell:$key", ObstacleKind.MESH, ObstacleName.LOW_OBSTACLE, Obb2.square(c, AccessRules.GRID_CELL_M / 2f), AccessRules.MESH_LOW_M, AccessRules.MESH_HIGH_M)
            }
        version++
    }

    // =========================================================================================
    // queries
    // =========================================================================================

    /** One obstacle met by a footprint: how deep, which way out (from the obstacle to the chair), where. */
    data class Hit(val obstacle: Obstacle, val depth: Float, val axis: V2, val contact: V2)

    fun hits(foot: Obb2): List<Hit> {
        var out: ArrayList<Hit>? = null
        for (o in all()) {
            val pen = obbPenetration(foot, o.box) ?: continue
            val contact = if (o.kind == ObstacleKind.MESH) o.box.center else o.box.closestPoint(foot.center)
            (out ?: ArrayList<Hit>().also { out = it }) += Hit(o, pen.first, pen.second, contact)
        }
        return out ?: emptyList()
    }

    /** Result of the turning-disc check (design `entities3d.turnRing`, review R1-1). */
    data class TurnResult(val red: BooleanArray, val shortCm: Int, val blocker: ObstacleName?, val blockerDir: V2?) {
        val pass: Boolean
            get() = red.none { it }
    }

    fun turnCheck(center: V2): TurnResult {
        val n = AccessRules.TURN_SEGMENTS
        val r = AccessRules.TURN_RADIUS_M
        val red = BooleanArray(n)
        var minDist = Float.MAX_VALUE
        var blocker: ObstacleName? = null
        var blockerDir: V2? = null
        val obstacles = all()
        val insideFloor = floorPolygon.size >= 3 && pointInPolygon(center, floorPolygon)
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            val dir = V2(cos(a).toFloat(), sin(a).toFloat())
            val end = center + dir * r
            for (o in obstacles) {
                val t = segmentEntersObb(center, end, o.box) ?: continue
                red[i] = true
                val d = t * r
                if (d < minDist) {
                    minDist = d
                    blocker = o.name
                    blockerDir = dir
                }
            }
            if (insideFloor) {
                val d = rayToPolygonEdge(center, dir, floorPolygon, r)
                if (d != null) {
                    red[i] = true
                    if (d < minDist) {
                        minDist = d
                        blocker = ObstacleName.WALL
                        blockerDir = dir
                    }
                }
            }
        }
        // things inside the disc that no ray happened to cross
        for (o in obstacles) {
            val closest = o.box.closestPoint(center)
            val d = closest.distanceTo(center)
            if (d >= r) continue
            val dir = if (d < 1e-4f) V2(1f, 0f) else (closest - center).normalized()
            val idx = ((atan2(dir.z, dir.x) / (2.0 * PI) * n).let { if (it < 0) it + n else it }.toInt()) % n
            red[idx] = true
            if (d < minDist) {
                minDist = d
                blocker = o.name
                blockerDir = dir
            }
        }
        val shortCm = if (minDist == Float.MAX_VALUE) 0 else max(1, ceil((r - minDist) * 100f).toInt())
        return TurnResult(red, shortCm, blocker, blockerDir)
    }

    /** The clear floor rectangle pressed into something: how much it is short and by what (design `rules.clearFloor`). */
    data class ClearResult(val shortCm: Int, val blocker: ObstacleName, val axis: V2)

    fun clearFloorCheck(box: Obb2): ClearResult? {
        var best: ClearResult? = null
        for (o in all()) {
            val pen = obbPenetration(box, o.box) ?: continue
            val cm = max(1, ceil(pen.first * 100f).toInt())
            if (best == null || cm > best.shortCm) best = ClearResult(cm, o.name, pen.second)
        }
        return best
    }

    /** The wall bar nearest to the footprint's side or front within [within] metres, with its distance. */
    fun nearestWall(foot: Obb2, within: Float): Pair<Obstacle, Float>? {
        var best: Pair<Obstacle, Float>? = null
        for (o in all()) {
            if (o.kind != ObstacleKind.WALL) continue
            // distance between the two rectangles, through their corner samples
            var d = Float.MAX_VALUE
            for (c in foot.corners()) d = minOf(d, o.box.distanceTo(c))
            for (c in o.box.corners()) d = minOf(d, foot.distanceTo(c))
            if (d <= within && (best == null || d < best.second)) best = o to d
        }
        return best
    }

    companion object {
        private const val WALL_HALF_M = 0.02f

        fun cellKey(p: V2): Long {
            val ix = floor(p.x / AccessRules.GRID_CELL_M).toLong()
            val iz = floor(p.z / AccessRules.GRID_CELL_M).toLong()
            return (ix shl 32) xor (iz and 0xffffffffL)
        }

        fun cellCenter(key: Long): V2 {
            val ix = (key shr 32).toInt()
            val iz = key.toInt()
            return V2((ix + 0.5f) * AccessRules.GRID_CELL_M, (iz + 0.5f) * AccessRules.GRID_CELL_M)
        }
    }
}
