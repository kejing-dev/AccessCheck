package io.embodit.accesscheck.domain.model

/** Semantic kind of a detected plane (the platform's `SemanticLabelType` name, mapped). */
enum class SurfaceKind {
    FLOOR,
    WALL,
    CEILING,
    DOOR,
    OPENING,
    TABLE,
    BED,
    CABINET,
    SOFA,
    CHAIR,
    WASHING_MACHINE,
    REFRIGERATOR,
    CURTAIN,
    WINDOW,
    OTHER;

    /** Furniture whose top plane is extended down to the floor as a solid block (design `rules.collide`). */
    val solidBlock: Boolean
        get() = this == CABINET || this == WASHING_MACHINE || this == REFRIGERATOR || this == BED || this == SOFA || this == TABLE || this == CHAIR

    val isDoorway: Boolean
        get() = this == DOOR || this == OPENING

    companion object {
        fun fromSemanticName(name: String?): SurfaceKind =
            when (name) {
                "FLOOR" -> FLOOR
                "WALL", "VIRTUAL_WALL" -> WALL
                "CEILING" -> CEILING
                "DOOR" -> DOOR
                "OPENING" -> OPENING
                "TABLE" -> TABLE
                "BED" -> BED
                "CABINET" -> CABINET
                "SOFA" -> SOFA
                "CHAIR" -> CHAIR
                "WASHING_MACHINE" -> WASHING_MACHINE
                "REFRIGERATOR" -> REFRIGERATOR
                "CURTAIN" -> CURTAIN
                "WINDOW" -> WINDOW
                else -> OTHER
            }
    }
}

/**
 * A detected surface: a planar polygon (a quad on the emulator) with its vertices already in Stage
 * metres. Triangle winding is not trusted (wall quads wind outwards on the emulator), so every hit
 * test is two-sided and normals are derived from the vertices. Same as the Spatial Ruler's.
 */
data class PlaneSurface(
    val id: String,
    val kind: SurfaceKind,
    val semanticName: String,
    val vertices: List<Vec3>,
    val indices: List<Int>,
) {
    /** Unit normal of the first non-degenerate triangle; its sign is arbitrary. */
    val normal: Vec3 by lazy {
        var n = Vec3.UP
        var i = 0
        while (i + 2 < indices.size) {
            val v0 = vertices[indices[i]]
            val c = (vertices[indices[i + 1]] - v0).cross(vertices[indices[i + 2]] - v0)
            if (c.length > 1e-9f) {
                n = c.normalized()
                break
            }
            i += 3
        }
        n
    }

    val centroid: Vec3 by lazy {
        if (vertices.isEmpty()) Vec3.ZERO
        else vertices.fold(Vec3.ZERO) { acc, v -> acc + v } / vertices.size.toFloat()
    }

    /** Lowest and highest vertex, metres. */
    val minY: Float by lazy { vertices.minOfOrNull { it.y } ?: 0f }
    val maxY: Float by lazy { vertices.maxOfOrNull { it.y } ?: 0f }

    /** True for a plane whose normal is within 30 degrees of the vertical (floor, table top, ceiling). */
    val horizontal: Boolean
        get() = kotlin.math.abs(normal.y) > 0.866f

    /** True for a plane whose normal is within 30 degrees of the horizontal (walls, doors). */
    val vertical: Boolean
        get() = kotlin.math.abs(normal.y) < 0.5f

    /** `SEMANTIC:uuid8`, the name used for the plane's collider entity and in logs. */
    val label: String
        get() = "$semanticName:${id.take(8)}"
}

/** Result of a ray / surface intersection. [normal] faces the ray origin. */
data class SurfaceHit(
    val point: Vec3,
    val normal: Vec3,
    val distance: Float,
    val plane: PlaneSurface,
)

/** A pointing ray in Stage space. */
data class PointerRay(val origin: Vec3, val direction: Vec3)
