package com.example.icu

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TrackMotionKind(val isActive: Boolean) {
    PASSIVE(false),
    ACTIVE(true),
    FAST(true)
}

data class AnalyzedTrackSection(
    val id: Int,
    val startIndex: Int,
    val endIndex: Int,
    val kind: TrackMotionKind
)

data class TrackTrimDisplaySection(
    val id: String,
    val sourceIds: Set<Int>,
    val kind: TrackMotionKind,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val startDistanceMeters: Float,
    val endDistanceMeters: Float
) {
    val durationMillis: Long = (endTimeMillis - startTimeMillis).coerceAtLeast(0L)
    val isActive: Boolean = kind.isActive
}

object TrackGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_MOVEMENT_METERS_PER_SECOND = 0.5

    fun distanceMeters(first: TrackPoint, second: TrackPoint): Float {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return (2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))).toFloat()
    }

    fun meaningfulDistanceMeters(first: TrackPoint, second: TrackPoint): Float {
        if (second.startsNewSegment) return 0f
        val distance = distanceMeters(first, second)
        val elapsedSeconds = ((second.timeMillis - first.timeMillis).coerceAtLeast(0L) / 1000.0)
            .takeIf { it > 0.0 } ?: 1.0
        return if (distance > MIN_MOVEMENT_METERS_PER_SECOND * elapsedSeconds) distance else 0f
    }
}

object TrackTrimAnalyzer {
    private data class AnalysisConfig(
        val windowMillis: Long,
        val activeSpeedMetersPerSecond: Double,
        val pauseMinMillis: Long,
        val pauseMaxMillis: Long,
        val tinyActiveDurationMillis: Long,
        val tinyActiveDistanceMeters: Float,
        val fastAbsoluteSpeedMetersPerSecond: Double,
        val fastMinDurationMillis: Long
    )

    private data class PointMotion(val active: Boolean, val speed: Double)

    fun canAnalyze(points: List<TrackPoint>): Boolean {
        if (points.size < 2) return false
        if (points.last().timeMillis <= points.first().timeMillis) return false
        val orderedPairs = points.zipWithNext().count { (first, second) ->
            second.startsNewSegment || second.timeMillis >= first.timeMillis
        }
        return orderedPairs >= (points.size - 1) * 0.9
    }

