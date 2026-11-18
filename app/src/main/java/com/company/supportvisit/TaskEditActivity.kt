package com.company.supportvisit

import com.company.supportvisit.repository.TaskDeletions
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.TaskEntity
import com.company.supportvisit.data.TaskTime
import com.company.supportvisit.data.TimeEntry
import com.company.supportvisit.databinding.ActivityTaskEditBinding
import com.company.supportvisit.databinding.DialogTimeEntryBinding
import com.company.supportvisit.databinding.ItemTimeEntryBinding
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import com.company.supportvisit.utils.DateUtils
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add or edit a task, optionally for a client and assigned to a teammate, and log the time
 * spent on it: a Start/Finish timer, or start and end times entered by hand. Logging time
 * only adds up hours; it never creates or sends a visit report.
 */
class TaskEditActivity : AppCompatActivity() {

    private data class Person(val userId: String, val name: String)

    private lateinit var binding: ActivityTaskEditBinding

    private var existing: TaskEntity? = null
    private var dueDate: String? = null
    private var clients: List<ClientEntity> = emptyList()
    private var clientId: String? = null
    private var people: List<Person> = emptyList()
    private var assignee: Person? = null

    /** Null on a phone working on its own (no team), which matches its own time entries. */
    private val myId: String? by lazy { TeamSession.userId(this).ifBlank { null } }
    private val myName: String get() = AuthSession.accountEmail.value?.substringBefore('@').orEmpty()

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setUpBackBar()

        val me = Person(TeamSession.userId(this), getString(R.string.me))
        people = listOf(me)
        assignee = me
        binding.actAssignee.setText(me.name, false)
        setPeople(people)

