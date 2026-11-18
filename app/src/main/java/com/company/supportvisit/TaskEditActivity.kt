package com.company.supportvisit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.company.supportvisit.data.ClientEntity
import com.company.supportvisit.data.TaskEntity
import com.company.supportvisit.databinding.ActivityTaskEditBinding
import com.company.supportvisit.auth.AuthSession
import com.company.supportvisit.sync.SyncScheduler
import com.company.supportvisit.team.TeamRepository
import com.company.supportvisit.team.TeamSession
import com.company.supportvisit.ui.applySystemBarInsets
import com.company.supportvisit.ui.setUpBackBar
import com.company.supportvisit.utils.DateUtils
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/** Add or edit a task, optionally for a client and assigned to a teammate. */
class TaskEditActivity : AppCompatActivity() {

    private data class Person(val userId: String, val name: String)

    private lateinit var binding: ActivityTaskEditBinding

    private var existing: TaskEntity? = null
    private var dueDate: String? = null
    private var clients: List<ClientEntity> = emptyList()
    private var clientId: String? = null
    private var people: List<Person> = emptyList()
    private var assignee: Person? = null

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

        lifecycleScope.launch {
            clients = app.clientRepository.getClients()
            val labels = listOf(getString(R.string.none)) + clients.map { it.name }
            binding.actClient.setAdapter(ArrayAdapter(this@TaskEditActivity, android.R.layout.simple_dropdown_item_1line, labels))
            binding.actClient.setOnItemClickListener { _, _, i, _ -> clientId = if (i == 0) null else clients[i - 1].id }

            intent.getStringExtra(EXTRA_ID)?.let { id ->
                existing = app.taskRepository.getTask(id)
                existing?.let(::fill)
            }
            loadTeam(me)
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

    private fun save() {
        val title = binding.etTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.error_task_title, Toast.LENGTH_LONG).show()
            return
        }
        val myId = TeamSession.userId(this)
        val myName = AuthSession.accountEmail.value?.substringBefore('@').orEmpty()
        val target = assignee?.takeIf { it.userId.isNotBlank() && it.userId != myId }
        val task = (existing ?: TaskEntity(id = "", title = title, createdById = myId, createdByName = myName)).copy(
            title = title,
            notes = binding.etNotes.text.toString(),
            dueDate = dueDate,
            clientId = clientId,
            assigneeId = target?.userId ?: myId.ifBlank { null },
            assigneeName = target?.name ?: myName
        )
        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            app.taskRepository.save(task)
            SyncScheduler.enqueue(this@TaskEditActivity)
            finish()
        }
    }

    companion object {
        private const val EXTRA_ID = "task_id"

        fun intent(context: Context, id: String?) =
            Intent(context, TaskEditActivity::class.java).apply { id?.let { putExtra(EXTRA_ID, it) } }
    }
}