    fun analyze(points: List<TrackPoint>, type: TrackType): List<AnalyzedTrackSection> {
        require(type != TrackType.CUSTOM) { "Manual tracks use the ruler editor" }
        require(canAnalyze(points)) { "Track does not contain usable timestamps" }
        val config = config(type)
        val segmentBounds = segmentBounds(points)
        val motion = MutableList(points.size) { PointMotion(active = false, speed = 0.0) }

        segmentBounds.forEach { bounds ->
            val cumulativeDistance = FloatArray(bounds.last - bounds.first + 1)
            if (bounds.first < bounds.last) {
                for (index in (bounds.first + 1)..bounds.last) {
                    cumulativeDistance[index - bounds.first] =
                        cumulativeDistance[index - bounds.first - 1] +
                        TrackGeometry.distanceMeters(points[index - 1], points[index])
                }
            }
            val segmentStartTime = points[bounds.first].timeMillis
            val segmentEndTime = points[bounds.last].timeMillis
            var left = bounds.first
            var right = bounds.first
            for (index in bounds) {
                val halfWindow = config.windowMillis / 2L
                val centerTime = points[index].timeMillis
                var windowStart = centerTime - halfWindow
                var windowEnd = centerTime + halfWindow
                if (windowStart < segmentStartTime) {
                    windowEnd = min(segmentEndTime, windowEnd + segmentStartTime - windowStart)
                    windowStart = segmentStartTime
                }
                if (windowEnd > segmentEndTime) {
                    windowStart = max(segmentStartTime, windowStart - (windowEnd - segmentEndTime))
                    windowEnd = segmentEndTime
                }
                while (left < index && points[left].timeMillis < windowStart) left++
                if (right < index) right = index
                while (right < bounds.last && points[right + 1].timeMillis <= windowEnd) right++
                if (right <= left) continue

                val pathDistance = cumulativeDistance[right - bounds.first] -
                    cumulativeDistance[left - bounds.first]
                val elapsedSeconds = (points[right].timeMillis - points[left].timeMillis) / 1000.0
                if (elapsedSeconds <= 0.0 || pathDistance <= 0f) continue
                val displacement = TrackGeometry.distanceMeters(points[left], points[right])
                val speed = displacement / elapsedSeconds
                val directionality = displacement / pathDistance
                motion[index] = PointMotion(
                    active = speed >= config.activeSpeedMetersPerSecond && directionality >= MIN_DIRECTIONALITY,
                    speed = speed
                )
            }
        }

        val activeSpeeds = motion.filter { it.active }.map { it.speed }.sorted()
        val medianActiveSpeed = activeSpeeds.getOrNull(activeSpeeds.size / 2) ?: 0.0
        val bikeFastThreshold = min(
            config.fastAbsoluteSpeedMetersPerSecond,
            max(8.0, medianActiveSpeed * BIKE_FAST_SPEED_MULTIPLIER)
        )
        val pointKinds = motion.map { pointMotion ->
            when {
                !pointMotion.active -> TrackMotionKind.PASSIVE
                type == TrackType.WALK && pointMotion.speed >= config.fastAbsoluteSpeedMetersPerSecond -> TrackMotionKind.FAST
                type == TrackType.BIKE && pointMotion.speed >= bikeFastThreshold -> TrackMotionKind.FAST
                else -> TrackMotionKind.ACTIVE
            }
        }.toMutableList()

        var runs = buildRuns(pointKinds)
        runs.filter { it.kind == TrackMotionKind.FAST && duration(points, it) < config.fastMinDurationMillis }
            .forEach { run -> fill(pointKinds, run, TrackMotionKind.ACTIVE) }
        runs = buildRuns(pointKinds)

        runs.forEachIndexed { index, run ->
            if (run.kind != TrackMotionKind.ACTIVE) return@forEachIndexed
            val surroundedByPassive = runs.getOrNull(index - 1)?.kind == TrackMotionKind.PASSIVE &&
                runs.getOrNull(index + 1)?.kind == TrackMotionKind.PASSIVE
            if (surroundedByPassive &&
                duration(points, run) < config.tinyActiveDurationMillis &&
                rawDistance(points, run) < config.tinyActiveDistanceMeters
            ) {
                fill(pointKinds, run, TrackMotionKind.PASSIVE)
            }
        }
        runs = buildRuns(pointKinds)

        val totalDuration = (points.last().timeMillis - points.first().timeMillis).coerceAtLeast(0L)
        val pauseThreshold = (totalDuration * PAUSE_DURATION_RATIO)
            .toLong()
            .coerceIn(config.pauseMinMillis, config.pauseMaxMillis)
        runs.forEachIndexed { index, run ->
            if (run.kind != TrackMotionKind.PASSIVE || duration(points, run) >= pauseThreshold) return@forEachIndexed
            val previous = runs.getOrNull(index - 1)?.kind?.takeIf { it.isActive }
            val next = runs.getOrNull(index + 1)?.kind?.takeIf { it.isActive }
            if (previous != null && next != null) {
                fill(pointKinds, run, if (previous == next) previous else TrackMotionKind.ACTIVE)
            }
        }

        return buildRuns(pointKinds).mapIndexed { id, run -> run.copy(id = id) }
    }

    private fun config(type: TrackType): AnalysisConfig = when (type) {
        TrackType.WALK -> AnalysisConfig(
            windowMillis = 20_000L,
            activeSpeedMetersPerSecond = 0.45,
            pauseMinMillis = 60_000L,
            pauseMaxMillis = 5 * 60_000L,
            tinyActiveDurationMillis = 30_000L,
            tinyActiveDistanceMeters = 30f,
            fastAbsoluteSpeedMetersPerSecond = 3.0,
            fastMinDurationMillis = 30_000L
        )
        TrackType.BIKE -> AnalysisConfig(
            windowMillis = 12_000L,
            activeSpeedMetersPerSecond = 1.2,
            pauseMinMillis = 45_000L,
            pauseMaxMillis = 3 * 60_000L,
            tinyActiveDurationMillis = 20_000L,
            tinyActiveDistanceMeters = 60f,
            fastAbsoluteSpeedMetersPerSecond = 12.0,
            fastMinDurationMillis = 20_000L
        )
        TrackType.CUSTOM -> error("Manual tracks are not analyzed")
    }