        binding.etDueDate.setOnClickListener { pickDate() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnTimer.setOnClickListener { toggleTimer() }
        binding.btnAddTime.setOnClickListener { showAddTimeDialog() }
        binding.btnDeleteTask.setOnClickListener { confirmDeleteTask() }
        binding.btnDeleteTask.isVisible = false
        renderTime(null)

        lifecycleScope.launch {
            clients = app.clientRepository.getClients()
            val labels = listOf(getString(R.string.none)) + clients.map { it.name }
            binding.actClient.setAdapter(ArrayAdapter(this@TaskEditActivity, android.R.layout.simple_dropdown_item_1line, labels))
            binding.actClient.setOnItemClickListener { _, _, i, _ -> clientId = if (i == 0) null else clients[i - 1].id }

            intent.getStringExtra(EXTRA_ID)?.let { id ->
                existing = app.taskRepository.getTask(id)
                existing?.let(::fill)
                renderTime(existing)
                binding.btnDeleteTask.isVisible = existing?.let(::canDelete) == true
            }
            loadTeam(me)
        }

        // Keeps the running timer's clock and the total up to date while the screen is open.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderTimer(existing)
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Teammates to assign to (needs the server; offline you can only assign to yourself). */
    private suspend fun loadTeam(me: Person) {
        val members = try {
            withContext(Dispatchers.IO) { TeamRepository.api(this@TaskEditActivity)?.members() }
        } catch (e: Exception) {
            null
        } ?: return
        val others = members.map { Person(it.optString("userId"), it.optString("name").ifBlank { it.optString("email") }) }
            .filter { it.userId != me.userId }
        setPeople(listOf(me) + others)
    }

    private fun setPeople(list: List<Person>) {
        people = list
        binding.actAssignee.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list.map { it.name }))
        binding.actAssignee.setOnItemClickListener { _, _, i, _ -> assignee = people[i] }
    }

    private fun fill(t: TaskEntity) {
        binding.tvTitle.setText(R.string.edit_task)
        binding.etTitle.setText(t.title)
        binding.etNotes.setText(t.notes)
        dueDate = t.dueDate
        binding.etDueDate.setText(t.dueDate?.let { DateUtils.formatForDisplay(it) })
        clientId = t.clientId
        clients.firstOrNull { it.id == t.clientId }?.let { binding.actClient.setText(it.name, false) }
        if (t.assigneeId != null && t.assigneeId != TeamSession.userId(this)) {
            assignee = Person(t.assigneeId, t.assigneeName.orEmpty())
            binding.actAssignee.setText(t.assigneeName, false)
        }
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText(R.string.hint_due_date).build()
        picker.addOnPositiveButtonClickListener { millis ->
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            dueDate = date.toString()
            binding.etDueDate.setText(DateUtils.formatForDisplay(dueDate!!))
        }
        picker.show(supportFragmentManager, "due")
    }

    /** The form as a task, on top of [base] (the saved version, so logged time is kept). */
    private fun buildTask(base: TaskEntity?): TaskEntity? {
        val title = binding.etTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.error_task_title, Toast.LENGTH_LONG).show()
            return null
        }
        val myId = TeamSession.userId(this)
        val target = assignee?.takeIf { it.userId.isNotBlank() && it.userId != myId }
        return (base ?: TaskEntity(id = "", title = title, createdById = myId, createdByName = myName)).copy(
            title = title,
            notes = binding.etNotes.text.toString(),
            dueDate = dueDate,
            clientId = clientId,
            assigneeId = target?.userId ?: myId.ifBlank { null },
            assigneeName = target?.name ?: myName
        )
    }

    private fun save() {
        lifecycleScope.launch {
            val base = existing?.let { app.taskRepository.getTask(it.id) ?: it }
            val task = buildTask(base) ?: return@launch
            binding.btnSave.isEnabled = false
            app.taskRepository.save(task)
            SyncScheduler.enqueue(this@TaskEditActivity)
            finish()
        }
    }

    /** Time can be logged on a new task too: it's saved first (so it needs a title). */
    private suspend fun ensureSaved(): TaskEntity? {
        existing?.let { return app.taskRepository.getTask(it.id) ?: it }
        val task = buildTask(null) ?: return null
        val saved = app.taskRepository.save(task)
        existing = saved
        binding.tvTitle.setText(R.string.edit_task)
        return saved
    }

    /** Its creator, or a manager/admin (a phone on its own counts as its admin). */
    private fun canDelete(task: TaskEntity): Boolean =
        task.createdById.isNullOrBlank() || task.createdById == TeamSession.userId(this) ||
            TeamSession.role(this).canSeeEverything

    private fun confirmDeleteTask() {
        val task = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_task_title)
            .setMessage(getString(R.string.delete_task_message, task.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    TaskDeletions.request(this@TaskEditActivity, task.id)
                    app.taskRepository.deleteLocal(task.id)
                    SyncScheduler.enqueue(this@TaskEditActivity)
                    Toast.makeText(this@TaskEditActivity, R.string.task_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Time spent ---

    private fun toggleTimer() {
        lifecycleScope.launch {
            val task = ensureSaved() ?: return@launch
            val repo = app.taskRepository
            if (TaskTime.running(task.timeEntries, myId) != null) {
                repo.finishTimer(task.id, myId)
            } else {
                repo.startTimer(task.id, myId, myName)?.let { other ->
                    Toast.makeText(this@TaskEditActivity, getString(R.string.timer_stopped_on, other.title), Toast.LENGTH_LONG).show()
                }
            }
            SyncScheduler.enqueue(this@TaskEditActivity)
            reloadTime()
        }
    }

    private fun showAddTimeDialog() {
        val d = DialogTimeEntryBinding.inflate(layoutInflater)
        var date = LocalDate.now()
        var start: LocalTime? = null
        var end: LocalTime? = null
        d.etEntryDate.setText(DateUtils.formatForDisplay(date.toString()))
        d.etEntryDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                d.etEntryDate.setText(DateUtils.formatForDisplay(date.toString()))
            }
            picker.show(supportFragmentManager, "entry_date")
        }
        d.etEntryStart.setOnClickListener {
            pickTime(start ?: end) { start = it; d.etEntryStart.setText(it.format(timeFormat)) }
        }
        d.etEntryEnd.setOnClickListener {
            pickTime(end ?: start) { end = it; d.etEntryEnd.setText(it.format(timeFormat)) }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_time_title)
            .setView(d.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = start
                val to = end
                when {
                    from == null || to == null -> toast(R.string.error_time_required)
                    !to.isAfter(from) -> toast(R.string.error_end_before_start)
                    else -> {
                        dialog.dismiss()
                        addEntry(date, from, to)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun addEntry(date: LocalDate, from: LocalTime, to: LocalTime) {
        lifecycleScope.launch {
            val task = ensureSaved() ?: return@launch
            val zone = ZoneId.systemDefault()
            app.taskRepository.addTimeEntry(
                task.id,
                TimeEntry(
                    userId = myId,
                    userName = myName,
                    start = date.atTime(from).atZone(zone).toInstant().toEpochMilli(),
                    end = date.atTime(to).atZone(zone).toInstant().toEpochMilli()
                )
            )
            SyncScheduler.enqueue(this@TaskEditActivity)
            reloadTime()
        }
    }

    private fun confirmDelete(entry: TimeEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_time_entry_title)
            .setMessage(describe(entry))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val task = existing ?: return@launch
                    app.taskRepository.deleteTimeEntry(task.id, entry.id, myId, TeamSession.role(this@TaskEditActivity).canManageCompany)
                    SyncScheduler.enqueue(this@TaskEditActivity)
                    reloadTime()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickTime(initial: LocalTime?, onPicked: (LocalTime) -> Unit) {
        val now = initial ?: LocalTime.now()
        TimePickerDialog(this, { _, h, m -> onPicked(LocalTime.of(h, m)) }, now.hour, now.minute, DateFormat.is24HourFormat(this))
            .show()
    }

    /** Re-reads the task after a time change (the form fields are left as they are). */
    private suspend fun reloadTime() {
        val id = existing?.id ?: return
        existing = app.taskRepository.getTask(id) ?: existing
        renderTime(existing)
    }

    private fun renderTime(task: TaskEntity?) {
        renderTimer(task)
        val entries = task?.timeEntries.orEmpty().sortedByDescending { it.start }
        val canDeleteOthers = TeamSession.role(this).canManageCompany
        binding.timeEntries.removeAllViews()
        entries.forEach { entry ->
            val row = ItemTimeEntryBinding.inflate(layoutInflater, binding.timeEntries, false)
            row.tvEntry.text = describe(entry)
            row.btnDeleteEntry.isVisible = entry.userId == myId || canDeleteOthers
            row.btnDeleteEntry.setOnClickListener { confirmDelete(entry) }
            binding.timeEntries.addView(row.root)
        }
    }

    /** The total and the Start / Finish button (with the running clock). */
    private fun renderTimer(task: TaskEntity?) {
        val entries = task?.timeEntries.orEmpty()
        val now = System.currentTimeMillis()
        binding.tvTimeTotal.text = getString(R.string.time_total, TaskTime.format(TaskTime.totalMinutes(entries, now)))
        val running = TaskTime.running(entries, myId)
        binding.btnTimer.text = if (running != null) {
            getString(R.string.timer_finish, TaskTime.formatElapsed(now - running.start))
        } else {
            getString(R.string.timer_start)
        }
    }

    /** "Nov 19, 2026 · 09:00–11:15 · 2:15 h · dana" */
    private fun describe(entry: TimeEntry): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(entry.start).atZone(zone)
        val end = entry.end?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFormat) } ?: getString(R.string.time_entry_now)
        val parts = mutableListOf(
            DateUtils.formatForDisplay(start.toLocalDate().toString()),
            "${start.format(timeFormat)}–$end",
            getString(R.string.task_hours, TaskTime.format(TaskTime.minutes(entry)))
        )
        if (entry.userId != myId && !entry.userName.isNullOrBlank()) parts += entry.userName
        return parts.joinToString(" · ")
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ID = "task_id"
        private const val TICK_MS = 1_000L

        fun intent(context: Context, id: String?) =
            Intent(context, TaskEditActivity::class.java).apply { id?.let { putExtra(EXTRA_ID, it) } }
    }
}
