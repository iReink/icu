package com.example.icu

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class TrackTrimBottomSheet(
    private val activity: AppCompatActivity,
    private val model: TrackTrimEditorModel,
    private val onPreview: (List<TrackPoint>, TrackTrimDisplaySection, () -> Unit) -> Unit,
    private val onSave: (List<TrackPoint>, (Result<RecordedTrack>) -> Unit) -> Unit
) {
    private val dialog = BottomSheetDialog(activity)
    private lateinit var adapter: SectionAdapter
    private lateinit var undoButton: MaterialButton
    private lateinit var removePassiveButton: MaterialButton
    private lateinit var summaryText: TextView
    private lateinit var saveButton: MaterialButton
    private var saving = false

    fun show() {
        dialog.setContentView(buildContent())
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestDismiss()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(ContextCompat.getColor(activity, R.color.icu_screen_surface))
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                BottomSheetBehavior.from(it).apply {
                    isFitToContents = false
                    expandedOffset = 0
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    isDraggable = false
                }
                it.requestLayout()
            }
        }
        dialog.show()
    }

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.icu_screen_surface))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(20), 0)
            addView(MaterialButton(activity).apply {
                icon = ContextCompat.getDrawable(activity, R.drawable.ic_arrow_back)
                iconTint = ColorStateList.valueOf(Color.BLACK)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                text = ""
                contentDescription = activity.getString(R.string.back)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                insetTop = 0
                insetBottom = 0
                minWidth = 0
                minHeight = 0
                setPadding(dp(12), dp(12), dp(12), dp(12))
                elevation = 0f
                stateListAnimator = null
                setOnClickListener { requestDismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(activity).apply {
                text = activity.getString(R.string.trim_track_title)
                setTextColor(Color.BLACK)
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(72)
        ))

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(20))
        }
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.trim_track_hint)
            setTextColor(ContextCompat.getColor(activity, R.color.icu_text_secondary))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(20)
            }
        })

        val availableTimelineHeight = max(dp(300), activity.resources.displayMetrics.heightPixels - dp(390))
        adapter = SectionAdapter(availableTimelineHeight)
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@TrackTrimBottomSheet.adapter
            itemAnimator?.changeDuration = 140L
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setHasFixedSize(false)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val measuredHeight = height
                post { this@TrackTrimBottomSheet.adapter.updateAvailableHeight(measuredHeight) }
            }
        }
        ItemTouchHelper(SectionSwipeCallback { sectionId ->
            model.delete(sectionId)
            recycler.post { refresh() }
        }).attachToRecyclerView(recycler)
        val timelineCard = MaterialCardView(activity).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.WHITE)
            clipToOutline = true
            addView(recycler, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        content.addView(timelineCard)

        removePassiveButton = textActionButton(activity.getString(R.string.trim_remove_all_passive)) {
            model.deleteAllPassive()
            refresh()
        }
        content.addView(removePassiveButton)

        undoButton = textActionButton(activity.getString(R.string.undo_last_trim)) {
            model.undo()
            refresh()
        }
        content.addView(undoButton)

        summaryText = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.icu_text_primary))
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
        }
        content.addView(summaryText)

        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(outlinedButton(activity.getString(R.string.cancel)) { requestDismiss() })
            saveButton = filledButton(activity.getString(R.string.save)) { save() }
            addView(saveButton)
        })
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        refresh()
        return root
    }

    private fun refresh() {
        adapter.submit(model.displaySections())
        removePassiveButton.visibility = if (model.hasPassiveSections) View.VISIBLE else View.GONE
        undoButton.visibility = if (model.canUndo) View.VISIBLE else View.INVISIBLE
        val distance = formatDistance(model.distanceMeters())
        val duration = formatDuration(model.durationMillis())
        summaryText.text = activity.getString(R.string.trim_summary, distance, duration)
        saveButton.isEnabled = model.canSave && !saving
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
    }

    private fun save() {
        if (!model.canSave || saving) return
        saving = true
        refresh()
        onSave(model.buildPoints()) { result ->
            saving = false
            if (result.isSuccess) {
                dialog.dismiss()
            } else {
                refresh()
            }
        }
    }

    private fun requestDismiss() {
        if (saving) return
        if (!model.hasChanges) {
            dialog.dismiss()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.trim_discard_title)
            .setMessage(R.string.trim_discard_message)
            .setNegativeButton(R.string.return_to_route, null)
            .setPositiveButton(R.string.discard_changes) { _, _ -> dialog.dismiss() }
            .show()
    }

    private fun filledButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            text = label
            styleButton(filled = true)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
        }
    }

    private fun outlinedButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            text = label
            styleButton(filled = false)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
        }
    }

    private fun textActionButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            text = label
            isAllCaps = false
            letterSpacing = 0f
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(activity, R.color.icu_primary_button))
            elevation = 0f
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(2) }
        }
    }

    private fun MaterialButton.styleButton(filled: Boolean) {
        isAllCaps = false
        letterSpacing = 0f
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        insetTop = 0
        insetBottom = 0
        minHeight = 0
        minimumHeight = 0
        cornerRadius = dp(24)
        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
            .setAllCorners(CornerFamily.ROUNDED, dp(24).toFloat())
            .build()
        if (filled) {
            backgroundTintList = ContextCompat.getColorStateList(activity, R.color.icu_primary_button)
            setTextColor(Color.WHITE)
            elevation = dp(3).toFloat()
        } else {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(ContextCompat.getColor(activity, R.color.icu_primary_button))
            strokeColor = ContextCompat.getColorStateList(activity, R.color.icu_primary_button)
            strokeWidth = dp(1)
            elevation = 0f
            stateListAnimator = null
        }
    }

    private inner class SectionAdapter(
        initialAvailableHeight: Int
    ) : RecyclerView.Adapter<SectionHolder>() {
        private var sections: List<TrackTrimDisplaySection> = emptyList()
        private var totalDuration = 1L
        private var availableHeight = initialAvailableHeight
        private var rowHeights: List<Int> = emptyList()

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = sections[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.WHITE)
            }
            val stripe = View(parent.context)
            row.addView(stripe, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
            val labels = FrameLayout(parent.context).apply {
                setPadding(dp(12), 0, dp(12), 0)
            }
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return SectionHolder(row, stripe, labels)
        }

        override fun onBindViewHolder(holder: SectionHolder, position: Int) {
            val section = sections[position]
            holder.itemView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sectionHeight(position)
            )
            holder.stripe.setBackgroundColor(
                if (section.isActive) ContextCompat.getColor(activity, R.color.icu_primary_button)
                else PASSIVE_SECTION_COLOR
            )
            holder.labels.removeAllViews()
            holder.itemView.setOnLongClickListener {
                val points = model.pointsForDisplaySection(section.id)
                if (points.isEmpty()) return@setOnLongClickListener false
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onPreview(points, section) {
                    model.delete(section.id)
                    refresh()
                }
                true
            }
            val isShort = sectionHeight(position) <= dp(72) && sections.size > 1
            if (isShort) {
                holder.labels.addView(label(compactLabel(section, position)), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
                ))
            } else if (!section.isActive) {
                if (position == 0) {
                    holder.labels.addView(label(startLabel(section, position)), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP
                    ))
                }
                holder.labels.addView(
                    label(activity.getString(R.string.trim_pause, formatSectionDuration(section.durationMillis))),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL
                    )
                )
                if (position == sections.lastIndex) {
                    holder.labels.addView(label(endLabel(section, position)), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    ))
                }
            } else {
                holder.labels.addView(label(startLabel(section, position)), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ))
                holder.labels.addView(label(endLabel(section, position)), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ))
            }
        }

        override fun getItemCount(): Int = sections.size

        fun sectionIdAt(position: Int): String? = sections.getOrNull(position)?.id

        fun submit(updated: List<TrackTrimDisplaySection>) {
            val previous = sections
            totalDuration = updated.sumOf { max(it.durationMillis, 1_000L) }.coerceAtLeast(1L)
            sections = updated
            calculateRowHeights()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = updated.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].id == updated[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == updated[newItemPosition]
            })
            diff.dispatchUpdatesTo(this)
        }

        fun updateAvailableHeight(updatedHeight: Int) {
            if (updatedHeight <= 0 || updatedHeight == availableHeight) return
            availableHeight = updatedHeight
            calculateRowHeights()
            notifyItemRangeChanged(0, itemCount)
        }

        private fun calculateRowHeights() {
            if (sections.isEmpty()) {
                rowHeights = emptyList()
                return
            }
            val minimum = if (sections.size == 1) dp(80) else dp(44)
            val heights = sections.map { section ->
                val proportional = availableHeight * max(section.durationMillis, 1_000L) / totalDuration
                max(minimum, proportional.toInt())
            }.toMutableList()
            val remainder = availableHeight - heights.sum()
            if (remainder > 0) heights[heights.lastIndex] += remainder
            rowHeights = heights
        }

        private fun sectionHeight(position: Int): Int {
            return rowHeights.getOrElse(position) { dp(80) }
        }

        private fun startLabel(section: TrackTrimDisplaySection, position: Int): String {
            val time = formatTime(section.startTimeMillis)
            return if (position == 0) activity.getString(R.string.trim_start, time)
            else "$time · ${formatDistance(section.startDistanceMeters)}"
        }

        private fun endLabel(section: TrackTrimDisplaySection, position: Int): String {
            val time = formatTime(section.endTimeMillis)
            return if (position == sections.lastIndex) {
                activity.getString(R.string.trim_finish, time, formatDistance(section.endDistanceMeters))
            } else {
                "$time · ${formatDistance(section.endDistanceMeters)}"
            }
        }

        private fun compactLabel(section: TrackTrimDisplaySection, position: Int): String {
            val content = if (section.isActive) {
                activity.getString(
                    R.string.trim_compact_range,
                    formatTime(section.startTimeMillis),
                    formatTime(section.endTimeMillis),
                    formatDistance(section.endDistanceMeters - section.startDistanceMeters)
                )
            } else {
                activity.getString(R.string.trim_pause, formatSectionDuration(section.durationMillis))
            }
            return when (position) {
                0 -> activity.getString(R.string.trim_compact_start, content)
                sections.lastIndex -> activity.getString(R.string.trim_compact_finish, content)
                else -> content
            }
        }

        private fun label(value: String) = TextView(activity).apply {
            text = value
            setTextColor(ContextCompat.getColor(activity, R.color.icu_text_primary))
            textSize = 13f
            maxLines = 1
        }
    }

    private inner class SectionSwipeCallback(
        private val onDelete: (String) -> Unit
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(activity, R.color.icu_danger) }
        private val deleteIcon = ContextCompat.getDrawable(activity, R.drawable.ic_delete)?.mutate()?.apply {
            setTint(Color.WHITE)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            val sectionId = adapter.sectionIdAt(position)
            if (sectionId != null) onDelete(sectionId)
            else if (position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position)
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.35f

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val item = viewHolder.itemView
            if (dX != 0f) {
                val left = if (dX > 0) item.left.toFloat() else item.right + dX
                val right = if (dX > 0) item.left + dX else item.right.toFloat()
                canvas.drawRect(left, item.top.toFloat(), right, item.bottom.toFloat(), paint)
                deleteIcon?.let { icon ->
                    val size = dp(24)
                    val centerX = if (dX > 0) item.left + dp(28) else item.right - dp(28)
                    val centerY = (item.top + item.bottom) / 2
                    icon.setBounds(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2)
                    icon.draw(canvas)
                }
            }
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private class SectionHolder(
        itemView: View,
        val stripe: View,
        val labels: FrameLayout
    ) : RecyclerView.ViewHolder(itemView)

    private fun formatTime(timeMillis: Long): String = TIME_FORMATTER.format(Instant.ofEpochMilli(timeMillis))

    private fun formatDistance(meters: Float): String =
        String.format(Locale.forLanguageTag("ru-RU"), "%.2f км", meters / 1000f)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) activity.getString(R.string.trim_duration_hours, hours, minutes)
        else activity.getString(R.string.trim_duration_minutes, minutes)
    }

    private fun formatSectionDuration(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(1L)
        if (totalSeconds < 60L) return activity.getString(R.string.trim_duration_seconds, totalSeconds)
        val totalMinutes = totalSeconds / 60L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) activity.getString(R.string.trim_duration_hours, hours, minutes)
        else activity.getString(R.string.trim_duration_minutes, totalMinutes)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        private const val PASSIVE_SECTION_COLOR = 0xFFB88AEA.toInt()
    }
}