    private fun segmentBounds(points: List<TrackPoint>): List<IntRange> {
        val starts = mutableListOf(0)
        points.forEachIndexed { index, point ->
            if (index > 0 && point.startsNewSegment) starts.add(index)
        }
        return starts.mapIndexed { index, start ->
            start..((starts.getOrNull(index + 1) ?: points.size) - 1)
        }
    }

    private fun buildRuns(kinds: List<TrackMotionKind>): List<AnalyzedTrackSection> {
        if (kinds.isEmpty()) return emptyList()
        val result = mutableListOf<AnalyzedTrackSection>()
        var start = 0
        for (index in 1..kinds.size) {
            if (index == kinds.size || kinds[index] != kinds[start]) {
                result.add(AnalyzedTrackSection(result.size, start, index - 1, kinds[start]))
                start = index
            }
        }
        return result
    }

    private fun fill(kinds: MutableList<TrackMotionKind>, run: AnalyzedTrackSection, kind: TrackMotionKind) {
        for (index in run.startIndex..run.endIndex) kinds[index] = kind
    }

    private fun duration(points: List<TrackPoint>, run: AnalyzedTrackSection): Long {
        return (points[run.endIndex].timeMillis - points[run.startIndex].timeMillis).coerceAtLeast(0L)
    }

    private fun rawDistance(points: List<TrackPoint>, run: AnalyzedTrackSection): Float {
        var distance = 0f
        for (index in (run.startIndex + 1)..run.endIndex) {
            if (!points[index].startsNewSegment) {
                distance += TrackGeometry.distanceMeters(points[index - 1], points[index])
            }
        }
        return distance
    }

    private const val MIN_DIRECTIONALITY = 0.25f
    private const val PAUSE_DURATION_RATIO = 0.02
    private const val BIKE_FAST_SPEED_MULTIPLIER = 2.2
}

