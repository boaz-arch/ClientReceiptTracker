package com.company.supportvisit

import com.company.supportvisit.data.TaskTime
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.TaskEntity
import com.company.supportvisit.databinding.ActivityTasksBinding
import com.company.supportvisit.databinding.ItemTaskBinding
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import com.company.supportvisit.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/** To-do list: my tasks, tasks I assigned, all tasks (managers), done. */
class TasksActivity : AppCompatActivity() {

    enum class Filter { MINE, ASSIGNED_BY_ME, ALL, DONE }

    private lateinit var binding: ActivityTasksBinding

    private val filter = MutableStateFlow(Filter.MINE)
    private var clientsById: Map<String, ClientEntity> = emptyMap()

    private val adapter = TaskAdapter(
        onClick = { startActivity(TaskEditActivity.intent(this, it.id)) },
        onToggle = { task, done ->
            lifecycleScope.launch {
                app.taskRepository.setDone(task, done)
                SyncScheduler.enqueue(this@TasksActivity)
            }
        },
        meta = ::metaLine
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        binding.rvTasks.layoutManager = LinearLayoutManager(this)
        binding.rvTasks.adapter = adapter
        binding.btnAddTask.setOnClickListener { startActivity(TaskEditActivity.intent(this, null)) }

        // "All" is for managers and admins (the server only sends technicians their own tasks anyway).
        binding.chipAll.isVisible = TeamSession.role(this).canSeeEverything
        binding.chipFilter.setOnCheckedStateChangeListener { _, ids ->
            filter.value = when (ids.firstOrNull()) {
                R.id.chipAssigned -> Filter.ASSIGNED_BY_ME
                R.id.chipAll -> Filter.ALL
                R.id.chipDone -> Filter.DONE
                else -> Filter.MINE
            }
        }

        val me = TeamSession.userId(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.clientRepository.observeClients().collect { list -> clientsById = list.associateBy { it.id } } }
                combine(app.taskRepository.observeTasks(), filter) { tasks, f -> filterTasks(tasks, f, me) }
                    .collect { list ->
                        adapter.submitList(list)
                        binding.tvEmpty.isVisible = list.isEmpty()
                    }
            }
        }
    }

    private fun filterTasks(tasks: List<TaskEntity>, f: Filter, me: String): List<TaskEntity> = when (f) {
        Filter.MINE -> tasks.filter { !it.done && (it.assigneeId == me || (it.assigneeId == null && it.createdById == me) || me.isBlank()) }
        Filter.ASSIGNED_BY_ME -> tasks.filter { !it.done && it.createdById == me && it.assigneeId != null && it.assigneeId != me }
        Filter.ALL -> tasks.filter { !it.done }
        Filter.DONE -> tasks.filter { it.done }.sortedByDescending { it.doneAt ?: 0 }
    }

    private fun metaLine(task: TaskEntity): Pair<String, Boolean> {
        val parts = mutableListOf<String>()
        var overdue = false
        val me = TeamSession.userId(this)
        if (TaskTime.running(task.timeEntries, me.ifBlank { null }) != null) parts += getString(R.string.timer_running)
        val minutes = TaskTime.totalMinutes(task.timeEntries)
        if (minutes > 0) parts += getString(R.string.task_hours, TaskTime.format(minutes))
        task.dueDate?.let { due ->
            parts += DateUtils.formatForDisplay(due)
            overdue = !task.done && runCatching { LocalDate.parse(due).isBefore(LocalDate.now()) }.getOrDefault(false)
        }
        task.clientId?.let { clientsById[it]?.name }?.let { parts += it }
        if (task.assigneeId != null && task.assigneeId != me) parts += getString(R.string.task_for, task.assigneeName.orEmpty())
        if (task.createdById != null && task.createdById != me && task.assigneeId == me && !task.createdByName.isNullOrBlank()) {
            parts += getString(R.string.task_from, task.createdByName)
        }
        return parts.joinToString(" · ") to overdue
    }

    private class TaskAdapter(
        private val onClick: (TaskEntity) -> Unit,
        private val onToggle: (TaskEntity, Boolean) -> Unit,
        private val meta: (TaskEntity) -> Pair<String, Boolean>
    ) : ListAdapter<TaskEntity, TaskAdapter.ViewHolder>(DIFF) {

        class ViewHolder(val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val task = getItem(position)
            val b = holder.binding
            b.tvTitle.text = task.title
            b.tvTitle.paintFlags = if (task.done) b.tvTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            else b.tvTitle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            val (line, overdue) = meta(task)
            b.tvMeta.text = line
            b.tvMeta.isVisible = line.isNotEmpty()
            b.tvMeta.setTextColor(b.root.context.getColor(if (overdue) R.color.status_failed else R.color.text_secondary))
            b.cbDone.setOnCheckedChangeListener(null)
            b.cbDone.isChecked = task.done
            b.cbDone.setOnCheckedChangeListener { _, checked -> onToggle(task, checked) }
            b.root.setOnClickListener { onClick(task) }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<TaskEntity>() {
                override fun areItemsTheSame(old: TaskEntity, new: TaskEntity) = old.id == new.id
                override fun areContentsTheSame(old: TaskEntity, new: TaskEntity) = old == new
            }
        }
    }
}
