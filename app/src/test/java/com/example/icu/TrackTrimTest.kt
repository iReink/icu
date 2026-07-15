package com.example.icu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTrimTest {
    @Test
    fun stationaryGpsCloudIsPassive() {
        val points = (0..180).map { second ->
            point(
                metersEast = if (second % 2 == 0) 1.2 else -1.2,
                second = second
            )
        }

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)

        assertTrue(sections.all { !it.kind.isActive })
    }

    @Test
    fun regularWalkIsMostlyActive() {
        val points = (0..300).map { second -> point(second * 1.2, second) }

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)
        val activePoints = sections.filter { it.kind.isActive }.sumOf { it.endIndex - it.startIndex + 1 }

        assertTrue(activePoints > points.size * 0.8)
    }

    @Test
    fun movementAtTrackBoundaryIsNotMisclassifiedAsPause() {
        val points = (0..20).map { second -> point(second * 1.2, second) }

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)

        assertTrue(sections.first().kind.isActive)
        assertEquals(0, sections.first().startIndex)
    }

    @Test
    fun shortPauseIsMergedButLongPauseRemains() {
        val shortPause = walkingTrackWithPause(pauseSeconds = 30)
        val longPause = walkingTrackWithPause(pauseSeconds = 120)

        val shortSections = TrackTrimAnalyzer.analyze(shortPause, TrackType.WALK)
        val longSections = TrackTrimAnalyzer.analyze(longPause, TrackType.WALK)

        assertFalse(shortSections.any { !it.kind.isActive && sectionDuration(shortPause, it) >= 20_000L })
        assertTrue(longSections.any { !it.kind.isActive && sectionDuration(longPause, it) >= 60_000L })
    }

    @Test
    fun shortPauseAtTrackEndMergesIntoMovement() {
        val points = mutableListOf<TrackPoint>()
        var east = 0.0
        repeat(121) { second ->
            points.add(point(east, second))
            east += 1.2
        }
        repeat(10) { offset -> points.add(point(east, 121 + offset)) }

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)

        assertTrue(sections.last().kind.isActive)
    }

    @Test
    fun deletingMiddlePauseCreatesSegmentBreakWithoutCompressingTime() {
        val points = (0..7).map { index -> point(index * 2.0, index) }
        val sections = listOf(
            AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE)
        )
        val model = TrackTrimEditorModel(points, sections)

        model.delete("1")
        val result = model.buildPoints()

        assertEquals(6, result.size)
        assertTrue(result[3].startsNewSegment)
        assertEquals(7_000L, model.durationMillis())
        assertTrue(model.canUndo)
    }

    @Test
    fun deletingFirstActiveSectionAlsoRemovesFollowingPause() {
        val points = (0..7).map { index -> point(index * 2.0, index) }
        val sections = listOf(
            AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE)
        )
        val model = TrackTrimEditorModel(points, sections)

        model.delete("0")

        assertEquals(3, model.buildPoints().size)
        assertEquals(points[5].timeMillis, model.buildPoints().first().timeMillis)
        assertEquals(2_000L, model.durationMillis())
    }

    @Test
    fun undoRestoresEveryDeletion() {
        val points = (0..11).map { index -> point(index * 2.0, index) }
        val sections = listOf(
            AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(3, 8, 9, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(4, 10, 11, TrackMotionKind.ACTIVE)
        )
        val model = TrackTrimEditorModel(points, sections)

        model.delete("2")
        model.delete(model.displaySections().first().id)
        model.undo()
        model.undo()

        assertEquals(points, model.buildPoints())
        assertFalse(model.hasChanges)
    }

    @Test
    fun deleteAllPassiveIsOneUndoableOperation() {
        val points = (0..11).map { index -> point(index * 2.0, index) }
        val model = TrackTrimEditorModel(
            points,
            listOf(
                AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
                AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
                AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE),
                AnalyzedTrackSection(3, 8, 9, TrackMotionKind.PASSIVE),
                AnalyzedTrackSection(4, 10, 11, TrackMotionKind.ACTIVE)
            )
        )

        model.deleteAllPassive()

        assertFalse(model.hasPassiveSections)
        assertEquals(8, model.buildPoints().size)
        model.undo()
        assertEquals(points, model.buildPoints())
        assertTrue(model.hasPassiveSections)
    }

    @Test
    fun previewContainsOnlySelectedSectionPoints() {
        val points = (0..7).map { index -> point(index * 2.0, index) }
        val model = TrackTrimEditorModel(
            points,
            listOf(
                AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
                AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
                AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE)
            )
        )

        val preview = model.pointsForDisplaySection("1")

        assertEquals(points.subList(3, 5), preview)
    }

    @Test
    fun deletingMiddleActiveSectionRemovesAdjacentPauses() {
        val points = (0..14).map { index -> point(index * 2.0, index) }
        val sections = listOf(
            AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE),
            AnalyzedTrackSection(3, 8, 9, TrackMotionKind.PASSIVE),
            AnalyzedTrackSection(4, 10, 14, TrackMotionKind.ACTIVE)
        )
        val model = TrackTrimEditorModel(points, sections)

        model.delete("2")
        val result = model.buildPoints()

        assertEquals(8, result.size)
        assertTrue(result[3].startsNewSegment)
        assertEquals(points[10].timeMillis, result[3].timeMillis)
        assertEquals(14_000L, model.durationMillis())
    }

    @Test
    fun connectedSegmentsNeverBridgeTrimmedGap() {
        val points = listOf(
            point(0.0, 0),
            point(2.0, 1),
            point(200.0, 5).copy(startsNewSegment = true),
            point(202.0, 6)
        )

        val segments = points.connectedSegments()

        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
        assertTrue(GpxTrackStore.calculateDistance(points) < 10f)
    }

    @Test
    fun sustainedCarRideAfterWalkBecomesSeparateFastSection() {
        val points = mutableListOf<TrackPoint>()
        var east = 0.0
        var second = 0
        repeat(121) {
            points.add(point(east, second++))
            east += 1.2
        }
        repeat(61) {
            points.add(point(east, second++))
            east += 10.0
        }

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)

        assertTrue(sections.any { it.kind == TrackMotionKind.FAST && sectionDuration(points, it) >= 30_000L })
    }

    @Test
    fun carTurnsAndTrafficLightsRemainOneFastSection() {
        val points = mutableListOf<TrackPoint>()
        var east = 0.0
        var second = 0
        fun append(durationSeconds: Int, metersPerSecond: Double) {
            repeat(durationSeconds) {
                points.add(point(east, second++))
                east += metersPerSecond
            }
        }
        append(121, 1.2)
        append(12, 2.0)
        append(123, 10.0)
        append(15, 0.0)
        append(45, 10.0)
        append(9, 2.0)
        append(10, 0.0)

        val sections = TrackTrimAnalyzer.analyze(points, TrackType.WALK)
        val fastSections = sections.filter { it.kind == TrackMotionKind.FAST }

        assertEquals(1, fastSections.size)
        assertEquals(points.lastIndex, fastSections.single().endIndex)
    }

    @Test
    fun deletingLastActiveSectionMovesFinishToPreviousActivity() {
        val points = (0..7).map { index -> point(index * 2.0, index) }
        val model = TrackTrimEditorModel(
            points,
            listOf(
                AnalyzedTrackSection(0, 0, 2, TrackMotionKind.ACTIVE),
                AnalyzedTrackSection(1, 3, 4, TrackMotionKind.PASSIVE),
                AnalyzedTrackSection(2, 5, 7, TrackMotionKind.ACTIVE)
            )
        )

        model.delete("2")

        assertEquals(3, model.buildPoints().size)
        assertEquals(points[2].timeMillis, model.buildPoints().last().timeMillis)
        assertEquals(2_000L, model.durationMillis())
    }

    private fun walkingTrackWithPause(pauseSeconds: Int): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        var second = 0
        var east = 0.0
        repeat(121) {
            result.add(point(east, second++))
            east += 1.2
        }
        repeat(pauseSeconds) {
            result.add(point(east + if (it % 2 == 0) 0.4 else -0.4, second++))
        }
        repeat(121) {
            result.add(point(east, second++))
            east += 1.2
        }
        return result
    }

    private fun point(metersEast: Double, second: Int): TrackPoint {
        val longitude = BASE_LONGITUDE + metersEast / METERS_PER_LONGITUDE_DEGREE
        return TrackPoint(BASE_LATITUDE, longitude, null, second * 1_000L)
    }

    private fun sectionDuration(points: List<TrackPoint>, section: AnalyzedTrackSection): Long {
        return points[section.endIndex].timeMillis - points[section.startIndex].timeMillis
    }

    companion object {
        private const val BASE_LATITUDE = 56.84
        private const val BASE_LONGITUDE = 60.61
        private const val METERS_PER_LONGITUDE_DEGREE = 62_200.0
    }
}