class TrackTrimEditorModel(
    private val points: List<TrackPoint>,
    private val sections: List<AnalyzedTrackSection>
) {
    private val retainedIds = sections.mapTo(linkedSetOf()) { it.id }
    private val history = mutableListOf<Set<Int>>()

    val hasChanges: Boolean get() = history.isNotEmpty()
    val canUndo: Boolean get() = history.isNotEmpty()
    val hasPassiveSections: Boolean get() = retainedSections().any { !it.kind.isActive }
    val canSave: Boolean get() = retainedSections().any { it.kind.isActive } && buildPoints().size >= 2

    fun displaySections(): List<TrackTrimDisplaySection> {
        val retained = retainedSections()
        if (retained.isEmpty()) return emptyList()
        val result = mutableListOf<MutableDisplaySection>()
        var cumulativeDistance = 0f
        var previousSourceIndex: Int? = null

        retained.forEach { section ->
            val sectionStartDistance = cumulativeDistance
            for (index in section.startIndex..section.endIndex) {
                val previous = previousSourceIndex
                if (previous != null && index == previous + 1 && !points[index].startsNewSegment) {
                    cumulativeDistance += TrackGeometry.meaningfulDistanceMeters(points[previous], points[index])
                }
                previousSourceIndex = index
            }
            val last = result.lastOrNull()
            if (last != null && last.kind == section.kind) {
                last.sourceIds.add(section.id)
                last.endTimeMillis = points[section.endIndex].timeMillis
                last.endDistanceMeters = cumulativeDistance
            } else {
                result.add(
                    MutableDisplaySection(
                        sourceIds = linkedSetOf(section.id),
                        kind = section.kind,
                        startTimeMillis = points[section.startIndex].timeMillis,
                        endTimeMillis = points[section.endIndex].timeMillis,
                        startDistanceMeters = sectionStartDistance,
                        endDistanceMeters = cumulativeDistance
                    )
                )
            }
        }
        return result.map { it.toDisplaySection() }
    }

    fun delete(displaySectionId: String) {
        val displayed = displaySections()
        val selectedIndex = displayed.indexOfFirst { it.id == displaySectionId }
        if (selectedIndex < 0) return
        history.add(retainedIds.toSet())
        val selected = displayed[selectedIndex]
        val idsToDelete = selected.sourceIds.toMutableSet()
        if (selected.isActive) {
            val activeIndices = displayed.indices.filter { displayed[it].isActive }
            val activePosition = activeIndices.indexOf(selectedIndex)
            when {
                activePosition == 0 -> displayed.getOrNull(selectedIndex + 1)
                    ?.takeIf { !it.isActive }?.let { idsToDelete.addAll(it.sourceIds) }
                activePosition == activeIndices.lastIndex -> displayed.getOrNull(selectedIndex - 1)
                    ?.takeIf { !it.isActive }?.let { idsToDelete.addAll(it.sourceIds) }
                else -> {
                    displayed.getOrNull(selectedIndex - 1)?.takeIf { !it.isActive }
                        ?.let { idsToDelete.addAll(it.sourceIds) }
                    displayed.getOrNull(selectedIndex + 1)?.takeIf { !it.isActive }
                        ?.let { idsToDelete.addAll(it.sourceIds) }
                }
            }
        }
        retainedIds.removeAll(idsToDelete)
        pruneOuterPassiveSections()
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        retainedIds.clear()
        retainedIds.addAll(previous)
    }

    fun deleteAllPassive() {
        val passiveIds = retainedSections().filterNot { it.kind.isActive }.mapTo(mutableSetOf()) { it.id }
        if (passiveIds.isEmpty()) return
        history.add(retainedIds.toSet())
        retainedIds.removeAll(passiveIds)
        pruneOuterPassiveSections()
    }

    fun pointsForDisplaySection(displaySectionId: String): List<TrackPoint> {
        val sectionIds = displaySections().firstOrNull { it.id == displaySectionId }?.sourceIds ?: return emptyList()
        return buildPoints(sections.filter { sectionIds.contains(it.id) })
    }

    fun buildPoints(): List<TrackPoint> {
        return buildPoints(retainedSections())
    }

    private fun buildPoints(sourceSections: List<AnalyzedTrackSection>): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        var previousSourceIndex: Int? = null
        sourceSections.forEach { section ->
            for (index in section.startIndex..section.endIndex) {
                val point = points[index]
                val startsNewSegment = result.isNotEmpty() &&
                    (previousSourceIndex == null || index != previousSourceIndex!! + 1 || point.startsNewSegment)
                result.add(point.copy(startsNewSegment = startsNewSegment))
                previousSourceIndex = index
            }
        }
        return result
    }

    fun distanceMeters(): Float {
        val retainedPoints = buildPoints()
        var distance = 0f
        retainedPoints.zipWithNext { first, second ->
            distance += TrackGeometry.meaningfulDistanceMeters(first, second)
        }
        return distance
    }

    fun durationMillis(): Long {
        val retainedPoints = buildPoints()
        return (retainedPoints.lastOrNull()?.timeMillis.orZero() - retainedPoints.firstOrNull()?.timeMillis.orZero())
            .coerceAtLeast(0L)
    }

    private fun retainedSections(): List<AnalyzedTrackSection> = sections.filter { retainedIds.contains(it.id) }

    private fun pruneOuterPassiveSections() {
        val retained = retainedSections()
        val firstActive = retained.indexOfFirst { it.kind.isActive }
        val lastActive = retained.indexOfLast { it.kind.isActive }
        if (firstActive < 0 || lastActive < 0) {
            retainedIds.clear()
            return
        }
        retained.take(firstActive).forEach { retainedIds.remove(it.id) }
        retained.drop(lastActive + 1).forEach { retainedIds.remove(it.id) }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private data class MutableDisplaySection(
        val sourceIds: LinkedHashSet<Int>,
        val kind: TrackMotionKind,
        val startTimeMillis: Long,
        var endTimeMillis: Long,
        val startDistanceMeters: Float,
        var endDistanceMeters: Float
    ) {
        fun toDisplaySection() = TrackTrimDisplaySection(
            id = sourceIds.joinToString("-"),
            sourceIds = sourceIds,
            kind = kind,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            startDistanceMeters = startDistanceMeters,
            endDistanceMeters = endDistanceMeters
        )
    }
}
